package com.example.bookserver.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the internal, Cloud-Scheduler-driven expiry endpoint. It is
 * outside the JWT surface and guarded only by the shared {@code X-Internal-Token} secret.
 */
@WebMvcTest(InternalOrderController.class)
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
@TestPropertySource(properties = "internal.sweep-token=s3cret")
class InternalOrderControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UnpaidOrderSweeper unpaidOrderSweeper;

    // valid token -> 200 with the number of orders cancelled; the sweep runs
    @Test
    void expireUnpaid_returnsCount_whenTokenValid() throws Exception {
        when(unpaidOrderSweeper.sweep()).thenReturn(3);

        mockMvc.perform(post("/internal/orders/expire-unpaid").header("X-Internal-Token", "s3cret"))
                .andExpect(status().isOk())
                .andExpect(content().string("3"));

        verify(unpaidOrderSweeper).sweep();
    }

    // wrong token -> 403 and the sweep never runs
    @Test
    void expireUnpaid_returns403_whenTokenWrong() throws Exception {
        mockMvc.perform(post("/internal/orders/expire-unpaid").header("X-Internal-Token", "nope"))
                .andExpect(status().isForbidden());

        verify(unpaidOrderSweeper, never()).sweep();
    }

    // missing token -> 403 and the sweep never runs
    @Test
    void expireUnpaid_returns403_whenTokenMissing() throws Exception {
        mockMvc.perform(post("/internal/orders/expire-unpaid"))
                .andExpect(status().isForbidden());

        verify(unpaidOrderSweeper, never()).sweep();
    }
}
