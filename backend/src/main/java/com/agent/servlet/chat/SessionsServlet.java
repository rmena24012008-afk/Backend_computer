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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/sessions")
public class SessionsServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(SessionsServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            List<ChatSession> sessions = SessionDao.findByUserId(userId);

            List<Map<String, Object>> data = new ArrayList<>();
            for (ChatSession session : sessions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("session_id", session.getId());
                item.put("title",      session.getTitle());
                item.put("summary",    session.getSummary());   // v1.1
                item.put("created_at", session.getCreatedAt());
                item.put("updated_at", session.getUpdatedAt());
                data.add(item);
            }

            log.debug("SESSIONS GET | userId={} | count={}", userId, data.size());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("SESSIONS GET — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = ((Number) request.getAttribute("userId")).longValue();

            String body = new String(request.getInputStream().readAllBytes());
            String title = "New conversation";

            if (body != null && !body.isBlank()) {
                try {
                    JsonObject json = JsonUtil.parse(body);
                    if (json.has("title") && !json.get("title").isJsonNull()) {
                        String t = json.get("title").getAsString().trim();
                        if (!t.isEmpty()) {
                            title = t;
                        }
                    }
                } catch (Exception ignored) {
                    // Invalid JSON — use default title
                }
            }

            ChatSession session = SessionDao.create(userId, title);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("session_id", session.getId());
            data.put("title",      session.getTitle());
            data.put("summary",    session.getSummary());   // v1.1 — null on creation
            data.put("created_at", session.getCreatedAt());

            log.info("SESSIONS POST — created | userId={} | sessionId={} | title={}",
                    userId, session.getId(), session.getTitle());
            ResponseUtil.sendCreated(response, data);

        } catch (Exception e) {
            log.error("SESSIONS POST — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
