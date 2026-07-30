package com.example.bookserver;

import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    // created_at is omitted — the DB fills it via DEFAULT CURRENT_TIMESTAMP
    @Insert("""
            INSERT INTO book_user
                (user_uuid, user_id, user_password, user_name, phone, birth_date)
            VALUES
                (#{userUuid}, #{userId}, #{userPassword}, #{userName}, #{phone}, #{birthDate})
            """)
    void insert(User user);

    @Select("SELECT * FROM book_user WHERE user_uuid = #{userUuid}")
    User findById(UUID userUuid);

    // login id lookup — used for duplicate-id checks (and later, authentication)
    @Select("SELECT * FROM book_user WHERE user_id = #{userId}")
    User findByUserId(String userId);

    // user_id (login id) and created_at are immutable
    @Update("""
            UPDATE book_user SET
                user_password = #{userPassword},
                user_name = #{userName},
                phone = #{phone},
                birth_date = #{birthDate}
            WHERE user_uuid = #{userUuid}
            """)
    void update(User user);

    @Delete("DELETE FROM book_user WHERE user_uuid = #{userUuid}")
    void delete(UUID userUuid);
}
