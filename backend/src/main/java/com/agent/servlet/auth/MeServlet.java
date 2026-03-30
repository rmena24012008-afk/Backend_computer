package com.agent.servlet.auth;

import com.agent.dao.UserDao;
import com.agent.model.User;
import com.agent.util.AppLogger;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import javax.servlet.ServletException;
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
public class MeServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(MeServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            // AuthFilter has already validated JWT and set userId attribute
            long userId = ((Number) request.getAttribute("userId")).longValue();
            log.debug("ME — fetching profile | userId={}", userId);

            User user = UserDao.findById(userId);
            if (user == null) {
                log.warn("ME — user not found | userId={}", userId);
                ResponseUtil.sendError(response, 404, "User not found");
                return;
            }

            // Merge stored preferences with application defaults — never returns null
            JsonObject effectivePreferences = PreferencesServlet.getEffectivePreferences(user);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id",     user.getId());
            data.put("username",    user.getUsername());
            data.put("email",       user.getEmail());
            data.put("theme",       user.getTheme());
            data.put("preferences", effectivePreferences);

            log.debug("ME — profile served | userId={} | username={}", user.getId(), user.getUsername());
            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            log.error("ME — error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
