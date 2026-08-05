package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.bookserver.payment.OpenedPayment;

/**
 * An opened payment intent. {@code clientSecret} is what the frontend passes to the provider's
 * SDK to confirm the card; {@code amount} is the server's order total, echoed so the UI can show
 * what is about to be charged (it is not an input — the client never gets to choose it).
 */
public record PaymentIntentResponse(
        UUID paymentUuid,
        String clientSecret,
        BigDecimal amount) {

    public static PaymentIntentResponse from(OpenedPayment opened) {
        return new PaymentIntentResponse(opened.payment().getPaymentUuid(), opened.clientSecret(),
                opened.payment().getAmount());
    }
}
