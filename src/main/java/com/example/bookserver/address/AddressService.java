package com.example.bookserver.address;

import java.util.List;
import java.util.UUID;

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
 * user: setting a new default clears the old one first, within one transaction, so the
 * partial unique index (uq_address_one_default) is never violated.
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
            addressMapper.clearDefaultForUser(userUuid);
        }
        addressMapper.insert(a);
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
            addressMapper.clearDefaultForUser(userUuid);
        }
        if (addressMapper.update(a) == 0) {
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
