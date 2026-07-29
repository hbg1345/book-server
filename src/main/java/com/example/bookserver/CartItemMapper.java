package com.example.bookserver;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CartItemMapper {

    // created_at is omitted — the DB fills it via DEFAULT CURRENT_TIMESTAMP
    @Insert("""
            INSERT INTO cart_item (user_uuid, book_uuid, quantity)
            VALUES (#{userUuid}, #{bookUuid}, #{quantity})
            """)
    void insert(CartItem cartItem);

    @Select("""
            SELECT * FROM cart_item
            WHERE user_uuid = #{userUuid} AND book_uuid = #{bookUuid}
            """)
    CartItem findByUserAndBook(@Param("userUuid") UUID userUuid, @Param("bookUuid") UUID bookUuid);

    // the whole cart for one user
    @Select("SELECT * FROM cart_item WHERE user_uuid = #{userUuid}")
    List<CartItem> findByUser(UUID userUuid);

    @Update("""
            UPDATE cart_item SET quantity = #{quantity}
            WHERE user_uuid = #{userUuid} AND book_uuid = #{bookUuid}
            """)
    void updateQuantity(@Param("userUuid") UUID userUuid,
                        @Param("bookUuid") UUID bookUuid,
                        @Param("quantity") int quantity);

    @Delete("""
            DELETE FROM cart_item
            WHERE user_uuid = #{userUuid} AND book_uuid = #{bookUuid}
            """)
    void delete(@Param("userUuid") UUID userUuid, @Param("bookUuid") UUID bookUuid);
}
