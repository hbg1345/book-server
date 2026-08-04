package com.example.bookserver.purchase.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Class-level constraint on {@link PlaceOrderRequest}: exactly one of {@code addressUuid} or
 * {@code address} must be set. The violation is reported on the {@code address} field so the
 * 400 response carries a real field key rather than an internal accessor name.
 */
@Documented
@Constraint(validatedBy = ExactlyOneOrderAddressValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExactlyOneOrderAddress {

    String message() default "provide exactly one of addressUuid or address";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
