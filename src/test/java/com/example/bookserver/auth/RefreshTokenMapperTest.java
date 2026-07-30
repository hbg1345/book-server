package com.example.bookserver.auth;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class RefreshTokenMapperTest {

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private UserMapper userMapper;

    // FK parent: a refresh_token must reference an existing user
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

    private RefreshToken newToken(UUID userUuid, UUID familyId, String hash) {
        RefreshToken token = new RefreshToken();
        token.setTokenId(Uuids.newId());
        token.setFamilyId(familyId);
        token.setUserUuid(userUuid);
        token.setTokenHash(hash);
        token.setExpiresAt(LocalDateTime.of(2026, 8, 13, 10, 0));
        return token;   // used/revoked/created_at filled by DB defaults
    }

    // Verifies: an inserted token round-trips by hash; used/revoked default false
    // and created_at is auto-filled by the DB.
    @Test
    void insert_and_findByHash() {
        UUID userUuid = persistUser();
        UUID familyId = Uuids.newId();
        refreshTokenMapper.insert(newToken(userUuid, familyId, "hash-1"));

        RefreshToken found = refreshTokenMapper.findByHash("hash-1");

        assertThat(found).isNotNull();
        assertThat(found.getFamilyId()).isEqualTo(familyId);
        assertThat(found.getUserUuid()).isEqualTo(userUuid);
        assertThat(found.isUsed()).isFalse();
        assertThat(found.isRevoked()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();   // DB default
    }

    // Verifies: findByHash returns null for an unknown hash.
    @Test
    void findByHash_returnsNull_whenAbsent() {
        assertThat(refreshTokenMapper.findByHash("nope")).isNull();
    }

    // Verifies: markUsed flips the used flag (rotation consuming the old token).
    @Test
    void markUsed_setsUsedTrue() {
        UUID userUuid = persistUser();
        RefreshToken token = newToken(userUuid, Uuids.newId(), "hash-1");
        refreshTokenMapper.insert(token);

        refreshTokenMapper.markUsed(token.getTokenId());

        assertThat(refreshTokenMapper.findByHash("hash-1").isUsed()).isTrue();
    }

    // Verifies: revokeFamily revokes every token sharing the family_id, and leaves
    // tokens of a different family untouched.
    @Test
    void revokeFamily_revokesWholeFamilyOnly() {
        UUID userUuid = persistUser();
        UUID family = Uuids.newId();
        UUID otherFamily = Uuids.newId();
        refreshTokenMapper.insert(newToken(userUuid, family, "f1-a"));
        refreshTokenMapper.insert(newToken(userUuid, family, "f1-b"));
        refreshTokenMapper.insert(newToken(userUuid, otherFamily, "f2-a"));

        refreshTokenMapper.revokeFamily(family);

        assertThat(refreshTokenMapper.findByHash("f1-a").isRevoked()).isTrue();
        assertThat(refreshTokenMapper.findByHash("f1-b").isRevoked()).isTrue();
        assertThat(refreshTokenMapper.findByHash("f2-a").isRevoked()).isFalse();  // other family kept
    }
}
