package com.example.bookserver.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies stateless access tokens (JWT, HMAC-SHA256). The subject is
 * the user's uuid. Verification failures (bad signature, expired, malformed) surface
 * as {@link io.jsonwebtoken.JwtException}.
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.access-token-ttl}") Duration accessTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    /** Issue an access token for the given user, expiring after the configured TTL. */
    public String issueAccessToken(UUID userUuid) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userUuid.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify the token's signature and expiry and return the user uuid (the subject).
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid, tampered, or expired
     */
    public UUID parseUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }
}
