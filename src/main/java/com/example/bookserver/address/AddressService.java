package com.example.bookserver.address;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.address.dto.UpdateAddressRequest;
import com.example.bookserver.common.Uuids;

/**
 * Address-book operations, always scoped to the authenticated user's uuid.
 *
 * <p>Postal codes are format-validated per country (the schema is international, so a US
 * ZIP and a KR postal code are both valid rows; only their formats differ). Countries
 * without a rule configured are accepted as-is. At most one default address is kept per
 * user: setting a new default takes the user's row (lockUserForDefaultChange) and then clears
 * the old default, within one transaction, so the partial unique index (uq_address_one_default)
 * is never violated. The lock is what makes the clear reliable — without it two callers can
 * each clear a default the other is about to insert, and both then claim it.
 *
 * <p>A saved address may not be saved twice. The rule lives in the database
 * (uq_address_no_duplicate_per_user) rather than in a read-then-write check here, because a
 * check has nothing to stop two simultaneous submissions both passing it; the write is where
 * they meet. The rejection is translated to {@link DuplicateAddressException} so callers get
 * a 409 rather than the raw driver error. See #61.
 */
@Service
public class AddressService {

    private final AddressMapper addressMapper;

    public AddressService(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    /** Save a new address to the user's book; returns the generated address_uuid. */
    @Transactional
    public UUID addAddress(UUID userUuid, CreateAddressRequest req) {
        String country = PostalCodes.normalizeCountry(req.country());
        PostalCodes.validate(country, req.postalCode());

        UUID addressUuid = Uuids.newId();
        Address a = new Address(addressUuid, userUuid, req.alias(), req.recipient(), req.phone(),
                country, req.roadAddress(), req.detailAddress(), req.postalCode(),
                req.defaultAddress(), null);

        if (req.defaultAddress()) {
            addressMapper.lockUserForDefaultChange(userUuid);
            addressMapper.clearDefaultForUser(userUuid);
        }
        try {
            addressMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw new DuplicateAddressException();
        }
        return addressUuid;
    }

    /** Every saved address for the user, default first. */
    public List<Address> listMyAddresses(UUID userUuid) {
        return addressMapper.findByUser(userUuid);
    }

    /** Replace a saved address the user owns; 404 if it is not theirs / missing. */
    @Transactional
    public void updateAddress(UUID userUuid, UUID addressUuid, UpdateAddressRequest req) {
        String country = PostalCodes.normalizeCountry(req.country());
        PostalCodes.validate(country, req.postalCode());

        Address a = new Address(addressUuid, userUuid, req.alias(), req.recipient(), req.phone(),
                country, req.roadAddress(), req.detailAddress(), req.postalCode(),
                req.defaultAddress(), null);

        if (req.defaultAddress()) {
            addressMapper.lockUserForDefaultChange(userUuid);
            addressMapper.clearDefaultForUser(userUuid);
        }
        int updated;
        try {
            updated = addressMapper.update(a);
        } catch (DuplicateKeyException e) {
            // editing one saved address into a copy of another is the same duplicate, reached
            // from the other direction
            throw new DuplicateAddressException();
        }
        if (updated == 0) {
            throw new AddressNotFoundException(addressUuid);   // rolls back the clear above
        }
    }

    /** Remove a saved address the user owns; 404 if it is not theirs / missing. */
    public void deleteAddress(UUID userUuid, UUID addressUuid) {
        if (addressMapper.delete(addressUuid, userUuid) == 0) {
            throw new AddressNotFoundException(addressUuid);
        }
    }
}
