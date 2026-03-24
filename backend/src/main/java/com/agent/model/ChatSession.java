package com.agent.model;

import java.sql.Timestamp;

/**
 * ChatSession model — maps to the {@code chat_sessions} table.
 *
 * Includes the {@code summary} TEXT column (v1.1 migration).
 * The summary is populated by the AI after the first response, every 10
 * messages, or on session close. It may be {@code null} for new/empty sessions.
 */
public class ChatSession {

    private long      id;
    private long      userId;
    private String    title;
    /** AI-generated or auto-extracted summary of the conversation. May be {@code null}. */
    private String    summary;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    public ChatSession() {
    }

    /**
     * Full constructor (without summary — backward-compatible with existing code).
     */
    public ChatSession(long id, long userId, String title,
                       Timestamp createdAt, Timestamp updatedAt) {
        this.id        = id;
        this.userId    = userId;
        this.title     = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Full constructor including summary.
     */
    public ChatSession(long id, long userId, String title, String summary,
                       Timestamp createdAt, Timestamp updatedAt) {
        this.id        = id;
        this.userId    = userId;
        this.title     = title;
        this.summary   = summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the AI-generated conversation summary, or {@code null} if not yet
     * populated (new sessions, or sessions created before the v1.1 migration).
     */
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
