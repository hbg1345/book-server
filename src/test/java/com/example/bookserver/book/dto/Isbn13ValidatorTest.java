package com.example.bookserver.book.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check digit is the whole reason this constraint exists rather than a length pattern, so
 * the cases that matter are the ones a pattern would let through: a mistyped digit and two
 * digits swapped.
 */
class Isbn13ValidatorTest {

    private final Isbn13Validator validator = new Isbn13Validator();

    private boolean valid(String isbn) {
        return validator.isValid(isbn, null);
    }

    @Test
    void acceptsRealIsbns() {
        assertThat(valid("9780134494166")).isTrue();   // Clean Architecture
        assertThat(valid("9780132350884")).isTrue();   // Clean Code
    }

    @Test
    void rejectsAMistypedDigit() {
        // 9780134494166 with one digit changed: right length, right prefix, wrong book
        assertThat(valid("9780134494176")).isFalse();
    }

    @Test
    void rejectsTransposedDigits() {
        // ...4166 -> ...1466: the other way a human copying thirteen digits gets them wrong
        assertThat(valid("9780134491466")).isFalse();
    }

    @Test
    void rejectsTheWrongShape() {
        assertThat(valid("978013449416")).isFalse();     // twelve digits
        assertThat(valid("97801344941665")).isFalse();   // fourteen
        assertThat(valid("9770134494166")).isFalse();    // 977 is not a book prefix
        assertThat(valid("978-0134494166")).isFalse();   // hyphens are the display form
        assertThat(valid("")).isFalse();
    }

    @Test
    void passesNullToNotBlank() {
        assertThat(valid(null)).isTrue();
    }
}
