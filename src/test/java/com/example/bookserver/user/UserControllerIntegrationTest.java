package com.example.bookserver.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.bookserver.TestcontainersConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole stack: real controller -> real {@link UserService} -> real
 * mapper -> real PostgreSQL (Testcontainers). Deliberately minimal — it only proves the
 * layers are wired together and real login works end-to-end (BCrypt hash stored on
 * register actually verifies on login). The exhaustive per-case checks live in the fast
 * {@code UserControllerWebMvcTest} slice.
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

    // register -> login -> read own profile, all against the real DB and a real session.
    @Test
    void register_login_me_roundTrip() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userUuid").isNotEmpty());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"jdoe","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("jdoe"))
                .andExpect(jsonPath("$.userName").value("Jane Doe"))
                .andExpect(jsonPath("$.userPassword").doesNotExist());  // hash never leaks
    }

    // Wrong password against the real stored BCrypt hash -> 401 (the check Mocks can't prove).
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
