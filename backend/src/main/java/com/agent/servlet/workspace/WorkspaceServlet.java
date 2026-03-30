package com.agent.servlet.workspace;

import com.agent.config.AppConfig;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Workspace Explorer Proxy — proxies file-explorer requests to the AI Agent.
 *
 * The backend's only job is: verify JWT → extract user_id → proxy to AI Agent → return response.
 *
 * Frontend sends:   /api/workspace/projects/tic-tac-toe/files
 * Agent receives:   /workspace/{userId}/projects/tic-tac-toe/files
 *
 * Mapped to /api/workspace/* so all sub-paths are handled by pathInfo.
 * Auth is handled by AuthFilter which runs on /api/* and sets userId attribute.
 */
@WebServlet("/api/workspace/*")
public class WorkspaceServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(WorkspaceServlet.class);
    private static final String AI_AGENT = AppConfig.FLASK_AGENT_URL;

    // ── GET ───────────────────────────────────────────────────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        long userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String agentPath = buildAgentPath(req, userId);
        String queryString = req.getQueryString();
        if (queryString != null) agentPath += "?" + queryString;

log.debug("WORKSPACE GET | userId={} | path={}", userId, agentPath);
        proxyRequest(resp, "GET", agentPath, null, userId);
    }

    // ── PUT ───────────────────────────────────────────────────────────────────
    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        long userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String body = readBody(req);
log.debug("WORKSPACE PUT | userId={} | path={}", userId, buildAgentPath(req, userId));
        proxyRequest(resp, "PUT", buildAgentPath(req, userId), body, userId);
    }

    // ── POST ──────────────────────────────────────────────────────────────────
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        long userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String body = readBody(req);
log.debug("WORKSPACE POST | userId={} | path={}", userId, buildAgentPath(req, userId));
        proxyRequest(resp, "POST", buildAgentPath(req, userId), body, userId);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        long userId = getAuthenticatedUserId(req, resp);
        if (userId < 0) return;

        String agentPath = buildAgentPath(req, userId);
        String queryString = req.getQueryString();
        if (queryString != null) agentPath += "?" + queryString;

log.debug("WORKSPACE DELETE | userId={} | path={}", userId, agentPath);
        proxyRequest(resp, "DELETE", agentPath, null, userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Convert frontend path to agent path by injecting user_id.
     *
     * pathInfo gives everything after /api/workspace, e.g. "/projects/tic-tac-toe/files"
     * Result:  /workspace/{userId}/projects/tic-tac-toe/files
     */
    private String buildAgentPath(HttpServletRequest req, long userId) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";
        return "/workspace/" + userId + pathInfo;
    }

    /**
     * Proxy a request to the AI Agent and pipe the response back to the frontend.
     */
    private void proxyRequest(HttpServletResponse resp, String method,
                              String agentPath, String body, long userId) throws IOException {

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(AI_AGENT + agentPath).openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            // ── New: Forward authenticated user ID so AI Agent can enforce access control ──
            conn.setRequestProperty("X-User-Id", String.valueOf(userId));

            // Optional API key forwarding (if configured via env/system/secrets)
            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);

            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = conn.getResponseCode();
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");

            InputStream in = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            if (in != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                     PrintWriter writer = resp.getWriter()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.println(line);
                    }
                }
            }
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            log.error("WORKSPACE — agent unreachable | method={} | path={} | error={}",
                    method, agentPath, e.getMessage());
            ResponseUtil.sendError(resp, 502, "AI Agent unreachable: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Extract the authenticated user ID set by AuthFilter.
     * Returns -1 and writes a 401 response if not authenticated.
     */
    private long getAuthenticatedUserId(HttpServletRequest req,
                                        HttpServletResponse resp) throws IOException {
        Object userIdAttr = req.getAttribute("userId");
        if (userIdAttr == null) {
            ResponseUtil.sendError(resp, 401, "Unauthorized");
            return -1;
        }
        return ((Number) userIdAttr).longValue();
    }

    /**
     * Read the full request body as a string.
     */
    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
