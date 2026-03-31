package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.util.AppLogger;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-based client for communicating with the Task Executor service.
 *
 * Replaces the previous WebSocket-based implementation. All communication
 * with the Task Executor (port 6000) and the AI Agent (Flask) now uses
 * plain HTTP requests — no persistent WebSocket connections needed.
 *
 * Task run updates and completion notifications are received via the
 * {@link com.agent.servlet.task.TaskUpdateWebhookServlet} HTTP webhook.
 */
public class TaskExecutorClient {

    private static final Logger log = AppLogger.get(TaskExecutorClient.class);

    private static TaskExecutorClient instance;

    private final String executorBaseUrl;

    // ── Singleton Access ──

    public static synchronized TaskExecutorClient getInstance() {
        if (instance == null) {
            instance = new TaskExecutorClient();
        }
        return instance;
    }

    private TaskExecutorClient() {
        // Derive HTTP base URL from the configured executor URL
        // Handles both legacy ws:// URLs and modern http(s):// URLs
        String raw = AppConfig.TASK_EXECUTOR_WS_URL;
        if (raw.startsWith("ws://") || raw.startsWith("wss://")) {
            raw = raw.replace("ws://", "http://")
                     .replace("wss://", "https://")
                     .replace("/ws", "");
        }
        // Strip trailing slash if present
        this.executorBaseUrl = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        log.info("TASK_EXECUTOR HTTP client initialized | baseUrl={}", executorBaseUrl);
    }

    // ── Public API ──

    /**
     * Send a cancel command to the Task Executor via HTTP POST.
     *
     * @param taskId the task to cancel
     * @return true if the executor acknowledged the cancel, false otherwise
     */
    public boolean cancelTask(String taskId) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "cancel_task");
            payload.addProperty("task_id", taskId);

            String url = executorBaseUrl + "/cancel/" + taskId;
            int status = postJson(url, payload.toString());

            log.info("TASK_EXECUTOR cancel request | taskId={} | httpStatus={}", taskId, status);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.warn("TASK_EXECUTOR cancel request failed | taskId={} | error={}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * Send an arbitrary JSON command to the Task Executor via HTTP POST.
     *
     * @param path    relative path (e.g. "/execute")
     * @param command JSON payload
     * @return HTTP response body, or null on failure
     */
    public String send(String path, JsonObject command) throws IOException {
        String url = executorBaseUrl + path;
        return postJsonWithResponse(url, command.toString());
    }

    /**
     * Check if the Task Executor service is reachable.
     */
    public boolean isConnected() {
        try {
            URL url = new URL(executorBaseUrl + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3_000);
            conn.setReadTimeout(3_000);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the derived HTTP base URL of the Task Executor.
     */
    public String getBaseUrl() {
        return executorBaseUrl;
    }

    // ── Internal HTTP helpers ──

    private int postJson(String urlStr, String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            return conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String postJsonWithResponse(String urlStr, String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String apiKey = AppConfig.get("AI_AGENT_API_KEY", "");
            if (!apiKey.isBlank()) {
                conn.setRequestProperty("X-API-Key", apiKey);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } else {
                throw new IOException("HTTP " + status + " from Task Executor: " + urlStr);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
