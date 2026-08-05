package com.example.bookserver.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;
import com.example.bookserver.common.InternalTokenGuard;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the deploy-time payment check. The value of the endpoint is that a pipeline can
 * fail on its status code, so the statuses are what these pin.
 */
@WebMvcTest(InternalPaymentController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class, InternalTokenGuard.class})
@TestPropertySource(properties = "internal.sweep-token=s3cret")
class InternalPaymentControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentGateway paymentGateway;

    // credentials accepted -> 200, so the deploy step passes
    @Test
    void health_returns200_whenTheProviderAcceptsOurCredentials() throws Exception {
        when(paymentGateway.credentialsValid()).thenReturn(true);
        when(paymentGateway.provider()).thenReturn("STRIPE");

        mockMvc.perform(get("/internal/payment/health").header("X-Internal-Token", "s3cret"))
                .andExpect(status().isOk());
    }

    // THE point of the endpoint: a revision holding a bad key is a failed deploy, not a healthy one
    @Test
    void health_returns503_whenTheProviderRefusesOurCredentials() throws Exception {
        when(paymentGateway.credentialsValid()).thenReturn(false);
        when(paymentGateway.provider()).thenReturn("STRIPE");

        mockMvc.perform(get("/internal/payment/health").header("X-Internal-Token", "s3cret"))
                .andExpect(status().isServiceUnavailable());
    }

    // it makes an outbound call per request, so an unauthenticated caller must not reach it
    @Test
    void health_returns403_whenTokenWrong_andNeverCallsTheProvider() throws Exception {
        mockMvc.perform(get("/internal/payment/health").header("X-Internal-Token", "nope"))
                .andExpect(status().isForbidden());

        verify(paymentGateway, never()).credentialsValid();
    }

    @Test
    void health_returns403_whenTokenMissing_andNeverCallsTheProvider() throws Exception {
        mockMvc.perform(get("/internal/payment/health"))
                .andExpect(status().isForbidden());

        verify(paymentGateway, never()).credentialsValid();
    }
}
