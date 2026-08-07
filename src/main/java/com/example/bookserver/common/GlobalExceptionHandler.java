package com.example.bookserver.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.bookserver.address.AddressNotFoundException;
import com.example.bookserver.address.DuplicateAddressException;
import com.example.bookserver.address.InvalidPostalCodeException;
import com.example.bookserver.auth.InvalidRefreshTokenException;
import com.example.bookserver.book.BlankSearchQueryException;
import com.example.bookserver.book.BookNotFoundException;
import com.example.bookserver.book.InvalidPageException;
import com.example.bookserver.cart.CartItemNotFoundException;
import com.example.bookserver.payment.PaymentAmountMismatchException;
import com.example.bookserver.payment.PaymentIntentFailedException;
import com.example.bookserver.payment.RefundFailedException;
import com.example.bookserver.purchase.EmptyCartException;
import com.example.bookserver.purchase.IllegalOrderStateException;
import com.example.bookserver.purchase.InsufficientInventoryException;
import com.example.bookserver.purchase.InvalidCancellationException;
import com.example.bookserver.purchase.OrderItemNotFoundException;
import com.example.bookserver.purchase.OrderNotFoundException;
import com.example.bookserver.user.DuplicateUserIdException;
import com.example.bookserver.user.InvalidCredentialsException;
import com.example.bookserver.user.InvalidPasswordException;
import com.example.bookserver.user.UserNotFoundException;

/**
 * Maps domain and validation errors to RFC 9457 Problem Details responses
 * (application/problem+json). Extending {@link ResponseEntityExceptionHandler}
 * makes Spring MVC's own exceptions come out in the same format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Registering an already-taken login id. */
    @ExceptionHandler(DuplicateUserIdException.class)
    public ProblemDetail handleDuplicateUserId(DuplicateUserIdException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Operating on a user_uuid that no longer exists. */
    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Operating on a book_uuid that does not exist. */
    @ExceptionHandler(BookNotFoundException.class)
    public ProblemDetail handleBookNotFound(BookNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** A catalogue search with an empty query, which would match the whole catalogue. */
    @ExceptionHandler(BlankSearchQueryException.class)
    public ProblemDetail handleBlankSearchQuery(BlankSearchQueryException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** A page request the catalogue will not serve: negative, too large, or too deep. */
    @ExceptionHandler(InvalidPageException.class)
    public ProblemDetail handleInvalidPage(InvalidPageException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Changing the quantity of a book that is not in the user's cart. */
    @ExceptionHandler(CartItemNotFoundException.class)
    public ProblemDetail handleCartItemNotFound(CartItemNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Operating on an address that does not exist or is not the caller's. */
    @ExceptionHandler(AddressNotFoundException.class)
    public ProblemDetail handleAddressNotFound(AddressNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Saving an address the user's book already holds — a double-clicked form, typically. */
    @ExceptionHandler(DuplicateAddressException.class)
    public ProblemDetail handleDuplicateAddress(DuplicateAddressException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** A postal code that does not match its country's expected format. */
    @ExceptionHandler(InvalidPostalCodeException.class)
    public ProblemDetail handleInvalidPostalCode(InvalidPostalCodeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Cancelling a line the order does not hold. */
    @ExceptionHandler(OrderItemNotFoundException.class)
    public ProblemDetail handleOrderItemNotFound(OrderItemNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Cancelling more copies than the order holds, or none at all. */
    @ExceptionHandler(InvalidCancellationException.class)
    public ProblemDetail handleInvalidCancellation(InvalidCancellationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Acting on an order that does not exist for this user. */
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Placing an order from an empty cart. */
    @ExceptionHandler(EmptyCartException.class)
    public ProblemDetail handleEmptyCart(EmptyCartException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Not enough stock to reserve for an order. */
    @ExceptionHandler(InsufficientInventoryException.class)
    public ProblemDetail handleInsufficientInventory(InsufficientInventoryException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** A state transition not allowed from the order's current state (pay/cancel). */
    @ExceptionHandler(IllegalOrderStateException.class)
    public ProblemDetail handleIllegalOrderState(IllegalOrderStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** The confirmed charge did not match the order total (reported by the provider's webhook). */
    @ExceptionHandler(PaymentAmountMismatchException.class)
    public ProblemDetail handlePaymentAmountMismatch(PaymentAmountMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** The provider would not open a payment intent; nothing was charged, the caller may retry. */
    @ExceptionHandler(PaymentIntentFailedException.class)
    public ProblemDetail handlePaymentIntentFailed(PaymentIntentFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    /** The gateway could not refund; the order is left unchanged for a retry. */
    @ExceptionHandler(RefundFailedException.class)
    public ProblemDetail handleRefundFailed(RefundFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    /** Wrong current password on a password change. */
    @ExceptionHandler(InvalidPasswordException.class)
    public ProblemDetail handleInvalidPassword(InvalidPasswordException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Failed login (unknown id or wrong password). */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** Invalid, expired, revoked, or replayed refresh token. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** Bean-validation failures on @Valid request bodies: 400 with per-field messages. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "request validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }
}
