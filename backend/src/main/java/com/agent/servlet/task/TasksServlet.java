package com.agent.servlet.task;

import com.agent.config.AppConfig;
import com.agent.dao.ScheduledTaskDao;
import com.agent.dao.TaskRunLogDao;
import com.agent.model.ScheduledTask;
import com.agent.model.TaskRunLog;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/tasks       — List all scheduled tasks (MySQL + AI Agent live status merged).
 * GET /api/tasks/{id}  — Single task detail with run logs.
 *
 * Merges MySQL metadata (description, interval, ends_at, run counts) with
 * live data from the AI Agent (is_active, next_run, last result).
 */
@WebServlet("/api/tasks/*")
public class TasksServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(TasksServlet.class);
    private static final String FLASK_AGENT_URL = AppConfig.FLASK_AGENT_URL;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            String pathInfo = request.getPathInfo(); // null, "/", "/{taskId}"

            // ── Single task detail: GET /api/tasks/{taskId} ──
            if (pathInfo != null && pathInfo.length() > 1 && !pathInfo.equals("/")) {
                String taskId = pathInfo.substring(1); // strip leading "/"
                getSingleTask(userId, taskId, response);
                return;
            }

            // ── List all tasks: GET /api/tasks ──
            listTasks(userId, response);

        } catch (Exception e) {
            log.error("TASKS — unexpected error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    // ── List all tasks (merged MySQL + AI Agent) ──────────────────────────

    private void listTasks(long userId, HttpServletResponse response) throws IOException {
        // 1. Fetch tasks from MySQL (our source of truth for metadata)
        List<ScheduledTask> mysqlTasks = ScheduledTaskDao.findByUserId(userId);

        // 2. Fetch live status from AI Agent
        JsonObject agentData = fetchFromAgent("/tasks/" + userId, userId);
        JsonArray agentTasks = null;
        if (agentData != null && agentData.has("tasks") && agentData.get("tasks").isJsonArray()) {
            agentTasks = agentData.getAsJsonArray("tasks");
        }

        // 3. Index agent tasks by ID for fast lookup
        Map<String, JsonObject> agentById = new HashMap<>();
        if (agentTasks != null) {
            for (int i = 0; i < agentTasks.size(); i++) {
                JsonObject agentTask = agentTasks.get(i).getAsJsonObject();
                String id = agentTask.has("id") ? agentTask.get("id").getAsString() : null;
                if (id != null) {
                    agentById.put(id, agentTask);
                }
            }
        }

        // 4. Merge: MySQL is the base, agent data enriches with live fields
        List<Map<String, Object>> data = new ArrayList<>();
        for (ScheduledTask task : mysqlTasks) {
            Map<String, Object> item = buildTaskMap(task);

            // Overlay live agent fields if available
            JsonObject agentTask = agentById.remove(task.getTaskId());
            if (agentTask != null) {
                item.put("is_active", agentTask.has("is_active")
                        ? agentTask.get("is_active").getAsBoolean() : false);
                item.put("next_run", agentTask.has("next_run")
                        ? agentTask.get("next_run").getAsString() : null);
                // Agent may have a more current status
                if (agentTask.has("status")) {
                    String agentStatus = agentTask.get("status").getAsString();
                    // Only override if MySQL status is still "scheduled" or "running"
                    String mysqlStatus = task.getStatus();
                    if ("scheduled".equals(mysqlStatus) || "running".equals(mysqlStatus)) {
                        item.put("status", agentStatus);
                    }
                }
            } else {
                item.put("is_active", false);
                item.put("next_run", null);
            }

            data.add(item);
        }

        // 5. Include agent-only tasks that MySQL doesn't know about (edge case)
        for (Map.Entry<String, JsonObject> entry : agentById.entrySet()) {
            JsonObject agentTask = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task_id", entry.getKey());
            item.put("description", agentTask.has("description")
                    ? agentTask.get("description").getAsString() : "");
            item.put("status", agentTask.has("status")
                    ? agentTask.get("status").getAsString() : "scheduled");
            item.put("is_active", agentTask.has("is_active")
                    ? agentTask.get("is_active").getAsBoolean() : false);
            item.put("next_run", agentTask.has("next_run")
                    ? agentTask.get("next_run").getAsString() : null);
            item.put("interval_seconds", agentTask.has("interval_seconds")
                    ? agentTask.get("interval_seconds").getAsInt() : 0);
            item.put("total_runs", agentTask.has("total_runs")
                    ? agentTask.get("total_runs").getAsInt() : 0);
            item.put("completed_runs", agentTask.has("completed_runs")
                    ? agentTask.get("completed_runs").getAsInt() : 0);
            item.put("output_file", null);
            item.put("started_at", null);
            item.put("ends_at", null);
            item.put("created_at", null);
            data.add(item);
        }

        ResponseUtil.sendSuccess(response, data);
    }

    // ── Single task detail (MySQL + Agent + run logs) ─────────────────────

    private void getSingleTask(long userId, String taskId,
                               HttpServletResponse response) throws IOException {
        // 1. Verify ownership in MySQL
        ScheduledTask task = ScheduledTaskDao.findByTaskId(taskId);
        if (task == null || task.getUserId() != userId) {
            ResponseUtil.sendError(response, 404, "Task not found");
            return;
        }

        // 2. Build base response from MySQL
        Map<String, Object> data = buildTaskMap(task);

        // 3. Enrich with live agent status
        JsonObject agentTask = fetchFromAgent("/tasks/" + userId + "/" + taskId, userId);
        if (agentTask != null) {
            data.put("is_active", agentTask.has("is_active")
                    ? agentTask.get("is_active").getAsBoolean() : false);
            data.put("next_run", agentTask.has("next_run")
                    ? agentTask.get("next_run").getAsString() : null);
            if (agentTask.has("result") && !agentTask.get("result").isJsonNull()) {
                data.put("last_result", agentTask.get("result").toString());
            }
        } else {
            data.put("is_active", false);
            data.put("next_run", null);
        }

        // 4. Fetch run logs from MySQL
        List<TaskRunLog> logs = TaskRunLogDao.findByTaskId(taskId);
        List<Map<String, Object>> logList = new ArrayList<>();
        for (TaskRunLog rl : logs) {
            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("run_number", rl.getRunNumber());
            logEntry.put("status", rl.getStatus());
            logEntry.put("result_data", rl.getResultData());
            logEntry.put("error_message", rl.getErrorMessage());
            logEntry.put("executed_at", rl.getExecutedAt());
            logList.add(logEntry);
        }
        data.put("run_logs", logList);

        ResponseUtil.sendSuccess(response, data);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Build a standard task map from a MySQL ScheduledTask model.
     */
    private Map<String, Object> buildTaskMap(ScheduledTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("task_id", task.getTaskId());
        item.put("description", task.getDescription());
        item.put("status", task.getStatus());
        item.put("interval_seconds", task.getIntervalSecs());
        item.put("total_runs", task.getTotalRuns());
        item.put("completed_runs", task.getCompletedRuns());
        item.put("output_file", task.getOutputFile());
        item.put("started_at", task.getStartedAt());
        item.put("ends_at", task.getEndsAt());
        item.put("created_at", task.getCreatedAt());
        return item;
    }

    /**
     * Fetch JSON data from the AI Agent (Flask Server on port 5000).
     * Returns null if the agent is unreachable or returns a non-200 status.
     *
     * @param path   the agent path (e.g. "/tasks/42" or "/tasks/42/sched_abc")
     * @param userId the authenticated user's ID — forwarded as X-User-Id header
     */
    private JsonObject fetchFromAgent(String path, long userId) {
        try {
            URL url = new URL(FLASK_AGENT_URL + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            // Forward user identity — required by the agent to prevent cross-user access
            conn.setRequestProperty("X-User-Id", String.valueOf(userId));

            // Forward API key if configured
            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    return JsonUtil.parse(sb.toString());
                }
            } else {
                log.debug("TASKS — agent returned non-200 | path={} | status={}", path, status);
            }
        } catch (Exception e) {
            log.warn("TASKS — agent fetch failed | path={} | error={}", path, e.getMessage());
        }
        return null;
    }
}
