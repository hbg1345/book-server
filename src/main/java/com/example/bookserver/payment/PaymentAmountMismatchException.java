package com.example.bookserver.payment;

import java.math.BigDecimal;

/** The client-supplied charge amount does not match the server's order total (tampering guard). */
public class PaymentAmountMismatchException extends RuntimeException {

    public PaymentAmountMismatchException(BigDecimal orderTotal, BigDecimal requestedAmount) {
        super("Charge amount " + requestedAmount + " does not match the order total " + orderTotal);
    }
}
