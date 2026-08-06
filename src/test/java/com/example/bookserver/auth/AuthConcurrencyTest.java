package com.example.bookserver.auth;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.Concurrently;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.DuplicateUserIdException;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;
import com.example.bookserver.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two auth paths that check a value and then write based on it. Both are reachable without any
 * unusual client behaviour: a page with two tabs refreshes twice, and a signup form submitted
 * twice sends the same id twice.
 *
 * <p>See #59.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class AuthConcurrencyTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;

    private UUID persistUser() {
        UUID userUuid = Uuids.newId();
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId("u-" + userUuid);
        user.setUserPassword("secret");
        user.setUserName("Jane Doe");
        user.setPhone("010-1234-5678");
        user.setBirthDate(LocalDate.of(1990, 5, 20));
        userMapper.insert(user);
        return userUuid;
    }

    /**
     * One refresh token, rotated from two tabs at the same instant.
     *
     * <p>{@code rotate} reads the row, checks {@code used}, then marks it used. Both callers can
     * read {@code used = false}, so both mint a successor: the family ends up with two live
     * tokens descended from one, and the theft detection that the whole rotation scheme exists
     * for never fires. A real attacker replaying a stolen token alongside the owner's refresh
     * would get exactly this treatment.
     *
     * <p>Either outcome is acceptable — one rotation succeeding and the other being rejected, or
     * both being rejected as a suspected replay. What is not acceptable is two successors.
     */
    @Test
    void rotatingOneTokenTwiceAtOnce_yieldsAtMostOneSuccessor() throws Exception {
        UUID userUuid = persistUser();
        String token = refreshTokenService.issue(userUuid);

        List<RotationResult> results = Concurrently.runAtOnce(2, () -> {
            try {
                return refreshTokenService.rotate(token);
            } catch (InvalidRefreshTokenException e) {
                return null;   // the legitimate way to lose
            }
        });

        assertThat(results.stream().filter(java.util.Objects::nonNull).count())
                .as("one token in, at most one token out")
                .isLessThanOrEqualTo(1);
    }

    /**
     * The same login id registered twice at once. {@code register} checks whether the id is
     * taken and then inserts, so both callers can pass the check; the unique index then rejects
     * the loser with a raw {@code DataIntegrityViolationException}, which nothing in
     * {@link com.example.bookserver.common.GlobalExceptionHandler} maps — a 500 where the API
     * contract promises 409.
     */
    @Test
    void registeringOneIdTwiceAtOnce_reportsTheDuplicateAsSuch() throws Exception {
        String userId = "racer-" + Uuids.newId();

        List<Callable<Boolean>> signups = List.of(
                () -> registerReportingDuplicate(userId),
                () -> registerReportingDuplicate(userId));

        List<Boolean> results = Concurrently.runAtOnce(signups);

        assertThat(results).as("exactly one signup wins, the other is told the id is taken")
                .containsExactlyInAnyOrder(true, false);
        assertThat(userMapper.findByUserId(userId)).isNotNull();
    }

    /** @return true if the account was created, false if the id was reported as already taken. */
    private boolean registerReportingDuplicate(String userId) {
        try {
            userService.register(userId, "secret", "Jane Doe", "010-1234-5678",
                    LocalDate.of(1990, 5, 20));
            return true;
        } catch (DuplicateUserIdException e) {
            return false;
        }
    }
}
