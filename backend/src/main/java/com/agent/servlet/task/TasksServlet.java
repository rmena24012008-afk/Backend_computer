package com.agent.servlet.task;

import com.agent.config.AppConfig;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/tasks       — List all scheduled tasks for the authenticated user.
 * GET /api/tasks/{id}  — Single task detail.
 *
 * All data is fetched from the AI Agent (Flask); no local DB access.
 * The MCP team manages task persistence on the agent side.
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

    // ── List all tasks (proxied from AI Agent) ───────────────────────────

    private void listTasks(long userId, HttpServletResponse response) throws IOException {
        // Fetch tasks from AI Agent (single source of truth)
        JsonObject agentData = fetchFromAgent("/tasks/" + userId, userId);

        if (agentData == null) {
            // Agent unreachable — return empty list
            log.warn("TASKS — agent unreachable, returning empty list | userId={}", userId);
            ResponseUtil.sendSuccess(response, new ArrayList<>());
            return;
        }

        JsonArray agentTasks = null;
        if (agentData.has("tasks") && agentData.get("tasks").isJsonArray()) {
            agentTasks = agentData.getAsJsonArray("tasks");
        }

        if (agentTasks == null || agentTasks.isEmpty()) {
            ResponseUtil.sendSuccess(response, new ArrayList<>());
            return;
        }

        // Map agent tasks to response format
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < agentTasks.size(); i++) {
            JsonObject agentTask = agentTasks.get(i).getAsJsonObject();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task_id", getString(agentTask, "id"));
            item.put("description", getString(agentTask, "description", ""));
            item.put("status", getString(agentTask, "status", "scheduled"));
            item.put("is_active", getBoolean(agentTask, "is_active", false));
            item.put("next_run", getString(agentTask, "next_run"));
            item.put("interval_seconds", getInt(agentTask, "interval_seconds", 0));
            item.put("total_runs", getInt(agentTask, "total_runs", 0));
            item.put("completed_runs", getInt(agentTask, "completed_runs", 0));
            item.put("output_file", getString(agentTask, "output_file"));
            item.put("started_at", getString(agentTask, "started_at"));
            item.put("ends_at", getString(agentTask, "ends_at"));
            item.put("created_at", getString(agentTask, "created_at"));
            data.add(item);
        }

        ResponseUtil.sendSuccess(response, data);
    }

    // ── Single task detail (proxied from AI Agent) ───────────────────────

    private void getSingleTask(long userId, String taskId,
                               HttpServletResponse response) throws IOException {
        JsonObject agentTask = fetchFromAgent("/tasks/" + userId + "/" + taskId, userId);

        if (agentTask == null) {
            ResponseUtil.sendError(response, 404, "Task not found: " + taskId);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", getString(agentTask, "id", taskId));
        data.put("description", getString(agentTask, "description", ""));
        data.put("status", getString(agentTask, "status", "scheduled"));
        data.put("is_active", getBoolean(agentTask, "is_active", false));
        data.put("next_run", getString(agentTask, "next_run"));
        data.put("interval_seconds", getInt(agentTask, "interval_seconds", 0));
        data.put("total_runs", getInt(agentTask, "total_runs", 0));
        data.put("completed_runs", getInt(agentTask, "completed_runs", 0));
        data.put("output_file", getString(agentTask, "output_file"));
        data.put("started_at", getString(agentTask, "started_at"));
        data.put("ends_at", getString(agentTask, "ends_at"));
        data.put("created_at", getString(agentTask, "created_at"));

        // Include run logs if the agent provides them
        if (agentTask.has("run_logs") && agentTask.get("run_logs").isJsonArray()) {
            JsonArray runLogs = agentTask.getAsJsonArray("run_logs");
            List<Map<String, Object>> logList = new ArrayList<>();
            for (int i = 0; i < runLogs.size(); i++) {
                JsonObject rl = runLogs.get(i).getAsJsonObject();
                Map<String, Object> logEntry = new LinkedHashMap<>();
                logEntry.put("run_number", getInt(rl, "run_number", 0));
                logEntry.put("status", getString(rl, "status"));
                logEntry.put("result_data", getString(rl, "result_data"));
                logEntry.put("error_message", getString(rl, "error_message"));
                logEntry.put("executed_at", getString(rl, "executed_at"));
                logList.add(logEntry);
            }
            data.put("run_logs", logList);
        } else {
            data.put("run_logs", new ArrayList<>());
        }

        // Include last_result if provided
        if (agentTask.has("result") && !agentTask.get("result").isJsonNull()) {
            data.put("last_result", agentTask.get("result").toString());
        }

        ResponseUtil.sendSuccess(response, data);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static String getString(JsonObject source, String field) {
        return getString(source, field, null);
    }

    static String getString(JsonObject source, String field, String defaultValue) {
        JsonElement value = getValue(source, field);
        if (value == null) return defaultValue;
        try {
            return value.getAsString();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return defaultValue;
        }
    }

    static boolean getBoolean(JsonObject source, String field, boolean defaultValue) {
        JsonElement value = getValue(source, field);
        if (value == null) return defaultValue;
        try {
            return value.getAsBoolean();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return defaultValue;
        }
    }

    static int getInt(JsonObject source, String field, int defaultValue) {
        JsonElement value = getValue(source, field);
        if (value == null) return defaultValue;
        try {
            return value.getAsInt();
        } catch (UnsupportedOperationException | IllegalStateException | NumberFormatException e) {
            return defaultValue;
        }
    }

    private static JsonElement getValue(JsonObject source, String field) {
        if (source == null || field == null || !source.has(field)) {
            return null;
        }
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value;
    }

    /**
     * Fetch JSON data from the AI Agent (Flask Server).
     * Returns null if the agent is unreachable or returns a non-200 status.
     */
    private JsonObject fetchFromAgent(String path, long userId) {
        try {
            URL url = new URL(FLASK_AGENT_URL + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            conn.setRequestProperty("X-User-Id", String.valueOf(userId));

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
