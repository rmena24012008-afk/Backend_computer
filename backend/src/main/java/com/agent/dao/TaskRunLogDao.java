package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.TaskRunLog;
import com.agent.util.AppLogger;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskRunLogDao {

    private static final Logger log = AppLogger.get(TaskRunLogDao.class);

    /**
     * Find all run logs for a task, ordered by run_number ASC.
     */
    public static List<TaskRunLog> findByTaskId(String taskId) {
        log.debug("RUN_LOG FIND_BY_TASK | taskId={}", taskId);
        String sql = "SELECT * FROM task_run_logs WHERE task_id = ? ORDER BY run_number ASC";
        List<TaskRunLog> logs = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapRow(rs));
            }
            log.debug("RUN_LOG FIND_BY_TASK OK | taskId={} | count={}", taskId, logs.size());
            return logs;
        } catch (SQLException e) {
            log.error("RUN_LOG FIND_BY_TASK FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error finding task run logs", e);
        }
    }

    /**
     * Find run logs for a task with pagination, ordered by run_number DESC (newest first).
     *
     * @param taskId the task ID
     * @param limit  max rows to return (capped at 200)
     * @param offset number of rows to skip
     * @return paginated list of run logs
     */
    public static List<TaskRunLog> findByTaskIdPaginated(String taskId, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        log.debug("RUN_LOG FIND_PAGINATED | taskId={} | limit={} | offset={}", taskId, safeLimit, safeOffset);
        String sql = "SELECT * FROM task_run_logs WHERE task_id = ? ORDER BY run_number DESC LIMIT ? OFFSET ?";
        List<TaskRunLog> logs = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            stmt.setInt(2, safeLimit);
            stmt.setInt(3, safeOffset);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapRow(rs));
            }
            log.debug("RUN_LOG FIND_PAGINATED OK | taskId={} | count={}", taskId, logs.size());
            return logs;
        } catch (SQLException e) {
            log.error("RUN_LOG FIND_PAGINATED FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error finding paginated task run logs", e);
        }
    }

    /**
     * Find the latest (most recent) run log for a task.
     *
     * @param taskId the task ID
     * @return the latest run log, or null if none exist
     */
    public static TaskRunLog findLatestByTaskId(String taskId) {
        log.debug("RUN_LOG FIND_LATEST | taskId={}", taskId);
        String sql = "SELECT * FROM task_run_logs WHERE task_id = ? ORDER BY run_number DESC LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            log.debug("RUN_LOG FIND_LATEST — no results | taskId={}", taskId);
            return null;
        } catch (SQLException e) {
            log.error("RUN_LOG FIND_LATEST FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error finding latest task run log", e);
        }
    }

    /**
     * Get aggregate statistics for a task's run logs.
     * Returns a map with: total_runs, success_count, failure_count,
     * avg_duration_ms, first_run_at, last_run_at.
     *
     * @param taskId the task ID
     * @return stats map
     */
    public static Map<String, Object> getStatsByTaskId(String taskId) {
        log.debug("RUN_LOG GET_STATS | taskId={}", taskId);
        String sql = "SELECT " +
                "COUNT(*) AS total_runs, " +
                "SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS success_count, " +
                "SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failure_count, " +
                "MIN(executed_at) AS first_run_at, " +
                "MAX(executed_at) AS last_run_at " +
                "FROM task_run_logs WHERE task_id = ?";
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                stats.put("total_runs", rs.getInt("total_runs"));
                stats.put("success_count", rs.getInt("success_count"));
                stats.put("failure_count", rs.getInt("failure_count"));

                Timestamp firstRun = rs.getTimestamp("first_run_at");
                Timestamp lastRun = rs.getTimestamp("last_run_at");
                stats.put("first_run_at", firstRun != null ? firstRun.getTime() / 1000.0 : null);
                stats.put("last_run_at", lastRun != null ? lastRun.getTime() / 1000.0 : null);
            } else {
                stats.put("total_runs", 0);
                stats.put("success_count", 0);
                stats.put("failure_count", 0);
                stats.put("first_run_at", null);
                stats.put("last_run_at", null);
            }
            return stats;
        } catch (SQLException e) {
            log.error("RUN_LOG GET_STATS FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error getting task run stats", e);
        }
    }

    /**
     * Count total run logs for a task (used for pagination metadata).
     *
     * @param taskId the task ID
     * @return total count
     */
    public static int countByTaskId(String taskId) {
        log.debug("RUN_LOG COUNT | taskId={}", taskId);
        String sql = "SELECT COUNT(*) FROM task_run_logs WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            log.error("RUN_LOG COUNT FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error counting task run logs", e);
        }
    }

    /**
     * Delete all run logs for a task. Returns the number of deleted rows.
     *
     * @param taskId the task ID
     * @return number of deleted rows
     */
    public static int deleteByTaskId(String taskId) {
        log.info("RUN_LOG DELETE | taskId={}", taskId);
        String sql = "DELETE FROM task_run_logs WHERE task_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, taskId);
            int deleted = stmt.executeUpdate();
            log.info("RUN_LOG DELETE OK | taskId={} | deletedRows={}", taskId, deleted);
            return deleted;
        } catch (SQLException e) {
            log.error("RUN_LOG DELETE FAILED | taskId={} | error={}", taskId, e.getMessage(), e);
            throw new RuntimeException("DB error deleting task run logs", e);
        }
    }

    /**
     * Get latest run log for each task belonging to a user, with optional status filter.
     * Used by the user dashboard endpoint.
     *
     * @param userId the user ID
     * @param statusFilter optional status filter ("success", "failed"); null for all
     * @param limit  max rows
     * @param offset rows to skip
     * @return list of latest run logs per task
     */
    public static List<TaskRunLog> findLatestPerTaskForUser(long userId, String statusFilter,
                                                             int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);

        // Subquery: latest run_number per task for this user
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT trl.* FROM task_run_logs trl ");
        sql.append("INNER JOIN scheduled_tasks st ON trl.task_id = st.task_id ");
        sql.append("INNER JOIN (");
        sql.append("  SELECT trl2.task_id, MAX(trl2.run_number) AS max_run ");
        sql.append("  FROM task_run_logs trl2 ");
        sql.append("  INNER JOIN scheduled_tasks st2 ON trl2.task_id = st2.task_id ");
        sql.append("  WHERE st2.user_id = ? ");
        sql.append("  GROUP BY trl2.task_id ");
        sql.append(") latest ON trl.task_id = latest.task_id AND trl.run_number = latest.max_run ");
        sql.append("WHERE st.user_id = ? ");
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append("AND trl.status = ? ");
        }
        sql.append("ORDER BY trl.executed_at DESC LIMIT ? OFFSET ?");

        List<TaskRunLog> logs = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            stmt.setLong(paramIdx++, userId);
            stmt.setLong(paramIdx++, userId);
            if (statusFilter != null && !statusFilter.isBlank()) {
                stmt.setString(paramIdx++, statusFilter);
            }
            stmt.setInt(paramIdx++, safeLimit);
            stmt.setInt(paramIdx, safeOffset);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapRow(rs));
            }
            log.debug("RUN_LOG FIND_LATEST_PER_TASK OK | userId={} | count={}", userId, logs.size());
            return logs;
        } catch (SQLException e) {
            log.error("RUN_LOG FIND_LATEST_PER_TASK FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error finding latest run logs per task for user", e);
        }
    }

    /**
     * Count distinct tasks with run logs for a user (for dashboard pagination).
     *
     * @param userId       the user ID
     * @param statusFilter optional status filter
     * @return count of distinct tasks with results
     */
    public static int countTasksWithResultsForUser(long userId, String statusFilter) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT trl.task_id) FROM task_run_logs trl ");
        sql.append("INNER JOIN scheduled_tasks st ON trl.task_id = st.task_id ");
        sql.append("WHERE st.user_id = ? ");
        if (statusFilter != null && !statusFilter.isBlank()) {
            // Filter: at least one run with this status
            sql.append("AND trl.task_id IN (");
            sql.append("  SELECT task_id FROM task_run_logs WHERE status = ?");
            sql.append(") ");
        }
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            stmt.setLong(paramIdx++, userId);
            if (statusFilter != null && !statusFilter.isBlank()) {
                stmt.setString(paramIdx, statusFilter);
            }
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            log.error("RUN_LOG COUNT_TASKS_FOR_USER FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error counting tasks with results for user", e);
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
        log.debug("RUN_LOG CREATE | taskId={} | runNumber={} | status={}", taskId, runNumber, status);
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
            long id = keys.getLong(1);
            log.info("RUN_LOG CREATE OK | taskId={} | runNumber={} | status={} | id={}", taskId, runNumber, status, id);
            return id;
        } catch (SQLException e) {
            log.error("RUN_LOG CREATE FAILED | taskId={} | runNumber={} | error={}", taskId, runNumber, e.getMessage(), e);
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
