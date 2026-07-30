package com.example.bookserver.book;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface AuthorMapper {

    @Insert("""
            INSERT INTO author (author_uuid, author_name)
            VALUES (#{authorUuid}, #{authorName})
            """)
    void insert(Author author);

    @Select("""
            SELECT * FROM Author
            WHERE author_uuid = #{authorUuid}
            """)
    Author findById(UUID authorUuid);

    // name search — may return several homonyms (author_name is not unique);
    // callers disambiguate via each author's books (see findBookTitlesByAuthorId)
    @Select("""
            SELECT * FROM author
            WHERE author_name = #{name}
            ORDER BY author_uuid
            """)
    List<Author> findByName(String name);

    // titles of the books this author wrote — used to tell homonyms apart
    @Select("""
            SELECT b.book_title
            FROM book b
            JOIN book_author ba ON b.book_uuid = ba.book_uuid
            WHERE ba.author_uuid = #{authorUuid}
            ORDER BY b.book_uuid DESC
            """)
    List<String> findBookTitlesByAuthorId(UUID authorUuid);

    @Update("""
            UPDATE author
            SET author_name = #{authorName}
            WHERE author_uuid = #{authorUuid}
            """)
    void update(Author author);

    @Delete("""
            DELETE FROM author
            WHERE author_uuid = #{authorUuid}
            """)
    void delete(UUID authorUuid);
}
