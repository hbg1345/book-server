package com.example.bookserver.purchase;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The delivery address snapshotted onto one order. Values are copied from whatever address
 * the order used (a saved address-book entry or a one-off inline address) at order time, so
 * later edits to the source address never change this. One row per order (purchase_uuid).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderAddress {

    private UUID purchaseUuid;
    private String recipient;
    private String phone;
    private String country;        // ISO 3166-1 alpha-2, e.g. KR
    private String roadAddress;
    private String detailAddress;  // optional
    private String postalCode;
    private LocalDateTime createdAt;
}
