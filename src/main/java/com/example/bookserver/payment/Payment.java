package com.example.bookserver.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The persisted payment for an order — one row, created when the intent is opened and updated as
 * the provider reports progress. Links to the order by purchase_uuid, records which provider
 * handled it and the provider's intent id (used to address a later refund), and carries the
 * order-scoped idempotency key that guards against opening a second intent.
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
