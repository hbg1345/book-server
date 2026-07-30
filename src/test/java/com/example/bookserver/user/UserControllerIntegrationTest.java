package com.example.bookserver.user;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole stack under JWT security: register -> login (get tokens) ->
 * call a protected endpoint with the access token, all against the real DB. Proves the
 * BCrypt hash stored on register verifies on login and the JWT filter authenticates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String REGISTER_BODY = """
            {"userId":"jdoe","password":"secret","userName":"Jane Doe",
             "phone":"010-1234-5678","birthDate":"1990-05-20"}
            """;

    private static final String LOGIN_BODY = """
            {"userId":"jdoe","password":"secret"}
            """;

    // register -> login -> read own profile with the access token, end to end.
    @Test
    void register_login_me_roundTrip() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        String accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("jdoe"))
                .andExpect(jsonPath("$.userName").value("Jane Doe"))
                .andExpect(jsonPath("$.userPassword").doesNotExist());  // hash never leaks
    }

    // /me without a token -> 401 (the security entry point).
    @Test
    void me_returns401_withoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    // Wrong password against the real stored BCrypt hash -> 401 (the check mocks can't prove).
    @Test
    void login_returns401_whenWrongPassword() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
