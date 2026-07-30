package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The current (latest) state of a purchase: exactly one row per purchase.
 * Upserted from each state change; kept alongside the append-only
 * {@link PurchaseHistory} log so that reading a user's latest order states is a
 * plain indexed lookup instead of an ORDER BY + DISTINCT over the whole history.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseCurrent {

    private UUID purchaseUuid;      // PK: stable id of the purchase
    private UUID userUuid;
    private UUID historyUuid;       // head pointer: the history event that is currently in effect
    private PurchaseState purchaseState;
    private BigDecimal price;       // order total snapshot (latest)
    private LocalDateTime updatedAt;
}
