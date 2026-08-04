package com.example.bookserver.address;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-country postal-code format rules, shared by the address book and by the delivery
 * address snapshotted onto an order. The schema is international, so a US ZIP and a KR
 * postal code are both valid rows; only their formats differ. Countries without a rule
 * configured are accepted as-is (validation is KR-only for now).
 */
public final class PostalCodes {

    private static final Map<String, Pattern> FORMATS = Map.of(
            "KR", Pattern.compile("\\d{5}"));

    private PostalCodes() {
    }

    /** Country codes are matched case-insensitively; callers should store the normalized form. */
    public static String normalizeCountry(String country) {
        return country.toUpperCase();
    }

    /** Throws {@link InvalidPostalCodeException} if the code does not match its country's format. */
    public static void validate(String country, String postalCode) {
        Pattern format = FORMATS.get(country);
        if (format != null && !format.matcher(postalCode).matches()) {
            throw new InvalidPostalCodeException(country, postalCode);
        }
    }
}
