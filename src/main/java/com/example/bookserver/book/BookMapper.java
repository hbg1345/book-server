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
                (book_uuid, isbn, book_title, book_description, price, publish_date, publisher, inventory)
            VALUES
                (#{bookUuid}, #{isbn}, #{bookTitle}, #{bookDescription}, #{price}, #{publishDate}, #{publisher}, #{inventory})
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

    // Title search: literal substring and typo-tolerant trigram candidates, relevance first.
    //
    // The nested replace() escapes the LIKE wildcards out of the user's text before it is
    // wrapped in '%...%'. This is not injection defence — #{title} is a bound parameter, so
    // the value never reaches the parser as SQL. It is about what LIKE does with the value
    // afterwards: '%' and '_' are wildcards *within the operator*, so a search for "100%"
    // would silently become "anything containing 100", and "100_" would match "1000". The
    // escaping keeps the result equal to what was typed, and lives here rather than in the
    // caller so that every caller gets it, not only the one that remembers.
    //
    // Fuzzy matching starts at three characters and is disabled when the user deliberately
    // typed a LIKE metacharacter. That preserves the literal '%'/'_' contract instead of
    // quietly returning a merely similar title after escaping the substring branch.
    //
    // Full-title equality is the strongest signal. Remaining candidates use the best matching
    // title extent, whole-title similarity, title-start position and compactness. UUIDv7 is only
    // a deterministic final tie-breaker; recency is not treated as relevance.
    //
    // Each parenthesised branch follows the first two fuzzy ranking keys before it is capped.
    // Both distances are GiST KNN orderings. WITH TIES keeps the complete score group at the
    // boundary; without it, asking for 20 and then 40 candidates could select different members
    // of an equal-score group and make adjacent pages overlap. UNION removes matches shared by
    // the literal, word and whole-title sources before the deterministic final tie-breakers.
    @Select("""
            WITH candidates AS MATERIALIZED (
                (SELECT * FROM book
                 WHERE book_title ILIKE '%' ||
                       replace(replace(replace(#{title}, '\\', '\\\\'), '%', '\\%'), '_', '\\_')
                       || '%' ESCAPE '\\'
                 ORDER BY #{title} <<-> book_title, #{title} <-> book_title
                 FETCH FIRST (#{offset} + #{limit}) ROWS WITH TIES)
                UNION
                (SELECT * FROM book
                 WHERE char_length(#{title}) >= 3
                   AND strpos(#{title}, '%') = 0
                   AND strpos(#{title}, '_') = 0
                   AND strpos(#{title}, chr(92)) = 0
                   AND #{title} <% book_title
                 ORDER BY #{title} <<-> book_title, #{title} <-> book_title
                 FETCH FIRST (#{offset} + #{limit}) ROWS WITH TIES)
                UNION
                (SELECT * FROM book
                 WHERE char_length(#{title}) >= 3
                   AND strpos(#{title}, '%') = 0
                   AND strpos(#{title}, '_') = 0
                   AND strpos(#{title}, chr(92)) = 0
                   AND book_title % #{title}
                 ORDER BY #{title} <<-> book_title, #{title} <-> book_title
                 FETCH FIRST (#{offset} + #{limit}) ROWS WITH TIES)
            )
            SELECT * FROM candidates
            ORDER BY
                CASE WHEN lower(book_title) = lower(#{title}) THEN 0 ELSE 1 END,
                word_similarity(#{title}, book_title) DESC,
                similarity(#{title}, book_title) DESC,
                CASE WHEN starts_with(lower(book_title), lower(#{title})) THEN 0 ELSE 1 END,
                char_length(book_title),
                book_uuid DESC
            OFFSET #{offset}
            LIMIT #{limit}
            """)
    List<Book> searchByTitle(@Param("title") String title,
                             @Param("offset") long offset,
                             @Param("limit") int limit);

    // Bounded look-ahead for numeric navigation. This is not a count of every search hit: the
    // inner query stops after the five visible pages plus one row. The extra row is enough to
    // decide whether the client should render >> for the next block. Unlike the ranked result
    // query, this only asks whether enough matches exist; GIN can filter that bounded window
    // without paying for similarity ordering.
    @Select("""
            SELECT COUNT(*)
            FROM (
                SELECT 1
                FROM book
                WHERE (book_title ILIKE '%' ||
                       replace(replace(replace(#{title}, '\\', '\\\\'), '%', '\\%'), '_', '\\_')
                       || '%' ESCAPE '\\')
                   OR (char_length(#{title}) >= 3
                       AND strpos(#{title}, '%') = 0
                       AND strpos(#{title}, '_') = 0
                       AND strpos(#{title}, chr(92)) = 0
                       AND (book_title %> #{title} OR book_title % #{title}))
                OFFSET #{offset}
                LIMIT #{limit}
            ) navigation_window
            """)
    int countSearchWindow(@Param("title") String title,
                          @Param("offset") long offset,
                          @Param("limit") int limit);

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

    // Move stock by a delta, refusing to go below zero. Relative and conditional for the same
    // reason decrementInventory is: a total read a moment ago is a total that may already be
    // wrong, and writing it back would erase whatever happened in between.
    // Returns rows updated (1 = applied, 0 = would go negative).
    @Update("""
            UPDATE book SET inventory = inventory + #{delta}
            WHERE book_uuid = #{bookUuid} AND inventory + #{delta} >= 0
            """)
    int adjustInventory(@Param("bookUuid") UUID bookUuid, @Param("delta") int delta);

    // The catalogue entry only. inventory is not listed here on purpose: it is not the editor's
    // to restate, and every column named in a SET is a column this statement will overwrite
    // with whatever the caller last read.
    @Update("""
            UPDATE book SET
                book_title = #{bookTitle},
                book_description = #{bookDescription},
                price = #{price},
                publish_date = #{publishDate},
                publisher = #{publisher}
            WHERE book_uuid = #{bookUuid}
            """)
    void update(Book book);

    // remove all author links for a book (used to re-link on update)
    @Delete("DELETE FROM book_author WHERE book_uuid = #{bookUuid}")
    void unlinkAuthors(UUID bookUuid);

    @Delete("DELETE FROM book WHERE book_uuid = #{bookUuid}")
    void delete(UUID bookUuid);
}
