package com.agent.model;

import java.sql.Timestamp;

/**
 * ChatMessage model — maps to the `chat_messages` table.
 */
public class ChatMessage {

    private long id;
    private long sessionId;
    private String role;       // "user" | "assistant"
    private String content;
    private Timestamp createdAt;

    public ChatMessage() {
    }

    public ChatMessage(long id, long sessionId, String role, String content, Timestamp createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    // ── Getters and Setters ──

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
