package com.example.bookserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.dto.TokenResponse;
import com.example.bookserver.common.GlobalExceptionHandler;
import com.example.bookserver.user.InvalidCredentialsException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the auth endpoints under the real security filter chain
 * ({@link AuthService} mocked). Auth endpoints are public; this verifies HTTP wiring,
 * token JSON, and error mapping.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    // login: 200 + token pair
    @Test
    void login_returns200AndTokens() throws Exception {
        when(authService.login("jdoe", "secret"))
                .thenReturn(new TokenResponse("access-jwt", "refresh-opaque"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-opaque"))
                .andDo(document("auth-login",
                        requestFields(
                                fieldWithPath("userId").description("Login id"),
                                fieldWithPath("password").description("Password")),
                        responseFields(
                                fieldWithPath("accessToken").description("Short-lived JWT for the Authorization header"),
                                fieldWithPath("refreshToken").description("Opaque token to obtain new access tokens"))));

        verify(authService).login("jdoe", "secret");
    }

    // login with bad credentials -> 401
    @Test
    void login_returns401_whenBadCredentials() throws Exception {
        when(authService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // refresh: 200 + a new token pair
    @Test
    void refresh_returns200AndTokens() throws Exception {
        when(authService.refresh("refresh-opaque"))
                .thenReturn(new TokenResponse("new-access", "new-refresh"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"refresh-opaque"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"))
                .andDo(document("auth-refresh",
                        requestFields(
                                fieldWithPath("refreshToken").description("The current refresh token")),
                        responseFields(
                                fieldWithPath("accessToken").description("A new access token"),
                                fieldWithPath("refreshToken").description("A rotated refresh token (the old one is now dead)"))));
    }

    // refresh with an invalid/expired/replayed token -> 401
    @Test
    void refresh_returns401_whenInvalid() throws Exception {
        when(authService.refresh(any())).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"stale"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // logout: 200 and delegates to the service
    @Test
    void logout_delegatesToService() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"refresh-opaque"}
                                """))
                .andExpect(status().isOk())
                .andDo(document("auth-logout",
                        requestFields(
                                fieldWithPath("refreshToken").description("The refresh token to revoke"))));

        verify(authService).logout("refresh-opaque");
    }

    // blank refresh token -> 400 (validation), service not reached
    @Test
    void refresh_returns400_whenBlank() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":""}
                                """))
                .andExpect(status().isBadRequest());
    }
}
