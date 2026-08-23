package com.eshop.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    /** Parser riutilizzabile: costruito una sola volta (invece di un nuovo parser per ogni chiamata). */
    private final JwtParser parser;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiration:3600000}") long accessTokenValidityMs,
            @Value("${app.jwt.refresh-token-expiration:86400000}") long refreshTokenValidityMs) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.accessTokenValidityMs = accessTokenValidityMs;
        this.refreshTokenValidityMs = refreshTokenValidityMs;
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    public String createAccessToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenValidityMs))
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenValidityMs))
                .signWith(key)
                .compact();
    }

    /**
     * Parse il token una sola volta, restituendo i claim.
     * @return i claim se il token è valido, altrimenti {@code null}
     */
    public Claims parseClaims(String token) {
        if (token == null) {
            return null;
        }
        try {
            return parser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @throws JwtException se il token è invalido o scaduto (semantica storica, usata dai test)
     */
    public String getUsernameFromToken(String token) {
        return parser.parseSignedClaims(token).getPayload().getSubject();
    }

    /**
     * @throws JwtException se il token è invalido o scaduto (semantica storica, usata dai test)
     */
    public String getRoleFromToken(String token) {
        return parser.parseSignedClaims(token).getPayload().get("role", String.class);
    }

    public boolean validateToken(String token) {
        return parseClaims(token) != null;
    }
}
