package com.example.bookserver.purchase;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurchaseBookHistoryMapper {

    // append-only: each order state event records one row per book; never updated in place
    @Insert("""
            INSERT INTO purchase_book_history
                (history_uuid, book_uuid, purchase_state, quantity, price, updated_at)
            VALUES
                (#{historyUuid}, #{bookUuid}, #{purchaseState}, #{quantity}, #{price}, #{updatedAt})
            """)
    void insert(PurchaseBookHistory bookHistory);

    // the per-book states of one order state event — served by the PK's leftmost prefix
    @Select("""
            SELECT * FROM purchase_book_history
            WHERE history_uuid = #{historyUuid}
            ORDER BY book_uuid
            """)
    List<PurchaseBookHistory> findByHistoryUuid(UUID historyUuid);

    // the per-book lines of one state event, joined with each book's title for the
    // order-detail view. Served by the PK's leftmost prefix (history_uuid).
    @Select("""
            SELECT pbh.book_uuid, b.book_title, pbh.quantity, pbh.price
            FROM purchase_book_history pbh
            JOIN book b ON b.book_uuid = pbh.book_uuid
            WHERE pbh.history_uuid = #{historyUuid}
            ORDER BY b.book_title
            """)
    @Results({
            @Result(property = "bookUuid", column = "book_uuid"),
            @Result(property = "bookTitle", column = "book_title"),
            @Result(property = "quantity", column = "quantity"),
            @Result(property = "price", column = "price")
    })
    List<OrderBookItem> findItemsWithBookByHistoryUuid(UUID historyUuid);

    // a single book's row within one state event — full PK point lookup
    @Select("""
            SELECT * FROM purchase_book_history
            WHERE history_uuid = #{historyUuid} AND book_uuid = #{bookUuid}
            """)
    PurchaseBookHistory findByHistoryUuidAndBookUuid(
            @Param("historyUuid") UUID historyUuid, @Param("bookUuid") UUID bookUuid);

    // remove all book rows of one state event (individual rows are never deleted alone)
    @Delete("DELETE FROM purchase_book_history WHERE history_uuid = #{historyUuid}")
    void deleteByHistoryUuid(UUID historyUuid);
}
