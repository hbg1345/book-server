package com.example.bookserver.payment.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe configuration. {@code secretKey} is also the switch that activates the adapter: with it
 * unset the application falls back to the refusing stub, so a misconfigured deployment fails
 * closed rather than pretending to take payments.
 *
 * @param secretKey     server-side API key ({@code sk_test_…} / {@code sk_live_…}); never sent to a client
 * @param webhookSecret endpoint signing secret ({@code whsec_…}) used to authenticate webhooks
 * @param currency      ISO currency the charges are made in
 */
@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(String secretKey, String webhookSecret, String currency) {

    public StripeProperties {
        currency = currency == null ? "usd" : currency.toLowerCase();
    }
}
