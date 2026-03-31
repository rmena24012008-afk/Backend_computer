package com.agent.servlet.chat;

import com.agent.dao.MessageDao;
import com.agent.dao.SessionDao;
import com.agent.model.ChatMessage;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import org.slf4j.Logger;
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

@WebServlet("/api/messages/*")
public class MessagesServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(MessagesServlet.class);

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

            log.debug("MESSAGES GET | userId={} | sessionId={} | count={}", userId, sessionId, data.size());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("MESSAGES GET — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
