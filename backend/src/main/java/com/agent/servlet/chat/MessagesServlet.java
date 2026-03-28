package com.agent.servlet.chat;

import com.agent.dao.MessageDao;
import com.agent.dao.SessionDao;
import com.agent.model.ChatMessage;
import com.agent.util.ResponseUtil;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/sessions/{id}/messages — Fetch all messages for a chat session
 *
 * Mapped to /api/messages/* — the session ID is passed as a path parameter.
 * Path format: /api/messages/{sessionId}
 *
 * NOTE: Servlet spec does NOT support mid-path wildcards like /api/sessions/X/messages.
 * The pattern /api/sessions/* is already taken by SessionServlet, so we use a
 * dedicated /api/messages/* path to avoid routing conflicts.
 */
@WebServlet("/api/messages/*")
public class MessagesServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            // Extract sessionId from path info: /api/messages/{sessionId}
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                ResponseUtil.sendError(response, 400, "Session ID is required in path: /api/messages/{sessionId}");
                return;
            }

            String[] parts = pathInfo.split("/");
            if (parts.length < 2) {
                ResponseUtil.sendError(response, 400, "Invalid path format");
                return;
            }

            long sessionId;
            try {
                sessionId = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                ResponseUtil.sendError(response, 400, "Invalid session ID format");
                return;
            }

            // Verify session belongs to user
            if (!SessionDao.belongsToUser(sessionId, userId)) {
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            // Fetch messages (ordered by created_at ASC)
            List<ChatMessage> messages = MessageDao.findBySessionId(sessionId);

            // Build response array
            List<Map<String, Object>> data = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("message_id", msg.getId());
                item.put("role", msg.getRole());
                item.put("content", msg.getContent());
                item.put("created_at", msg.getCreatedAt());
                data.add(item);
            }

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
