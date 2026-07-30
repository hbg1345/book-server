package com.example.bookserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.bookserver.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack auth flow against the real DB: rotation, reuse detection, and logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String register_and_login_refreshToken() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret","userName":"Jane Doe",
                                 "phone":"010-1234-5678","birthDate":"1990-05-20"}
                                """))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.refreshToken");
    }

    private String refresh(String refreshToken) throws Exception {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }

    // login -> refresh rotates to a new token -> replaying the old token is rejected
    // and, because reuse revokes the family, the rotated token dies too.
    @Test
    void refresh_rotates_and_reuseRevokesFamily() throws Exception {
        String first = register_and_login_refreshToken();

        MvcResult rotated = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        String second = JsonPath.read(rotated.getResponse().getContentAsString(), "$.refreshToken");

        // replay the consumed token -> reuse detected -> 401
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(first)))
                .andExpect(status().isUnauthorized());

        // family is revoked, so the rotated token is dead too
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(second)))
                .andExpect(status().isUnauthorized());
    }

    // logout revokes the refresh token; a subsequent refresh with it is rejected.
    @Test
    void logout_thenRefresh_isRejected() throws Exception {
        String refreshToken = register_and_login_refreshToken();

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(refreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refresh(refreshToken)))
                .andExpect(status().isUnauthorized());
    }
}
