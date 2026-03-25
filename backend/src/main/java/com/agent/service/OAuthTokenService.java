package com.agent.service;

import com.agent.dao.AuthTokenDao;
import com.agent.model.AuthToken;
import com.agent.util.JsonUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;

/**
 * Service that handles the complete OAuth2 token lifecycle:
 *   1. Build the authorization URL (redirect user to provider)
 *   2. Exchange authorization code for access + refresh tokens
 *   3. Refresh expired access tokens using the refresh token
 *   4. Return a valid (auto-refreshed) access token for API calls
 */
public class OAuthTokenService {

    /* ──────────────────────────────────────────────
     * 1) Build the authorization URL
     * ────────────────────────────────────────────── */
    public static String buildAuthorizationUrl(AuthToken token, String scope, String redirectUri) {
        if (token.getOauthTokenLink() == null || token.getOauthTokenLink().isBlank()) {
            throw new IllegalStateException("oauth_token_link is not set for provider: " + token.getProvider());
        }
        String state = token.getProvider() + "_" + token.getUserId() + "_" + System.currentTimeMillis();
        return token.getOauthTokenLink()
                + "?scope="         + encode(scope)
                + "&client_id="     + encode(token.getClientId())
                + "&state="         + encode(state)
                + "&response_type=code"
                + "&redirect_uri="  + encode(redirectUri)
                + "&prompt=consent"
                + "&access_type=offline";
    }

    /* ──────────────────────────────────────────────
     * 2) Exchange authorization code → tokens
     * ────────────────────────────────────────────── */
    public static AuthToken exchangeAuthorizationCode(long userId, String provider,
                                                       String authCode, String redirectUri) {
        // Fetch stored credentials from DB
        AuthToken stored = AuthTokenDao.findByUserAndProvider(userId, provider);
        if (stored == null) {
            throw new IllegalStateException("No OAuth credentials found for user=" + userId
                    + " provider=" + provider + ". Link the provider first.");
        }

        String tokenEndpoint = stored.getTokenEndpoint();
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            throw new IllegalStateException("token_endpoint is not configured for provider: " + provider);
        }

        // Build form payload (matches reference code pattern)
        String payload = "grant_type=authorization_code"
                + "&client_id="     + encode(stored.getClientId())
                + "&client_secret=" + encode(stored.getClientSecret())
                + "&code="          + encode(authCode)
                + "&redirect_uri="  + encode(redirectUri);

        System.out.println("[OAuthTokenService] Exchange payload: " + payload.replaceAll("client_secret=[^&]+", "client_secret=***"));

        // POST to token endpoint
        JsonObject response = doPost(tokenEndpoint, payload);

        System.out.println("[OAuthTokenService] Token response has access_token="
                + response.has("access_token") + " refresh_token=" + response.has("refresh_token"));

        if (response.has("error")) {
            String error = response.get("error").getAsString();
            String desc  = response.has("error_description")
                    ? response.get("error_description").getAsString() : "";
            throw new RuntimeException("OAuth token exchange failed: " + error + " — " + desc);
        }

        // Extract tokens
        String accessToken  = response.has("access_token")  ? response.get("access_token").getAsString()  : null;
        String refreshToken = response.has("refresh_token") ? response.get("refresh_token").getAsString() : null;

        if (accessToken == null) {
            throw new RuntimeException("No access_token in provider response: " + response);
        }

        // Calculate expiry: expires_in (seconds) → absolute timestamp with 60s safety buffer
        Timestamp expiresAt = null;
        if (response.has("expires_in")) {
            long expiresInSeconds = response.get("expires_in").getAsLong();
            long expiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000) - 60_000;
            expiresAt = new Timestamp(expiresAtMs);
        }

        // Update the stored token record
        stored.setAccessToken(accessToken);
        if (refreshToken != null) {
            stored.setRefreshToken(refreshToken);
        }
        stored.setExpiresAt(expiresAt);

        // Persist to DB (encrypted)
        AuthTokenDao.upsert(stored);

        System.out.println("[OAuthTokenService] Tokens saved for user=" + userId
                + " provider=" + provider + " expires_at=" + expiresAt);

        return stored;
    }

    /* ──────────────────────────────────────────────
     * 3) Refresh an expired access token
     * ────────────────────────────────────────────── */
    public static AuthToken refreshAccessToken(long userId, String provider) {
        AuthToken stored = AuthTokenDao.findByUserAndProvider(userId, provider);
        if (stored == null) {
            throw new IllegalStateException("No OAuth credentials found for user=" + userId
                    + " provider=" + provider);
        }
        if (stored.getRefreshToken() == null || stored.getRefreshToken().isBlank()) {
            throw new IllegalStateException("No refresh_token available for provider: " + provider
                    + ". Re-authorize with access_type=offline.");
        }

        String payload = "grant_type=refresh_token"
                + "&client_id="     + encode(stored.getClientId())
                + "&client_secret=" + encode(stored.getClientSecret())
                + "&refresh_token=" + encode(stored.getRefreshToken());

        System.out.println("[OAuthTokenService] Refresh payload for provider=" + provider);

        JsonObject response = doPost(stored.getTokenEndpoint(), payload);

        if (response.has("error")) {
            String error = response.get("error").getAsString();
            String desc  = response.has("error_description")
                    ? response.get("error_description").getAsString() : "";
            throw new RuntimeException("OAuth refresh failed: " + error + " — " + desc);
        }

        String newAccessToken = response.has("access_token")
                ? response.get("access_token").getAsString() : null;
        if (newAccessToken == null) {
            throw new RuntimeException("No access_token in refresh response: " + response);
        }

        stored.setAccessToken(newAccessToken);

        // Some providers rotate refresh tokens
        if (response.has("refresh_token")) {
            stored.setRefreshToken(response.get("refresh_token").getAsString());
        }

        if (response.has("expires_in")) {
            long expiresInSeconds = response.get("expires_in").getAsLong();
            long expiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000) - 60_000;
            stored.setExpiresAt(new Timestamp(expiresAtMs));
        }

        AuthTokenDao.upsert(stored);

        System.out.println("[OAuthTokenService] Refreshed tokens for user=" + userId
                + " provider=" + provider + " new_expires_at=" + stored.getExpiresAt());

        return stored;
    }

    /* ──────────────────────────────────────────────
     * 4) Get a valid access token (auto-refresh)
     * ────────────────────────────────────────────── */
    public static AuthToken getValidAccessToken(long userId, String provider) {
        AuthToken stored = AuthTokenDao.findByUserAndProvider(userId, provider);
        if (stored == null) {
            throw new IllegalStateException("No OAuth credentials for user=" + userId
                    + " provider=" + provider);
        }

        // Auto-refresh if expired
        if (stored.isExpired() && stored.getRefreshToken() != null && !stored.getRefreshToken().isBlank()) {
            System.out.println("[OAuthTokenService] Token expired for provider=" + provider + ", auto-refreshing...");
            return refreshAccessToken(userId, provider);
        }

        return stored;
    }

    /* ──────────────────────────────────────────────
     * HTTP helper: POST form-encoded data
     * ────────────────────────────────────────────── */
    private static JsonObject doPost(String urlStr, String formPayload) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(formPayload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            System.out.println("[OAuthTokenService] HTTP " + status + " from " + urlStr);
            return JsonUtil.parse(body);

        } catch (IOException e) {
            throw new RuntimeException("HTTP POST to " + urlStr + " failed: " + e.getMessage(), e);
        }
    }

    /* URL-encode helper */
    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
