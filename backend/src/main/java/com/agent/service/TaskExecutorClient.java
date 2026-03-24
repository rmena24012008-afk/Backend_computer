package com.agent.service;

import com.agent.config.AppConfig;
import com.agent.dao.ScheduledTaskDao;
import com.agent.dao.TaskRunLogDao;
import com.agent.model.ScheduledTask;
import com.agent.util.JsonUtil;
import com.google.gson.JsonObject;
import javax.websocket.*;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Singleton WebSocket client that connects to the Task Executor (Port 6000).
 * Handles:
 *   - Sending commands (cancel task, etc.) and waiting for responses
 *   - Receiving async push notifications (task run updates, task completed)
 *   - Heartbeat ping/pong every 30 seconds
 *   - Auto-reconnect on connection loss
 */
@ClientEndpoint
public class TaskExecutorClient {

    private static TaskExecutorClient instance;
    private Session wsSession;
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private Timer heartbeatTimer;
    private volatile boolean connected = false;

    // ── Singleton Access ──

    /**
     * Get the singleton instance, connecting on first call.
     */
    public static synchronized TaskExecutorClient getInstance() {
        if (instance == null) {
            instance = new TaskExecutorClient();
            instance.connect();
        }
        return instance;
    }

    // ── Connection Management ──

    private void connect() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            String wsUrl = AppConfig.TASK_EXECUTOR_WS_URL;
            container.connectToServer(this, new URI(wsUrl));
        } catch (Exception e) {
            System.err.println("[TaskExecutorClient] Connection failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        this.wsSession = session;
        this.connected = true;
        System.out.println("[TaskExecutorClient] Connected to Task Executor");
        startHeartbeat();
    }

    @OnMessage
    public void onMessage(String message) {
        try {
            JsonObject json = JsonUtil.parse(message);
            String type = json.has("type") ? json.get("type").getAsString() : "";

            // Handle pong (heartbeat response)
            if ("pong".equals(type)) {
                return;
            }

            // Handle request-response pattern (for sendAndWait calls)
            if (json.has("request_id")) {
                String requestId = json.get("request_id").getAsString();
                CompletableFuture<String> future = pendingRequests.remove(requestId);
                if (future != null) {
                    future.complete(message);
                }
            }

            // Handle async push notifications (scheduled task updates)
            switch (type) {
                case "task_run_update":
                    handleTaskRunUpdate(json);
                    break;
                case "task_completed":
                    handleTaskCompleted(json);
                    break;
                default:
                    // Other message types handled by request-response pattern above
                    break;
            }
        } catch (Exception e) {
            System.err.println("[TaskExecutorClient] Error processing message: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        this.connected = false;
        System.out.println("[TaskExecutorClient] Connection closed: " + reason.getReasonPhrase());
        stopHeartbeat();
        scheduleReconnect();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("[TaskExecutorClient] WebSocket error: " + throwable.getMessage());
    }

    // ── Send Methods ──

    /**
     * Send a command and wait for the response (request-response pattern).
     *
     * @param command        the JSON command to send
     * @param timeoutSeconds maximum time to wait for a response
     * @return the response JSON string
     * @throws Exception if the send fails or times out
     */
    public String sendAndWait(JsonObject command, long timeoutSeconds) throws Exception {
        String requestId = "req_" + UUID.randomUUID().toString().substring(0, 8);
        command.addProperty("request_id", requestId);

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        wsSession.getBasicRemote().sendText(command.toString());

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw e;
        }
    }

    /**
     * Send a command without waiting for a response (fire and forget).
     */
    public void send(JsonObject command) throws IOException {
        if (wsSession != null && wsSession.isOpen()) {
            wsSession.getBasicRemote().sendText(command.toString());
        } else {
            throw new IOException("WebSocket not connected to Task Executor");
        }
    }

    /**
     * Check if the WebSocket connection is active.
     */
    public boolean isConnected() {
        return connected && wsSession != null && wsSession.isOpen();
    }

    // ── Async Push Handlers ──

    /**
     * Handle a scheduled task run update (pushed by Task Executor after each run).
     */
    private void handleTaskRunUpdate(JsonObject json) {
        try {
            String taskId = json.get("task_id").getAsString();
            int runNumber = json.get("run_number").getAsInt();
            String status = json.get("status").getAsString();
            String resultData = json.has("result") ? json.get("result").toString() : null;

            // Save run log to DB
            TaskRunLogDao.create(taskId, runNumber, status, resultData, null);

            // Increment completed runs counter
            ScheduledTaskDao.incrementCompletedRuns(taskId);

            // Only promote from "scheduled" to "running" — never overwrite completed/cancelled
            ScheduledTask task = ScheduledTaskDao.findByTaskId(taskId);
            if (task != null && "scheduled".equals(task.getStatus())) {
                ScheduledTaskDao.updateStatus(taskId, "running");
            }

            System.out.println("[TaskExecutorClient] Task run update: " + taskId +
                    " run #" + runNumber + " status=" + status);
        } catch (Exception e) {
            System.err.println("[TaskExecutorClient] Error handling task run update: " + e.getMessage());
        }
    }

    /**
     * Handle task completed notification (all runs finished).
     */
    private void handleTaskCompleted(JsonObject json) {
        try {
            String taskId = json.get("task_id").getAsString();
            String outputFile = json.has("output_file") ? json.get("output_file").getAsString() : null;

            ScheduledTaskDao.updateStatus(taskId, "completed");
            if (outputFile != null) {
                ScheduledTaskDao.updateOutputFile(taskId, outputFile);
            }

            System.out.println("[TaskExecutorClient] Task completed: " + taskId);
        } catch (Exception e) {
            System.err.println("[TaskExecutorClient] Error handling task completed: " + e.getMessage());
        }
    }

    // ── Heartbeat ──

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = new Timer(true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    JsonObject ping = new JsonObject();
                    ping.addProperty("type", "ping");
                    send(ping);
                } catch (IOException e) {
                    System.err.println("[TaskExecutorClient] Heartbeat failed: " + e.getMessage());
                    scheduleReconnect();
                }
            }
        }, 30000, 30000); // Every 30 seconds
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    // ── Reconnection ──

    private void scheduleReconnect() {
        stopHeartbeat();
        new Timer(true).schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("[TaskExecutorClient] Attempting reconnection...");
                connect();
            }
        }, 5000); // Retry after 5 seconds
    }
}
