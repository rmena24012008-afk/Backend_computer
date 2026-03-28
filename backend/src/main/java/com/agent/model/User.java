package com.agent.model;

import com.google.gson.JsonObject;

import java.sql.Timestamp;

/**
 * User model — maps to the `users` table.
 *
 * Includes the {@code preferences} JSON column (v1.1 migration).
 * When {@code preferences} is {@code null} (e.g. for users created before the
 * migration), callers should merge with
 * {@link com.agent.servlet.auth.PreferencesServlet#DEFAULT_PREFERENCES} to
 * obtain effective settings.
 */
public class User {

    private long        id;
    private String      username;
    private String      email;
    private String      passwordHash;
    /** Per-user settings stored as a JSON object. May be {@code null}. */
    private JsonObject  preferences;
    /** Dedicated theme column for quick frontend reads/writes. Defaults to {@code "light"}. */
    private String      theme;
    private Timestamp   createdAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    public User() {
    }

    /**
     * Full constructor (without preferences — used when creating a brand-new
     * user before any preferences have been saved).
     */
    public User(long id, String username, String email,
                String passwordHash, Timestamp createdAt) {
        this.id           = id;
        this.username     = username;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.createdAt    = createdAt;
    }

    /**
     * Full constructor including preferences.
     */
    public User(long id, String username, String email,
                String passwordHash, JsonObject preferences, Timestamp createdAt) {
        this.id           = id;
        this.username     = username;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.preferences  = preferences;
        this.createdAt    = createdAt;
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Returns the stored preferences {@link JsonObject}, or {@code null} if the
     * user has never saved any preferences.
     */
    public JsonObject getPreferences() {
        return preferences;
    }

    public void setPreferences(JsonObject preferences) {
        this.preferences = preferences;
    }

    /**
     * Returns the dedicated theme value, or {@code "light"} if not set.
     */
    public String getTheme() {
        return theme != null ? theme : "light";
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
