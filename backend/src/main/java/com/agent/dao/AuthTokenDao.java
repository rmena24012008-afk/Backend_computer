package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.AuthToken;
import com.agent.service.TokenEncryptionService;
import com.agent.util.AppLogger;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class AuthTokenDao {

    private static final Logger log = AppLogger.get(AuthTokenDao.class);

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Insert or update a token record for the given {@code (user_id, provider)}
     * pair.  Sensitive fields are encrypted before being written to the database.
     *
     * @param token the {@link AuthToken} to persist (plain-text access token etc.)
     * @throws RuntimeException on any SQL or encryption error
     */
    public static void upsert(AuthToken token) {
        log.debug("AUTH_TOKEN UPSERT | userId={} | provider={}", token.getUserId(), token.getProvider());
        String sql = """
                INSERT INTO auth_tokens
                    (user_id, provider, header_type, access_token, refresh_token,
                     expires_at, client_id, client_secret, token_endpoint, oauth_token_link,
                     scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    header_type      = VALUES(header_type),
                    access_token     = VALUES(access_token),
                    refresh_token    = VALUES(refresh_token),
                    expires_at       = VALUES(expires_at),
                    client_id        = VALUES(client_id),
                    client_secret    = VALUES(client_secret),
                    token_endpoint   = VALUES(token_endpoint),
                    oauth_token_link = VALUES(oauth_token_link),
                    scope            = VALUES(scope)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1,   token.getUserId());
            stmt.setString(2, token.getProvider());
            stmt.setString(3, token.getHeaderType() != null ? token.getHeaderType() : "Bearer");
            stmt.setString(4, TokenEncryptionService.encrypt(token.getAccessToken()));
            stmt.setString(5, TokenEncryptionService.encrypt(token.getRefreshToken()));
            stmt.setTimestamp(6, token.getExpiresAt());
            stmt.setString(7, token.getClientId());
            stmt.setString(8, TokenEncryptionService.encrypt(token.getClientSecret()));
            stmt.setString(9, token.getTokenEndpoint());
            stmt.setString(10, token.getOauthTokenLink());
            stmt.setString(11, token.getScope());

            stmt.executeUpdate();
            log.info("AUTH_TOKEN UPSERT OK | userId={} | provider={}", token.getUserId(), token.getProvider());

        } catch (SQLException e) {
            log.error("AUTH_TOKEN UPSERT FAILED | userId={} | provider={} | error={}",
                    token.getUserId(), token.getProvider(), e.getMessage(), e);
            throw new RuntimeException("DB error upserting auth token for provider '"
                    + token.getProvider() + "'", e);
        }
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /**
     * Find a single token record by {@code (user_id, provider)}.
     *
     * @param userId   the user's primary key
     * @param provider the provider identifier (e.g. {@code "google"})
     * @return the decrypted {@link AuthToken}, or {@code null} if not found
     */
    public static AuthToken findByUserAndProvider(long userId, String provider) {
        log.debug("AUTH_TOKEN FIND | userId={} | provider={}", userId, provider);
        String sql = "SELECT * FROM auth_tokens WHERE user_id = ? AND provider = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.setString(2, provider);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                log.debug("AUTH_TOKEN FOUND | userId={} | provider={}", userId, provider);
                return mapRow(rs);
            }
            log.debug("AUTH_TOKEN NOT FOUND | userId={} | provider={}", userId, provider);
            return null;

        } catch (SQLException e) {
            log.error("AUTH_TOKEN FIND FAILED | userId={} | provider={} | error={}",
                    userId, provider, e.getMessage(), e);
            throw new RuntimeException("DB error finding auth token for user=" + userId
                    + " provider=" + provider, e);
        }
    }

    /**
     * Find all token records for a given user (all linked providers).
     *
     * @param userId the user's primary key
     * @return list of decrypted {@link AuthToken} objects (may be empty)
     */
    public static List<AuthToken> findByUser(long userId) {
        log.debug("AUTH_TOKEN LIST | userId={}", userId);
        String sql = "SELECT * FROM auth_tokens WHERE user_id = ?";
        List<AuthToken> tokens = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                tokens.add(mapRow(rs));
            }
            log.debug("AUTH_TOKEN LIST OK | userId={} | count={}", userId, tokens.size());
            return tokens;

        } catch (SQLException e) {
            log.error("AUTH_TOKEN LIST FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error listing auth tokens for user=" + userId, e);
        }
    }

    // ── Delete operations ─────────────────────────────────────────────────────

    /**
     * Delete the token record for the given {@code (user_id, provider)} pair.
     * No-op if the record does not exist.
     *
     * @param userId   the user's primary key
     * @param provider the provider identifier
     */
    public static void delete(long userId, String provider) {
        log.info("AUTH_TOKEN DELETE | userId={} | provider={}", userId, provider);
        String sql = "DELETE FROM auth_tokens WHERE user_id = ? AND provider = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.setString(2, provider);
            stmt.executeUpdate();
            log.info("AUTH_TOKEN DELETE OK | userId={} | provider={}", userId, provider);

        } catch (SQLException e) {
            log.error("AUTH_TOKEN DELETE FAILED | userId={} | provider={} | error={}",
                    userId, provider, e.getMessage(), e);
            throw new RuntimeException("DB error deleting auth token for user=" + userId
                    + " provider=" + provider, e);
        }
    }

    /**
     * Delete ALL token records for a user.  Called when the user account is
     * deleted at the application layer (the DB cascade handles this too, but
     * explicit deletion is useful when only de-linking all providers at once).
     *
     * @param userId the user's primary key
     */
    public static void deleteAllForUser(long userId) {
        log.info("AUTH_TOKEN DELETE_ALL | userId={}", userId);
        String sql = "DELETE FROM auth_tokens WHERE user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.executeUpdate();
            log.info("AUTH_TOKEN DELETE_ALL OK | userId={}", userId);

        } catch (SQLException e) {
            log.error("AUTH_TOKEN DELETE_ALL FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error deleting all auth tokens for user=" + userId, e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Maps a {@link ResultSet} row to an {@link AuthToken}, decrypting the
     * three sensitive fields on the way out.
     *
     * @param rs an open ResultSet positioned at the current row
     * @return a fully populated, decrypted {@link AuthToken}
     */
    private static AuthToken mapRow(ResultSet rs) throws SQLException {
        AuthToken token = new AuthToken();
        token.setId(rs.getLong("id"));
        token.setUserId(rs.getLong("user_id"));
        token.setProvider(rs.getString("provider"));
        token.setHeaderType(rs.getString("header_type"));
        token.setAccessToken(TokenEncryptionService.decrypt(rs.getString("access_token")));
        token.setRefreshToken(TokenEncryptionService.decrypt(rs.getString("refresh_token")));
        token.setExpiresAt(rs.getTimestamp("expires_at"));
        token.setClientId(rs.getString("client_id"));
        token.setClientSecret(TokenEncryptionService.decrypt(rs.getString("client_secret")));
        token.setTokenEndpoint(rs.getString("token_endpoint"));
        token.setOauthTokenLink(rs.getString("oauth_token_link"));
        token.setScope(rs.getString("scope"));
        token.setUpdatedAt(rs.getTimestamp("updated_at"));
        return token;
    }
}
