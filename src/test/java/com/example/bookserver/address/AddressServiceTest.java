package com.example.bookserver.address;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import com.example.bookserver.TestcontainersConfiguration;
import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.address.dto.UpdateAddressRequest;
import com.example.bookserver.common.Uuids;
import com.example.bookserver.user.User;
import com.example.bookserver.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisTest
@Import(TestcontainersConfiguration.class)
@Sql("/reset.sql")
public class AddressServiceTest {

    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private UserMapper userMapper;

    private AddressService addressService;

    @BeforeEach
    void setUp() {
        addressService = new AddressService(addressMapper);
    }

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

    private CreateAddressRequest createReq(String alias, String country, String postalCode, boolean isDefault) {
        return new CreateAddressRequest(alias, "Jane Doe", "010-1234-5678", country,
                "서울특별시 강남구 테헤란로 1", "101동 1001호", postalCode, isDefault);
    }

    /** Same address as createReq("Home", "KR", "06234", false) but with no unit number. */
    private CreateAddressRequest noDetailReq() {
        return new CreateAddressRequest("Home", "Jane Doe", "010-1234-5678", "KR",
                "서울특별시 강남구 테헤란로 1", null, "06234", false);
    }

    private UpdateAddressRequest updateReq(String alias, String country, String postalCode, boolean isDefault) {
        return new UpdateAddressRequest(alias, "Jane Doe", "010-1234-5678", country,
                "서울특별시 강남구 테헤란로 2", "202동 2002호", postalCode, isDefault);
    }

    // add: persists all fields, uppercases the country code, returns the new id.
    @Test
    void addAddress_savesAndReturnsId() {
        UUID user = persistUser();

        UUID id = addressService.addAddress(user, createReq("Home", "kr", "06234", false));

        Address saved = addressMapper.findById(id);
        assertThat(saved).isNotNull();
        assertThat(saved.getUserUuid()).isEqualTo(user);
        assertThat(saved.getAlias()).isEqualTo("Home");
        assertThat(saved.getCountry()).isEqualTo("KR");        // normalized to upper case
        assertThat(saved.getPostalCode()).isEqualTo("06234");
        assertThat(saved.isDefaultAddress()).isFalse();
    }

    // add with default=true after an existing default: the old default is cleared first,
    // so only the new one is default (and the partial unique index is not violated).
    @Test
    void addAddress_settingDefault_clearsPreviousDefault() {
        UUID user = persistUser();
        addressService.addAddress(user, createReq("Home", "KR", "06234", true));

        addressService.addAddress(user, createReq("Work", "KR", "07001", true));

        assertThat(addressMapper.findByUser(user))
                .filteredOn(Address::isDefaultAddress)
                .extracting(Address::getAlias)
                .containsExactly("Work");
    }

    // the same address saved twice -> 409-style error rather than a second copy of it.
    // (Only the throw is asserted: the unique violation aborts the surrounding transaction,
    // so any query after it would fail on the aborted transaction rather than on the data.)
    @Test
    void addAddress_sameAddressTwice_throwsDuplicate() {
        UUID user = persistUser();
        addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        assertThatThrownBy(() -> addressService.addAddress(user, createReq("Home", "KR", "06234", false)))
                .isInstanceOf(DuplicateAddressException.class);
    }

    // the same address under a different label is still the same address.
    @Test
    void addAddress_sameAddressDifferentAlias_throwsDuplicate() {
        UUID user = persistUser();
        addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        assertThatThrownBy(() -> addressService.addAddress(user, createReq("집", "KR", "06234", false)))
                .isInstanceOf(DuplicateAddressException.class);
    }

    // detail_address is nullable, and a unique index counts two NULLs as distinct — so without
    // the COALESCE in the index, addresses with no unit number would slip past the constraint.
    @Test
    void addAddress_sameAddressWithoutDetail_throwsDuplicate() {
        UUID user = persistUser();
        addressService.addAddress(user, noDetailReq());

        assertThatThrownBy(() -> addressService.addAddress(user, noDetailReq()))
                .isInstanceOf(DuplicateAddressException.class);
    }

    // the same street address sent to someone else is a separate entry, not a duplicate.
    @Test
    void addAddress_sameAddressDifferentRecipient_isSaved() {
        UUID user = persistUser();
        addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        UUID gift = addressService.addAddress(user, new CreateAddressRequest("Gift", "John Roe",
                "010-9999-8888", "KR", "서울특별시 강남구 테헤란로 1", "101동 1001호", "06234", false));

        assertThat(addressMapper.findById(gift)).isNotNull();
        assertThat(addressMapper.findByUser(user)).hasSize(2);
    }

    // one user's address does not block another user's identical one.
    @Test
    void addAddress_sameAddressAnotherUser_isSaved() {
        UUID me = persistUser();
        UUID other = persistUser();
        addressService.addAddress(me, createReq("Home", "KR", "06234", false));

        UUID theirs = addressService.addAddress(other, createReq("Home", "KR", "06234", false));

        assertThat(addressMapper.findById(theirs)).isNotNull();
    }

    // editing one saved address into a copy of another is the same duplicate, reached from the
    // other direction.
    @Test
    void updateAddress_intoAnExistingAddress_throwsDuplicate() {
        UUID user = persistUser();
        addressService.addAddress(user, createReq("Home", "KR", "06234", false));
        UUID work = addressService.addAddress(user, new CreateAddressRequest("Work", "Jane Doe",
                "010-1234-5678", "KR", "서울특별시 중구 세종대로 100", "5층", "04524", false));

        assertThatThrownBy(() -> addressService.updateAddress(user, work,
                new UpdateAddressRequest("Work", "Jane Doe", "010-1234-5678", "KR",
                        "서울특별시 강남구 테헤란로 1", "101동 1001호", "06234", false)))
                .isInstanceOf(DuplicateAddressException.class);
    }

    // add with a KR postal code that is not 5 digits -> 400-style error, nothing saved.
    @Test
    void addAddress_invalidKrPostalCode_throws() {
        UUID user = persistUser();

        assertThatThrownBy(() -> addressService.addAddress(user, createReq("Home", "KR", "123", false)))
                .isInstanceOf(InvalidPostalCodeException.class);
        assertThat(addressMapper.findByUser(user)).isEmpty();
    }

    // a country without a configured rule (US) is not format-checked: any postal is accepted.
    @Test
    void addAddress_nonKrCountry_skipsPostalValidation() {
        UUID user = persistUser();

        UUID id = addressService.addAddress(user, createReq("US home", "US", "1", false));

        assertThat(addressMapper.findById(id).getPostalCode()).isEqualTo("1");
    }

    // list is scoped to the calling user and returns the default first.
    @Test
    void listMyAddresses_isScopedToUser_defaultFirst() {
        UUID me = persistUser();
        UUID other = persistUser();
        addressService.addAddress(me, createReq("Work", "KR", "07001", false));
        addressService.addAddress(me, createReq("Home", "KR", "06234", true));
        addressService.addAddress(other, createReq("Other", "KR", "08001", true));

        assertThat(addressService.listMyAddresses(me))
                .extracting(Address::getAlias)
                .containsExactly("Home", "Work");   // default first, other user's excluded
    }

    // update replaces the mutable fields of the owner's address.
    @Test
    void updateAddress_replacesFields() {
        UUID user = persistUser();
        UUID id = addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        addressService.updateAddress(user, id, updateReq("Office", "KR", "07001", false));

        Address updated = addressMapper.findById(id);
        assertThat(updated.getAlias()).isEqualTo("Office");
        assertThat(updated.getPostalCode()).isEqualTo("07001");
        assertThat(updated.getRoadAddress()).isEqualTo("서울특별시 강남구 테헤란로 2");
    }

    // updating an address the caller does not own -> 404, and the owner's row is untouched
    // (the whole transaction, including any default clear, rolls back).
    @Test
    void updateAddress_notOwned_throwsNotFound() {
        UUID owner = persistUser();
        UUID other = persistUser();
        UUID id = addressService.addAddress(owner, createReq("Home", "KR", "06234", false));

        assertThatThrownBy(() -> addressService.updateAddress(other, id, updateReq("Hacked", "KR", "07001", false)))
                .isInstanceOf(AddressNotFoundException.class);
        assertThat(addressMapper.findById(id).getAlias()).isEqualTo("Home");
    }

    // update validates the postal code too.
    @Test
    void updateAddress_invalidKrPostalCode_throws() {
        UUID user = persistUser();
        UUID id = addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        assertThatThrownBy(() -> addressService.updateAddress(user, id, updateReq("Home", "KR", "12", false)))
                .isInstanceOf(InvalidPostalCodeException.class);
    }

    // delete removes the owner's address.
    @Test
    void deleteAddress_removes() {
        UUID user = persistUser();
        UUID id = addressService.addAddress(user, createReq("Home", "KR", "06234", false));

        addressService.deleteAddress(user, id);

        assertThat(addressMapper.findById(id)).isNull();
    }

    // deleting an address the caller does not own -> 404, and the row survives.
    @Test
    void deleteAddress_notOwned_throwsNotFound() {
        UUID owner = persistUser();
        UUID other = persistUser();
        UUID id = addressService.addAddress(owner, createReq("Home", "KR", "06234", false));

        assertThatThrownBy(() -> addressService.deleteAddress(other, id))
                .isInstanceOf(AddressNotFoundException.class);
        assertThat(addressMapper.findById(id)).isNotNull();
    }
}
