package com.example.bookserver.address;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import java.time.LocalDate;
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
public class AddressMapperTest {

    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private UserMapper userMapper;

    // --- helpers ---
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

    private Address newAddress(UUID userUuid, String alias, boolean isDefault) {
        return newAddress(userUuid, alias, "서울특별시 강남구 테헤란로 1", isDefault);
    }

    /**
     * A distinct road makes a distinct address. uq_address_no_duplicate_per_user keys on the
     * address itself and not on the alias, so two rows that differ only by their label are a
     * duplicate — any test wanting two addresses has to give them two roads.
     */
    private Address newAddress(UUID userUuid, String alias, String roadAddress, boolean isDefault) {
        Address a = new Address();
        a.setAddressUuid(Uuids.newId());
        a.setUserUuid(userUuid);
        a.setAlias(alias);
        a.setRecipient("Jane Doe");
        a.setPhone("010-1234-5678");
        a.setCountry("KR");
        a.setRoadAddress(roadAddress);
        a.setDetailAddress("101동 1001호");
        a.setPostalCode("06234");
        a.setDefaultAddress(isDefault);
        return a;
    }

    // Verifies: an address inserts and reads back by id, all fields round-trip
    // (including the explicitly-mapped is_default), and created_at is DB-filled.
    @Test
    void insert_and_findById() {
        UUID userUuid = persistUser();
        Address a = newAddress(userUuid, "Home", true);

        addressMapper.insert(a);

        Address found = addressMapper.findById(a.getAddressUuid());
        assertThat(found).isNotNull();
        assertThat(found.getUserUuid()).isEqualTo(userUuid);
        assertThat(found.getAlias()).isEqualTo("Home");
        assertThat(found.getRecipient()).isEqualTo("Jane Doe");
        assertThat(found.getCountry()).isEqualTo("KR");
        assertThat(found.getRoadAddress()).isEqualTo("서울특별시 강남구 테헤란로 1");
        assertThat(found.getDetailAddress()).isEqualTo("101동 1001호");
        assertThat(found.getPostalCode()).isEqualTo("06234");
        assertThat(found.isDefaultAddress()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();   // DB default
    }

    // Verifies: findByIdAndUser returns the row only for its owner — the owner gets it,
    // another user (guessing the id) gets null. Used to snapshot an address onto an order.
    @Test
    void findByIdAndUser_isOwnerScoped() {
        UUID owner = persistUser();
        UUID other = persistUser();
        Address a = newAddress(owner, "Home", false);
        addressMapper.insert(a);

        assertThat(addressMapper.findByIdAndUser(a.getAddressUuid(), other)).isNull();
        Address found = addressMapper.findByIdAndUser(a.getAddressUuid(), owner);
        assertThat(found).isNotNull();
        assertThat(found.getAlias()).isEqualTo("Home");
        assertThat(found.isDefaultAddress()).isFalse();
    }

    // Verifies: findByUser returns every address for that user, default first.
    @Test
    void findByUser_returnsAll_defaultFirst() {
        UUID userUuid = persistUser();
        addressMapper.insert(newAddress(userUuid, "Work", "서울특별시 중구 세종대로 100", false));
        addressMapper.insert(newAddress(userUuid, "Home", true));

        assertThat(addressMapper.findByUser(userUuid))
                .extracting(Address::getAlias)
                .containsExactly("Home", "Work");   // default first, then the other
    }

    // Verifies: update only affects the owner's row — updating with a different
    // user_uuid guard touches nothing (returns 0 rows) and leaves the data intact.
    @Test
    void update_isOwnerScoped() {
        UUID owner = persistUser();
        UUID other = persistUser();
        Address a = newAddress(owner, "Home", false);
        addressMapper.insert(a);

        // an attacker guessing the id but not owning it changes nothing
        a.setUserUuid(other);
        a.setAlias("Hacked");
        assertThat(addressMapper.update(a)).isZero();

        assertThat(addressMapper.findById(a.getAddressUuid()).getAlias()).isEqualTo("Home");
    }

    // Verifies: delete is owner-scoped — deleting another user's address affects 0
    // rows; deleting one's own returns 1 and the row is gone.
    @Test
    void delete_isOwnerScoped() {
        UUID owner = persistUser();
        UUID other = persistUser();
        Address a = newAddress(owner, "Home", false);
        addressMapper.insert(a);

        assertThat(addressMapper.delete(a.getAddressUuid(), other)).isZero();
        assertThat(addressMapper.findById(a.getAddressUuid())).isNotNull();

        assertThat(addressMapper.delete(a.getAddressUuid(), owner)).isEqualTo(1);
        assertThat(addressMapper.findById(a.getAddressUuid())).isNull();
    }

    // Verifies: clearDefaultForUser unsets every default flag for that user, so a new
    // default can then be set without violating the one-default partial unique index.
    @Test
    void clearDefaultForUser_unsetsDefault() {
        UUID userUuid = persistUser();
        addressMapper.insert(newAddress(userUuid, "Home", true));

        addressMapper.clearDefaultForUser(userUuid);

        assertThat(addressMapper.findByUser(userUuid))
                .allSatisfy(a -> assertThat(a.isDefaultAddress()).isFalse());
    }

    // Verifies: the DB enforces at most one default address per user — a second
    // default insert for the same user hits the partial unique index.
    // The two addresses must be genuinely different, or this would pass on
    // uq_address_no_duplicate_per_user instead and stop testing what it names.
    @Test
    void secondDefault_forSameUser_isRejected() {
        UUID userUuid = persistUser();
        addressMapper.insert(newAddress(userUuid, "Home", true));

        assertThatThrownBy(() ->
                addressMapper.insert(newAddress(userUuid, "Work", "서울특별시 중구 세종대로 100", true)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // Verifies: the DB enforces one saved address per user — the same address inserted twice
    // hits uq_address_no_duplicate_per_user, whatever it is labelled. This is the constraint
    // that gives two simultaneous creates something to collide on (#61).
    @Test
    void sameAddressTwice_forSameUser_isRejected() {
        UUID userUuid = persistUser();
        addressMapper.insert(newAddress(userUuid, "Home", false));

        assertThatThrownBy(() -> addressMapper.insert(newAddress(userUuid, "집", false)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // Verifies: the constraint is per user, so it never blocks one user because another
    // happens to live at the same place (flatmates, an office, a family home).
    @Test
    void sameAddress_forAnotherUser_isAccepted() {
        UUID me = persistUser();
        UUID other = persistUser();
        addressMapper.insert(newAddress(me, "Home", false));

        Address theirs = newAddress(other, "Home", false);
        addressMapper.insert(theirs);

        assertThat(addressMapper.findById(theirs.getAddressUuid())).isNotNull();
    }
}
