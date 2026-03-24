package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.TaskRunLog;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the `task_run_logs` table.
 */
public class TaskRunLogDao {

    /**
     * Find all run logs for a task, ordered by run_number ASC.
     */
    public static List<TaskRunLog> findByTaskId(String taskId) {
        String sql = "SELECT * FROM task_run_logs WHERE task_id = ? ORDER BY run_number ASC";
        List<TaskRunLog> logs = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapRow(rs));
            }
            return logs;
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding task run logs", e);
        }
    }

    /**
     * Create a new task run log entry. Returns the auto-generated ID.
     *
     * @param taskId       the scheduled task ID
     * @param runNumber    the run number (1-indexed)
     * @param status       "success" or "failed"
     * @param resultData   JSON string of result data (nullable)
     * @param errorMessage error message if failed (nullable)
     * @return the generated log ID
     */
    public static long create(String taskId, int runNumber, String status,
                              String resultData, String errorMessage) {
        String sql = "INSERT INTO task_run_logs (task_id, run_number, status, result_data, error_message) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, taskId);
            stmt.setInt(2, runNumber);
            stmt.setString(3, status);
            if (resultData != null) {
                stmt.setString(4, resultData);
            } else {
                stmt.setNull(4, Types.VARCHAR);
            }
            if (errorMessage != null) {
                stmt.setString(5, errorMessage);
            } else {
                stmt.setNull(5, Types.VARCHAR);
            }
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating task run log", e);
        }
    }

    /**
     * Map a ResultSet row to a TaskRunLog object.
     */
    private static TaskRunLog mapRow(ResultSet rs) throws SQLException {
        TaskRunLog log = new TaskRunLog();
        log.setId(rs.getLong("id"));
        log.setTaskId(rs.getString("task_id"));
        log.setRunNumber(rs.getInt("run_number"));
        log.setStatus(rs.getString("status"));
        log.setResultData(rs.getString("result_data"));
        log.setErrorMessage(rs.getString("error_message"));
        log.setExecutedAt(rs.getTimestamp("executed_at"));
        return log;
    }
}
