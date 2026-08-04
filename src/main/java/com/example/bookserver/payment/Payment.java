package com.example.bookserver.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A persisted payment record for one charge attempt against an order. Links to the order by
 * purchase_uuid, records which provider handled it and the provider's transaction id, and
 * carries the idempotency key that guards against a double charge.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    private UUID paymentUuid;
    private UUID purchaseUuid;
    private String provider;         // which gateway handled it, e.g. TOSS
    private String providerTxnId;    // the provider's transaction id (null if it never confirmed)
    private BigDecimal amount;
    private PaymentStatus status;
    private String idempotencyKey;   // dedup unit: same key => same charge
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
