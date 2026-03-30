package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.model.User;
import com.agent.util.AppLogger;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Service — generates and validates JSON Web Tokens for authentication.
 * Uses HS256 algorithm with a secret key from environment configuration.
 * Tokens expire after 24 hours.
 */
public class JwtService {

    private static final Logger log = AppLogger.get(JwtService.class);
    private static final String SECRET = AppConfig.JWT_SECRET;
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    /**
     * Generate a JWT token for a given user.
     *
     * @param user the authenticated user
     * @return signed JWT token string
     */
    public static String generateToken(User user) {
        log.debug("JWT GENERATE | userId={} | username={}", user.getId(), user.getUsername());
        try {
            String token = Jwts.builder()
                    .claim("user_id", user.getId())
                    .claim("username", user.getUsername())
                    .claim("email", user.getEmail())
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                    .signWith(getSigningKey())
                    .compact();
            log.info("JWT GENERATED | userId={} | username={}", user.getId(), user.getUsername());
            return token;
        } catch (Exception e) {
            log.error("JWT GENERATE FAILED | userId={} | error={}", user.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Validate a JWT token and return its claims.
     * Throws an exception if the token is invalid or expired.
     *
     * @param token the JWT token string
     * @return the token's claims
     */
    public static Claims validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            log.debug("JWT VALIDATED | userId={}", claims.get("user_id"));
            return claims;
        } catch (Exception e) {
            log.warn("JWT VALIDATION FAILED | error={}", e.getMessage());
            throw e;
        }
    }

    private static SecretKey getSigningKey() {
        String secret = AppConfig.requireSecret("JWT_SECRET", SECRET, 32);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
