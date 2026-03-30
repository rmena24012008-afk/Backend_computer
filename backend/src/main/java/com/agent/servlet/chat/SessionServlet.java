package com.agent.servlet.chat;

import com.agent.dao.SessionDao;
import com.agent.model.ChatSession;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet("/api/sessions/*")
public class SessionServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(SessionServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            long sessionId = parseSessionId(request, response);
            if (sessionId < 0) return;

            ChatSession session = SessionDao.findById(sessionId);
            if (session == null || session.getUserId() != userId) {
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("session_id", session.getId());
            data.put("title",      session.getTitle());
            data.put("summary",    session.getSummary());   // v1.1
            data.put("created_at", session.getCreatedAt());
            data.put("updated_at", session.getUpdatedAt());

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * PUT /api/sessions/{id} — Update the title (name) of an existing chat session.
     *
     * <p>Request body (JSON):
     * <pre>{ "title": "My New Chat Name" }</pre>
     *
     * <p>Validation rules:
     * <ul>
     *   <li>Title is required and must not be blank.</li>
     *   <li>Title is trimmed; maximum 255 characters.</li>
     *   <li>Session must belong to the authenticated user.</li>
     * </ul>
     *
     * <p>Response (200 OK):
     * <pre>{
     *   "success": true,
     *   "data": {
     *     "session_id": 42,
     *     "title": "My New Chat Name",
     *     "summary": "...",
     *     "created_at": "...",
     *     "updated_at": "..."
     *   }
     * }</pre>
     */
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            long sessionId = parseSessionId(request, response);
            if (sessionId < 0) return;

            // Verify session exists and belongs to the authenticated user
            if (!SessionDao.belongsToUser(sessionId, userId)) {
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            // Parse request body
            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            // Validate title field
            if (!json.has("title") || json.get("title").isJsonNull()) {
                ResponseUtil.sendError(response, 400, "Title is required");
                return;
            }

            String title = json.get("title").getAsString().trim();
            if (title.isEmpty()) {
                ResponseUtil.sendError(response, 400, "Title must not be empty");
                return;
            }

            // Enforce maximum length
            if (title.length() > 255) {
                title = title.substring(0, 255);
            }

            // Persist the new title
            SessionDao.updateTitle(sessionId, title);

            // Fetch the updated session to return fresh data
            ChatSession updated = SessionDao.findById(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("session_id", updated.getId());
            data.put("title",      updated.getTitle());
            data.put("summary",    updated.getSummary());
            data.put("created_at", updated.getCreatedAt());
            data.put("updated_at", updated.getUpdatedAt());

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            long sessionId = parseSessionId(request, response);
            if (sessionId < 0) return;

            if (!SessionDao.belongsToUser(sessionId, userId)) {
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            SessionDao.delete(sessionId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", "Session deleted");

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    private long parseSessionId(HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            ResponseUtil.sendError(response, 400, "Session ID is required");
            return -1;
        }

        String[] parts = pathInfo.split("/");
        if (parts.length < 2) {
            ResponseUtil.sendError(response, 400, "Invalid session path");
            return -1;
        }

        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ResponseUtil.sendError(response, 400, "Invalid session ID format");
            return -1;
        }
    }
}
