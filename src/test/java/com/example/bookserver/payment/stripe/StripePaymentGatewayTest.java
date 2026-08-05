package com.example.bookserver.payment.stripe;

import java.math.BigDecimal;
import java.util.UUID;

import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.BalanceService;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.RefundService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.bookserver.payment.IntentRequest;
import com.example.bookserver.payment.IntentResult;
import com.example.bookserver.payment.RefundRequest;
import com.example.bookserver.payment.RefundResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Stripe adapter against a mocked {@link StripeClient} — no network, no keys.
 * They pin the things that would be expensive to get wrong in production: the amount sent to
 * Stripe, the idempotency key, and that a provider error becomes a failed result rather than an
 * exception escaping into the domain.
 */
class StripePaymentGatewayTest {

    private StripeClient stripe;
    private PaymentIntentService intents;
    private RefundService refunds;
    private StripePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        stripe = mock(StripeClient.class);
        intents = mock(PaymentIntentService.class);
        refunds = mock(RefundService.class);
        when(stripe.paymentIntents()).thenReturn(intents);
        when(stripe.refunds()).thenReturn(refunds);
        gateway = new StripePaymentGateway(stripe, "usd");
    }

    private static PaymentIntent stripeIntent(String id, String clientSecret) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setClientSecret(clientSecret);
        return intent;
    }

    // opening an intent returns Stripe's id + client secret, and sends the amount in cents.
    @Test
    void openIntent_sendsAmountInMinorUnits_andReturnsClientSecret() throws Exception {
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeIntent("pi_123", "pi_123_secret_abc"));

        IntentResult result = gateway.openIntent(
                new IntentRequest(UUID.randomUUID(), new BigDecimal("39.99"), "order-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.providerIntentId()).isEqualTo("pi_123");
        assertThat(result.clientSecret()).isEqualTo("pi_123_secret_abc");

        ArgumentCaptor<PaymentIntentCreateParams> params =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verifyCreate(params, options);
        assertThat(params.getValue().getAmount()).isEqualTo(3999L);   // 39.99 USD, not 39
        assertThat(params.getValue().getCurrency()).isEqualTo("usd");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("order-1");
    }

    private void verifyCreate(ArgumentCaptor<PaymentIntentCreateParams> params,
                              ArgumentCaptor<RequestOptions> options) throws Exception {
        org.mockito.Mockito.verify(intents).create(params.capture(), options.capture());
    }

    // a zero-decimal currency is charged in whole units — multiplying by 100 would overcharge 100x.
    @Test
    void openIntent_zeroDecimalCurrency_isNotScaled() throws Exception {
        gateway = new StripePaymentGateway(stripe, "krw");
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeIntent("pi_krw", "cs_krw"));

        gateway.openIntent(new IntentRequest(UUID.randomUUID(), new BigDecimal("39000"), "order-2"));

        ArgumentCaptor<PaymentIntentCreateParams> params =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verifyCreate(params, options);
        assertThat(params.getValue().getAmount()).isEqualTo(39000L);
    }

    // a Stripe error is a failed result carrying Stripe's code — it must not escape as an exception.
    @Test
    void openIntent_stripeError_becomesFailedResult() throws Exception {
        when(intents.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("boom", "req_1", "api_error", 500, null));

        IntentResult result = gateway.openIntent(
                new IntentRequest(UUID.randomUUID(), new BigDecimal("39.99"), "order-3"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("api_error");
        assertThat(result.clientSecret()).isNull();
    }

    // a refund is addressed by the intent id and reports the refund's id back.
    @Test
    void refund_succeeds_andIsIdempotent() throws Exception {
        Refund refund = new Refund();
        refund.setId("re_1");
        refund.setStatus("succeeded");
        when(refunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);

        RefundResult result = gateway.refund(
                new RefundRequest("pi_123", new BigDecimal("39.99"), "order-1-refund"));

        assertThat(result.success()).isTrue();
        assertThat(result.refundTransactionId()).isEqualTo("re_1");

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        org.mockito.Mockito.verify(refunds).create(params.capture(), options.capture());
        assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_123");
        assertThat(params.getValue().getAmount()).isEqualTo(3999L);
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("order-1-refund");
    }

    // the deploy check reports a refused key as unusable rather than letting the exception out.
    @Test
    void credentialsValid_isFalse_whenStripeRefusesTheKey() throws Exception {
        BalanceService balances = mock(BalanceService.class);
        when(stripe.balance()).thenReturn(balances);
        when(balances.retrieve()).thenThrow(
                new AuthenticationException("bad key", "req_2", "invalid_api_key", 401));

        assertThat(gateway.credentialsValid()).isFalse();
    }

    // Stripe reporting the refund as failed is a failed result, so the order is left for a retry.
    @Test
    void refund_failedStatus_isNotSuccess() throws Exception {
        Refund refund = new Refund();
        refund.setId("re_2");
        refund.setStatus("failed");
        when(refunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);

        RefundResult result = gateway.refund(
                new RefundRequest("pi_123", new BigDecimal("39.99"), "order-2-refund"));

        assertThat(result.success()).isFalse();
    }
}
