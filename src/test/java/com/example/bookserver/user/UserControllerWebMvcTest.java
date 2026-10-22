package com.example.bookserver.user;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.example.bookserver.docs.ApiDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the user endpoints under the real security filter chain.
 * Registration is public; the {@code /me} endpoints require an authenticated principal
 * (the user's uuid, as the JWT filter would set it).
 */
@WebMvcTest(UserController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    /** Request post-processor: authenticate as the given user uuid (what the JWT filter does). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor asUser(UUID uuid) {
        return authentication(new UsernamePasswordAuthenticationToken(uuid, null, List.of()));
    }

    // register: 201 + generated uuid; service receives the parsed fields
    @Test
    void register_returns201AndUuid() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(userService.register(eq("jdoe"), eq("secret"), eq("Jane Doe"),
                eq("010-1234-5678"), eq(LocalDate.of(1990, 5, 20)))).thenReturn(uuid);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret","userName":"Jane Doe",
                                 "phone":"010-1234-5678","birthDate":"1990-05-20"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userUuid").value(uuid.toString()))
                .andDo(document("user-register",
                        requestFields(
                                fieldWithPath("userId").description("로그인 아이디(중복 불가)"),
                                fieldWithPath("password").description("가입할 비밀번호(서버에서 해시하여 저장)"),
                                fieldWithPath("userName").description("회원 이름"),
                                fieldWithPath("phone").description("전화번호(예: 010-1234-5678)"),
                                fieldWithPath("birthDate").description("생년월일(yyyy-MM-dd)")),
                        responseFields(
                                fieldWithPath("userUuid").description("가입한 회원 식별자(UUID)"))));

        verify(userService).register("jdoe", "secret", "Jane Doe",
                "010-1234-5678", LocalDate.of(1990, 5, 20));
    }

    // register with a taken id -> 409
    @Test
    void register_returns409_whenDuplicate() throws Exception {
        when(userService.register(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateUserIdException("jdoe"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret","userName":"Jane Doe",
                                 "phone":"010-1234-5678","birthDate":"1990-05-20"}
                                """))
                .andExpect(status().isConflict())
                .andDo(document("user-register--register_returns409_whenDuplicate"));
    }

    // invalid body -> 400 problem+json with per-field errors; service never reached
    @Test
    void register_returns400WithFieldErrors_whenInvalid() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"","password":"secret","userName":"Jane Doe",
                                 "phone":"123","birthDate":"1990-05-20"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.userId").exists())
                .andExpect(jsonPath("$.errors.phone").value("must match the format 010-1234-5678"))
                .andDo(document("user-register--register_returns400WithFieldErrors_whenInvalid"));

        verify(userService, never()).register(any(), any(), any(), any(), any());
    }

    // /me without authentication -> 401
    @Test
    void me_returns401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andDo(document("user-me--me_returns401_whenNotAuthenticated"));
    }

    // /me authenticated -> 200 and the profile JSON never carries the password
    @Test
    void me_returnsProfileWithoutPassword() throws Exception {
        UUID uuid = UUID.randomUUID();
        User user = new User(uuid, "jdoe", "HASHED", "Jane Doe",
                "010-1234-5678", LocalDate.of(1990, 5, 20), Role.USER,
                java.time.LocalDateTime.of(2026, 7, 30, 10, 0));
        when(userService.getProfile(uuid)).thenReturn(user);

        mockMvc.perform(get("/api/users/me").with(asUser(uuid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("jdoe"))
                .andExpect(jsonPath("$.userName").value("Jane Doe"))
                .andExpect(jsonPath("$.userPassword").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andDo(document("user-me",
                        responseFields(
                                fieldWithPath("userUuid").description("회원 식별자(UUID)"),
                                fieldWithPath("userId").description("로그인 아이디"),
                                fieldWithPath("userName").description("회원 이름"),
                                fieldWithPath("phone").description("전화번호"),
                                fieldWithPath("birthDate").description("생년월일"),
                                fieldWithPath("createdAt").description("가입 일시"))));
    }

    @Test
    void updateProfile_updatesTheAuthenticatedUser() throws Exception {
        UUID uuid = UUID.randomUUID();
        mockMvc.perform(put("/api/users/me").with(asUser(uuid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"홍길동","phone":"010-1234-5678","birthDate":"1990-05-20"}
                                """))
                .andExpect(status().isOk())
                .andDo(document("user-update", requestFields(
                        fieldWithPath("userName").description("회원 이름(최대 100자)"),
                        fieldWithPath("phone").description("전화번호(예: 010-1234-5678)"),
                        fieldWithPath("birthDate").description("생년월일(yyyy-MM-dd, 과거 날짜)"))));
        verify(userService).updateProfile(uuid, "홍길동", "010-1234-5678", LocalDate.of(1990, 5, 20));
    }

    @Test
    void changePassword_acceptsTheCurrentPassword() throws Exception {
        UUID uuid = UUID.randomUUID();
        mockMvc.perform(put("/api/users/me/password").with(asUser(uuid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldsecret","newPassword":"newsecret"}
                                """))
                .andExpect(status().isOk())
                .andDo(document("user-password", requestFields(
                        fieldWithPath("currentPassword").description("현재 비밀번호"),
                        fieldWithPath("newPassword").description("새 비밀번호"))));
        verify(userService).changePassword(uuid, "oldsecret", "newsecret");
    }

    // changePassword with a wrong current password -> 400
    @Test
    void changePassword_returns400_whenCurrentWrong() throws Exception {
        UUID uuid = UUID.randomUUID();
        doThrow(new InvalidPasswordException())
                .when(userService).changePassword(eq(uuid), eq("wrong"), eq("newsecret"));

        mockMvc.perform(put("/api/users/me/password").with(asUser(uuid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong","newPassword":"newsecret"}
                                """))
                .andExpect(status().isBadRequest())
                .andDo(document("user-password--changePassword_returns400_whenCurrentWrong"));
    }

    // withdraw: delegates to the service with the authenticated user
    @Test
    void withdraw_delegatesToService() throws Exception {
        UUID uuid = UUID.randomUUID();

        mockMvc.perform(delete("/api/users/me").with(asUser(uuid)))
                .andExpect(status().isOk())
                .andDo(document("user-withdraw"));

        verify(userService).withdraw(uuid);
    }
}
