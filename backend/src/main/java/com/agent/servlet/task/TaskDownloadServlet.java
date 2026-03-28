package com.agent.servlet.task;

import com.agent.config.AppConfig;
import com.agent.dao.ScheduledTaskDao;
import com.agent.model.ScheduledTask;
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
 * Resolution strategy (matches the Scheduler spec):
 *   1. Proxy to the AI Agent (Flask): GET /tasks/{userId}/{taskId}/download
 *      The agent checks task.result.output_file → task.output_file in the JSON store.
 *   2. Fallback: fetch from the Task Executor using the MySQL output_file path.
 *
 * Supports ?token= query param for browser downloads that can't set Authorization headers.
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/tasks/X/download.
 * Remapped to /api/task-download/* for correct routing.
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

            // ── Strategy 1: Proxy download via AI Agent (Flask) ──
            // The agent resolves output_file from task.result.output_file or task.output_file
            boolean agentDownloaded = proxyDownloadFromAgent(userId, taskId, response);
            if (agentDownloaded) {
                return;
            }

            log.debug("TASK_DOWNLOAD — agent download unavailable, trying executor fallback | taskId={}", taskId);

            // ── Strategy 2: Fallback to Task Executor using MySQL output_file ──
            if (task.getOutputFile() == null || task.getOutputFile().isEmpty()) {
                ResponseUtil.sendError(response, 404, "No output file available for this task");
                return;
            }

            proxyDownloadFromExecutor(userId, taskId, task.getOutputFile(), response);

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
                // Derive filename from taskId as fallback
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
     * Fallback: fetch the output file from the Task Executor (port 6000).
     * GET http://localhost:6000/download/{userId}/{filePath}
     */
    private void proxyDownloadFromExecutor(long userId, String taskId, String outputFile,
                                           HttpServletResponse response) throws IOException {
        HttpURLConnection conn = null;
        try {
            String executorBaseUrl = AppConfig.TASK_EXECUTOR_WS_URL
                    .replace("ws://", "http://")
                    .replace("/ws", "");
            String downloadUrl = executorBaseUrl + "/download/" + userId + "/" + outputFile;

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
                log.warn("TASK_DOWNLOAD — executor returned non-200 | taskId={} | status={}", taskId, statusCode);
                ResponseUtil.sendError(response, 404, "Output file not found on disk");
                return;
            }

            // Extract filename from output file path
            String filename = outputFile.contains("/")
                    ? outputFile.substring(outputFile.lastIndexOf("/") + 1)
                    : outputFile;

            // Determine content type based on file extension
            String contentType = conn.getContentType();
            if (contentType == null || "application/octet-stream".equals(contentType)) {
                contentType = resolveContentType(filename);
            }
            response.setContentType(contentType);
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

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

            log.info("TASK_DOWNLOAD — served via executor | userId={} | taskId={} | file={}",
                    userId, taskId, filename);

        } catch (java.net.ConnectException e) {
            log.error("TASK_DOWNLOAD — executor unreachable | taskId={} | error={}", taskId, e.getMessage());
            ResponseUtil.sendError(response, 502, "File download service unavailable");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Resolve Content-Type based on file extension.
     * Per the Scheduler spec, .xlsx gets the Office MIME type; everything else is octet-stream.
     */
    private String resolveContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else if (lower.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        } else if (lower.endsWith(".csv")) {
            return "text/csv";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }
}
