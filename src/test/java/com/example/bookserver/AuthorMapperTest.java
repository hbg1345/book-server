package com.example.bookserver;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class AuthorMapperTest {

    @Autowired
    private AuthorMapper authorMapper;

    // Verifies: findById returns null when no author with that id exists.
    @Test
    void findById_returnsNull_whenNotExists() {
        Author found = authorMapper.findById(UUID.randomUUID());
        assertThat(found).isNull();
    }

    // Verifies: an inserted author can be read back by id with its fields intact.
    @Test
    void findById() {
        UUID authorUuid = UUID.randomUUID();
        String authorName = "Jane Doe";
        Author author = new Author(authorUuid, authorName);

        authorMapper.insert(author);
        Author found = authorMapper.findById(authorUuid);

        assertThat(found).isNotNull();
        assertThat(found.getAuthorUuid()).isEqualTo(authorUuid);
        assertThat(found.getAuthorName()).isEqualTo(authorName);
    }

    // Verifies: update changes the author's name and the new value persists.
    @Test
    void update() {
        UUID authorUuid = UUID.randomUUID();
        authorMapper.insert(new Author(authorUuid, "Jane Doe"));

        authorMapper.update(new Author(authorUuid, "Jane Smith"));

        Author found = authorMapper.findById(authorUuid);
        assertThat(found.getAuthorName()).isEqualTo("Jane Smith");
    }

    // Verifies: delete removes the author so it can no longer be found.
    @Test
    void delete() {
        UUID authorUuid = UUID.randomUUID();
        authorMapper.insert(new Author(authorUuid, "Jane Doe"));

        authorMapper.delete(authorUuid);

        assertThat(authorMapper.findById(authorUuid)).isNull();
    }
}
