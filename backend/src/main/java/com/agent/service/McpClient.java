package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.model.ChatMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client for communicating with the MCP Server (Port 5000).
 * Sends chat messages and reads SSE (Server-Sent Events) stream responses.
 */
public class McpClient {

    private static final String MCP_URL = AppConfig.FLASK_AGENT_URL;

    /**
     * Callback interface for SSE events received from MCP.
     */
    public interface SseCallback {
        void onEvent(String eventType, String eventData) throws IOException;
    }

    /**
     * Send a chat message to the MCP Server and stream the SSE response.
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

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("session_id", sessionId);
        requestBody.addProperty("user_id", userId);
        requestBody.addProperty("message", message);
        requestBody.add("history", buildHistoryArray(history));

        // Make HTTP POST request
        URL url = new URL(MCP_URL + "/agent/chat");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);       // 10 second connect timeout
        conn.setReadTimeout(300000);         // 5 minute read timeout for long AI responses

        // Send request body
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
        }

        // Read SSE stream
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            String currentEvent = "";

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    callback.onEvent(currentEvent, data);

                    // Stop reading after terminal events
                    if ("done".equals(currentEvent) || "error".equals(currentEvent)) {
                        break;
                    }
                }
                // Empty lines are SSE event separators — skip them
            }
        } finally {
            conn.disconnect();
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
