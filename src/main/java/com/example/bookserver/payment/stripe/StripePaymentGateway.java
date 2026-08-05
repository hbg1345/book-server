package com.example.bookserver.payment.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.bookserver.payment.IntentRequest;
import com.example.bookserver.payment.IntentResult;
import com.example.bookserver.payment.PaymentGateway;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;

/**
 * Stripe adapter for the {@link PaymentGateway} port.
 *
 * <p>Opening an intent does not charge anything: it reserves an amount server-side and returns a
 * client secret the frontend confirms the card with. The outcome comes back as a webhook, so this
 * class never learns whether the charge succeeded. Refunds, being server-to-server, do complete
 * here.
 *
 * <p>Every call carries the caller's idempotency key. Stripe replays the original response for a
 * repeated key, which is what makes re-opening an intent return the same intent and secret, and
 * what stops a retried refund from paying the customer twice.
 *
 * <p>{@link StripeException} is caught rather than propagated: the port's contract is that a
 * provider-side failure is a result with {@code success=false}, so the domain never has to know
 * Stripe's exception types.
 */
public class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final StripeClient stripe;
    private final String currency;

    public StripePaymentGateway(StripeClient stripe, String currency) {
        this.stripe = stripe;
        this.currency = currency;
    }

    @Override
    public String provider() {
        return "STRIPE";
    }

    @Override
    public IntentResult openIntent(IntentRequest request) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(StripeAmounts.toMinorUnits(request.amount(), currency))
                .setCurrency(currency)
                // carried back on the webhook, so an event can be traced to an order even if our
                // payment row is somehow missing
                .putMetadata("purchaseUuid", request.purchaseUuid().toString())
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build())
                .build();
        try {
            PaymentIntent intent = stripe.paymentIntents().create(params, idempotent(request.idempotencyKey()));
            return IntentResult.opened(intent.getId(), intent.getClientSecret());
        } catch (StripeException e) {
            log.warn("Stripe refused to open an intent for order {}", request.purchaseUuid(), e);
            return IntentResult.failed(reasonOf(e));
        }
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(request.providerTransactionId())
                .setAmount(StripeAmounts.toMinorUnits(request.amount(), currency))
                .build();
        try {
            Refund refund = stripe.refunds().create(params, idempotent(request.idempotencyKey()));
            // "failed" is terminal; "pending" still settles asynchronously but is not a refusal,
            // so only an outright failure is reported as unsuccessful.
            if ("failed".equals(refund.getStatus())) {
                return RefundResult.failed("refund_failed");
            }
            return RefundResult.refunded(refund.getId());
        } catch (StripeException e) {
            log.warn("Stripe refused to refund {}", request.providerTransactionId(), e);
            return RefundResult.failed(reasonOf(e));
        }
    }

    private static RequestOptions idempotent(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    /** Stripe's machine-readable code where there is one, so the reason we persist stays stable. */
    private static String reasonOf(StripeException e) {
        return e.getCode() != null ? e.getCode() : e.getClass().getSimpleName();
    }
}
