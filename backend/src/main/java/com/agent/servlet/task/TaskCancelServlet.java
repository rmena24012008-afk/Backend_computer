package com.agent.servlet.task;

import com.agent.config.AppConfig;
import com.agent.dao.ScheduledTaskDao;
import com.agent.model.ScheduledTask;
import com.agent.service.TaskExecutorClient;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/task-cancel/{taskId} — Cancel a running scheduled task.
 *
 * Flow:
 *   1. Verify ownership in MySQL (returns 404 if missing, 403 if wrong user).
 *   2. Proxy cancel to the AI Agent (Flask) — removes APScheduler job + updates JSON store.
 *   3. Send cancel to the Task Executor via WebSocket (best-effort).
 *   4. Update MySQL status to "cancelled".
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/tasks/X/cancel.
 * Remapped to /api/task-cancel/* for correct routing.
 */
@WebServlet("/api/task-cancel/*")
public class TaskCancelServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(TaskCancelServlet.class);
    private static final String FLASK_AGENT_URL = AppConfig.FLASK_AGENT_URL;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            // Extract taskId from path info: /api/task-cancel/{taskId}
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                ResponseUtil.sendError(response, 400, "Task ID is required in path");
                return;
            }

            String[] parts = pathInfo.split("/");
            if (parts.length < 2 || parts[1].isBlank()) {
                ResponseUtil.sendError(response, 400, "Invalid path format");
                return;
            }

            String taskId = parts[1];

            // Verify task exists
            ScheduledTask task = ScheduledTaskDao.findByTaskId(taskId);
            if (task == null) {
                ResponseUtil.sendError(response, 404, "Task not found: " + taskId);
                return;
            }

            // Verify ownership — return 403 if task belongs to a different user
            if (task.getUserId() != userId) {
                ResponseUtil.sendError(response, 403, "Access denied");
                return;
            }

            // 1. Proxy cancel to AI Agent (Flask) — removes APScheduler job + updates JSON store
            boolean agentCancelOk = cancelViaAgent(userId, taskId);
            if (!agentCancelOk) {
                log.warn("TASK_CANCEL — agent cancel failed or unreachable, proceeding with local cancel | taskId={}", taskId);
            }

            // 2. Send cancel command via WebSocket to Task Executor (best-effort)
            try {
                TaskExecutorClient client = TaskExecutorClient.getInstance();
                if (client.isConnected()) {
                    JsonObject cancelCommand = new JsonObject();
                    cancelCommand.addProperty("type", "cancel_task");
                    cancelCommand.addProperty("task_id", taskId);
                    client.send(cancelCommand);
                    log.debug("TASK_CANCEL — cancel sent to executor via WebSocket | taskId={}", taskId);
                }
            } catch (Exception e) {
                log.warn("TASK_CANCEL — failed to send cancel to Task Executor | taskId={} | error={}",
                        taskId, e.getMessage());
                // Continue — still update DB status even if executor is unreachable
            }

            // 3. Update status in MySQL
            ScheduledTaskDao.updateStatus(taskId, "cancelled");

            log.info("TASK_CANCEL — task cancelled | userId={} | taskId={} | agentOk={}",
                    userId, taskId, agentCancelOk);

            // Build response
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", taskId);
            data.put("status", "cancelled");

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("TASK_CANCEL — unexpected error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Proxy the cancel request to the AI Agent (Flask Server).
     * POST {FLASK_AGENT_URL}/tasks/{userId}/{taskId}/cancel
     *
     * This removes the job from APScheduler and updates the on-disk JSON store.
     *
     * @return true if the agent returned 200, false otherwise
     */
    private boolean cancelViaAgent(long userId, String taskId) {
        try {
            String agentPath = "/tasks/" + userId + "/" + taskId + "/cancel";
            URL url = new URL(FLASK_AGENT_URL + agentPath);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-User-Id", String.valueOf(userId));

            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            // POST with empty body
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.flush();
            }

            int status = conn.getResponseCode();
            log.debug("TASK_CANCEL — agent response | path={} | status={}", agentPath, status);

            conn.disconnect();
            return status == 200;
        } catch (Exception e) {
            log.warn("TASK_CANCEL — agent cancel request failed | taskId={} | error={}",
                    taskId, e.getMessage());
            return false;
        }
    }
}
