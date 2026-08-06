package com.example.bookserver.payment;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.purchase.PurchaseCurrent;
import com.example.bookserver.purchase.PurchaseCurrentMapper;
import com.example.bookserver.purchase.PurchaseHistory;
import com.example.bookserver.purchase.PurchaseHistoryMapper;
import com.example.bookserver.purchase.PurchaseState;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class PaymentMapperTest {

    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private PurchaseCurrentMapper purchaseCurrentMapper;
    @Autowired
    private PurchaseHistoryMapper purchaseHistoryMapper;
    @Autowired
    private UserMapper userMapper;

    private UUID persistUser() {
        UUID userUuid = Uuids.newId();
        User user = new User();
        user.setUserUuid(userUuid);
        user.setUserId("u-" + userUuid);
        user.setUserPassword("secret");
        user.setUserName("Jane Doe");
        user.setPhone("010-1234-5678");
        user.setBirthDate(LocalDate.of(1990, 5, 20));
        userMapper.insert(user);
        return userUuid;
    }

    // FK parent: payment references an order (purchase_current row).
    private UUID persistOrder(UUID userUuid) {
        UUID purchaseUuid = Uuids.newId();
        UUID historyUuid = Uuids.newId();
        PurchaseHistory h = new PurchaseHistory();
        h.setHistoryUuid(historyUuid);
        h.setPurchaseUuid(purchaseUuid);
        h.setUserUuid(userUuid);
        h.setPurchaseState(PurchaseState.PAYMENT_PENDING);
        h.setPrice(new BigDecimal("50.00"));
        h.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        purchaseHistoryMapper.insert(h);
        PurchaseCurrent c = new PurchaseCurrent(purchaseUuid, userUuid, historyUuid,
                PurchaseState.PAYMENT_PENDING, new BigDecimal("50.00"), LocalDateTime.of(2026, 1, 1, 10, 0));
        purchaseCurrentMapper.upsert(c);
        return purchaseUuid;
    }

    private Payment payment(UUID purchaseUuid, String idempotencyKey, PaymentStatus status) {
        return new Payment(Uuids.newId(), purchaseUuid, "TOSS", "txn_123",
                new BigDecimal("50.00"), status, BigDecimal.ZERO, idempotencyKey, null, null);
    }

    // Verifies: a payment inserts and reads back by purchase_uuid; all fields round-trip.
    @Test
    void insert_and_findByPurchaseUuid() {
        UUID user = persistUser();
        UUID order = persistOrder(user);

        paymentMapper.insert(payment(order, "idem-1", PaymentStatus.PAID));

        Payment found = paymentMapper.findByPurchaseUuid(order);
        assertThat(found).isNotNull();
        assertThat(found.getProvider()).isEqualTo("TOSS");
        assertThat(found.getProviderTxnId()).isEqualTo("txn_123");
        assertThat(found.getAmount()).isEqualByComparingTo("50.00");
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(found.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(found.getCreatedAt()).isNotNull();   // DB default
    }

    // Verifies: updateStatus flips a payment's status (e.g. PAID -> REFUNDED on a refund).
    @Test
    void updateStatus_changesStatus() {
        UUID user = persistUser();
        UUID order = persistOrder(user);
        Payment p = payment(order, "idem-refund", PaymentStatus.PAID);
        paymentMapper.insert(p);

        int updated = paymentMapper.updateStatus(p.getPaymentUuid(), PaymentStatus.REFUNDED);

        assertThat(updated).isEqualTo(1);
        assertThat(paymentMapper.findByPurchaseUuid(order).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    // Verifies: findByIdempotencyKey finds the row for idempotent retry short-circuiting.
    @Test
    void findByIdempotencyKey_returnsRow() {
        UUID user = persistUser();
        UUID order = persistOrder(user);
        paymentMapper.insert(payment(order, "idem-key-xyz", PaymentStatus.PAID));

        assertThat(paymentMapper.findByIdempotencyKey("idem-key-xyz")).isNotNull();
        assertThat(paymentMapper.findByIdempotencyKey("missing")).isNull();
    }

    // Verifies: findByProviderTxnId resolves the provider's intent id back to our payment —
    // the lookup a webhook does, since the provider only knows its own id.
    @Test
    void findByProviderTxnId_returnsRow() {
        UUID user = persistUser();
        UUID order = persistOrder(user);
        paymentMapper.insert(payment(order, "idem-webhook", PaymentStatus.PENDING));

        Payment found = paymentMapper.findByProviderTxnId("txn_123");
        assertThat(found).isNotNull();
        assertThat(found.getPurchaseUuid()).isEqualTo(order);
        assertThat(paymentMapper.findByProviderTxnId("pi_unknown")).isNull();
    }

    // Verifies: the idempotency key is unique — the same key cannot be inserted twice
    // (the DB backstop against a double charge).
    @Test
    void duplicateIdempotencyKey_isRejected() {
        UUID user = persistUser();
        UUID order = persistOrder(user);
        paymentMapper.insert(payment(order, "dup", PaymentStatus.PAID));

        assertThatThrownBy(() -> paymentMapper.insert(payment(order, "dup", PaymentStatus.FAILED)))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
