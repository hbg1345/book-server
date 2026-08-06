package com.example.bookserver.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Payment persistence. created_at/updated_at are DB-filled. Column names map to camel-case
 * properties via map-underscore-to-camel-case; status maps to the enum by name.
 */
@Mapper
public interface PaymentMapper {

    @Insert("""
            INSERT INTO payment
                (payment_uuid, purchase_uuid, provider, provider_txn_id, amount, status, idempotency_key)
            VALUES
                (#{paymentUuid}, #{purchaseUuid}, #{provider}, #{providerTxnId},
                 #{amount}, #{status}, #{idempotencyKey})
            """)
    void insert(Payment payment);

    // the payment for an order. One row per order (the key is order-scoped), whose status moves
    // PENDING -> PAID/FAILED -> REFUNDED; ordering is kept as a guard, not a real expectation.
    @Select("""
            SELECT * FROM payment
            WHERE purchase_uuid = #{purchaseUuid}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Payment findByPurchaseUuid(UUID purchaseUuid);

    // idempotency lookup: a retry presenting the same key resolves to the existing charge
    @Select("SELECT * FROM payment WHERE idempotency_key = #{idempotencyKey}")
    Payment findByIdempotencyKey(String idempotencyKey);

    // webhook lookup: the provider only knows its own intent id, so that is how it addresses us
    @Select("SELECT * FROM payment WHERE provider_txn_id = #{providerTxnId}")
    Payment findByProviderTxnId(String providerTxnId);

    // flip a payment's status (e.g. PAID -> REFUNDED on a refund); bumps updated_at
    @Update("""
            UPDATE payment SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE payment_uuid = #{paymentUuid}
            """)
    int updateStatus(@Param("paymentUuid") UUID paymentUuid, @Param("status") PaymentStatus status);

    // Record money going back, and settle the status from the running total in the same statement.
    //
    // The guard is `refunded_amount + #{amount} <= amount`, evaluated by the database with the row
    // locked. Reading the row, adding in Java and writing it back would let two refunds that
    // overlap each read the same starting figure and each conclude there is room — and the
    // customer is handed back more than they ever paid. Returns 0 when there is not enough left,
    // so the caller can tell a rejected refund from a recorded one without a second read.
    //
    // REFUNDED once the whole charge has gone back, PAID while some of it is still held: the
    // status stays a summary of the number rather than a separate thing to keep in step.
    @Update("""
            UPDATE payment SET
                refunded_amount = refunded_amount + #{amount},
                status = CASE WHEN refunded_amount + #{amount} >= amount
                              THEN 'REFUNDED' ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE payment_uuid = #{paymentUuid}
              AND refunded_amount + #{amount} <= amount
            """)
    int recordRefund(@Param("paymentUuid") UUID paymentUuid, @Param("amount") BigDecimal amount);
}
