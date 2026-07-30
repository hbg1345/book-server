package com.example.bookserver.book;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
