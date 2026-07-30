package com.example.bookserver.auth;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stored refresh token. The opaque token string itself is never persisted —
 * only its SHA-256 hash. All rotations of one login share a {@code familyId};
 * a rotated token is kept (used=true) so a replay can be detected.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    private UUID tokenId;
    private UUID familyId;
    private UUID userUuid;
    private String tokenHash;
    private boolean used;
    private boolean revoked;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
