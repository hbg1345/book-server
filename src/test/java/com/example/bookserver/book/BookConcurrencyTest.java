package com.example.bookserver.book;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.Concurrently;
import com.example.bookserver.Isbns;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two paths write {@code book.inventory}: sales through {@code decrementInventory} and admin
 * adjustments through {@link BookService#adjustStock}. {@code BookServiceTest} runs them one
 * after the other, which shows they compose but not that they can run at the same time — the
 * ordering there is chosen by the test rather than by the database.
 *
 * <p>That distinction is the whole reason the update statement was changed. An absolute write
 * looks correct sequentially too: read ten, write twelve, and the row says twelve. It only fails
 * when something else wrote in between, and only a real race puts something there.
 *
 * <p>Same setup as {@link com.example.bookserver.purchase.PurchaseConcurrencyTest}, for the same
 * reasons: {@code @SpringBootTest} so {@code @Transactional} is honoured on a proxied bean, no
 * rolled-back wrapper transaction (the workers would not see the fixture), and a pool large
 * enough that the threads contend on the row rather than queueing for a connection.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class BookConcurrencyTest {

    @Autowired
    private BookService bookService;
    @Autowired
    private BookMapper bookMapper;

    private UUID bookWithStock(int inventory) {
        UUID bookUuid = Uuids.newId();
        Book book = new Book(bookUuid, Isbns.next(), "Clean Architecture", "desc",
                new BigDecimal("39.99"), LocalDate.of(2021, 1, 1), "Wikibooks", inventory, null);
        bookMapper.insert(book);
        return bookUuid;
    }

    private int inventoryOf(UUID bookUuid) {
        return bookMapper.findById(bookUuid).getInventory();
    }

    /**
     * Twenty sales and ten receipts of five, all at once. The shelf must end at exactly
     * 100 − 20 + 50.
     *
     * <p>Every number here is exact rather than bounded, because both statements are relative:
     * neither reads a figure it could then write back stale, so no interleaving can produce any
     * other total. A shortfall would mean a receipt swallowed a sale; a surplus would mean a
     * sale was applied to a figure read before a receipt landed.
     */
    @Test
    void salesAndAdjustments_neitherLosesTheOther() throws Exception {
        UUID bookUuid = bookWithStock(100);

        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            work.add(() -> {
                bookMapper.decrementInventory(bookUuid, 1);
                return null;
            });
        }
        for (int i = 0; i < 10; i++) {
            work.add(() -> {
                bookService.adjustStock(bookUuid, 5);
                return null;
            });
        }

        Concurrently.runAtOnce(work);

        assertThat(inventoryOf(bookUuid)).isEqualTo(100 - 20 + 50);
    }

    /**
     * Ten simultaneous receipts of twenty all land. Adjustments are additive, so unlike the
     * sales below there is no winner: every one of them is entitled to succeed, and any total
     * short of the full 200 is a lost update rather than a refusal.
     */
    @Test
    void concurrentAdjustments_allApply() throws Exception {
        UUID bookUuid = bookWithStock(0);

        Concurrently.runAtOnce(10, () -> bookService.adjustStock(bookUuid, 20));

        assertThat(inventoryOf(bookUuid)).isEqualTo(200);
    }

    /**
     * A write-off and a sale that cannot both fit: ten copies, six written off, six sold. One
     * has to lose.
     *
     * <p>This is the case a check-then-write would get wrong — both read ten, both find six
     * available, both proceed, and the shelf ends at minus two. The floor lives in the WHERE
     * clause of the statement that applies the change, so the second one matches no row and the
     * shop never sells copies it does not have.
     */
    @Test
    void writeOffRacingASale_onlyOneFits() throws Exception {
        UUID bookUuid = bookWithStock(10);

        List<Callable<Boolean>> work = List.of(
                () -> {
                    try {
                        bookService.adjustStock(bookUuid, -6);
                        return true;
                    } catch (InsufficientStockException e) {
                        return false;
                    }
                },
                () -> bookMapper.decrementInventory(bookUuid, 6) == 1);

        List<Boolean> succeeded = Concurrently.runAtOnce(work);

        assertThat(succeeded).containsExactlyInAnyOrder(true, false);
        assertThat(inventoryOf(bookUuid))
                .as("the loser changed nothing, and the shelf never went below zero")
                .isEqualTo(4);
    }

    /**
     * A title edit saved while the book is selling. The sequential version of this is the bug
     * that started all of it; here the sale commits <em>during</em> the edit's transaction
     * rather than before it, which is the arrangement an absolute write would still lose.
     */
    @Test
    void editRacingASale_leavesTheSaleAlone() throws Exception {
        UUID bookUuid = bookWithStock(10);

        Concurrently.runAtOnce(List.<Callable<Void>>of(
                () -> {
                    bookService.update(bookUuid, new com.example.bookserver.book.dto.UpdateBookRequest(
                            "Clean Architecture, 2nd ed.", "desc", new BigDecimal("39.99"),
                            LocalDate.of(2021, 1, 1), "Wikibooks", null));
                    return null;
                },
                () -> {
                    bookMapper.decrementInventory(bookUuid, 3);
                    return null;
                }));

        assertThat(bookService.get(bookUuid).getBookTitle()).isEqualTo("Clean Architecture, 2nd ed.");
        assertThat(inventoryOf(bookUuid))
                .as("the edit has no stock figure to write, whenever it commits")
                .isEqualTo(7);
    }
}
