package com.example.bookserver;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
