package com.example.bookserver.book.dto;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks the shape and then the check digit. See {@link Isbn13}. */
public class Isbn13Validator implements ConstraintValidator<Isbn13, String> {

    private static final Pattern SHAPE = Pattern.compile("97[89]\\d{10}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;   // @NotBlank's job, not this one's
        }
        return SHAPE.matcher(value).matches() && checkDigitAgrees(value);
    }

    /**
     * The first twelve digits are weighted 1, 3, 1, 3, ... left to right; the thirteenth is
     * whatever makes the total a multiple of ten.
     */
    private static boolean checkDigitAgrees(String isbn) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return (10 - sum % 10) % 10 == isbn.charAt(12) - '0';
    }
}
