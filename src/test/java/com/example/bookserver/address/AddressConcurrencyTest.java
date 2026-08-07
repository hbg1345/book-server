package com.example.bookserver.address;

import java.time.LocalDate;
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
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.address.dto.UpdateAddressRequest;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Saving one address to the book, submitted twice at the same instant — a double-clicked form,
 * or a client that retries a request whose response was lost.
 *
 * <p>A create has nothing for two callers to meet on by itself: {@link Uuids#newId()} mints a
 * fresh primary key per call, so the two submissions never contend and the database is content
 * to hold both rows. {@code uq_address_no_duplicate_per_user} is what makes them meet. See #61.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=25")
class AddressConcurrencyTest {

    @Autowired
    private AddressService addressService;
    @Autowired
    private AddressMapper addressMapper;
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

    private CreateAddressRequest createReq(boolean isDefault) {
        return createReq("Home", "서울특별시 강남구 테헤란로 1", isDefault);
    }

    /** A distinct address per road, so the duplicate constraint stays out of the way. */
    private CreateAddressRequest createReq(String alias, String roadAddress, boolean isDefault) {
        return new CreateAddressRequest(alias, "Jane Doe", "010-1234-5678", "KR",
                roadAddress, "101동 1001호", "06234", isDefault);
    }

    private UpdateAddressRequest updateReq(String alias, String roadAddress, boolean isDefault) {
        return new UpdateAddressRequest(alias, "Jane Doe", "010-1234-5678", "KR",
                roadAddress, "101동 1001호", "06234", isDefault);
    }

    private long defaultCount(UUID user) {
        return addressMapper.findByUser(user).stream().filter(Address::isDefaultAddress).count();
    }

    /** @return true if the address was saved, false if it was reported as already in the book. */
    private boolean saveReportingDuplicate(UUID user, CreateAddressRequest req) {
        try {
            addressService.addAddress(user, req);
            return true;
        } catch (DuplicateAddressException e) {
            return false;
        }
    }

    /**
     * The same address saved twice at once. One submission wins; the other is told the address
     * is already there rather than silently adding a second copy of it.
     */
    @Test
    void savingOneAddressTwiceAtOnce_keepsOneRow() throws Exception {
        UUID user = persistUser();
        List<Callable<Boolean>> submissions = List.of(
                () -> saveReportingDuplicate(user, createReq(false)),
                () -> saveReportingDuplicate(user, createReq(false)));

        List<Boolean> results = Concurrently.runAtOnce(submissions);

        assertThat(results).as("one submission saves, the other is told it is already saved")
                .containsExactlyInAnyOrder(true, false);
        assertThat(addressMapper.findByUser(user))
                .as("one address submitted, one address in the book")
                .hasSize(1);
    }

    /**
     * The same submission again, this time asking for the address to be the default one — the
     * path that also clears the previous default before inserting.
     *
     * <p>The loser's clear must go with its insert: it rolled back, so the winner is still the
     * default. Before the constraint existed this pair collided on
     * {@code uq_address_one_default} instead, and the loser surfaced as a raw
     * {@code DuplicateKeyException} that {@link com.example.bookserver.common.GlobalExceptionHandler}
     * did not map — a 500 for an address that was saved perfectly well.
     */
    @Test
    void savingOneDefaultAddressTwiceAtOnce_keepsOneDefaultRow() throws Exception {
        UUID user = persistUser();
        List<Callable<Boolean>> submissions = List.of(
                () -> saveReportingDuplicate(user, createReq(true)),
                () -> saveReportingDuplicate(user, createReq(true)));

        List<Boolean> results = Concurrently.runAtOnce(submissions);

        assertThat(results).as("a double-clicked default address is a conflict, not a server fault")
                .containsExactlyInAnyOrder(true, false);
        assertThat(addressMapper.findByUser(user))
                .as("one address in the book, and it is still the default")
                .singleElement()
                .returns(true, Address::isDefaultAddress);
    }

    /**
     * Two <em>different</em> addresses, both asked to be the default, at the same instant.
     *
     * <p>Nothing here is a duplicate and nothing is a user error: done one after the other both
     * saves succeed, the second simply takes the default from the first. Done together they must
     * do the same — a legitimate write being refused is the opposite of the defect above.
     *
     * <p>What made this fail was that {@code clearDefaultForUser} cannot see a row another
     * transaction has only just inserted. Under READ COMMITTED an UPDATE scans the snapshot
     * taken when its statement began; waiting on a row lock makes it re-check the rows it
     * already found, but a row inserted meanwhile was never in that scan at all. The clear
     * missed the new default, and the insert that followed collided with the survivor on
     * {@code uq_address_one_default}. {@code lockUserForDefaultChange} is what orders the two.
     *
     * <p>With no addresses saved yet the clear matches nothing and takes no locks at all, so
     * there is nothing else for the two callers to queue behind — which is why this test starts
     * from an empty address book.
     */
    @Test
    void makingTwoDifferentAddressesDefaultAtOnce_savesBoth() throws Exception {
        UUID user = persistUser();

        assertThatCode(() -> Concurrently.runAtOnce(List.of(
                () -> addressService.addAddress(user, createReq("Home", "서울특별시 강남구 테헤란로 1", true)),
                () -> addressService.addAddress(user, createReq("Work", "서울특별시 중구 세종대로 100", true)))))
                .as("neither address is a duplicate, so neither save is a conflict")
                .doesNotThrowAnyException();

        assertThat(addressMapper.findByUser(user)).as("both addresses saved").hasSize(2);
        assertThat(defaultCount(user)).as("exactly one of them is the default").isEqualTo(1);
    }

    /**
     * The same race with one side editing instead of inserting: a saved address is promoted to
     * default while a brand-new address is saved as default.
     *
     * <p>Two edits racing each other were always safe — both rows already exist, so the loser's
     * clear locks them, re-checks them after the winner commits, and its own update then holds.
     * It is the insert that breaks the pattern, by adding a row the other side's clear never
     * sees. So this pairing broke for exactly the same reason as the one above, and pure
     * edit-vs-edit gets no test here because there was never anything to reproduce.
     */
    @Test
    void promotingOneAddressWhileSavingAnotherAsDefault_keepsBoth() throws Exception {
        UUID user = persistUser();
        UUID saved = addressService.addAddress(user, createReq("Work", "서울특별시 중구 세종대로 100", false));

        List<Callable<Void>> writes = List.of(
                () -> {
                    addressService.addAddress(user, createReq("Home", "서울특별시 강남구 테헤란로 1", true));
                    return null;
                },
                () -> {
                    addressService.updateAddress(user, saved,
                            updateReq("Work", "서울특별시 중구 세종대로 100", true));
                    return null;
                });

        assertThatCode(() -> Concurrently.runAtOnce(writes))
                .as("saving one address and promoting another are both legitimate writes")
                .doesNotThrowAnyException();

        assertThat(addressMapper.findByUser(user)).as("both addresses in the book").hasSize(2);
        assertThat(defaultCount(user)).as("exactly one of them is the default").isEqualTo(1);
    }
}
