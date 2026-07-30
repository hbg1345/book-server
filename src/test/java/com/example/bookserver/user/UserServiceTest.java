package com.example.bookserver.user;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class UserServiceTest {

    @Autowired
    private UserMapper userMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, passwordEncoder);
    }

    private UUID register(String userId) {
        return userService.register(userId, "secret", "Jane Doe",
                "010-1234-5678", LocalDate.of(1990, 5, 20));
    }

    // Verifies: the stored password is a BCrypt hash (not the plaintext), and the
    // hash verifies against the original password; other fields round-trip.
    @Test
    void register_storesHashedPassword_notPlaintext() {
        UUID userUuid = register("jdoe");

        User saved = userMapper.findById(userUuid);
        assertThat(saved).isNotNull();
        assertThat(saved.getUserId()).isEqualTo("jdoe");
        assertThat(saved.getUserPassword()).isNotEqualTo("secret");           // not plaintext
        assertThat(passwordEncoder.matches("secret", saved.getUserPassword())) // but verifies
                .isTrue();
    }

    // Verifies: registering an already-taken user_id is rejected.
    @Test
    void register_throws_whenUserIdTaken() {
        register("jdoe");

        assertThatThrownBy(() -> register("jdoe"))
                .isInstanceOf(DuplicateUserIdException.class);
    }

    // Verifies: isUserIdTaken reflects whether the id exists.
    @Test
    void isUserIdTaken_reflectsExistence() {
        assertThat(userService.isUserIdTaken("jdoe")).isFalse();
        register("jdoe");
        assertThat(userService.isUserIdTaken("jdoe")).isTrue();
    }

    // Verifies: getProfile returns the registered user; missing id throws.
    @Test
    void getProfile_returnsUser_orThrowsWhenAbsent() {
        UUID userUuid = register("jdoe");

        assertThat(userService.getProfile(userUuid).getUserId()).isEqualTo("jdoe");
        assertThatThrownBy(() -> userService.getProfile(Uuids.newId()))
                .isInstanceOf(UserNotFoundException.class);
    }

    // Verifies: updateProfile changes name/phone/birth but leaves the password hash intact.
    @Test
    void updateProfile_changesFields_keepsPassword() {
        UUID userUuid = register("jdoe");

        userService.updateProfile(userUuid, "Jane Smith", "010-9999-0000", LocalDate.of(1985, 1, 1));

        User saved = userMapper.findById(userUuid);
        assertThat(saved.getUserName()).isEqualTo("Jane Smith");
        assertThat(saved.getPhone()).isEqualTo("010-9999-0000");
        assertThat(saved.getBirthDate()).isEqualTo(LocalDate.of(1985, 1, 1));
        assertThat(passwordEncoder.matches("secret", saved.getUserPassword())).isTrue();  // unchanged
    }

    // Verifies: changePassword swaps the hash when the current password is correct.
    @Test
    void changePassword_replacesHash_whenCurrentMatches() {
        UUID userUuid = register("jdoe");

        userService.changePassword(userUuid, "secret", "newsecret");

        User saved = userMapper.findById(userUuid);
        assertThat(passwordEncoder.matches("secret", saved.getUserPassword())).isFalse();
        assertThat(passwordEncoder.matches("newsecret", saved.getUserPassword())).isTrue();
    }

    // Verifies: changePassword is rejected when the current password is wrong.
    @Test
    void changePassword_throws_whenCurrentWrong() {
        UUID userUuid = register("jdoe");

        assertThatThrownBy(() -> userService.changePassword(userUuid, "wrong", "newsecret"))
                .isInstanceOf(InvalidPasswordException.class);

        // password stays the original
        assertThat(passwordEncoder.matches("secret", userMapper.findById(userUuid).getUserPassword()))
                .isTrue();
    }

    // Verifies: withdraw deletes the user; withdrawing a missing user throws.
    @Test
    void withdraw_deletesUser_orThrowsWhenAbsent() {
        UUID userUuid = register("jdoe");

        userService.withdraw(userUuid);
        assertThat(userMapper.findById(userUuid)).isNull();

        assertThatThrownBy(() -> userService.withdraw(Uuids.newId()))
                .isInstanceOf(UserNotFoundException.class);
    }
}
