package com.example.bookserver.address;

/**
 * The postal code does not match the expected format for its country (e.g. a KR postal
 * code that is not 5 digits). Countries without a format rule configured are not checked.
 */
public class InvalidPostalCodeException extends RuntimeException {

    public InvalidPostalCodeException(String country, String postalCode) {
        super("Invalid postal code for " + country + ": " + postalCode);
    }
}
