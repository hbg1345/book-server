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
 * the user's uuid and a {@code role} claim carries the authority. Verification failures
 * (bad signature, expired, malformed) surface as {@link io.jsonwebtoken.JwtException}.
 */
@Component
public class JwtProvider {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.access-token-ttl}") Duration accessTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    /** The verified contents of an access token: who the caller is and their role. */
    public record AccessToken(UUID userUuid, String role) {
    }

    /** Issue an access token for the given user + role, expiring after the configured TTL. */
    public String issueAccessToken(UUID userUuid, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userUuid.toString())
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify the token's signature and expiry and return its user uuid and role.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid, tampered, or expired
     */
    public AccessToken parse(String token) {
        var claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AccessToken(UUID.fromString(claims.getSubject()), claims.get(ROLE_CLAIM, String.class));
    }
}
