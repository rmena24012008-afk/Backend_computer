package com.agent.servlet.auth;

import com.agent.dao.UserDao;
import com.agent.model.User;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonElement;
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
 * PUT /api/auth/preferences — Update (deep-merge) the authenticated user's preferences.
 * GET /api/auth/preferences — Retrieve the authenticated user's effective preferences.
 *
 * <p>Preferences are MERGED, not replaced. Sending {"theme":"dark"} only
 * changes the theme key — all other stored keys remain untouched.
 * Nested objects (chat, notifications, editor) are also merged recursively.
 *
 * <p>{@link #DEFAULT_PREFERENCES} defines the application-level fallback for
 * every preference key. Stored values always take priority over defaults.
 */
@WebServlet("/api/auth/preferences")
public class PreferencesServlet extends HttpServlet {

    // ── Application-level defaults ────────────────────────────────────────────

    public static final JsonObject DEFAULT_PREFERENCES = JsonUtil.parse(
        "{" +
        "  \"theme\": \"light\"," +
        "  \"language\": \"en\"," +
        "  \"notifications\": {" +
        "    \"email\": false," +
        "    \"task_complete\": true," +
        "    \"task_failed\": true" +
        "  }," +
        "  \"editor\": {" +
        "    \"font_size\": 14," +
        "    \"word_wrap\": true" +
        "  }," +
        "  \"chat\": {" +
        "    \"stream_speed\": \"normal\"," +
        "    \"show_tool_details\": false," +
        "    \"auto_scroll\": true" +
        "  }," +
        "  \"default_model\": \"claude-sonnet-4-20250514\"," +
        "  \"timezone\": \"UTC\"" +
        "}"
    );

    // ── GET /api/auth/preferences ─────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

            User user = UserDao.findById(userId);
            if (user == null) {
                ResponseUtil.sendError(response, 404, "User not found");
                return;
            }

            JsonObject effective = getEffectivePreferences(user);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("preferences", effective);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    // ── PUT /api/auth/preferences ─────────────────────────────────────────────

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            long userId = (long) request.getAttribute("userId");

            // 1. Parse request body
            String body = new String(request.getInputStream().readAllBytes()).trim();
            if (body.isEmpty()) {
                ResponseUtil.sendError(response, 400, "Request body must be a JSON object");
                return;
            }

            JsonObject incoming;
            try {
                incoming = JsonUtil.parse(body);
            } catch (Exception e) {
                ResponseUtil.sendError(response, 400, "Invalid JSON: " + e.getMessage());
                return;
            }

            // 2. Load current user
            User user = UserDao.findById(userId);
            if (user == null) {
                ResponseUtil.sendError(response, 404, "User not found");
                return;
            }

            // 3. Deep-merge: current effective preferences <- incoming
            JsonObject current = deepCopy(getEffectivePreferences(user));
            JsonObject merged  = deepMerge(current, incoming);

            // 4. Persist
            UserDao.updatePreferences(userId, merged);

            // 5. Respond
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message",     "Preferences updated");
            data.put("preferences", merged);

            ResponseUtil.sendSuccess(response, data);

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    // ── Public static helpers (used by MeServlet and others) ─────────────────

    /**
     * Returns the effective preferences for the given user by merging
     * application defaults with whatever the user has stored.
     *
     * <p>Stored values override defaults. Nested keys are merged recursively.
     *
     * @param user the {@link User} whose preferences should be resolved
     * @return a non-null {@link JsonObject} ready to be sent to the frontend
     */
    public static JsonObject getEffectivePreferences(User user) {
        JsonObject base   = deepCopy(DEFAULT_PREFERENCES);
        JsonObject stored = user.getPreferences();
        if (stored == null || stored.size() == 0) {
            return base;
        }
        return deepMerge(base, stored);
    }

    // ── Private utilities ─────────────────────────────────────────────────────

    /**
     * Recursively merges {@code override} into {@code base} (modifies base in place).
     * If both values for a key are JsonObjects they are merged recursively;
     * otherwise the override value replaces the base value.
     */
    private static JsonObject deepMerge(JsonObject base, JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String      key         = entry.getKey();
            JsonElement overrideVal = entry.getValue();

            if (base.has(key)
                    && base.get(key).isJsonObject()
                    && overrideVal.isJsonObject()) {
                deepMerge(base.getAsJsonObject(key), overrideVal.getAsJsonObject());
            } else {
                base.add(key, overrideVal);
            }
        }
        return base;
    }

    /**
     * Returns a deep copy of a JsonObject via JSON round-trip.
     * Ensures DEFAULT_PREFERENCES is never mutated.
     */
    private static JsonObject deepCopy(JsonObject source) {
        return JsonUtil.parse(JsonUtil.toJson(source));
    }
}
