package com.example.bookserver.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bookserver.common.Uuids;

/**
 * Issues, rotates, and revokes opaque refresh tokens. The raw token is a random
 * high-entropy string returned to the caller and never stored — only its SHA-256
 * hash is persisted. Rotation consumes the presented token and issues a new one in
 * the same family; presenting an already-consumed token is treated as a replay and
 * revokes the whole family.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenMapper refreshTokenMapper;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenMapper refreshTokenMapper,
                               @Value("${jwt.refresh-token-ttl}") Duration refreshTtl) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.refreshTtl = refreshTtl;
    }

    /** Issue a refresh token that starts a brand-new family (used at login). */
    public String issue(UUID userUuid) {
        return issueInFamily(userUuid, Uuids.newId());
    }

    /**
     * Rotate the presented token: validate it, consume it, and issue a replacement in
     * the same family. Replaying a consumed token revokes the entire family.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, revoked, expired, or replayed
     */
    // noRollbackFor: on reuse detection we revokeFamily() and then throw — that revoke
    // must commit, so the exception must not roll the transaction back.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotationResult rotate(String rawToken) {
        RefreshToken row = refreshTokenMapper.findByHash(hash(rawToken));
        if (row == null || row.isRevoked()) {
            throw new InvalidRefreshTokenException();
        }
        if (row.isUsed()) {
            // a token that was already rotated away is being replayed → assume theft
            refreshTokenMapper.revokeFamily(row.getFamilyId());
            throw new InvalidRefreshTokenException();
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException();
        }
        refreshTokenMapper.markUsed(row.getTokenId());
        String newToken = issueInFamily(row.getUserUuid(), row.getFamilyId());
        return new RotationResult(row.getUserUuid(), newToken);
    }

    /** Revoke the whole family the token belongs to (logout). No-op if unknown. */
    @Transactional
    public void revoke(String rawToken) {
        RefreshToken row = refreshTokenMapper.findByHash(hash(rawToken));
        if (row != null) {
            refreshTokenMapper.revokeFamily(row.getFamilyId());
        }
    }

    private String issueInFamily(UUID userUuid, UUID familyId) {
        String raw = randomToken();
        RefreshToken token = new RefreshToken();
        token.setTokenId(Uuids.newId());
        token.setFamilyId(familyId);
        token.setUserUuid(userUuid);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(LocalDateTime.now().plus(refreshTtl));
        refreshTokenMapper.insert(token);
        return raw;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];   // 256 bits of entropy
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex. The token is high-entropy random, so a plain (unsalted) hash is fine. */
    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
