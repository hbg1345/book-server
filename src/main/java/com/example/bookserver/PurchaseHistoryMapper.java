package com.example.bookserver;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurchaseHistoryMapper {

    // append-only: a state change adds a row, it is never updated in place;
    // updated_at (the moment the state took effect) is supplied by the app
    @Insert("""
            INSERT INTO purchase_history
                (history_uuid, purchase_uuid, user_uuid, purchase_state, price, updated_at)
            VALUES
                (#{historyUuid}, #{purchaseUuid}, #{userUuid}, #{purchaseState}, #{price}, #{updatedAt})
            """)
    void insert(PurchaseHistory history);

    // the current state of a purchase = its most recent history row
    @Select("""
            SELECT * FROM purchase_history
            WHERE purchase_uuid = #{purchaseUuid}
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    PurchaseHistory findLatestByPurchaseUuid(UUID purchaseUuid);

    // the full state history of a purchase, oldest first
    @Select("""
            SELECT * FROM purchase_history
            WHERE purchase_uuid = #{purchaseUuid}
            ORDER BY updated_at
            """)
    List<PurchaseHistory> findAllByPurchaseUuid(UUID purchaseUuid);

    // remove a whole purchase's history (e.g. the order is deleted); individual
    // events are never deleted on their own — that would break the audit trail
    @Delete("DELETE FROM purchase_history WHERE purchase_uuid = #{purchaseUuid}")
    void deleteByPurchaseUuid(UUID purchaseUuid);
}
