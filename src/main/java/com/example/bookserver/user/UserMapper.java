package com.example.bookserver.user;

import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    // created_at is omitted — the DB fills it via DEFAULT CURRENT_TIMESTAMP.
    // role falls back to the column default ('USER') when the caller leaves it unset.
    @Insert("""
            INSERT INTO book_user
                (user_uuid, user_id, user_password, user_name, phone, birth_date, role)
            VALUES
                (#{userUuid}, #{userId}, #{userPassword}, #{userName}, #{phone}, #{birthDate},
                 COALESCE(#{role,jdbcType=VARCHAR}, 'USER'))
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

    @Update("UPDATE book_user SET role = #{role} WHERE user_uuid = #{userUuid}")
    void updateRole(@Param("userUuid") UUID userUuid, @Param("role") Role role);

    @Delete("DELETE FROM book_user WHERE user_uuid = #{userUuid}")
    void delete(UUID userUuid);
}
