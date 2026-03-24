# 📦 Backend Database Changes — Summary

> **Project:** AI Task Agent  
> **Database:** MySQL 8 — `ai_task_agent`  
> **Last Updated:** July 2025  
> **Scope:** New columns in `chat_sessions`, new columns in `users`, new `auth_tokens` table

---

## 📋 Table of Contents

1. [Overview](#1-overview)
2. [Change 1 — Chat Session Summary](#2-change-1--chat-session-summary)
3. [Change 2 — User Preferences](#3-change-2--user-preferences)
4. [Change 3 — Auth Tokens Table (OAuth + Encryption)](#4-change-3--auth-tokens-table-oauth--encryption)
5. [Entity-Relationship Updates](#5-entity-relationship-updates)
6. [Migration SQL](#6-migration-sql)
7. [Security Considerations](#7-security-considerations)
8. [DAO Impact & Code Changes](#8-dao-impact--code-changes)

---

## 1. Overview

Three database-level changes are being introduced to support:

| # | Change | Purpose |
|---|--------|---------|
| 1 | **`summary` column** in `chat_sessions` | Store an AI-generated or auto-extracted summary of each chat conversation |
| 2 | **`preferences` column** in `users` | Store per-user settings/preferences as a JSON object (theme, language, notifications, etc.) |
| 3 | **`auth_tokens` table** (new) | Store encrypted OAuth tokens (access/refresh) for third-party provider integrations with automatic expiry management |

---

## 2. Change 1 — Chat Session Summary

### What Changed

A new `summary` column is added to the `chat_sessions` table to persist a brief summary of the conversation. This enables:

- **Session list previews** — Show a short description under each session title in the sidebar
- **Search & discovery** — Users can search across session summaries
- **Context loading** — When reopening a session, quickly remind the user what was discussed
- **AI context window optimization** — Send the summary instead of full history for long conversations

### Schema Change

```sql
ALTER TABLE chat_sessions
    ADD COLUMN summary TEXT DEFAULT NULL AFTER title;
```

### Updated `chat_sessions` Table Structure

| Field | Type | Null | Key | Default | Extra |
|-------|------|------|-----|---------|-------|
| `id` | BIGINT | NO | PRI | — | AUTO_INCREMENT |
| `user_id` | BIGINT | NO | MUL/FK | — | FK → `users(id)` |
| `title` | VARCHAR(255) | YES | — | `'New conversation'` | — |
| **`summary`** | **TEXT** | **YES** | — | **NULL** | **🆕 NEW** |
| `created_at` | TIMESTAMP | YES | — | `CURRENT_TIMESTAMP` | — |
| `updated_at` | TIMESTAMP | YES | — | `CURRENT_TIMESTAMP` | ON UPDATE CURRENT_TIMESTAMP |

### When is `summary` populated?

| Trigger | Logic |
|---------|-------|
| **After first AI response** | Auto-generate summary from first user message (first ~100 chars) |
| **After every 10 messages** | Re-generate summary using AI or extract key topics |
| **On session close/switch** | Backend requests MCP to generate a one-line summary of the conversation |
| **Manual override** | (Future) User can edit the summary |

### Example Data

```json
{
  "session_id": 1,
  "title": "Build a todo app",
  "summary": "User requested a Python Flask todo app with SQLite backend. AI created the project with CRUD endpoints, HTML templates, and a requirements.txt file.",
  "created_at": "2025-01-18T10:30:00Z",
  "updated_at": "2025-01-18T14:20:00Z"
}
```

---

## 3. Change 2 — User Preferences

### What Changed

A new `preferences` column is added to the `users` table to store per-user settings as a flexible JSON object. This avoids creating a separate settings table and keeps user data co-located.

### Schema Change

```sql
ALTER TABLE users
    ADD COLUMN preferences JSON DEFAULT NULL AFTER password_hash;
```

### Updated `users` Table Structure

| Field | Type | Null | Key | Default | Extra |
|-------|------|------|-----|---------|-------|
| `id` | BIGINT | NO | PRI | — | AUTO_INCREMENT |
| `username` | VARCHAR(50) | NO | UNI | — | — |
| `email` | VARCHAR(100) | NO | UNI | — | — |
| `password_hash` | VARCHAR(255) | NO | — | — | — |
| **`preferences`** | **JSON** | **YES** | — | **NULL** | **🆕 NEW** |
| `created_at` | TIMESTAMP | YES | — | `CURRENT_TIMESTAMP` | — |

### Preferences JSON Schema

The `preferences` column stores a JSON object. All keys are optional — the application applies defaults when a key is missing.

```json
{
  "theme": "dark",
  "language": "en",
  "notifications": {
    "email": true,
    "task_complete": true,
    "task_failed": true
  },
  "editor": {
    "font_size": 14,
    "word_wrap": true
  },
  "chat": {
    "stream_speed": "normal",
    "show_tool_details": true,
    "auto_scroll": true
  },
  "default_model": "claude-sonnet-4-20250514",
  "timezone": "Asia/Kolkata"
}
```

### Application-Level Defaults

If `preferences` is `NULL` or a specific key is missing, the backend returns these defaults:

```java
public static final JsonObject DEFAULT_PREFERENCES = JsonUtil.parse("""
{
    "theme": "light",
    "language": "en",
    "notifications": { "email": false, "task_complete": true, "task_failed": true },
    "editor": { "font_size": 14, "word_wrap": true },
    "chat": { "stream_speed": "normal", "show_tool_details": false, "auto_scroll": true },
    "default_model": "claude-sonnet-4-20250514",
    "timezone": "UTC"
}
""");
```

### API Impact

#### GET /api/auth/me — Updated Response

```json
{
  "success": true,
  "data": {
    "user_id": 42,
    "username": "john_doe",
    "email": "john@example.com",
    "preferences": {
      "theme": "dark",
      "language": "en",
      "timezone": "Asia/Kolkata"
    }
  }
}
```

#### PUT /api/auth/preferences — New Endpoint

```json
// Request
{
  "theme": "dark",
  "chat": {
    "auto_scroll": false
  }
}

// Response 200
{
  "success": true,
  "data": {
    "message": "Preferences updated",
    "preferences": { /* merged full preferences object */ }
  }
}
```

> **Note:** Preferences are **merged**, not replaced. Sending `{ "theme": "dark" }` only updates the theme — it does not wipe out other preferences.

---

## 4. Change 3 — Auth Tokens Table (OAuth + Encryption)

### What Changed

A brand-new `auth_tokens` table is introduced to securely store OAuth access tokens, refresh tokens, and related credentials for third-party service integrations (e.g., Google, GitHub, Slack, external APIs).

### Why This Table Exists

The AI Task Agent may need to:
- **Access third-party APIs** on behalf of the user (Google Sheets, GitHub, Slack, etc.)
- **Store OAuth2 tokens** obtained via OAuth flows
- **Auto-refresh expired tokens** using stored `refresh_token` + `client_id` + `client_secret`
- **Support multiple providers per user** (one user can link Google + GitHub + Slack)

### Schema — Full Table Definition

```sql
CREATE TABLE IF NOT EXISTS auth_tokens (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id          BIGINT NOT NULL,
    provider         VARCHAR(100) NOT NULL,
    header_type      VARCHAR(50) DEFAULT 'Bearer',
    access_token     TEXT NOT NULL,
    refresh_token    TEXT DEFAULT NULL,
    expires_at       TIMESTAMP DEFAULT (NOW() + INTERVAL 1 HOUR),
    client_id        VARCHAR(255) DEFAULT NULL,
    client_secret    TEXT DEFAULT NULL,
    token_endpoint   VARCHAR(500) DEFAULT NULL,
    oauth_token_link VARCHAR(1000) DEFAULT NULL,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_user_provider (user_id, provider)
);

CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens(expires_at);
```

### Column-by-Column Explanation

| Column | Type | Required | Description |
|--------|------|----------|-------------|
| `id` | BIGINT AUTO_INCREMENT | ✅ | Primary key — internal row identifier |
| `user_id` | BIGINT (FK → `users.id`) | ✅ | The user who owns these tokens. Cascades on delete — if the user is removed, all their tokens are purged |
| `provider` | VARCHAR(100) | ✅ | Identifies the third-party service. Examples: `"google"`, `"github"`, `"slack"`, `"custom_api"`. Combined with `user_id` forms a unique constraint (one token set per provider per user) |
| `header_type` | VARCHAR(50) | ❌ | The authorization scheme used in HTTP headers when making API calls. Defaults to `"Bearer"`. Other values: `"Basic"`, `"Token"`, `"ApiKey"` |
| `access_token` | TEXT | ✅ | **🔒 ENCRYPTED** — The OAuth2 access token used to authenticate API requests to the provider. Stored encrypted at rest using AES-256-GCM (see [Security Considerations](#7-security-considerations)) |
| `refresh_token` | TEXT | ❌ | **🔒 ENCRYPTED** — The OAuth2 refresh token used to obtain a new `access_token` when the current one expires. Not all providers issue refresh tokens (e.g., some API key-based services) |
| `expires_at` | TIMESTAMP | ❌ | When the `access_token` expires. Defaults to **1 hour from creation**. The backend checks this before every API call — if expired, it auto-refreshes using the `refresh_token` |
| `client_id` | VARCHAR(255) | ❌ | The OAuth2 client ID for the registered application. Needed for token refresh requests |
| `client_secret` | TEXT | ❌ | **🔒 ENCRYPTED** — The OAuth2 client secret. Needed alongside `client_id` for token refresh. Encrypted at rest |
| `token_endpoint` | VARCHAR(500) | ❌ | The provider's token refresh URL. Example: `"https://oauth2.googleapis.com/token"`. Used by the backend to POST refresh requests |
| `oauth_token_link` | VARCHAR(1000) | ❌ | The full OAuth authorization URL to initiate/re-initiate the OAuth flow. Example: `"https://accounts.google.com/o/oauth2/v2/auth?client_id=...&scope=..."`. Stored for convenience when re-authentication is needed |
| `updated_at` | TIMESTAMP | ❌ | Auto-updated on every row modification. Tracks when tokens were last refreshed |

### Unique Constraint

```
UNIQUE KEY uq_user_provider (user_id, provider)
```

This ensures **one token record per user per provider**. If User 42 links Google, there's exactly one row with `(user_id=42, provider='google')`. Re-linking overwrites via `INSERT ... ON DUPLICATE KEY UPDATE`.

### Example Data

| id | user_id | provider | header_type | access_token | refresh_token | expires_at | client_id | token_endpoint |
|----|---------|----------|-------------|--------------|---------------|------------|-----------|----------------|
| 1 | 42 | google | Bearer | `ENC[AES256:ya29.a0AfH6...]` | `ENC[AES256:1//0dx...]` | 2025-07-18 11:30:00 | 123456.apps.googleusercontent.com | https://oauth2.googleapis.com/token |
| 2 | 42 | github | Token | `ENC[AES256:ghp_abc...]` | NULL | 2099-12-31 23:59:59 | NULL | NULL |
| 3 | 15 | slack | Bearer | `ENC[AES256:xoxb-...]` | `ENC[AES256:xoxr-...]` | 2025-07-18 12:00:00 | 12345.6789 | https://slack.com/api/oauth.v2.access |

### Token Lifecycle

```
┌─────────────┐     OAuth Flow      ┌──────────────┐      Encrypted       ┌──────────────┐
│  User clicks │ ──────────────────► │  Provider     │ ────────────────────► │  auth_tokens │
│  "Link Google"│     Authorization   │  (Google etc) │   access_token +     │  table       │
└─────────────┘     Code Exchange    └──────────────┘   refresh_token       └──────┬───────┘
                                                                                    │
                                                                                    ▼
┌─────────────┐    Auto-refresh if   ┌──────────────┐     Decrypt & Use    ┌──────────────┐
│  API Call to │ ◄────── expired ──── │  Backend      │ ◄─────────────────── │  auth_tokens │
│  Provider    │                      │  TokenService │     read token       │  table       │
└─────────────┘                      └──────────────┘                      └──────────────┘
```

**Step-by-step:**

1. **User initiates OAuth** → Frontend redirects to `oauth_token_link`
2. **Provider returns auth code** → Backend exchanges code for tokens at `token_endpoint`
3. **Backend encrypts & stores** → `access_token`, `refresh_token`, `client_secret` encrypted with AES-256-GCM
4. **On API call** → Backend decrypts `access_token`, checks `expires_at`
5. **If expired** → Backend decrypts `refresh_token` + `client_secret`, POSTs to `token_endpoint`, gets new `access_token`, re-encrypts & updates row
6. **If refresh fails** → Mark token as invalid, notify user to re-authenticate

---

## 5. Entity-Relationship Updates

```
┌──────────────────┐
│      users       │
│──────────────────│
│ id (PK)          │───┐
│ username         │   │
│ email            │   │
│ password_hash    │   │
│ preferences 🆕   │   │
│ created_at       │   │
└──────────────────┘   │
        │              │
        │ 1:N          │ 1:N
        ▼              ▼
┌──────────────────┐  ┌──────────────────┐
│  chat_sessions   │  │   auth_tokens 🆕 │
│──────────────────│  │──────────────────│
│ id (PK)          │  │ id (PK)          │
│ user_id (FK)     │  │ user_id (FK)     │
│ title            │  │ provider         │
│ summary 🆕       │  │ header_type      │
│ created_at       │  │ access_token 🔒  │
│ updated_at       │  │ refresh_token 🔒 │
└──────────────────┘  │ expires_at       │
        │              │ client_id        │
        │ 1:N          │ client_secret 🔒 │
        ▼              │ token_endpoint   │
┌──────────────────┐  │ oauth_token_link │
│  chat_messages   │  │ updated_at       │
│──────────────────│  └──────────────────┘
│ id (PK)          │
│ session_id (FK)  │     🆕 = New column/table
│ role             │     🔒 = Encrypted at rest
│ content          │
│ tool_calls       │
│ created_at       │
└──────────────────┘
```

---

## 6. Migration SQL

Run this migration **after** the base `schema.sql` has been applied.

```sql
-- ============================================
-- MIGRATION: v1.1 — Summary, Preferences, Auth Tokens
-- Date: July 2025
-- ============================================

-- ── Change 1: Add summary to chat_sessions ──
ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS summary TEXT DEFAULT NULL AFTER title;

-- ── Change 2: Add preferences to users ──
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS preferences JSON DEFAULT NULL AFTER password_hash;

-- ── Change 3: Create auth_tokens table ──
CREATE TABLE IF NOT EXISTS auth_tokens (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id          BIGINT NOT NULL,
    provider         VARCHAR(100) NOT NULL,
    header_type      VARCHAR(50) DEFAULT 'Bearer',
    access_token     TEXT NOT NULL,
    refresh_token    TEXT DEFAULT NULL,
    expires_at       TIMESTAMP DEFAULT (NOW() + INTERVAL 1 HOUR),
    client_id        VARCHAR(255) DEFAULT NULL,
    client_secret    TEXT DEFAULT NULL,
    token_endpoint   VARCHAR(500) DEFAULT NULL,
    oauth_token_link VARCHAR(1000) DEFAULT NULL,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_user_provider (user_id, provider)
);

-- ── Indexes for auth_tokens ──
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);
CREATE INDEX idx_auth_tokens_expires ON auth_tokens(expires_at);

-- ── Verify ──
-- Run these to confirm:
-- DESCRIBE chat_sessions;
-- DESCRIBE users;
-- DESCRIBE auth_tokens;
```

### Rollback SQL (if needed)

```sql
-- ⚠️  DESTRUCTIVE — Only use if migration must be reverted

ALTER TABLE chat_sessions DROP COLUMN summary;
ALTER TABLE users DROP COLUMN preferences;
DROP TABLE IF EXISTS auth_tokens;
```

---

## 7. Security Considerations

### 🔐 Encryption at Rest (AES-256-GCM)

The following columns store **encrypted** values — they are **never** stored as plain text:

| Table | Column | Why Encrypted |
|-------|--------|--------------|
| `auth_tokens` | `access_token` | Grants API access to user's third-party accounts |
| `auth_tokens` | `refresh_token` | Can generate new access tokens — high-value target |
| `auth_tokens` | `client_secret` | OAuth app credential — compromising it affects all users |

### Encryption Implementation

```java
/**
 * TokenEncryptionService.java
 * 
 * Uses AES-256-GCM for authenticated encryption.
 * The encryption key is derived from the ENCRYPTION_SECRET environment variable.
 * Each encrypted value includes a unique IV (Initialization Vector) for security.
 * 
 * Storage format: Base64(IV + CipherText + AuthTag)
 */
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;    // 96 bits
    private static final int GCM_TAG_LENGTH = 128;   // 128 bits
    
    // Key derived from environment variable — NEVER hardcoded
    private static final SecretKey SECRET_KEY = deriveKey(
        AppConfig.get("ENCRYPTION_SECRET", "CHANGE-ME-IN-PRODUCTION")
    );

    public static String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, spec);
            
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    public static String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);
            
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, spec);
            
            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
    
    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}
```

### Environment Variable Required

```bash
# Add to .env or system environment
ENCRYPTION_SECRET=your-32-byte-encryption-key-here-change-in-prod
```

### Security Checklist

- [x] **Tokens encrypted at rest** — AES-256-GCM with unique IV per value
- [x] **Cascade delete** — Deleting a user purges all their tokens
- [x] **Unique constraint** — Prevents duplicate provider entries per user
- [x] **Expiry tracking** — `expires_at` enables automatic refresh before API calls
- [x] **No plain-text secrets in code** — All secrets from environment variables
- [ ] **Key rotation** (future) — Plan for rotating `ENCRYPTION_SECRET` without data loss
- [ ] **Audit logging** (future) — Log token access/refresh events

---

## 8. DAO Impact & Code Changes

### New/Modified Files

| File | Change | Description |
|------|--------|-------------|
| `schema.sql` | **Modified** | Add migration SQL for all three changes |
| `User.java` | **Modified** | Add `preferences` (JsonObject) field + getter/setter |
| `UserDao.java` | **Modified** | Update `create()`, `findById()`, `mapRow()` to handle `preferences` column; add `updatePreferences()` |
| `ChatSession.java` | **Modified** | Add `summary` (String) field + getter/setter |
| `SessionDao.java` | **Modified** | Update `create()`, `findById()`, `findByUserId()`, `mapRow()` to handle `summary`; add `updateSummary()` |
| `AuthToken.java` | **🆕 New** | Model class for the `auth_tokens` table |
| `AuthTokenDao.java` | **🆕 New** | Full CRUD DAO with encryption/decryption integrated |
| `TokenEncryptionService.java` | **🆕 New** | AES-256-GCM encrypt/decrypt utility |
| `PreferencesServlet.java` | **🆕 New** | `PUT /api/auth/preferences` endpoint |
| `OAuthServlet.java` | **🆕 New** | OAuth callback handler (future) |

### AuthToken Model

```java
// model/AuthToken.java
public class AuthToken {
    private long id;
    private long userId;
    private String provider;
    private String headerType;     // "Bearer", "Token", "Basic", etc.
    private String accessToken;    // Decrypted in memory, encrypted in DB
    private String refreshToken;   // Decrypted in memory, encrypted in DB
    private Timestamp expiresAt;
    private String clientId;
    private String clientSecret;   // Decrypted in memory, encrypted in DB
    private String tokenEndpoint;
    private String oauthTokenLink;
    private Timestamp updatedAt;
    
    // ... getters and setters ...
    
    public boolean isExpired() {
        return expiresAt != null && expiresAt.before(new Timestamp(System.currentTimeMillis()));
    }
    
    public String buildAuthHeader() {
        return headerType + " " + accessToken;
    }
}
```

### AuthTokenDao (with encryption)

```java
// dao/AuthTokenDao.java
public class AuthTokenDao {

    public static void upsert(AuthToken token) {
        String sql = """
            INSERT INTO auth_tokens 
                (user_id, provider, header_type, access_token, refresh_token, 
                 expires_at, client_id, client_secret, token_endpoint, oauth_token_link)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                header_type = VALUES(header_type),
                access_token = VALUES(access_token),
                refresh_token = VALUES(refresh_token),
                expires_at = VALUES(expires_at),
                client_id = VALUES(client_id),
                client_secret = VALUES(client_secret),
                token_endpoint = VALUES(token_endpoint),
                oauth_token_link = VALUES(oauth_token_link)
            """;
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, token.getUserId());
            stmt.setString(2, token.getProvider());
            stmt.setString(3, token.getHeaderType());
            stmt.setString(4, TokenEncryptionService.encrypt(token.getAccessToken()));   // 🔒
            stmt.setString(5, TokenEncryptionService.encrypt(token.getRefreshToken()));  // 🔒
            stmt.setTimestamp(6, token.getExpiresAt());
            stmt.setString(7, token.getClientId());
            stmt.setString(8, TokenEncryptionService.encrypt(token.getClientSecret()));  // 🔒
            stmt.setString(9, token.getTokenEndpoint());
            stmt.setString(10, token.getOauthTokenLink());
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error saving auth token", e);
        }
    }

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
            throw new RuntimeException("DB error finding auth token", e);
        }
    }

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
            throw new RuntimeException("DB error listing auth tokens", e);
        }
    }

    public static void delete(long userId, String provider) {
        String sql = "DELETE FROM auth_tokens WHERE user_id = ? AND provider = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, userId);
            stmt.setString(2, provider);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error deleting auth token", e);
        }
    }

    private static AuthToken mapRow(ResultSet rs) throws SQLException {
        AuthToken token = new AuthToken();
        token.setId(rs.getLong("id"));
        token.setUserId(rs.getLong("user_id"));
        token.setProvider(rs.getString("provider"));
        token.setHeaderType(rs.getString("header_type"));
        token.setAccessToken(TokenEncryptionService.decrypt(rs.getString("access_token")));     // 🔓
        token.setRefreshToken(TokenEncryptionService.decrypt(rs.getString("refresh_token")));   // 🔓
        token.setExpiresAt(rs.getTimestamp("expires_at"));
        token.setClientId(rs.getString("client_id"));
        token.setClientSecret(TokenEncryptionService.decrypt(rs.getString("client_secret")));   // 🔓
        token.setTokenEndpoint(rs.getString("token_endpoint"));
        token.setOauthTokenLink(rs.getString("oauth_token_link"));
        token.setUpdatedAt(rs.getTimestamp("updated_at"));
        return token;
    }
}
```

### Updated Folder Structure (additions only)

```
com/agent/
├── model/
│   └── AuthToken.java                    🆕
├── dao/
│   └── AuthTokenDao.java                 🆕
├── service/
│   └── TokenEncryptionService.java       🆕
└── servlet/
    └── auth/
        └── PreferencesServlet.java       🆕
```

---

## Quick Reference Card

| Change | Table | Column/Table | Type | Encrypted | Migration |
|--------|-------|-------------|------|-----------|-----------|
| Summary | `chat_sessions` | `summary` | TEXT | No | ALTER TABLE ADD COLUMN |
| Preferences | `users` | `preferences` | JSON | No | ALTER TABLE ADD COLUMN |
| Auth Tokens | `auth_tokens` | *entire table* | — | `access_token`, `refresh_token`, `client_secret` | CREATE TABLE |

---

> **⚠️ Before deploying:** Ensure `ENCRYPTION_SECRET` environment variable is set. Using the default value in production is a **critical security vulnerability**.
