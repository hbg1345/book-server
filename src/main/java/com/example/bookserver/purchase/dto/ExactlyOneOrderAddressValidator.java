package com.example.bookserver.purchase.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validates that a {@link PlaceOrderRequest} carries exactly one address source. */
public class ExactlyOneOrderAddressValidator
        implements ConstraintValidator<ExactlyOneOrderAddress, PlaceOrderRequest> {

    @Override
    public boolean isValid(PlaceOrderRequest req, ConstraintValidatorContext ctx) {
        if (req == null) {
            return true;   // @NotNull/@RequestBody handles a missing body
        }
        boolean exactlyOne = (req.addressUuid() != null) ^ (req.address() != null);
        if (!exactlyOne) {
            // report on the `address` field so the error surfaces under a real key
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("address")
                    .addConstraintViolation();
        }
        return exactlyOne;
    }
}
