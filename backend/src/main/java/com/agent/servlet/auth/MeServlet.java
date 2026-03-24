package com.agent.servlet.auth;

import com.agent.dao.UserDao;
import com.agent.model.User;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/auth/me
 *
 * Returns the currently authenticated user's profile info, including their
 * effective preferences (stored preferences merged on top of application
 * defaults — see {@link PreferencesServlet#getEffectivePreferences(User)}).
 *
 * Requires a valid JWT token; the {@code userId} attribute is set by
 * {@link com.agent.filter.AuthFilter} before this servlet is invoked.
 *
 * <h3>Response shape</h3>
 * <pre>{@code
 * {
 *   "success": true,
 *   "data": {
 *     "user_id": 42,
 *     "username": "john_doe",
 *     "email": "john@example.com",
 *     "preferences": {
 *       "theme": "dark",
 *       "language": "en",
 *       "notifications": { "email": false, "task_complete": true, "task_failed": true },
 *       "editor": { "font_size": 14, "word_wrap": true },
 *       "chat": { "stream_speed": "normal", "show_tool_details": false, "auto_scroll": true },
 *       "default_model": "claude-sonnet-4-20250514",
 *       "timezone": "Asia/Kolkata"
 *     }
 *   }
 * }
 * }</pre>
 */
@WebServlet("/api/auth/me")
public class MeServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            // AuthFilter has already validated JWT and set userId attribute
            long userId = (long) request.getAttribute("userId");

            User user = UserDao.findById(userId);
            if (user == null) {
                ResponseUtil.sendError(response, 404, "User not found");
                return;
            }

            // Merge stored preferences with application defaults — never returns null
            JsonObject effectivePreferences = PreferencesServlet.getEffectivePreferences(user);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id",     user.getId());
            data.put("username",    user.getUsername());
            data.put("email",       user.getEmail());
            data.put("preferences", effectivePreferences);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
