package com.example.bookserver.address;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A saved delivery address in a user's address book. The schema is international-shaped
 * ({@code country} + free-form lines) so the same row models a KR or non-KR address; the
 * app format-validates per country. An order snapshots these values at order time, so
 * later edits to this row never mutate a past order.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    private UUID addressUuid;
    private UUID userUuid;
    private String alias;          // user's label, e.g. Home/Work
    private String recipient;
    private String phone;
    private String country;        // ISO 3166-1 alpha-2, e.g. KR
    private String roadAddress;
    private String detailAddress;  // optional
    private String postalCode;
    private boolean defaultAddress;   // maps to column is_default; "the user's default"
    private LocalDateTime createdAt;
}
