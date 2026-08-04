package com.example.bookserver.payment;

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

    // the latest payment for an order (a failed attempt then a successful one are separate rows)
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

    // flip a payment's status (e.g. PAID -> REFUNDED on a refund); bumps updated_at
    @Update("""
            UPDATE payment SET status = #{status}, updated_at = CURRENT_TIMESTAMP
            WHERE payment_uuid = #{paymentUuid}
            """)
    int updateStatus(@Param("paymentUuid") UUID paymentUuid, @Param("status") PaymentStatus status);
}
