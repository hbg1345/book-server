package com.example.bookserver.cart;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
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

    // Add to the line, creating it if this is the first copy.
    //
    // One statement rather than "read the line, then insert or update it": between that read and
    // that write a second click can insert the same (user, book), and the loser then hits the
    // primary key with nothing mapping the violation to a status — a 500 for pressing a button
    // twice. When the line did exist, both clicks read the same quantity and write read+1, so one
    // addition disappears with no error at all.
    //
    // ON CONFLICT lets the database settle it: the second caller's insert becomes an increment of
    // whatever is committed at that moment, so both additions land whichever order they arrive in.
    @Insert("""
            INSERT INTO cart_item (user_uuid, book_uuid, quantity)
            VALUES (#{userUuid}, #{bookUuid}, #{quantity})
            ON CONFLICT (user_uuid, book_uuid)
            DO UPDATE SET quantity = cart_item.quantity + EXCLUDED.quantity
            """)
    void addQuantity(@Param("userUuid") UUID userUuid,
                     @Param("bookUuid") UUID bookUuid,
                     @Param("quantity") int quantity);

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
    @Results(id = "cartItemView", value = {
            @Result(property = "bookUuid", column = "book_uuid"),
            @Result(property = "bookTitle", column = "book_title"),
            @Result(property = "price", column = "price"),
            @Result(property = "quantity", column = "quantity"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<CartItemView> findByUserWithBook(UUID userUuid);

    // Same view, with the cart lines claimed for the caller's transaction.
    //
    // Checkout reads the cart, reserves stock for it, records an order and empties it. A second
    // submission — a double-click, an impatient retry, a second tab — reads the same lines before
    // the first has cleared them, and the customer ends up owing for two orders they placed once.
    // There is no order row to contend on: each call mints a new purchase_uuid, so the cart lines
    // are the only thing the two attempts share.
    //
    // Locking them makes the loser wait, and by the time it looks the winner has deleted them, so
    // it finds an empty cart and is turned away with EmptyCartException.
    //
    // `OF ci` restricts the lock to cart_item: without it the join would lock the book rows too,
    // and every other customer's checkout for the same title would queue behind this one.
    @Select("""
            SELECT ci.book_uuid, ci.quantity, ci.created_at, b.book_title, b.price
            FROM cart_item ci
            JOIN book b ON b.book_uuid = ci.book_uuid
            WHERE ci.user_uuid = #{userUuid}
            ORDER BY ci.created_at
            FOR UPDATE OF ci
            """)
    @ResultMap("cartItemView")
    List<CartItemView> findByUserWithBookForUpdate(UUID userUuid);

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
