package com.agent.model;

import java.sql.Timestamp;

/**
 * TaskRunLog model — maps to the `task_run_logs` table.
 * One row per execution of a scheduled task.
 */
public class TaskRunLog {

    private long id;
    private String taskId;
    private int runNumber;
    private String status;        // "success" | "failed"
    private String resultData;    // JSON string (nullable)
    private String errorMessage;
    private Timestamp executedAt;

    public TaskRunLog() {
    }

    // ── Getters and Setters ──

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public int getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(int runNumber) {
        this.runNumber = runNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultData() {
        return resultData;
    }

    public void setResultData(String resultData) {
        this.resultData = resultData;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Timestamp getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Timestamp executedAt) {
        this.executedAt = executedAt;
    }
}
