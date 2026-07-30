package com.example.bookserver.user;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import java.time.LocalDate;
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
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    // --- helper: a fully-populated user (createdAt left null; DB fills it) ---
    private User sampleUser(UUID userUuid) {
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId("jdoe");
        user.setUserPassword("secret");
        user.setUserName("Jane Doe");
        user.setPhone("010-1234-5678");
        user.setBirthDate(LocalDate.of(1990, 5, 20));
        return user;
    }

    // Verifies: findById returns null when no user with that id exists.
    @Test
    void findById_returnsNull_whenNotExists() {
        assertThat(userMapper.findById(Uuids.newId())).isNull();
    }

    // Verifies: an inserted user round-trips every field, and created_at is
    // auto-filled by the DB default (not set by the app).
    @Test
    void insert_and_findById() {
        UUID userUuid = Uuids.newId();
        userMapper.insert(sampleUser(userUuid));

        User found = userMapper.findById(userUuid);

        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo("jdoe");
        assertThat(found.getUserPassword()).isEqualTo("secret");
        assertThat(found.getUserName()).isEqualTo("Jane Doe");
        assertThat(found.getPhone()).isEqualTo("010-1234-5678");
        assertThat(found.getBirthDate()).isEqualTo(LocalDate.of(1990, 5, 20));
        assertThat(found.getCreatedAt()).isNotNull();   // filled by the DB default
    }

    // Verifies: findByUserId looks a user up by login id, and returns null when
    // no user has that id.
    @Test
    void findByUserId_returnsUser_orNullWhenAbsent() {
        UUID userUuid = Uuids.newId();
        userMapper.insert(sampleUser(userUuid));

        User found = userMapper.findByUserId("jdoe");
        assertThat(found).isNotNull();
        assertThat(found.getUserUuid()).isEqualTo(userUuid);

        assertThat(userMapper.findByUserId("nobody")).isNull();
    }

    // Verifies: update changes every updatable field (password/name/phone/
    // birth_date) while user_id and created_at stay untouched.
    @Test
    void update() {
        UUID userUuid = Uuids.newId();
        userMapper.insert(sampleUser(userUuid));

        // change every updatable field to a distinct value
        User changed = sampleUser(userUuid);
        changed.setUserPassword("newpass");
        changed.setUserName("Jane Smith");
        changed.setPhone("010-9999-0000");
        changed.setBirthDate(LocalDate.of(1985, 1, 1));
        userMapper.update(changed);

        User found = userMapper.findById(userUuid);
        assertThat(found.getUserPassword()).isEqualTo("newpass");
        assertThat(found.getUserName()).isEqualTo("Jane Smith");
        assertThat(found.getPhone()).isEqualTo("010-9999-0000");
        assertThat(found.getBirthDate()).isEqualTo(LocalDate.of(1985, 1, 1));
    }

    // Verifies: delete removes the user so it can no longer be found.
    @Test
    void delete() {
        UUID userUuid = Uuids.newId();
        userMapper.insert(sampleUser(userUuid));

        userMapper.delete(userUuid);

        assertThat(userMapper.findById(userUuid)).isNull();
    }
}
