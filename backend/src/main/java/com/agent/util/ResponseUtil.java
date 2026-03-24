package com.agent.util;

import com.google.gson.JsonObject;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Standard JSON response utilities.
 * All API endpoints use these methods to ensure consistent response format:
 *   Success: { "success": true, "data": { ... } }
 *   Error:   { "success": false, "error": "..." }
 */
public class ResponseUtil {

    /**
     * Send a success response with HTTP 200.
     */
    public static void sendSuccess(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(200);

        JsonObject json = new JsonObject();
        json.addProperty("success", true);
        json.add("data", JsonUtil.getGson().toJsonTree(data));

        response.getWriter().write(json.toString());
    }

    /**
     * Send a success response with HTTP 201 (Created).
     */
    public static void sendCreated(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(201);

        JsonObject json = new JsonObject();
        json.addProperty("success", true);
        json.add("data", JsonUtil.getGson().toJsonTree(data));

        response.getWriter().write(json.toString());
    }

    /**
     * Send an error response with the given HTTP status code.
     */
    public static void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);

        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("error", message);

        response.getWriter().write(json.toString());
    }
}
