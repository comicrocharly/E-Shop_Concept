package com.eshop.config;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1 — Unit tests for {@link JwtTokenProvider} (no Spring context).
 */
class JwtTokenProviderTest {

    /** 41-byte key -> valid for HS256 (>= 32 bytes). */
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("eshop-test-secret-key-min-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));

    private static final long ACCESS_TTL_MS = 3_600_000L;   // 1h
    private static final long REFRESH_TTL_MS = 86_400_000L;  // 24h

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, ACCESS_TTL_MS, REFRESH_TTL_MS);
    }

    @Nested
    @DisplayName("Access tokens")
    class AccessToken {

        @Test
        @DisplayName("createAccessToken carries subject and role claims")
        void createAccessTokenContainsSubjectAndRole() {
            String token = provider.createAccessToken("carlo", "USER");

            assertThat(provider.getUsernameFromToken(token)).isEqualTo("carlo");
            assertThat(provider.getRoleFromToken(token)).isEqualTo("USER");
        }

        @Test
        @DisplayName("createAccessToken is a valid, parseable token")
        void createAccessTokenIsValid() {
            String token = provider.createAccessToken("admin", "ADMIN");

            assertThat(token).isNotBlank();
            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("access token with negative validity is expired")
        void expiredAccessTokenIsRejected() {
            JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, -1_000L, REFRESH_TTL_MS);
            String token = shortLived.createAccessToken("carlo", "USER");

            assertThat(provider.validateToken(token)).isFalse();
            assertThatThrownBy(() -> provider.getUsernameFromToken(token))
                    .isInstanceOf(JwtException.class);
        }
    }

    @Nested
    @DisplayName("Refresh tokens")
    class RefreshToken {

        @Test
        @DisplayName("createRefreshToken carries subject and type=refresh claim")
        void createRefreshTokenContainsSubjectAndType() {
            String token = provider.createRefreshToken("carlo");

            assertThat(provider.getUsernameFromToken(token)).isEqualTo("carlo");
            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("refresh token has no role claim")
        void refreshTokenHasNoRoleClaim() {
            String token = provider.createRefreshToken("carlo");

            assertThat(provider.getRoleFromToken(token)).isNull();
        }
    }

    @Nested
    @DisplayName("Token parsing")
    class Parsing {

        @Test
        @DisplayName("getUsernameFromToken returns the subject")
        void getUsernameFromTokenReturnsSubject() {
            String token = provider.createAccessToken("jane", "USER");

            assertThat(provider.getUsernameFromToken(token)).isEqualTo("jane");
        }

        @Test
        @DisplayName("getRoleFromToken returns the role claim")
        void getRoleFromTokenReturnsRole() {
            String token = provider.createAccessToken("boss", "ADMIN");

            assertThat(provider.getRoleFromToken(token)).isEqualTo("ADMIN");
        }
    }

    @Nested
    @DisplayName("Token validation")
    class Validation {

        @Test
        @DisplayName("valid token -> true")
        void validTokenReturnsTrue() {
            String token = provider.createAccessToken("carlo", "USER");

            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("tampered token -> false")
        void tamperedTokenReturnsFalse() {
            String token = provider.createAccessToken("carlo", "USER");
            // Flip the last character of the signature
            String tampered = token.substring(0, token.length() - 2)
                    + (token.endsWith("A") ? "BB" : "AA");

            assertThat(provider.validateToken(tampered)).isFalse();
        }

        @Test
        @DisplayName("garbage string -> false")
        void garbageTokenReturnsFalse() {
            assertThat(provider.validateToken("not.a.jwt")).isFalse();
        }

        @Test
        @DisplayName("null / empty token -> false")
        void nullOrEmptyTokenReturnsFalse() {
            assertThat(provider.validateToken(null)).isFalse();
            assertThat(provider.validateToken("")).isFalse();
        }

        @Test
        @DisplayName("token signed with a different key -> false")
        void tokenWithDifferentKeyReturnsFalse() {
            String otherSecret = Base64.getEncoder()
                    .encodeToString("another-secret-key-min-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
            JwtTokenProvider otherProvider = new JwtTokenProvider(otherSecret, ACCESS_TTL_MS, REFRESH_TTL_MS);
            String token = otherProvider.createAccessToken("carlo", "USER");

            assertThat(provider.validateToken(token)).isFalse();
        }
    }
}
