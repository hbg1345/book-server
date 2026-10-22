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
import static com.example.bookserver.docs.ApiDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
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
                                fieldWithPath("userId").description("로그인 아이디"),
                                fieldWithPath("password").description("비밀번호")),
                        responseFields(
                                fieldWithPath("accessToken").description("인증 헤더에 사용하는 액세스 토큰(JWT)"),
                                fieldWithPath("refreshToken").description("액세스 토큰 갱신에 사용하는 리프레시 토큰"))));

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
                .andExpect(status().isUnauthorized())
                .andDo(document("auth-login--login_returns401_whenBadCredentials"));
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
                                fieldWithPath("refreshToken").description("현재 리프레시 토큰")),
                        responseFields(
                                fieldWithPath("accessToken").description("새 액세스 토큰"),
                                fieldWithPath("refreshToken").description("새 리프레시 토큰. 기존 토큰은 폐기됩니다"))));
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
                .andExpect(status().isUnauthorized())
                .andDo(document("auth-refresh--refresh_returns401_whenInvalid"));
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
                                fieldWithPath("refreshToken").description("폐기할 리프레시 토큰"))));

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
                .andExpect(status().isBadRequest())
                .andDo(document("auth-refresh--refresh_returns400_whenBlank"));
    }
}
