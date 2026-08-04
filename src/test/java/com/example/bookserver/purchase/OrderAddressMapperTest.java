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
@Sql("/reset.sql")
public class OrderAddressMapperTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Autowired
    private OrderAddressMapper orderAddressMapper;
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

    // FK parent: order_address references purchase_current(purchase_uuid); create one
    // (log first, then point) mirroring the real write order.
    private UUID persistOrder(UUID userUuid) {
        UUID purchaseUuid = Uuids.newId();
        UUID historyUuid = Uuids.newId();
        PurchaseHistory h = new PurchaseHistory();
        h.setHistoryUuid(historyUuid);
        h.setPurchaseUuid(purchaseUuid);
        h.setUserUuid(userUuid);
        h.setPurchaseState(PurchaseState.PAYMENT_PENDING);
        h.setPrice(new BigDecimal("50.00"));
        h.setUpdatedAt(BASE);
        purchaseHistoryMapper.insert(h);
        PurchaseCurrent c = new PurchaseCurrent();
        c.setPurchaseUuid(purchaseUuid);
        c.setUserUuid(userUuid);
        c.setHistoryUuid(historyUuid);
        c.setPurchaseState(PurchaseState.PAYMENT_PENDING);
        c.setPrice(new BigDecimal("50.00"));
        c.setUpdatedAt(BASE);
        purchaseCurrentMapper.upsert(c);
        return purchaseUuid;
    }

    private OrderAddress snapshot(UUID purchaseUuid) {
        return new OrderAddress(purchaseUuid, "Grace Hopper", "010-1234-5678", "KR",
                "서울특별시 강남구 테헤란로 1", "101동 1001호", "06236", null);
    }

    // Verifies: an order's delivery snapshot inserts and reads back by purchase_uuid, all
    // fields round-trip, and created_at is DB-filled.
    @Test
    void insert_and_findByPurchaseUuid() {
        UUID userUuid = persistUser();
        UUID purchaseUuid = persistOrder(userUuid);

        orderAddressMapper.insert(snapshot(purchaseUuid));

        OrderAddress found = orderAddressMapper.findByPurchaseUuid(purchaseUuid);
        assertThat(found).isNotNull();
        assertThat(found.getRecipient()).isEqualTo("Grace Hopper");
        assertThat(found.getPhone()).isEqualTo("010-1234-5678");
        assertThat(found.getCountry()).isEqualTo("KR");
        assertThat(found.getRoadAddress()).isEqualTo("서울특별시 강남구 테헤란로 1");
        assertThat(found.getDetailAddress()).isEqualTo("101동 1001호");
        assertThat(found.getPostalCode()).isEqualTo("06236");
        assertThat(found.getCreatedAt()).isNotNull();   // DB default
    }

    // Verifies: findByPurchaseUuid returns null for an order with no snapshot.
    @Test
    void findByPurchaseUuid_returnsNull_whenAbsent() {
        assertThat(orderAddressMapper.findByPurchaseUuid(Uuids.newId())).isNull();
    }
}
