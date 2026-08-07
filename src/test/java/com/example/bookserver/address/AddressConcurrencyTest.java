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
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;

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
        return new CreateAddressRequest("Home", "Jane Doe", "010-1234-5678", "KR",
                "서울특별시 강남구 테헤란로 1", "101동 1001호", "06234", isDefault);
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
}
