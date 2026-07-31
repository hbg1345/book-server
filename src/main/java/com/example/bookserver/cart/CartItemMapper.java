package com.example.bookserver.cart;

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

    // the whole cart joined with each book's title and price, for the cart view.
    // Oldest-added first so the list order is stable.
    @Select("""
            SELECT ci.book_uuid, ci.quantity, ci.created_at, b.book_title, b.price
            FROM cart_item ci
            JOIN book b ON b.book_uuid = ci.book_uuid
            WHERE ci.user_uuid = #{userUuid}
            ORDER BY ci.created_at
            """)
    @Results({
            @Result(property = "bookUuid", column = "book_uuid"),
            @Result(property = "bookTitle", column = "book_title"),
            @Result(property = "price", column = "price"),
            @Result(property = "quantity", column = "quantity"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<CartItemView> findByUserWithBook(UUID userUuid);

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

    // empty a user's whole cart (e.g. after its items are turned into an order)
    @Delete("DELETE FROM cart_item WHERE user_uuid = #{userUuid}")
    void deleteByUser(UUID userUuid);
}
