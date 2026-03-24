package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.ScheduledTask;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the `scheduled_tasks` table.
 */
public class ScheduledTaskDao {

    /**
     * Find all tasks for a user, ordered by created_at DESC.
     */
    public static List<ScheduledTask> findByUserId(long userId) {
        String sql = "SELECT * FROM scheduled_tasks WHERE user_id = ? ORDER BY created_at DESC";
        List<ScheduledTask> tasks = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                tasks.add(mapRow(rs));
            }
            return tasks;
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding tasks by user", e);
        }
    }

    /**
     * Find a task by its task_id (e.g., "sched_abc123").
     */
    public static ScheduledTask findByTaskId(String taskId) {
        String sql = "SELECT * FROM scheduled_tasks WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding task by taskId", e);
        }
    }

    /**
     * Create a new scheduled task record. Returns the auto-generated ID.
     */
    public static long create(long userId, Long sessionId, String taskId, String description,
                              int intervalSecs, Timestamp endsAt, int totalRuns) {
        String sql = "INSERT INTO scheduled_tasks (user_id, session_id, task_id, description, " +
                "interval_secs, started_at, ends_at, total_runs) VALUES (?, ?, ?, ?, ?, NOW(), ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, userId);
            if (sessionId != null) {
                stmt.setLong(2, sessionId);
            } else {
                stmt.setNull(2, Types.BIGINT);
            }
            stmt.setString(3, taskId);
            stmt.setString(4, description);
            stmt.setInt(5, intervalSecs);
            stmt.setTimestamp(6, endsAt);
            stmt.setInt(7, totalRuns);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating scheduled task", e);
        }
    }

    /**
     * Update the status of a task.
     */
    public static void updateStatus(String taskId, String status) {
        String sql = "UPDATE scheduled_tasks SET status = ? WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error updating task status", e);
        }
    }

    /**
     * Increment the completed_runs counter by 1.
     */
    public static void incrementCompletedRuns(String taskId) {
        String sql = "UPDATE scheduled_tasks SET completed_runs = completed_runs + 1 WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error incrementing completed runs", e);
        }
    }

    /**
     * Update the output file path of a task.
     */
    public static void updateOutputFile(String taskId, String outputFile) {
        String sql = "UPDATE scheduled_tasks SET output_file = ? WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, outputFile);
            stmt.setString(2, taskId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error updating task output file", e);
        }
    }

    /**
     * Check if a task belongs to a specific user.
     */
    public static boolean belongsToUser(String taskId, long userId) {
        ScheduledTask task = findByTaskId(taskId);
        return task != null && task.getUserId() == userId;
    }

    /**
     * Map a ResultSet row to a ScheduledTask object.
     */
    private static ScheduledTask mapRow(ResultSet rs) throws SQLException {
        ScheduledTask task = new ScheduledTask();
        task.setId(rs.getLong("id"));
        task.setUserId(rs.getLong("user_id"));
        long sessionId = rs.getLong("session_id");
        task.setSessionId(rs.wasNull() ? null : sessionId);
        task.setTaskId(rs.getString("task_id"));
        task.setDescription(rs.getString("description"));
        task.setStatus(rs.getString("status"));
        task.setIntervalSecs(rs.getInt("interval_secs"));
        task.setStartedAt(rs.getTimestamp("started_at"));
        task.setEndsAt(rs.getTimestamp("ends_at"));
        task.setTotalRuns(rs.getInt("total_runs"));
        task.setCompletedRuns(rs.getInt("completed_runs"));
        task.setOutputFile(rs.getString("output_file"));
        task.setCreatedAt(rs.getTimestamp("created_at"));
        return task;
    }
}
