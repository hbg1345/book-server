package com.example.bookserver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseHistory {

    private UUID historyUuid;       // PK of this history event
    private UUID purchaseUuid;      // stable id of the purchase
    private UUID userUuid;
    private PurchaseState purchaseState;
    private BigDecimal price;       // order total snapshot
    private LocalDateTime updatedAt;
}
