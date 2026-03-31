package com.agent.servlet.task;

import com.agent.config.AppConfig;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GET /api/task-download/{taskId} — Download the output file of a scheduled task.
 *
 * Proxies the download request to the AI Agent (Flask):
 *   GET {FLASK_AGENT_URL}/tasks/{userId}/{taskId}/download
 *
 * The agent resolves the output file from its own store.
 * No local DB access — the MCP team manages task persistence on the agent side.
 */
@WebServlet("/api/task-download/*")
public class TaskDownloadServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(TaskDownloadServlet.class);
    private static final String FLASK_AGENT_URL = AppConfig.FLASK_AGENT_URL;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            // Extract taskId from path info: /api/task-download/{taskId}
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

            // Proxy download via AI Agent (Flask)
            boolean agentDownloaded = proxyDownloadFromAgent(userId, taskId, response);
            if (agentDownloaded) {
                return;
            }

            log.debug("TASK_DOWNLOAD — agent download unavailable, trying executor fallback | taskId={}", taskId);

            // Fallback: try the Task Executor directly
            boolean executorDownloaded = proxyDownloadFromExecutor(userId, taskId, response);
            if (executorDownloaded) {
                return;
            }

            ResponseUtil.sendError(response, 404, "No output file available for this task");

        } catch (Exception e) {
            log.error("TASK_DOWNLOAD — unexpected error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Proxy the download request to the AI Agent (Flask Server).
     * GET {FLASK_AGENT_URL}/tasks/{userId}/{taskId}/download
     *
     * @return true if the agent served the file (status 200 and bytes streamed),
     *         false if the agent returned non-200 or was unreachable.
     */
    private boolean proxyDownloadFromAgent(long userId, String taskId,
                                           HttpServletResponse response) {
        HttpURLConnection conn = null;
        try {
            String agentPath = "/tasks/" + userId + "/" + taskId + "/download";
            URL url = new URL(FLASK_AGENT_URL + agentPath);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("X-User-Id", String.valueOf(userId));

            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            log.debug("TASK_DOWNLOAD — proxying to agent | userId={} | taskId={} | url={}",
                    userId, taskId, url);

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                log.debug("TASK_DOWNLOAD — agent returned non-200 | taskId={} | status={}", taskId, statusCode);
                return false;
            }

            // Forward headers from the agent response
            String contentType = conn.getContentType();
            String contentDisposition = conn.getHeaderField("Content-Disposition");
            int contentLength = conn.getContentLength();

            if (contentType != null) {
                response.setContentType(contentType);
            } else {
                response.setContentType("application/octet-stream");
            }

            if (contentDisposition != null) {
                response.setHeader("Content-Disposition", contentDisposition);
            } else {
                response.setHeader("Content-Disposition", "attachment; filename=\"" + taskId + "_output\"");
            }

            if (contentLength > 0) {
                response.setContentLength(contentLength);
            }

            // Stream file bytes to frontend
            try (InputStream in = conn.getInputStream();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            log.info("TASK_DOWNLOAD — served via agent | userId={} | taskId={}", userId, taskId);
            return true;

        } catch (Exception e) {
            log.warn("TASK_DOWNLOAD — agent download failed | taskId={} | error={}", taskId, e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Fallback: fetch the output file from the Task Executor.
     * GET {EXECUTOR_URL}/download/{userId}/{taskId}
     *
     * @return true if the executor served the file, false otherwise.
     */
    private boolean proxyDownloadFromExecutor(long userId, String taskId,
                                              HttpServletResponse response) {
        HttpURLConnection conn = null;
        try {
            String rawUrl = AppConfig.TASK_EXECUTOR_WS_URL;
            if (rawUrl == null || rawUrl.isBlank()) {
                return false;
            }
            if (rawUrl.startsWith("ws://") || rawUrl.startsWith("wss://")) {
                rawUrl = rawUrl.replace("ws://", "http://")
                               .replace("wss://", "https://")
                               .replace("/ws", "");
            }
            String executorBaseUrl = rawUrl.endsWith("/") ? rawUrl.substring(0, rawUrl.length() - 1) : rawUrl;
            String downloadUrl = executorBaseUrl + "/download/" + userId + "/" + taskId;

            URL url = new URL(downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);

            conn.setRequestProperty("X-User-Id", String.valueOf(userId));
            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            log.debug("TASK_DOWNLOAD — proxying to executor | userId={} | taskId={} | url={}",
                    userId, taskId, downloadUrl);

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                log.debug("TASK_DOWNLOAD — executor returned non-200 | taskId={} | status={}", taskId, statusCode);
                return false;
            }

            // Forward headers
            String contentType = conn.getContentType();
            String contentDisposition = conn.getHeaderField("Content-Disposition");

            if (contentType != null) {
                response.setContentType(contentType);
            } else {
                response.setContentType("application/octet-stream");
            }

            if (contentDisposition != null) {
                response.setHeader("Content-Disposition", contentDisposition);
            } else {
                response.setHeader("Content-Disposition", "attachment; filename=\"" + taskId + "_output\"");
            }

            int contentLength = conn.getContentLength();
            if (contentLength > 0) {
                response.setContentLength(contentLength);
            }

            // Stream file bytes to frontend
            try (InputStream in = conn.getInputStream();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            log.info("TASK_DOWNLOAD — served via executor | userId={} | taskId={}", userId, taskId);
            return true;

        } catch (Exception e) {
            log.warn("TASK_DOWNLOAD — executor download failed | taskId={} | error={}", taskId, e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
