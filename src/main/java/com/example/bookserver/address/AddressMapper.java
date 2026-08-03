package com.example.bookserver.address;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Address-book persistence. Every mutating query is scoped by user_uuid as well as
 * address_uuid, so a user can never update or delete another user's address even if
 * they guess its id. The "at most one default per user" rule is also backed by a
 * partial unique index (uq_address_one_default); the service clears the old default
 * before setting a new one, within one transaction.
 *
 * is_default is mapped explicitly because Lombok names the boolean property
 * `defaultAddress`, which underscore-to-camel-case would not derive from is_default.
 */
@Mapper
public interface AddressMapper {

    // created_at is omitted — the DB fills it via DEFAULT CURRENT_TIMESTAMP
    @Insert("""
            INSERT INTO address
                (address_uuid, user_uuid, alias, recipient, phone, country,
                 road_address, detail_address, postal_code, is_default)
            VALUES
                (#{addressUuid}, #{userUuid}, #{alias}, #{recipient}, #{phone}, #{country},
                 #{roadAddress}, #{detailAddress}, #{postalCode}, #{defaultAddress})
            """)
    void insert(Address address);

    @Select("SELECT * FROM address WHERE address_uuid = #{addressUuid}")
    @Results({
            @Result(property = "defaultAddress", column = "is_default")
    })
    Address findById(UUID addressUuid);

    // a user's whole address book; default first, then oldest-added, for a stable list
    @Select("""
            SELECT * FROM address
            WHERE user_uuid = #{userUuid}
            ORDER BY is_default DESC, created_at
            """)
    @Results({
            @Result(property = "defaultAddress", column = "is_default")
    })
    List<Address> findByUser(UUID userUuid);

    // owner-scoped update: the user_uuid guard means another user's row is never touched
    @Update("""
            UPDATE address SET
                alias = #{alias}, recipient = #{recipient}, phone = #{phone},
                country = #{country}, road_address = #{roadAddress},
                detail_address = #{detailAddress}, postal_code = #{postalCode},
                is_default = #{defaultAddress}
            WHERE address_uuid = #{addressUuid} AND user_uuid = #{userUuid}
            """)
    int update(Address address);

    // owner-scoped delete (idempotent); returns rows affected so the service can 404
    @Delete("DELETE FROM address WHERE address_uuid = #{addressUuid} AND user_uuid = #{userUuid}")
    int delete(@Param("addressUuid") UUID addressUuid, @Param("userUuid") UUID userUuid);

    // clear every default flag for a user; used before setting a new default so the
    // one-default-per-user invariant (and its partial unique index) holds
    @Update("UPDATE address SET is_default = FALSE WHERE user_uuid = #{userUuid}")
    void clearDefaultForUser(UUID userUuid);
}
