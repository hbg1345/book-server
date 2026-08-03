package com.example.bookserver.auth;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    // HS256 needs a key of at least 32 bytes
    private static final String SECRET = "test-secret-that-is-long-enough-32bytes!!";

    private final JwtProvider jwt = new JwtProvider(SECRET, Duration.ofHours(1));

    // a freshly issued token verifies and yields back the same user uuid and role
    @Test
    void issue_then_parse_roundTrips() {
        UUID userUuid = UUID.randomUUID();

        String token = jwt.issueAccessToken(userUuid, "ADMIN");

        JwtProvider.AccessToken parsed = jwt.parse(token);
        assertThat(parsed.userUuid()).isEqualTo(userUuid);
        assertThat(parsed.role()).isEqualTo("ADMIN");
    }

    // a token signed with a different secret must not verify
    @Test
    void parse_throws_whenSignedWithDifferentSecret() {
        JwtProvider other = new JwtProvider("another-secret-that-is-long-enough-32b!!", Duration.ofHours(1));
        String foreign = other.issueAccessToken(UUID.randomUUID(), "USER");

        assertThatThrownBy(() -> jwt.parse(foreign))
                .isInstanceOf(JwtException.class);
    }

    // a tampered token must not verify
    @Test
    void parse_throws_whenTampered() {
        String token = jwt.issueAccessToken(UUID.randomUUID(), "USER");
        // Flip the FIRST character of the signature segment. The last base64url char of
        // a 32-byte HMAC carries padding bits, so some single-char flips (e.g. 'a'<->'b')
        // decode to the same signature and would not be detected — a flaky test. The
        // first char is fully significant, so any change always breaks verification.
        int sig = token.lastIndexOf('.') + 1;
        char c = token.charAt(sig);
        String tampered = token.substring(0, sig) + (c == 'A' ? 'B' : 'A') + token.substring(sig + 1);

        assertThatThrownBy(() -> jwt.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    // an already-expired token is rejected (TTL in the past)
    @Test
    void parse_throws_whenExpired() {
        JwtProvider shortLived = new JwtProvider(SECRET, Duration.ofSeconds(-1));
        String expired = shortLived.issueAccessToken(UUID.randomUUID(), "USER");

        assertThatThrownBy(() -> jwt.parse(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
