package com.example.bookserver.address;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.address.dto.UpdateAddressRequest;

/**
 * Address-book operations, always scoped to the authenticated user's uuid.
 *
 * <p>NOTE: method bodies are implemented in the service layer step (mapper -> controller
 * -> service). This skeleton exists so the controller and its slice test compile; the
 * controller test replaces this bean with a Mockito mock, so these bodies never run there.
 */
@Service
public class AddressService {

    private final AddressMapper addressMapper;

    public AddressService(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    /** Save a new address to the user's book; returns the generated address_uuid. */
    public UUID addAddress(UUID userUuid, CreateAddressRequest req) {
        throw new UnsupportedOperationException("implemented in the service layer step");
    }

    /** Every saved address for the user, default first. */
    public List<Address> listMyAddresses(UUID userUuid) {
        throw new UnsupportedOperationException("implemented in the service layer step");
    }

    /** Replace a saved address the user owns; 404 if it is not theirs / missing. */
    public void updateAddress(UUID userUuid, UUID addressUuid, UpdateAddressRequest req) {
        throw new UnsupportedOperationException("implemented in the service layer step");
    }

    /** Remove a saved address the user owns; 404 if it is not theirs / missing. */
    public void deleteAddress(UUID userUuid, UUID addressUuid) {
        throw new UnsupportedOperationException("implemented in the service layer step");
    }
}
