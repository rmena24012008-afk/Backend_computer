package com.agent.servlet.chat;

import com.agent.dao.SessionDao;
import com.agent.model.ChatSession;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
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
 * GET  /api/sessions  — List all chat sessions for the authenticated user.
 * POST /api/sessions  — Create a new chat session.
 *
 * <p>v1.1: Both responses now include the {@code summary} field
 * from the new {@code chat_sessions.summary} column. The field is
 * {@code null} for new sessions or pre-migration sessions until the
 * AI layer generates a summary.
 */
@WebServlet("/api/sessions")
public class SessionsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

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

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

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

            ResponseUtil.sendCreated(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
