package com.example.bookserver.address;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.Concurrently;
import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Saving one address to the book, submitted twice at the same instant — a double-clicked form,
 * or a client that retries a request whose response was lost.
 *
 * <p>Unlike the paths fixed in #55 and #60, a create has nothing for two callers to meet on:
 * {@link Uuids#newId()} mints a fresh primary key per call, so the two submissions never contend
 * and the database is content to hold both rows. See #61.
 *
 * <p>These tests are written to describe the behaviour that is wanted, so they fail against the
 * service as it stands. They are the diagnosis, not the fix.
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

    /**
     * The same address saved twice at once. Nothing in the write distinguishes the second
     * submission from a user who genuinely wants two identical entries, so the address book ends
     * up holding the same place twice and someone has to delete one by hand.
     *
     * <p>Either outcome is acceptable — one save winning and the other being told the address is
     * already there, or the repeat returning the address it already created. What is not
     * acceptable is two rows.
     */
    @Test
    void savingOneAddressTwiceAtOnce_keepsOneRow() throws Exception {
        UUID user = persistUser();

        List<UUID> saved = Concurrently.runAtOnce(2, () -> addressService.addAddress(user, createReq(false)));

        assertThat(addressMapper.findByUser(user))
                .as("one address submitted, one address in the book")
                .hasSize(1);
        assertThat(saved).as("both submissions name the same address").containsOnly(saved.get(0));
    }

    /**
     * The same submission again, this time asking for the address to be the default one.
     *
     * <p>This is the one shape of the bug the schema half-catches: {@code uq_address_one_default}
     * is a collision point, so the two inserts do meet and the loser is rejected. But it is
     * rejected as a raw {@link DuplicateKeyException}, which nothing in
     * {@link com.example.bookserver.common.GlobalExceptionHandler} maps — the user double-clicks
     * and gets a 500 for an address that was saved perfectly well. The same 500-where-409 shape
     * that signup had before #60.
     */
    @Test
    void savingOneDefaultAddressTwiceAtOnce_doesNotFailWithAServerError() throws Exception {
        UUID user = persistUser();

        assertThatCode(() -> Concurrently.runAtOnce(2, () -> addressService.addAddress(user, createReq(true))))
                .as("a double-clicked default address is not a server fault")
                .doesNotThrowAnyException();

        assertThat(addressMapper.findByUser(user))
                .as("one address submitted, one address in the book")
                .hasSize(1);
    }
}
