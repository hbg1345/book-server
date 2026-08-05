package com.example.bookserver.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The shared secret guarding {@code /internal/**}. Those endpoints sit outside the JWT surface
 * because their callers are machines — Cloud Scheduler, Cloud Tasks, the deploy pipeline — that
 * hold no user credentials, so a token in {@code X-Internal-Token} is what stands in for authn.
 *
 * <p>Kept in one place rather than repeated per controller: a security check that exists in two
 * copies eventually differs in one of them.
 *
 * <p>Fails closed. With no token configured every call is rejected, so a deployment that forgets
 * the secret exposes nothing.
 */
@Component
public class InternalTokenGuard {

    private final String token;

    public InternalTokenGuard(@Value("${internal.sweep-token:}") String token) {
        this.token = token;
    }

    /** Constant-time comparison, so a caller cannot learn the secret by timing its guesses. */
    public boolean matches(String provided) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
