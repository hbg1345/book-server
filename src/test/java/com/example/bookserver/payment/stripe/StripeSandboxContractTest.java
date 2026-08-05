package com.example.bookserver.payment.stripe;

import java.math.BigDecimal;
import java.util.UUID;

import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentConfirmParams;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.bookserver.payment.IntentRequest;
import com.example.bookserver.payment.IntentResult;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract tests against the real Stripe sandbox. Everything else in this package mocks
 * {@link StripeClient}, which can only confirm what we <em>send</em> — a mock accepts any request,
 * honours nothing, and invents its own errors. These confirm what Stripe actually does with it.
 *
 * <p>They are tagged out of the normal build because they need a real test-mode key and a network,
 * and they leave objects on the account. Run them with {@code ./gradlew contractTest}, which reads
 * the key from the environment (or the local {@code .env}); with no key they skip rather than fail,
 * so a checkout without credentials is not a broken build.
 *
 * <p>Everything here is test mode: no money moves, and the card numbers are Stripe's own fixtures.
 */
@Tag("contract")
class StripeSandboxContractTest {

    /** Stripe's "always succeeds" test card. */
    private static final String CARD_OK = "pm_card_visa";

    private StripeClient stripe;
    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        String key = System.getenv("STRIPE_SECRET_KEY");
        assumeTrue(key != null && key.startsWith("sk_test_"),
                "no test-mode STRIPE_SECRET_KEY in the environment; skipping the sandbox contract");
        stripe = StripeClient.builder().setApiKey(key).build();
        gateway = new StripePaymentGateway(stripe, "usd");
    }

    /** Order-scoped in production; unique per run here so repeats do not replay an old response. */
    private static String key(String suffix) {
        return "contract-" + UUID.randomUUID() + "-" + suffix;
    }

    /**
     * The request we build is one Stripe accepts, and the amount lands as the minor units we meant.
     * A mock cannot show this: it returns whatever we stub no matter what params it was handed, so
     * a rejected-in-production request still looks fine.
     */
    @Test
    void openIntent_isAcceptedAndCarriesTheAmountWeMeant() throws Exception {
        UUID purchaseUuid = UUID.randomUUID();

        IntentResult result = gateway.openIntent(
                new IntentRequest(purchaseUuid, new BigDecimal("39.99"), key("open")));

        assertThat(result.success()).isTrue();
        assertThat(result.clientSecret()).isNotBlank();

        // read it back from Stripe rather than trusting the create response
        PaymentIntent stored = stripe.paymentIntents().retrieve(result.providerIntentId());
        assertThat(stored.getAmount()).isEqualTo(3999L);      // 39.99 USD, not 39 and not 399900
        assertThat(stored.getCurrency()).isEqualTo("usd");
        assertThat(stored.getStatus()).isEqualTo("requires_payment_method");
        // the webhook can trace an event back to an order even if our payment row is missing
        assertThat(stored.getMetadata()).containsEntry("purchaseUuid", purchaseUuid.toString());
    }

    /**
     * Stripe honours the idempotency key — the guard against charging a customer twice when a
     * request is retried. The mocked test asserts only that we <em>send</em> the key; that Stripe
     * acts on it is an assumption about someone else's server, and this is what checks it.
     */
    @Test
    void openIntent_repeatedWithTheSameKey_returnsTheSameIntent() {
        UUID purchaseUuid = UUID.randomUUID();
        String idempotencyKey = key("retry");
        IntentRequest request = new IntentRequest(purchaseUuid, new BigDecimal("39.99"), idempotencyKey);

        IntentResult first = gateway.openIntent(request);
        IntentResult second = gateway.openIntent(request);

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        // one intent, so one eventual charge
        assertThat(second.providerIntentId()).isEqualTo(first.providerIntentId());
        assertThat(second.clientSecret()).isEqualTo(first.clientSecret());
    }

    /**
     * A charge confirmed with a test card refunds, and the money comes back on Stripe's side too.
     * This is the whole refund path — the cancel and return flows call exactly this — exercised
     * against the real API for the first time; everywhere else the refund is a stubbed object.
     *
     * <p>The confirm step stands in for the frontend, which is what confirms the card in
     * production. Doing it server-side is only possible with Stripe's test payment methods.
     */
    @Test
    void refund_ofAConfirmedCharge_comesBack() throws Exception {
        IntentResult opened = gateway.openIntent(
                new IntentRequest(UUID.randomUUID(), new BigDecimal("39.99"), key("refund")));
        assertThat(opened.success()).isTrue();

        PaymentIntent confirmed = stripe.paymentIntents().confirm(
                opened.providerIntentId(),
                PaymentIntentConfirmParams.builder()
                        .setPaymentMethod(CARD_OK)
                        // required once a redirect-capable method could be involved
                        .setReturnUrl("https://example.com/return")
                        .build());
        assertThat(confirmed.getStatus()).isEqualTo("succeeded");

        RefundResult refunded = gateway.refund(new RefundRequest(
                opened.providerIntentId(), new BigDecimal("39.99"), key("refund-1")));

        assertThat(refunded.success()).isTrue();
        assertThat(refunded.refundTransactionId()).startsWith("re_");

        PaymentIntent after = stripe.paymentIntents().retrieve(opened.providerIntentId());
        assertThat(after.getLatestCharge()).isNotNull();
        assertThat(stripe.charges().retrieve(after.getLatestCharge()).getRefunded()).isTrue();
    }

    /**
     * A refusal arrives as a failed result carrying Stripe's own code, not an exception. The code
     * matters beyond this assertion: it is persisted on the payment row, so it has to be Stripe's
     * stable machine-readable string and not a Java class name that changes when we upgrade.
     */
    @Test
    void openIntent_refused_isAFailedResultCarryingStripesCode() {
        // below the 50-cent minimum Stripe enforces for USD
        IntentResult result = gateway.openIntent(
                new IntentRequest(UUID.randomUUID(), new BigDecimal("0.01"), key("too-small")));

        assertThat(result.success()).isFalse();
        assertThat(result.providerIntentId()).isNull();
        assertThat(result.failureReason()).isEqualTo("amount_too_small");
    }
}
