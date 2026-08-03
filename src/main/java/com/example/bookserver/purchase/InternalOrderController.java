package com.example.bookserver.purchase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal maintenance endpoints, meant to be driven by Cloud Scheduler rather than end
 * users. They are not part of the public JWT-secured surface; instead they are guarded by
 * a shared secret ({@code internal.sweep-token}) sent in the {@code X-Internal-Token}
 * header, so Cloud Scheduler can call them while the rest of {@code /internal/**} stays
 * closed. Fails closed: if no token is configured, every call is rejected.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final UnpaidOrderSweeper unpaidOrderSweeper;
    private final String sweepToken;

    public InternalOrderController(UnpaidOrderSweeper unpaidOrderSweeper,
                                   @Value("${internal.sweep-token:}") String sweepToken) {
        this.unpaidOrderSweeper = unpaidOrderSweeper;
        this.sweepToken = sweepToken;
    }

    /** Cancel unpaid orders past the payment window; returns how many were cancelled. */
    @PostMapping("/expire-unpaid")
    public ResponseEntity<Integer> expireUnpaid(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(unpaidOrderSweeper.sweep());
    }

    /** Constant-time comparison; rejects everything when no secret is configured. */
    private boolean tokenMatches(String provided) {
        if (!StringUtils.hasText(sweepToken) || !StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(sweepToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
