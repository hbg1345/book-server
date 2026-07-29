package com.example.bookserver;

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

    @Test
    void findById_returnsNull_whenNotExists() {
        assertThat(userMapper.findById(UUID.randomUUID())).isNull();
    }

    @Test
    void insert_and_findById() {
        UUID userUuid = UUID.randomUUID();
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

    @Test
    void update() {
        UUID userUuid = UUID.randomUUID();
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

    @Test
    void delete() {
        UUID userUuid = UUID.randomUUID();
        userMapper.insert(sampleUser(userUuid));

        userMapper.delete(userUuid);

        assertThat(userMapper.findById(userUuid)).isNull();
    }
}
