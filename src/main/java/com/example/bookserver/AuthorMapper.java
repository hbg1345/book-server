package com.example.bookserver;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthorMapper {

    @Insert("""
            INSERT INTO author (author_uuid, author_name)
            VALUES (#{authorUuid}, #{authorName})
            """)
    void insert(Author author);
}
