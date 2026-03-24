package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.AuthToken;
import com.agent.service.TokenEncryptionService;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the {@code auth_tokens} table (v1.1 migration).
 *
 * <h3>Encryption contract</h3>
 * The following columns are <em>always encrypted</em> at rest using
 * {@link TokenEncryptionService} (AES-256-GCM):
 * <ul>
 *   <li>{@code access_token}</li>
 *   <li>{@code refresh_token}</li>
 *   <li>{@code client_secret}</li>
 * </ul>
 * This class handles transparent encryption on write and decryption on read;
 * callers always work with plain-text values via the {@link AuthToken} model.
 *
 * <h3>Upsert semantics</h3>
 * {@link #upsert(AuthToken)} uses {@code INSERT … ON DUPLICATE KEY UPDATE} to
 * enforce the {@code UNIQUE KEY uq_user_provider (user_id, provider)} constraint.
 * Re-linking an existing provider simply overwrites the stored tokens.
 */
public class AuthTokenDao {

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Insert or update a token record for the given {@code (user_id, provider)}
     * pair.  Sensitive fields are encrypted before being written to the database.
     *
     * @param token the {@link AuthToken} to persist (plain-text access token etc.)
     * @throws RuntimeException on any SQL or encryption error
     */
    public static void upsert(AuthToken token) {
        String sql = """
                INSERT INTO auth_tokens
                    (user_id, provider, header_type, access_token, refresh_token,
                     expires_at, client_id, client_secret, token_endpoint, oauth_token_link)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    header_type      = VALUES(header_type),
                    access_token     = VALUES(access_token),
                    refresh_token    = VALUES(refresh_token),
                    expires_at       = VALUES(expires_at),
                    client_id        = VALUES(client_id),
                    client_secret    = VALUES(client_secret),
                    token_endpoint   = VALUES(token_endpoint),
                    oauth_token_link = VALUES(oauth_token_link)
                """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1,   token.getUserId());
            stmt.setString(2, token.getProvider());
            stmt.setString(3, token.getHeaderType() != null ? token.getHeaderType() : "Bearer");
            stmt.setString(4, TokenEncryptionService.encrypt(token.getAccessToken()));    // 🔒
            stmt.setString(5, TokenEncryptionService.encrypt(token.getRefreshToken()));   // 🔒
            stmt.setTimestamp(6, token.getExpiresAt());
            stmt.setString(7, token.getClientId());
            stmt.setString(8, TokenEncryptionService.encrypt(token.getClientSecret()));   // 🔒
            stmt.setString(9, token.getTokenEndpoint());
            stmt.setString(10, token.getOauthTokenLink());

            stmt.executeUpdate();

        } catch (SQLException e) {
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
        String sql = "SELECT * FROM auth_tokens WHERE user_id = ? AND provider = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.setString(2, provider);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapRow(rs);
            }
            return null;

        } catch (SQLException e) {
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
        String sql = "SELECT * FROM auth_tokens WHERE user_id = ?";
        List<AuthToken> tokens = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                tokens.add(mapRow(rs));
            }
            return tokens;

        } catch (SQLException e) {
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
        String sql = "DELETE FROM auth_tokens WHERE user_id = ? AND provider = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.setString(2, provider);
            stmt.executeUpdate();

        } catch (SQLException e) {
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
        String sql = "DELETE FROM auth_tokens WHERE user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, userId);
            stmt.executeUpdate();

        } catch (SQLException e) {
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
        token.setAccessToken(TokenEncryptionService.decrypt(rs.getString("access_token")));    // 🔓
        token.setRefreshToken(TokenEncryptionService.decrypt(rs.getString("refresh_token")));  // 🔓
        token.setExpiresAt(rs.getTimestamp("expires_at"));
        token.setClientId(rs.getString("client_id"));
        token.setClientSecret(TokenEncryptionService.decrypt(rs.getString("client_secret")));  // 🔓
        token.setTokenEndpoint(rs.getString("token_endpoint"));
        token.setOauthTokenLink(rs.getString("oauth_token_link"));
        token.setUpdatedAt(rs.getTimestamp("updated_at"));
        return token;
    }
}
