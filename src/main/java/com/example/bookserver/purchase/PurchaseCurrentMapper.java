package com.example.bookserver.purchase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurchaseCurrentMapper {

    // A state change upserts the single current row for the purchase: first change
    // inserts it, every later change overwrites state/price/updated_at in place.
    // (Pair this with PurchaseHistoryMapper.insert to also append to the audit log.)
    @Insert("""
            INSERT INTO purchase_current
                (purchase_uuid, user_uuid, history_uuid, purchase_state, price, updated_at)
            VALUES
                (#{purchaseUuid}, #{userUuid}, #{historyUuid}, #{purchaseState}, #{price}, #{updatedAt})
            ON CONFLICT (purchase_uuid) DO UPDATE SET
                history_uuid   = EXCLUDED.history_uuid,
                purchase_state = EXCLUDED.purchase_state,
                price          = EXCLUDED.price,
                updated_at     = EXCLUDED.updated_at
            """)
    void upsert(PurchaseCurrent current);

    // the current state of a single purchase — a PK point lookup
    @Select("""
            SELECT * FROM purchase_current
            WHERE purchase_uuid = #{purchaseUuid}
            """)
    PurchaseCurrent findByPurchaseUuid(UUID purchaseUuid);

    // the hot path: current state of ALL of a user's purchases, newest first.
    // Served by idx_purchase_current_user — no sort/distinct over the history log.
    @Select("""
            SELECT * FROM purchase_current
            WHERE user_uuid = #{userUuid}
            ORDER BY updated_at DESC
            """)
    List<PurchaseCurrent> findByUserUuid(UUID userUuid);

    // purchases stuck in a given state since before a cutoff — used by the unpaid-order
    // sweep to find PAYMENT_PENDING orders that have outlived the payment window.
    @Select("""
            SELECT purchase_uuid FROM purchase_current
            WHERE purchase_state = #{state} AND updated_at < #{cutoff}
            """)
    List<UUID> findPurchaseUuidsByStateOlderThan(@Param("state") PurchaseState state,
                                                 @Param("cutoff") LocalDateTime cutoff);

    // remove a purchase's current row (e.g. the order is deleted)
    @Delete("DELETE FROM purchase_current WHERE purchase_uuid = #{purchaseUuid}")
    void deleteByPurchaseUuid(UUID purchaseUuid);
}
