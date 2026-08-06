package com.example.bookserver.book;

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

    // list view: book bodies only (authors not fetched to avoid N+1), newest first.
    // book_uuid is UUIDv7 (time-ordered), so DESC ≈ most-recently-created first.
    @Select("SELECT * FROM book ORDER BY book_uuid DESC")
    List<Book> findAll();

    // Title search: substring match, case-insensitive, newest first (book_uuid is UUIDv7).
    //
    // The nested replace() escapes the LIKE wildcards out of the user's text before it is
    // wrapped in '%...%'. This is not injection defence — #{title} is a bound parameter, so
    // the value never reaches the parser as SQL. It is about what LIKE does with the value
    // afterwards: '%' and '_' are wildcards *within the operator*, so a search for "100%"
    // would silently become "anything containing 100", and "100_" would match "1000". The
    // escaping keeps the result equal to what was typed, and lives here rather than in the
    // caller so that every caller gets it, not only the one that remembers.
    //
    // The limit is part of the query for the same reason: the catalogue holds ~103k rows and
    // this predicate cannot use an index, so trimming in Java would still have paid for the
    // full scan and the full transfer.
    @Select("""
            SELECT * FROM book
            WHERE book_title ILIKE '%' ||
                  replace(replace(replace(#{title}, '\\', '\\\\'), '%', '\\%'), '_', '\\_')
                  || '%' ESCAPE '\\'
            ORDER BY book_uuid DESC
            LIMIT #{limit}
            """)
    List<Book> searchByTitle(@Param("title") String title, @Param("limit") int limit);

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

    // Atomically reserve stock: only succeeds while enough inventory remains.
    // Returns the number of rows updated (1 = reserved, 0 = insufficient stock),
    // so the caller can detect an out-of-stock book without a separate read+race.
    @Update("""
            UPDATE book SET inventory = inventory - #{quantity}
            WHERE book_uuid = #{bookUuid} AND inventory >= #{quantity}
            """)
    int decrementInventory(@Param("bookUuid") UUID bookUuid, @Param("quantity") int quantity);

    // Give stock back (e.g. an order is cancelled). Always applies.
    @Update("""
            UPDATE book SET inventory = inventory + #{quantity}
            WHERE book_uuid = #{bookUuid}
            """)
    void incrementInventory(@Param("bookUuid") UUID bookUuid, @Param("quantity") int quantity);

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

    // remove all author links for a book (used to re-link on update)
    @Delete("DELETE FROM book_author WHERE book_uuid = #{bookUuid}")
    void unlinkAuthors(UUID bookUuid);

    @Delete("DELETE FROM book WHERE book_uuid = #{bookUuid}")
    void delete(UUID bookUuid);
}
