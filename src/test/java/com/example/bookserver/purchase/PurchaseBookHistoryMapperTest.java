package com.example.bookserver.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.book.Book;
import com.example.bookserver.book.BookMapper;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/schema.sql")
public class PurchaseBookHistoryMapperTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @Autowired
    private PurchaseBookHistoryMapper purchaseBookHistoryMapper;
    @Autowired
    private PurchaseHistoryMapper purchaseHistoryMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private UserMapper userMapper;

    // FK parent: purchase_history rows reference an existing user
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

    // FK parent: a book row referenced by book_uuid
    private UUID persistBook(String title) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book();
        book.setBookUuid(bookUuid);
        book.setBookTitle(title);
        book.setBookDescription("desc");
        book.setPrice(new BigDecimal("25.00"));
        book.setPublishDate(LocalDate.of(2020, 1, 1));
        book.setPublisher("ACME");
        book.setInventory(100);
        bookMapper.insert(book);
        return bookUuid;
    }

    // FK parent: an order-level state event whose per-book rows we record
    private UUID logOrderEvent(UUID purchaseUuid, UUID userUuid, PurchaseState state, LocalDateTime at) {
        UUID historyUuid = Uuids.newId();
        PurchaseHistory h = new PurchaseHistory();
        h.setHistoryUuid(historyUuid);
        h.setPurchaseUuid(purchaseUuid);
        h.setUserUuid(userUuid);
        h.setPurchaseState(state);
        h.setPrice(new BigDecimal("50.00"));
        h.setUpdatedAt(at);
        purchaseHistoryMapper.insert(h);
        return historyUuid;
    }

    private PurchaseBookHistory bookRow(UUID historyUuid, UUID bookUuid, PurchaseState state,
                                        int quantity, LocalDateTime at) {
        PurchaseBookHistory b = new PurchaseBookHistory();
        b.setHistoryUuid(historyUuid);
        b.setBookUuid(bookUuid);
        b.setPurchaseState(state);
        b.setQuantity(quantity);
        b.setPrice(new BigDecimal("25.00"));
        b.setUpdatedAt(at);
        return b;
    }

    // Verifies: insert then a full-PK point lookup maps enum/quantity/price/updated_at back.
    @Test
    void insert_and_findByHistoryUuidAndBookUuid() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook("Effective Java");
        UUID historyUuid = logOrderEvent(Uuids.newId(), userUuid, PurchaseState.ORDERED, BASE);

        purchaseBookHistoryMapper.insert(bookRow(historyUuid, bookUuid, PurchaseState.ORDERED, 2, BASE));

        PurchaseBookHistory found =
                purchaseBookHistoryMapper.findByHistoryUuidAndBookUuid(historyUuid, bookUuid);
        assertThat(found.getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(found.getQuantity()).isEqualTo(2);
        assertThat(found.getPrice()).isEqualByComparingTo("25.00");
        assertThat(found.getUpdatedAt()).isEqualTo(BASE);
    }

    // Verifies: findByHistoryUuid returns every book of one state event.
    @Test
    void findByHistoryUuid_returnsAllBooksOfEvent() {
        UUID userUuid = persistUser();
        UUID book1 = persistBook("Book One");
        UUID book2 = persistBook("Book Two");
        UUID historyUuid = logOrderEvent(Uuids.newId(), userUuid, PurchaseState.SHIPPING, BASE);

        purchaseBookHistoryMapper.insert(bookRow(historyUuid, book1, PurchaseState.SHIPPING, 1, BASE));
        purchaseBookHistoryMapper.insert(bookRow(historyUuid, book2, PurchaseState.SHIPPING, 3, BASE));

        assertThat(purchaseBookHistoryMapper.findByHistoryUuid(historyUuid))
                .extracting(PurchaseBookHistory::getBookUuid)
                .containsExactlyInAnyOrder(book1, book2);
    }

    // Verifies: the same book can carry different states across two different events.
    @Test
    void sameBook_hasIndependentRowsPerEvent() {
        UUID userUuid = persistUser();
        UUID bookUuid = persistBook("Clean Code");
        UUID purchaseUuid = Uuids.newId();
        UUID orderedEvent = logOrderEvent(purchaseUuid, userUuid, PurchaseState.ORDERED, BASE);
        UUID cancelledEvent = logOrderEvent(purchaseUuid, userUuid, PurchaseState.CANCELLED, BASE.plusDays(1));

        purchaseBookHistoryMapper.insert(bookRow(orderedEvent, bookUuid, PurchaseState.ORDERED, 1, BASE));
        purchaseBookHistoryMapper.insert(bookRow(cancelledEvent, bookUuid, PurchaseState.CANCELLED, 1, BASE.plusDays(1)));

        assertThat(purchaseBookHistoryMapper.findByHistoryUuidAndBookUuid(orderedEvent, bookUuid)
                .getPurchaseState()).isEqualTo(PurchaseState.ORDERED);
        assertThat(purchaseBookHistoryMapper.findByHistoryUuidAndBookUuid(cancelledEvent, bookUuid)
                .getPurchaseState()).isEqualTo(PurchaseState.CANCELLED);
    }

    // Verifies: point lookup returns null for an absent (event, book) pair.
    @Test
    void findByHistoryUuidAndBookUuid_returnsNull_whenAbsent() {
        assertThat(purchaseBookHistoryMapper
                .findByHistoryUuidAndBookUuid(Uuids.newId(), Uuids.newId())).isNull();
    }

    // Verifies: deleteByHistoryUuid removes every book row of that event.
    @Test
    void deleteByHistoryUuid_removesAllBooks() {
        UUID userUuid = persistUser();
        UUID book1 = persistBook("Book One");
        UUID book2 = persistBook("Book Two");
        UUID historyUuid = logOrderEvent(Uuids.newId(), userUuid, PurchaseState.ORDERED, BASE);
        purchaseBookHistoryMapper.insert(bookRow(historyUuid, book1, PurchaseState.ORDERED, 1, BASE));
        purchaseBookHistoryMapper.insert(bookRow(historyUuid, book2, PurchaseState.ORDERED, 1, BASE));

        purchaseBookHistoryMapper.deleteByHistoryUuid(historyUuid);

        assertThat(purchaseBookHistoryMapper.findByHistoryUuid(historyUuid)).isEmpty();
    }
}
