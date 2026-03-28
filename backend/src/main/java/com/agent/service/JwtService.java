package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Service — generates and validates JSON Web Tokens for authentication.
 * Uses HS256 algorithm with a secret key from environment configuration.
 * Tokens expire after 24 hours.
 */
public class JwtService {

    private static final String SECRET = AppConfig.JWT_SECRET;
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    /**
     * Generate a JWT token for a given user.
     *
     * @param user the authenticated user
     * @return signed JWT token string
     */
    public static String generateToken(User user) {
        return Jwts.builder()
                .claim("user_id", user.getId())
                .claim("username", user.getUsername())
                .claim("email", user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Validate a JWT token and return its claims.
     * Throws an exception if the token is invalid or expired.
     *
     * @param token the JWT token string
     * @return the token's claims
     */
    public static Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static SecretKey getSigningKey() {
        String secret = AppConfig.requireSecret("JWT_SECRET", SECRET, 32);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
