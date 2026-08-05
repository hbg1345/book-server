package com.example.bookserver.payment;

import com.stripe.StripeClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.example.bookserver.payment.stripe.StripePaymentGateway;
import com.example.bookserver.payment.stripe.StripeProperties;

/**
 * The application's one {@link PaymentGateway}.
 *
 * <p>There is deliberately no fallback implementation. Without {@code stripe.secret-key} no
 * gateway bean exists, {@code PurchaseService} cannot be constructed, and the application refuses
 * to start. That is the intended behaviour: a deployment that cannot take payments should fail
 * loudly at startup, not boot and then fail every checkout with a 502 that leaves nothing in the
 * logs to explain it. An earlier stub did exactly that and hid the problem.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class PaymentGatewayConfig {

    /**
     * A key that is present but blank is refused outright rather than tolerated — it would build a
     * client that fails every call, which is harder to diagnose than a refusal to start. Empty is a
     * real possibility: a deployment that defines the variable and leaves it unset.
     */
    @Bean
    @ConditionalOnProperty(prefix = "stripe", name = "secret-key")
    public PaymentGateway stripePaymentGateway(StripeProperties properties) {
        if (!StringUtils.hasText(properties.secretKey())) {
            throw new IllegalStateException("stripe.secret-key is defined but empty");
        }
        StripeClient stripe = StripeClient.builder().setApiKey(properties.secretKey()).build();
        return new StripePaymentGateway(stripe, properties.currency());
    }
}
