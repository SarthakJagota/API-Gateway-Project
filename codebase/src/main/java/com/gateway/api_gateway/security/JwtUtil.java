package com.gateway.api_gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    // IMPORTANT: this comes from the JWT_SECRET environment variable, never
    // hardcoded. Local runs get a dev-only value from the 'local' profile.
    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtUtil(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiration-ms:86400000}") long expirationMillis
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT_SECRET is not set. Set it to a random string of 32+ characters, "
                + "or run locally with the 'local' profile.");
        }
        // HS256 needs a key of at least 256 bits; fail fast if the configured
        // secret is too short rather than deploying something weak.
        if (secret.getBytes().length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be at least 32 bytes long for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMillis = expirationMillis;
    }

    public String generateToken(String subjectEmail) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMillis);
        return Jwts.builder()
            .subject(subjectEmail)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, String expectedEmail) {
        try {
            Claims claims = parseClaims(token);
            boolean notExpired = claims.getExpiration().after(new Date());
            return notExpired && claims.getSubject().equals(expectedEmail);
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
