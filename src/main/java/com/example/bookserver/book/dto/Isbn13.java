package com.example.bookserver.book.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A well-formed ISBN-13: thirteen digits, a 978 or 979 prefix, and a final digit that agrees
 * with the twelve before it.
 *
 * <p>The check digit is the point. A plain length-and-digits pattern accepts every typo, and a
 * mistyped ISBN is worse than a missing one — it is a book claiming to be a different book. The
 * check digit catches every single-digit slip and most transpositions, which are exactly the
 * two ways a human copying thirteen digits gets them wrong.
 *
 * <p>Null passes: absence is {@code @NotBlank}'s business, not this constraint's.
 */
@Documented
@Constraint(validatedBy = Isbn13Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Isbn13 {

    String message() default "must be a valid ISBN-13";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
