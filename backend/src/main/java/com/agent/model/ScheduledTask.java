package com.agent.model;

import java.sql.Timestamp;

/**
 * ScheduledTask model — maps to the `scheduled_tasks` table.
 */
public class ScheduledTask {

    private long id;
    private long userId;
    private Long sessionId;       // nullable
    private String taskId;        // e.g., "sched_abc123"
    private String description;
    private String status;        // scheduled | running | completed | cancelled | failed
    private int intervalSecs;
    private Timestamp startedAt;
    private Timestamp endsAt;
    private int totalRuns;
    private int completedRuns;
    private String outputFile;
    private Timestamp createdAt;

    public ScheduledTask() {
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

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getIntervalSecs() {
        return intervalSecs;
    }

    public void setIntervalSecs(int intervalSecs) {
        this.intervalSecs = intervalSecs;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    public Timestamp getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Timestamp endsAt) {
        this.endsAt = endsAt;
    }

    public int getTotalRuns() {
        return totalRuns;
    }

    public void setTotalRuns(int totalRuns) {
        this.totalRuns = totalRuns;
    }

    public int getCompletedRuns() {
        return completedRuns;
    }

    public void setCompletedRuns(int completedRuns) {
        this.completedRuns = completedRuns;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
