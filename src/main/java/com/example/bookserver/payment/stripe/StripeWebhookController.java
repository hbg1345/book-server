package com.example.bookserver.payment.stripe;

import java.math.BigDecimal;

import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.purchase.PurchaseService;

/**
 * Receives Stripe's webhooks — the only path by which an order becomes paid, since the card is
 * confirmed between the browser and Stripe and never passes through this server.
 *
 * <p>The endpoint is unauthenticated (Stripe holds no JWT) and is instead authenticated per
 * request by the {@code Stripe-Signature} header, which is an HMAC of the timestamp and the exact
 * body bytes keyed by the endpoint's signing secret. Without that check anyone could POST
 * "payment succeeded" and get goods for free, so a body that does not verify is rejected outright.
 *
 * <p>That is also why the payload is taken as a raw {@code String}: the HMAC covers the bytes
 * Stripe sent, so a parsed-and-reserialized DTO would never match.
 *
 * <p><b>On status codes:</b> Stripe retries anything that is not 2xx, with backoff, for days. So a
 * 2xx here means "do not send this again", not "all was well" — conditions that a retry cannot
 * fix (an unknown intent, a tampered amount) are logged and acknowledged rather than failed.
 */
@RestController
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PurchaseService purchaseService;
    private final String webhookSecret;
    private final String currency;

    public StripeWebhookController(PurchaseService purchaseService, StripeProperties properties) {
        // Without a signing secret no webhook can be trusted, and without webhooks no order can
        // ever become paid — the application is not in a usable state, so refuse to start rather
        // than run a checkout that silently never completes.
        if (!StringUtils.hasText(properties.webhookSecret())) {
            throw new IllegalStateException(
                    "stripe.webhook-secret is not set; payments could never complete without it");
        }
        this.purchaseService = purchaseService;
        this.webhookSecret = properties.webhookSecret();
        this.currency = properties.currency();
    }

    @PostMapping("/stripe")
    public ResponseEntity<Void> handle(@RequestBody String payload,
                                       @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            // also enforces a timestamp tolerance, so a captured request cannot be replayed later
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Rejected a webhook with an invalid signature");
            return ResponseEntity.badRequest().build();
        }

        switch (event.getType()) {
            case "payment_intent.succeeded" -> settle(paymentIntent(event));
            case "payment_intent.payment_failed" ->
                    purchaseService.markPaymentFailed(paymentIntent(event).getId());
            // we subscribe to a narrow set; anything else is acknowledged so Stripe stops sending it
            default -> log.debug("Ignoring Stripe event of type {}", event.getType());
        }
        return ResponseEntity.ok().build();
    }

    /** Mark the order paid, unless the confirmed charge disagrees with what we opened. */
    private void settle(PaymentIntent intent) {
        if (!currency.equalsIgnoreCase(intent.getCurrency())) {
            // a charge in the wrong currency is the same class of problem as a wrong amount
            log.error("Refusing intent {}: charged in {} but this server charges in {}",
                    intent.getId(), intent.getCurrency(), currency);
            return;
        }
        // amount_received is what Stripe actually captured; amount is only what was requested
        long minorUnits = intent.getAmountReceived() != null ? intent.getAmountReceived() : intent.getAmount();
        BigDecimal charged = StripeAmounts.fromMinorUnits(minorUnits, currency);
        try {
            purchaseService.markPaymentSucceeded(intent.getId(), charged);
        } catch (PaymentAmountMismatchException e) {
            // Permanent: retrying delivers the same amount forever. Acknowledged so Stripe stops,
            // but loud — the order stays unpaid and this needs a human.
            log.error("Refusing intent {}: {}", intent.getId(), e.getMessage());
        }
    }

    /**
     * The event's payload as a {@link PaymentIntent}.
     *
     * <p>The typed accessor deliberately returns nothing when the event's API version differs from
     * the one this library pins, because a renamed or restructured field could otherwise be read as
     * a silent default. That check fires in normal operation, not just in theory: an account's API
     * version drifts ahead of the pinned library on Stripe's own schedule, and treating that as
     * unreadable meant every charge was acknowledged and then dropped — the customer paid and the
     * order sat unshipped until it expired.
     *
     * <p>So a version mismatch falls back to reading the payload anyway. That is sound for the four
     * fields taken off it — id, currency, amount, amount_received — which have been part of a
     * payment intent since the object existed; it would not be sound for a field that is new,
     * deprecated, or reshaped, and any such addition needs this decision revisited.
     */
    private PaymentIntent paymentIntent(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject object = deserializer.getObject().orElseGet(() -> {
            log.info("Event {} is on API version {} but this build pins {}; reading it leniently",
                    event.getId(), event.getApiVersion(), Stripe.API_VERSION);
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new UnreadableEventException(event, e);
            }
        });
        if (object instanceof PaymentIntent intent) {
            return intent;
        }
        throw new UnreadableEventException(event, null);
    }

    /** A webhook with no signature header at all is a bad request, not a server error. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Void> missingSignature() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /**
     * An event whose payload cannot be read at all — as opposed to one that is understood and
     * refused. This is the one failure here worth a 5xx: the cause is on our side, a retry after a
     * fix can succeed, and Stripe's retries are the only thing keeping the payment recoverable.
     * Acknowledging it would discard a real charge.
     */
    @ExceptionHandler(UnreadableEventException.class)
    public ResponseEntity<Void> unreadableEvent(UnreadableEventException e) {
        log.error("Could not read a PaymentIntent out of event {}; asking Stripe to retry",
                e.eventId, e.getCause());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private static class UnreadableEventException extends RuntimeException {
        private final String eventId;

        UnreadableEventException(Event event, Throwable cause) {
            super("unreadable payload on event " + event.getId() + " (" + event.getType() + ")", cause);
            this.eventId = event.getId();
        }
    }
}
