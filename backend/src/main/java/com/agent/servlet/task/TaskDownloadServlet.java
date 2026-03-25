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
 * Proxies the file download from the Task Executor (Port 6000) to the frontend.
 * Supports ?token= query param for browser downloads that can't set Authorization headers.
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/tasks/X/download.
 * Remapped to /api/task-download/* for correct routing.
 */
@WebServlet("/api/task-download/*")
public class TaskDownloadServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(TaskDownloadServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

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

            // Verify task belongs to user
            if (!ScheduledTaskDao.belongsToUser(taskId, userId)) {
                ResponseUtil.sendError(response, 404, "Task not found");
                return;
            }

            // Get task details
            ScheduledTask task = ScheduledTaskDao.findByTaskId(taskId);
            if (task == null || task.getOutputFile() == null || task.getOutputFile().isEmpty()) {
                ResponseUtil.sendError(response, 404, "No output file available for this task");
                return;
            }

            // Fetch file from Task Executor
            // GET http://localhost:6000/download/{userId}/{filePath}
            String executorBaseUrl = AppConfig.TASK_EXECUTOR_WS_URL
                    .replace("ws://", "http://")
                    .replace("/ws", "");
            String downloadUrl = executorBaseUrl + "/download/" + userId + "/" + task.getOutputFile();

            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            // ── Forward authenticated user identity to the agent/executor ──
            conn.setRequestProperty("X-User-Id", String.valueOf(userId));
            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }
            log.debug("TASK_DOWNLOAD — proxying to executor | userId={} | taskId={} | url={}",
                    userId, taskId, downloadUrl);

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                ResponseUtil.sendError(response, 502, "Failed to download file from executor");
                return;
            }

            // Extract filename from output file path
            String outputFile = task.getOutputFile();
            String filename = outputFile.contains("/")
                    ? outputFile.substring(outputFile.lastIndexOf("/") + 1)
                    : outputFile;

            // Set download headers
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

            // Transfer content type from executor if available
            String contentType = conn.getContentType();
            if (contentType != null) {
                response.setContentType(contentType);
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
            } finally {
                conn.disconnect();
            }

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
