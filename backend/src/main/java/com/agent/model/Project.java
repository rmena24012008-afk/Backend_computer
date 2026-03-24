package com.agent.model;

import java.sql.Timestamp;

/**
 * Project model — maps to the `projects` table.
 */
public class Project {

    private long id;
    private long userId;
    private Long sessionId;      // nullable
    private String projectId;    // e.g., "proj_xyz789"
    private String name;
    private String description;
    private String files;        // JSON array string
    private Timestamp createdAt;

    public Project() {
    }

    public Project(long id, long userId, Long sessionId, String projectId, String name,
                   String description, String files, Timestamp createdAt) {
        this.id = id;
        this.userId = userId;
        this.sessionId = sessionId;
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.files = files;
        this.createdAt = createdAt;
    }

    // ── Getters and Setters ──

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

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFiles() {
        return files;
    }

    public void setFiles(String files) {
        this.files = files;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
