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
public class PurchaseHistoryMapperTest {

    // a fixed base time; each state event is given a distinct, increasing timestamp
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Autowired
    private PurchaseHistoryMapper purchaseHistoryMapper;
    @Autowired
    private UserMapper userMapper;

    // FK parent: a purchase_history row references an existing user
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

    // updatedAt is the moment this state took effect — supplied by the caller (the app)
    private PurchaseHistory event(UUID purchaseUuid, UUID userUuid, PurchaseState state, LocalDateTime updatedAt) {
        PurchaseHistory h = new PurchaseHistory();
        h.setHistoryUuid(Uuids.newId());
        h.setPurchaseUuid(purchaseUuid);
        h.setUserUuid(userUuid);
        h.setPurchaseState(state);
        h.setPrice(new BigDecimal("50.00"));
        h.setUpdatedAt(updatedAt);
        return h;
    }

    // Verifies: after several state events for one purchase, findLatest returns the
    // state with the most recent updated_at, with enum/price/updated_at mapped back.
    @Test
    void insert_and_findLatestByPurchaseUuid() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();

        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, BASE));
        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE.plusMinutes(1)));

        PurchaseHistory latest = purchaseHistoryMapper.findLatestByPurchaseUuid(purchaseUuid);
        assertThat(latest.getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(latest.getPrice()).isEqualByComparingTo("50.00");
        assertThat(latest.getUpdatedAt()).isEqualTo(BASE.plusMinutes(1));
    }

    // Verifies: findAllByPurchaseUuid returns the full state history in chronological
    // (updated_at) order.
    @Test
    void findAllByPurchaseUuid_returnsStatesInOrder() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();

        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, BASE));
        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE.plusMinutes(1)));
        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.PREPARING, BASE.plusMinutes(2)));

        assertThat(purchaseHistoryMapper.findAllByPurchaseUuid(purchaseUuid))
                .extracting(PurchaseHistory::getPurchaseState)
                .containsExactly(
                        PurchaseState.PAYMENT_PENDING,
                        PurchaseState.ORDERED,
                        PurchaseState.PREPARING);
    }

    // Verifies: findLatest returns null for a purchase that has no history yet.
    @Test
    void findLatest_returnsNull_whenNoHistory() {
        assertThat(purchaseHistoryMapper.findLatestByPurchaseUuid(Uuids.newId())).isNull();
    }

    // Verifies: deleteByPurchaseUuid removes every history row of that purchase.
    @Test
    void deleteByPurchaseUuid_removesAllHistory() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = Uuids.newId();
        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.PAYMENT_PENDING, BASE));
        purchaseHistoryMapper.insert(event(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE.plusMinutes(1)));

        purchaseHistoryMapper.deleteByPurchaseUuid(purchaseUuid);

        assertThat(purchaseHistoryMapper.findAllByPurchaseUuid(purchaseUuid)).isEmpty();
        assertThat(purchaseHistoryMapper.findLatestByPurchaseUuid(purchaseUuid)).isNull();
    }
}
