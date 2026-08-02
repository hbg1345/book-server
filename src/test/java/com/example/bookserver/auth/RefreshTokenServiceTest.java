package com.example.bookserver.auth;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class RefreshTokenServiceTest {

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private UserMapper userMapper;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(refreshTokenMapper, Duration.ofDays(14));
    }

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

    // rotate consumes the old token and issues a new one for the same user.
    @Test
    void rotate_issuesNewToken_forSameUser() {
        UUID userUuid = persistUser();
        String first = service.issue(userUuid);

        RotationResult result = service.rotate(first);

        assertThat(result.userUuid()).isEqualTo(userUuid);
        assertThat(result.refreshToken()).isNotEqualTo(first);   // rotated to a new value
    }

    // replaying an already-rotated token is treated as theft: the whole family is
    // revoked, so even the legitimately rotated token stops working.
    @Test
    void rotate_detectsReuse_andRevokesFamily() {
        UUID userUuid = persistUser();
        String first = service.issue(userUuid);
        String second = service.rotate(first).refreshToken();   // first now consumed

        // replay the consumed token -> reuse detected
        assertThatThrownBy(() -> service.rotate(first))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // the family is revoked, so the current (second) token is dead too
        assertThatThrownBy(() -> service.rotate(second))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // an unknown token is rejected.
    @Test
    void rotate_throws_whenUnknown() {
        assertThatThrownBy(() -> service.rotate("no-such-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // an expired token is rejected.
    @Test
    void rotate_throws_whenExpired() {
        UUID userUuid = persistUser();
        RefreshTokenService shortLived =
                new RefreshTokenService(refreshTokenMapper, Duration.ofSeconds(-1));
        String expired = shortLived.issue(userUuid);

        assertThatThrownBy(() -> service.rotate(expired))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // logout revokes the token so it can no longer be rotated.
    @Test
    void revoke_invalidatesToken() {
        UUID userUuid = persistUser();
        String token = service.issue(userUuid);

        service.revoke(token);

        assertThatThrownBy(() -> service.rotate(token))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
