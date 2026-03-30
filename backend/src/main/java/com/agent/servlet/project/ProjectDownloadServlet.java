package com.agent.servlet.project;

import com.agent.config.AppConfig;
import com.agent.dao.ProjectDao;
import com.agent.model.Project;
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
 * GET /api/project-download/{projectId} — Download a project as a ZIP file.
 *
 * Proxies the ZIP download from the Task Executor (Port 6000) to the frontend.
 * Supports ?token= query param for browser downloads that can't set Authorization headers.
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/projects/X/download.
 * Remapped to /api/project-download/* for correct routing.
 */
@WebServlet("/api/project-download/*")
public class ProjectDownloadServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(ProjectDownloadServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            // Extract projectId from path info: /api/project-download/{projectId}
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                ResponseUtil.sendError(response, 400, "Project ID is required in path");
                return;
            }

            String[] parts = pathInfo.split("/");
            if (parts.length < 2 || parts[1].isBlank()) {
                ResponseUtil.sendError(response, 400, "Invalid path format");
                return;
            }

            String projectId = parts[1];

            // Verify project belongs to user
            if (!ProjectDao.belongsToUser(projectId, userId)) {
                ResponseUtil.sendError(response, 404, "Project not found");
                return;
            }

            // Get project details
            Project project = ProjectDao.findByProjectId(projectId);
            if (project == null) {
                ResponseUtil.sendError(response, 404, "Project not found");
                return;
            }

            // Fetch ZIP from Task Executor
            // GET http://localhost:6000/download-project/{userId}/{projectName}
            String executorBaseUrl = AppConfig.TASK_EXECUTOR_WS_URL
                    .replace("ws://", "http://")
                    .replace("/ws", "");
            String downloadUrl = executorBaseUrl + "/download-project/" + userId + "/" + project.getName();

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
            log.debug("PROJECT_DOWNLOAD — proxying to executor | userId={} | projectName={} | url={}",
                    userId, project.getName(), downloadUrl);

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                log.error("PROJECT_DOWNLOAD — executor returned non-200 | userId={} | projectId={} | status={}",
                        userId, projectId, statusCode);
                ResponseUtil.sendError(response, 502, "Failed to download project from executor");
                return;
            }

            // Set download headers
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + project.getName() + ".zip\"");

            int contentLength = conn.getContentLength();
            if (contentLength > 0) {
                response.setContentLength(contentLength);
            }

            // Stream ZIP bytes to frontend
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

        log.info("PROJECT_DOWNLOAD — served | userId={} | projectId={} | projectName={}",
                    userId, projectId, project.getName());

        } catch (Exception e) {
            log.error("PROJECT_DOWNLOAD — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
