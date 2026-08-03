package com.example.bookserver.address;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.address.dto.AddressResponse;
import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.address.dto.CreateAddressResponse;
import com.example.bookserver.address.dto.UpdateAddressRequest;

import jakarta.validation.Valid;

/**
 * Address-book endpoints for the authenticated user. The user's uuid is carried by the
 * JWT and injected via {@link AuthenticationPrincipal}; every operation is scoped to that
 * user, so one user's address book is never addressable by anyone else.
 */
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAddressResponse addAddress(@AuthenticationPrincipal UUID userUuid,
                                            @Valid @RequestBody CreateAddressRequest req) {
        return new CreateAddressResponse(addressService.addAddress(userUuid, req));
    }

    @GetMapping
    public List<AddressResponse> myAddresses(@AuthenticationPrincipal UUID userUuid) {
        return addressService.listMyAddresses(userUuid).stream().map(AddressResponse::from).toList();
    }

    @PutMapping("/{addressUuid}")
    public void updateAddress(@AuthenticationPrincipal UUID userUuid,
                              @PathVariable UUID addressUuid,
                              @Valid @RequestBody UpdateAddressRequest req) {
        addressService.updateAddress(userUuid, addressUuid, req);
    }

    @DeleteMapping("/{addressUuid}")
    public void deleteAddress(@AuthenticationPrincipal UUID userUuid,
                              @PathVariable UUID addressUuid) {
        addressService.deleteAddress(userUuid, addressUuid);
    }
}
