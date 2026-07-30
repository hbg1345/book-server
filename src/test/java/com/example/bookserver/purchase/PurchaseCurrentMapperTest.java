package com.example.bookserver.purchase;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
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
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class PurchaseCurrentMapperTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Autowired
    private PurchaseCurrentMapper purchaseCurrentMapper;
    @Autowired
    private PurchaseHistoryMapper purchaseHistoryMapper;
    @Autowired
    private UserMapper userMapper;

    // FK parent: a purchase_current row references an existing user
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

    // append a state change to the log (FK parent for the current head pointer) and
    // return its history_uuid — mirrors the real write order: log first, then point.
    private UUID logEvent(UUID purchaseUuid, UUID userUuid, PurchaseState state, LocalDateTime updatedAt) {
        UUID historyUuid = Uuids.newId();
        PurchaseHistory h = new PurchaseHistory();
        h.setHistoryUuid(historyUuid);
        h.setPurchaseUuid(purchaseUuid);
        h.setUserUuid(userUuid);
        h.setPurchaseState(state);
        h.setPrice(new BigDecimal("50.00"));
        h.setUpdatedAt(updatedAt);
        purchaseHistoryMapper.insert(h);
        return historyUuid;
    }

    // upsert the current row to point at the given (already-logged) event
    private void applyState(UUID purchaseUuid, UUID userUuid, PurchaseState state, LocalDateTime updatedAt) {
        UUID historyUuid = logEvent(purchaseUuid, userUuid, state, updatedAt);
        PurchaseCurrent c = new PurchaseCurrent();
        c.setPurchaseUuid(purchaseUuid);
        c.setUserUuid(userUuid);
        c.setHistoryUuid(historyUuid);
        c.setPurchaseState(state);
        c.setPrice(new BigDecimal("50.00"));
        c.setUpdatedAt(updatedAt);
        purchaseCurrentMapper.upsert(c);
    }

    // Verifies: the first state change inserts, and findByPurchaseUuid maps the row back.
    @Test
    void upsert_inserts_andFindByPurchaseUuid() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();

        applyState(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, BASE);

        PurchaseCurrent found = purchaseCurrentMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(found.getPurchaseState()).isEqualTo(PurchaseState.PAYMENT_PENDING);
        assertThat(found.getPrice()).isEqualByComparingTo("50.00");
        assertThat(found.getUpdatedAt()).isEqualTo(BASE);
    }

    // Verifies: a second state change OVERWRITES the current row in place (no new row)
    // and repoints history_uuid at the newer logged event.
    @Test
    void upsert_overwritesInPlace_onSecondStateChange() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();

        applyState(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, BASE);
        UUID orderedHistoryUuid = logEvent(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE.plusMinutes(1));
        PurchaseCurrent c = new PurchaseCurrent();
        c.setPurchaseUuid(purchaseUuid);
        c.setUserUuid(userUuid);
        c.setHistoryUuid(orderedHistoryUuid);
        c.setPurchaseState(PurchaseState.ORDERED);
        c.setPrice(new BigDecimal("50.00"));
        c.setUpdatedAt(BASE.plusMinutes(1));
        purchaseCurrentMapper.upsert(c);

        PurchaseCurrent found = purchaseCurrentMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(found.getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(found.getUpdatedAt()).isEqualTo(BASE.plusMinutes(1));
        assertThat(found.getHistoryUuid()).isEqualTo(orderedHistoryUuid);   // repointed at the newer event

        // only one current row exists for the user's single purchase
        assertThat(purchaseCurrentMapper.findByUserUuid(userUuid)).hasSize(1);
    }

    // Verifies: the hot path — one current row per purchase for a user, newest first.
    @Test
    void findByUserUuid_returnsOneRowPerPurchase_newestFirst() {
        UUID userUuid = persistUser();
        UUID older = Uuids.newId();
        UUID newer = Uuids.newId();

        applyState(older, userUuid, PurchaseState.DELIVERED, BASE);
        applyState(newer, userUuid, PurchaseState.ORDERED, BASE.plusDays(1));

        assertThat(purchaseCurrentMapper.findByUserUuid(userUuid))
                .extracting(PurchaseCurrent::getPurchaseUuid)
                .containsExactly(newer, older);
    }

    // Verifies: findByPurchaseUuid returns null for an unknown purchase.
    @Test
    void findByPurchaseUuid_returnsNull_whenAbsent() {
        assertThat(purchaseCurrentMapper.findByPurchaseUuid(Uuids.newId())).isNull();
    }

    // Verifies: deleteByPurchaseUuid removes the current row.
    @Test
    void deleteByPurchaseUuid_removesRow() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();
        applyState(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE);

        purchaseCurrentMapper.deleteByPurchaseUuid(purchaseUuid);

        assertThat(purchaseCurrentMapper.findByPurchaseUuid(purchaseUuid)).isNull();
        assertThat(purchaseCurrentMapper.findByUserUuid(userUuid)).isEmpty();
    }
}
