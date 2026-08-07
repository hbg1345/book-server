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

    // owner-scoped single fetch: used when snapshotting a saved address onto an order, so a
    // user can never order to (or probe the existence of) another user's address by guessing id
    @Select("SELECT * FROM address WHERE address_uuid = #{addressUuid} AND user_uuid = #{userUuid}")
    @Results({
            @Result(property = "defaultAddress", column = "is_default")
    })
    Address findByIdAndUser(@Param("addressUuid") UUID addressUuid, @Param("userUuid") UUID userUuid);

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

    /**
     * Take the user's row so that only one writer at a time may re-point their default address.
     *
     * <p>The clear-then-write pair above is not safe on its own: under READ COMMITTED the clear
     * scans the snapshot its statement began with, so a default row another transaction has just
     * inserted is invisible to it and survives the clear — and the write that follows then
     * collides with that survivor on uq_address_one_default. Blocking here means the second
     * writer runs its clear as a fresh statement after the first has committed, which does see
     * the new row. The lock is always taken before any address row, so the order cannot deadlock.
     *
     * <p>book_user rather than the address rows themselves, because the first address a user
     * saves has no row to lock yet — the case where the clear matches nothing and takes no locks
     * at all. See #61.
     *
     * <p>FOR NO KEY UPDATE, not FOR UPDATE. Five tables carry a foreign key to book_user
     * (refresh_token, cart_item, purchase_history, purchase_current, address), and inserting
     * into any of them takes FOR KEY SHARE on the referenced user row. FOR UPDATE conflicts
     * with that, so holding it would park this user's logins, cart additions and checkouts
     * behind an address edit that has nothing to do with them. FOR NO KEY UPDATE conflicts only
     * with the other default-changing writers, which is the whole of what needs ordering — the
     * user row itself is only read here, never modified.
     */
    @Select("SELECT 1 FROM book_user WHERE user_uuid = #{userUuid} FOR NO KEY UPDATE")
    Integer lockUserForDefaultChange(UUID userUuid);
}
