package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.model.ChatMessage;
import com.agent.util.AppLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for communicating with the Python Flask AI Agent Server (Port 5000).
 * Sends chat messages and reads SSE (Server-Sent Events) stream responses.
 */
public class FlaskAgentClient {

    private static final Logger log = AppLogger.get(FlaskAgentClient.class);
    private static final String FLASK_AGENT_URL = AppConfig.FLASK_AGENT_URL;

    /**
     * Callback interface for SSE events received from the Flask Agent.
     */
    public interface SseCallback {
        void onEvent(String eventType, String eventData) throws IOException;
    }

    /**
     * Send a chat message to the Flask Agent Server and stream the SSE response.
     * This method blocks until the stream completes (done/error event).
     *
     * @param userId    the authenticated user's ID
     * @param sessionId the chat session ID
     * @param message   the user's message
     * @param history   conversation history for context
     * @param callback  callback invoked for each SSE event
     * @throws Exception if the HTTP request or stream reading fails
     */
    public static void streamChat(long userId, long sessionId, String message,
                                  List<ChatMessage> history, SseCallback callback) throws Exception {

        log.info("FLASK_AGENT REQUEST | userId={} | sessionId={} | url={}",
                userId, sessionId, FLASK_AGENT_URL + "/agent/chat");

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("session_id", sessionId);
        requestBody.addProperty("user_id",    userId);
        requestBody.addProperty("message",    message);
        requestBody.add("history", buildHistoryArray(history));

        // Make HTTP POST request to Flask Agent
        URL url = new URL(FLASK_AGENT_URL + "/agent/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        // ── New headers for AI Agent authentication ──
        conn.setRequestProperty("X-User-Id", String.valueOf(userId));
        String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
        if (!apiKey.isBlank()) {
            conn.setRequestProperty("X-API-Key", apiKey);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);    // 10 second connect timeout
        conn.setReadTimeout(300000);      // 5 minute read timeout for long AI responses

        long sendStart = System.currentTimeMillis();

        // Send request body
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
        }

        int httpStatus = conn.getResponseCode();
        log.debug("FLASK_AGENT CONNECTED | userId={} | sessionId={} | httpStatus={} | connectMs={}",
                userId, sessionId, httpStatus, System.currentTimeMillis() - sendStart);

        if (httpStatus < 200 || httpStatus >= 300) {
            log.error("FLASK_AGENT ERROR | userId={} | sessionId={} | httpStatus={}",
                    userId, sessionId, httpStatus);
        }

        // Read SSE stream from Flask Agent
        int eventCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            String currentEvent = "";

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    log.trace("FLASK_AGENT SSE | userId={} | sessionId={} | event={}", userId, sessionId, currentEvent);
                    callback.onEvent(currentEvent, data);
                    eventCount++;

                    // Stop reading after terminal events
                    if ("done".equals(currentEvent) || "error".equals(currentEvent)) {
                        break;
                    }
                }
                // Empty lines are SSE event separators — skip them
            }
        } finally {
            conn.disconnect();
            log.info("FLASK_AGENT DONE | userId={} | sessionId={} | events={} | totalMs={}",
                    userId, sessionId, eventCount, System.currentTimeMillis() - sendStart);
        }
    }

    /**
     * Build a JSON array of conversation history from ChatMessage objects.
     */
    private static JsonArray buildHistoryArray(List<ChatMessage> history) {
        JsonArray arr = new JsonArray();
        if (history != null) {
            for (ChatMessage msg : history) {
                JsonObject entry = new JsonObject();
                entry.addProperty("role", msg.getRole());
                entry.addProperty("content", msg.getContent());
                arr.add(entry);
            }
        }
        return arr;
    }
}
