package com.agent.model;

import java.sql.Timestamp;

/**
 * AuthToken model — maps to the {@code auth_tokens} table (v1.1 migration).
 *
 * <p>Stores OAuth2 access/refresh tokens and related credentials for
 * third-party provider integrations (Google, GitHub, Slack, custom APIs, etc.).
 *
 * <p><strong>Encryption contract:</strong> The fields {@code accessToken},
 * {@code refreshToken}, and {@code clientSecret} are <em>always stored
 * encrypted</em> in the database.  {@link com.agent.dao.AuthTokenDao} handles
 * transparent encryption on write and decryption on read — this model class
 * always holds the <em>plain-text</em> (decrypted) values in memory.
 *
 * <h3>Unique constraint</h3>
 * One row per {@code (user_id, provider)} pair.  Upsert via
 * {@code INSERT … ON DUPLICATE KEY UPDATE} keeps the table tidy.
 */
public class AuthToken {

    private long      id;
    private long      userId;
    /** Provider identifier, e.g. {@code "google"}, {@code "github"}, {@code "slack"}. */
    private String    provider;
    /**
     * HTTP authorization scheme used when calling the provider's API.
     * Defaults to {@code "Bearer"}.  Other valid values: {@code "Token"},
     * {@code "Basic"}, {@code "ApiKey"}.
     */
    private String    headerType;
    /** Plain-text OAuth2 access token (decrypted in memory, encrypted at rest). */
    private String    accessToken;
    /** Plain-text OAuth2 refresh token (decrypted in memory, encrypted at rest). May be {@code null}. */
    private String    refreshToken;
    /** When the access token expires.  Defaults to 1 hour from creation. */
    private Timestamp expiresAt;
    /** OAuth2 client ID of the registered application. */
    private String    clientId;
    /** Plain-text OAuth2 client secret (decrypted in memory, encrypted at rest). May be {@code null}. */
    private String    clientSecret;
    /** Provider's token-refresh endpoint, e.g. {@code "https://oauth2.googleapis.com/token"}. */
    private String    tokenEndpoint;
    /** Full OAuth authorization URL used to initiate / re-initiate the OAuth flow. */
    private String    oauthTokenLink;
    /**
     * OAuth2 scopes requested for this provider, e.g.
     * {@code "ZohoCRM.modules.ALL,ZohoCRM.settings.READ"}.
     * Populated at init time via the MCP server scope-resolution call.
     */
    private String    scope;
    /** Auto-updated timestamp tracking last token refresh. */
    private Timestamp updatedAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    public AuthToken() {
    }

    /**
     * Convenience constructor for the most common upsert scenario.
     */
    public AuthToken(long userId, String provider, String headerType,
                     String accessToken, String refreshToken, Timestamp expiresAt,
                     String clientId, String clientSecret,
                     String tokenEndpoint, String oauthTokenLink) {
        this.userId         = userId;
        this.provider       = provider;
        this.headerType     = headerType != null ? headerType : "Bearer";
        this.accessToken    = accessToken;
        this.refreshToken   = refreshToken;
        this.expiresAt      = expiresAt;
        this.clientId       = clientId;
        this.clientSecret   = clientSecret;
        this.tokenEndpoint  = tokenEndpoint;
        this.oauthTokenLink = oauthTokenLink;
    }

    /**
     * Full constructor including scope — used by the Zoho OAuth init flow.
     */
    public AuthToken(long userId, String provider, String headerType,
                     String accessToken, String refreshToken, Timestamp expiresAt,
                     String clientId, String clientSecret,
                     String tokenEndpoint, String oauthTokenLink, String scope) {
        this(userId, provider, headerType, accessToken, refreshToken, expiresAt,
             clientId, clientSecret, tokenEndpoint, oauthTokenLink);
        this.scope = scope;
    }

    // ── Business helpers ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the access token has expired (i.e.
     * {@code expiresAt} is non-null and is before the current time).
     */
    public boolean isExpired() {
        return expiresAt != null
                && expiresAt.before(new Timestamp(System.currentTimeMillis()));
    }

    /**
     * Builds the {@code Authorization} header value ready to be sent in an
     * HTTP request to the provider's API.
     *
     * <p>Example output: {@code "Bearer ya29.a0AfH6..."}
     *
     * @return {@code "<headerType> <accessToken>"}
     */
    public String buildAuthHeader() {
        String scheme = (headerType != null && !headerType.isBlank()) ? headerType : "Bearer";
        return scheme + " " + accessToken;
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getHeaderType() {
        return headerType;
    }

    public void setHeaderType(String headerType) {
        this.headerType = headerType;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getOauthTokenLink() {
        return oauthTokenLink;
    }

    public void setOauthTokenLink(String oauthTokenLink) {
        this.oauthTokenLink = oauthTokenLink;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
