package com.example.bookserver.purchase.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.bookserver.payment.Payment;
import com.example.bookserver.payment.PaymentStatus;

/** Result of paying an order: the persisted payment record. */
public record PaymentResponse(
        UUID paymentUuid,
        PaymentStatus status,
        String providerTxnId,
        BigDecimal amount) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getPaymentUuid(), payment.getStatus(),
                payment.getProviderTxnId(), payment.getAmount());
    }
}
