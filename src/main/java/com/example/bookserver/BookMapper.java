package com.example.bookserver;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BookMapper {

    @Insert("""
            INSERT INTO book
                (book_uuid, book_title, book_description, price, publish_date, publisher, inventory)
            VALUES
                (#{bookUuid}, #{bookTitle}, #{bookDescription}, #{price}, #{publishDate}, #{publisher}, #{inventory})
            """)
    void insert(Book book);

    @Insert("""
            INSERT INTO book_author (book_uuid, author_uuid)
            VALUES (#{bookUuid}, #{authorUuid})
            """)
    void linkAuthor(@Param("bookUuid") UUID bookUuid, @Param("authorUuid") UUID authorUuid);

    // book body only — authors left null (not fetched)
    @Select("SELECT * FROM book WHERE book_uuid = #{bookUuid}")
    Book findById(UUID bookUuid);

    // book plus its authors, assembled via the nested @Many query
    @Select("SELECT * FROM book WHERE book_uuid = #{bookUuid}")
    @Results(id = "bookResult", value = {
            @Result(property = "bookUuid", column = "book_uuid", id = true),
            @Result(property = "bookTitle", column = "book_title"),
            @Result(property = "bookDescription", column = "book_description"),
            @Result(property = "price", column = "price"),
            @Result(property = "publishDate", column = "publish_date"),
            @Result(property = "publisher", column = "publisher"),
            @Result(property = "inventory", column = "inventory"),
            @Result(property = "authors", column = "book_uuid",
                    many = @Many(select = "findAuthorsByBookId"))
    })
    Book findByIdWithAuthors(UUID bookUuid);

    @Select("""
            SELECT a.author_uuid, a.author_name
            FROM author a
            JOIN book_author ba ON a.author_uuid = ba.author_uuid
            WHERE ba.book_uuid = #{bookUuid}
            """)
    @Results(id = "authorList", value= {
            @Result(property = "authorUuid", column = "author_uuid", id = true),
            @Result(property = "authorName", column = "author_name")
    })
    List<Author> findAuthorsByBookId(UUID bookUuid);

    @Update("""
            UPDATE book SET
                book_title = #{bookTitle},
                book_description = #{bookDescription},
                price = #{price},
                publish_date = #{publishDate},
                publisher = #{publisher},
                inventory = #{inventory}
            WHERE book_uuid = #{bookUuid}
            """)
    void update(Book book);

    @Delete("DELETE FROM book WHERE book_uuid = #{bookUuid}")
    void delete(UUID bookUuid);
}
