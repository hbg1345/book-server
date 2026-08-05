package com.example.bookserver.payment.stripe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Converts our {@code BigDecimal} amounts to the integer minor units Stripe charges in (cents for
 * USD, and so on). Getting this wrong is a factor-of-100 billing bug in either direction, so the
 * zero-decimal currencies — which are charged in whole units, not hundredths — are listed
 * explicitly rather than assumed.
 */
final class StripeAmounts {

    /** Currencies Stripe takes in whole units; multiplying these by 100 would overcharge 100×. */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga",
            "pyg", "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf");

    private StripeAmounts() {
    }

    /** {@code 39.99 USD -> 3999}; {@code 39000 KRW -> 39000}. */
    static long toMinorUnits(BigDecimal amount, String currency) {
        int scale = ZERO_DECIMAL.contains(currency) ? 0 : 2;
        return amount.setScale(scale, RoundingMode.HALF_UP).movePointRight(scale).longValueExact();
    }

    /** The inverse, for reading an amount back off a webhook event. */
    static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        int scale = ZERO_DECIMAL.contains(currency) ? 0 : 2;
        return BigDecimal.valueOf(minorUnits).movePointLeft(scale);
    }
}
