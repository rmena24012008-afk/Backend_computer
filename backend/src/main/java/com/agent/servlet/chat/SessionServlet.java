package com.agent.servlet.chat;

import com.agent.dao.SessionDao;
import com.agent.model.ChatSession;
import com.agent.util.ResponseUtil;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET    /api/sessions/{id}  — Get a single chat session's detail (including summary).
 * DELETE /api/sessions/{id}  — Delete a specific chat session.
 *
 * URL pattern: /api/sessions/*
 * The session ID is extracted from the path info.
 *
 * <p>v1.1: {@code doGet} is added to return individual session detail
 * including the {@code summary} field.
 */
@WebServlet("/api/sessions/*")
public class SessionServlet extends HttpServlet {

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
