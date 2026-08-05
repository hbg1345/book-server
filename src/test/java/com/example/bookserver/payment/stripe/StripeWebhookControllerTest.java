package com.example.bookserver.payment.stripe;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.stripe.Stripe;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.purchase.PurchaseService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the Stripe webhook. The signature is computed here the same way Stripe
 * computes it, so these exercise the real verification rather than stubbing it out — the header
 * is the only thing standing between this open endpoint and free merchandise.
 */
@WebMvcTest(StripeWebhookController.class)
@Import(StripeWebhookControllerTest.Config.class)
class StripeWebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";

    @TestConfiguration
    static class Config {
        @Bean
        StripeProperties stripeProperties() {
            return new StripeProperties("sk_test_x", SECRET, "usd");
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private PurchaseService purchaseService;
    @MockitoBean
    private JwtProvider jwtProvider;   // the security filter chain wants one

    /**
     * A real event as Stripe sent it, captured from a test-mode account with the CLI and committed
     * under {@code src/test/resources/stripe}. These exist because the hand-written bodies below
     * cannot catch a payload-shape problem: they stamp {@link Stripe#API_VERSION} into the event,
     * so they agree with the library by construction no matter what Stripe actually sends. A real
     * account's events carry the version configured on the account, which drifts ahead of the
     * pinned library — and did, silently breaking every payment until it was caught here.
     *
     * <p>Re-capture with: {@code stripe trigger payment_intent.succeeded} then
     * {@code stripe events retrieve <evt_id>}.
     */
    private static String fixture(String name) throws Exception {
        try (var in = StripeWebhookControllerTest.class.getResourceAsStream("/stripe/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing Stripe fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * A payment_intent event body. The API version is stamped as the library's own, which keeps
     * these focused on the handler's logic — for the payload as Stripe really sends it, and the
     * version skew that comes with it, see the fixture-backed tests.
     */
    private static String eventJson(String type, String intentId, long amount, String currency) {
        return """
                {"id":"evt_test","object":"event","api_version":"%s","type":"%s",
                 "data":{"object":{"id":"%s","object":"payment_intent",
                 "amount":%d,"amount_received":%d,"currency":"%s"}}}
                """.formatted(Stripe.API_VERSION, type, intentId, amount, amount, currency);
    }

    /** The {@code Stripe-Signature} header: HMAC-SHA256 of "timestamp.body" keyed by the secret. */
    private static String signature(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + hex;
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String payload, String sig)
            throws Exception {
        return mockMvc.perform(post("/api/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", sig)
                .content(payload));
    }

    // a correctly signed success event settles the payment with the amount Stripe captured.
    @Test
    void succeededEvent_settlesThePayment() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_1", 3999, "usd");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService).markPaymentSucceeded("pi_1", new BigDecimal("39.99"));
    }

    // a failed event records the failure and never marks anything paid.
    @Test
    void failedEvent_recordsFailure() throws Exception {
        String payload = eventJson("payment_intent.payment_failed", "pi_2", 3999, "usd");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService).markPaymentFailed("pi_2");
        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // THE security test: a body signed with the wrong secret is forged. Reject, touch nothing.
    @Test
    void wrongSignature_isRejected_andNothingIsSettled() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_3", 3999, "usd");

        postWebhook(payload, signature(payload, "whsec_attacker")).andExpect(status().isBadRequest());

        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // tampering with the body after signing invalidates the signature, even with a valid secret.
    @Test
    void tamperedBody_isRejected() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_4", 100, "usd");
        String signature = signature(payload, SECRET);
        String tampered = payload.replace("\"amount\":100", "\"amount\":999999");

        postWebhook(tampered, signature).andExpect(status().isBadRequest());

        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // no signature header at all -> 400, not a 500.
    @Test
    void missingSignatureHeader_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("payment_intent.succeeded", "pi_5", 3999, "usd")))
                .andExpect(status().isBadRequest());
    }

    // a charge in a different currency than this server charges in is refused.
    @Test
    void wrongCurrency_isRefused() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_6", 3999, "eur");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // an amount mismatch cannot be fixed by retrying, so it is acknowledged (200) — but the
    // payment is left unsettled.
    @Test
    void amountMismatch_isAcknowledged_soStripeStopsRetrying() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_7", 999, "usd");
        doThrow(new PaymentAmountMismatchException(new BigDecimal("39.99"), new BigDecimal("9.99")))
                .when(purchaseService).markPaymentSucceeded(eq("pi_7"), any());

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());
    }

    // THE regression test: a genuine Stripe event, whose api_version is newer than the pinned
    // library's, still settles the payment. Before this the typed deserializer returned nothing on
    // any version skew, so every real charge was acknowledged and then dropped — paid, never shipped.
    @Test
    void realSucceededEvent_withNewerApiVersion_stillSettles() throws Exception {
        String payload = fixture("payment_intent_succeeded.json");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService)
                .markPaymentSucceeded("pi_3U0uyHIoyUes54I42tcLehex", new BigDecimal("20.00"));
    }

    // the same skew must not swallow failures either, or a declined card leaves the order hanging
    // until the expiry sweep instead of being marked failed straight away.
    @Test
    void realFailedEvent_withNewerApiVersion_stillRecordsFailure() throws Exception {
        String payload = fixture("payment_intent_payment_failed.json");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService).markPaymentFailed("pi_3U0v7TIoyUes54I42wjUHTFJ");
        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // a refund that succeeded is confirmation of something we already recorded when we issued it,
    // so it must not touch anything — least of all look like a failure.
    @Test
    void refundSucceededEvent_isIgnored() throws Exception {
        String payload = fixture("charge_refund_updated.json");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService, never()).markRefundFailed(any(), any());
    }

    /**
     * THE case this event type exists for: Stripe accepted the refund, we told the buyer their
     * order was refunded, and the money then failed to land. Nothing else in the system finds out.
     *
     * <p>The fixture is a real captured event with two values edited — {@code status} to failed and
     * a documented {@code failure_reason} — because the CLI's fixture only produces a successful
     * refund and provoking a genuine reversal needs a test clock. The shape is Stripe's; the two
     * fields are ours.
     */
    @Test
    void refundFailedEvent_marksTheRefundFailed() throws Exception {
        String payload = fixture("charge_refund_updated_failed.json");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService)
                .markRefundFailed("pi_3U0xZuIoyUes54I413XWNsVP", "expired_or_canceled_card");
    }

    // a body we genuinely cannot read is the one case worth a 5xx: unlike a wrong amount, a retry
    // after a fix can succeed, and a 200 would throw the payment away for good.
    @Test
    void unreadablePayload_is5xx_soStripeRetries() throws Exception {
        // well-formed, and on an unknown future version, but the payload is not a payment intent
        String payload = """
                {"id":"evt_broken","object":"event","api_version":"2099-01-01.zinnia",
                 "type":"payment_intent.succeeded",
                 "data":{"object":{"id":"cus_1","object":"customer"}}}
                """;

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().is5xxServerError());

        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
    }

    // an unsubscribed event type is acknowledged and ignored.
    @Test
    void unrelatedEventType_isIgnored() throws Exception {
        String payload = eventJson("customer.created", "cus_1", 0, "usd");

        postWebhook(payload, signature(payload, SECRET)).andExpect(status().isOk());

        verify(purchaseService, never()).markPaymentSucceeded(any(), any());
        verify(purchaseService, never()).markPaymentFailed(any());
    }
}
