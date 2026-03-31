package com.agent.servlet.chat;

import com.agent.config.AppConfig;
import com.agent.dao.MessageDao;

import com.agent.dao.SessionDao;
import com.agent.model.ChatMessage;
import com.agent.model.ChatSession;
import com.agent.service.FlaskAgentClient;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
/**
 * POST /api/chat/{sessionId}/send — Send a chat message and stream AI response via SSE.
 *
 * This is the MOST COMPLEX servlet. It:
 * 1. Saves the user message to the database
 * 2. Fetches conversation history for AI context
 * 3. Starts an async context for SSE streaming
 * 4. Forwards the message to the Flask Agent Server
 * 5. Proxies the Flask Agent SSE stream back to the frontend in real-time
 * 6. Saves the complete assistant response to the database
 * 7. Updates the session title if it's the first message
 */
@WebServlet(urlPatterns = "/api/chat/*", asyncSupported = true)
public class ChatServlet extends HttpServlet {

    private static final Logger log = AppLogger.get(ChatServlet.class);
    private static final ExecutorService CHAT_STREAM_EXECUTOR = new ThreadPoolExecutor(
            parseInt("CHAT_STREAM_EXECUTOR_CORE_THREADS", 16),
            parseInt("CHAT_STREAM_EXECUTOR_MAX_THREADS", 64),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(parseInt("CHAT_STREAM_EXECUTOR_QUEUE_SIZE", 200)),
            r -> {
                Thread t = new Thread(r, "chat-stream");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            // 1. Extract session ID and user ID
            String pathInfo = request.getPathInfo(); // "/{sessionId}/send"
            if (pathInfo == null || !pathInfo.contains("/send")) {
                log.warn("CHAT — invalid endpoint path | pathInfo={}", pathInfo);
                ResponseUtil.sendError(response, 400, "Invalid chat endpoint. Use /api/chat/{sessionId}/send");
                return;
            }

            String[] pathParts = pathInfo.split("/");
            if (pathParts.length < 3) {
                log.warn("CHAT — missing sessionId in path | pathInfo={}", pathInfo);
                ResponseUtil.sendError(response, 400, "Session ID is required");
                return;
            }

            long sessionId;
            try {
                sessionId = Long.parseLong(pathParts[1]);
            } catch (NumberFormatException e) {
                log.warn("CHAT — non-numeric sessionId | raw={}", pathParts[1]);
                ResponseUtil.sendError(response, 400, "Invalid session ID format");
                return;
            }

            Object userIdAttr = request.getAttribute("userId");
            if (userIdAttr == null) {
                log.warn("CHAT — userId attribute missing from request (auth filter may have failed) | sessionId={}", sessionId);
                ResponseUtil.sendError(response, 401, "Unauthorised: missing user identity");
                return;
            }
            long userId = ((Number) userIdAttr).longValue();
            log.info("CHAT START | userId={} | sessionId={}", userId, sessionId);

            // 2. Verify session belongs to user
            if (!SessionDao.belongsToUser(sessionId, userId)) {
                log.warn("CHAT — session not found or unauthorised | userId={} | sessionId={}",
                        userId, sessionId);
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            // 3. Parse message from request body
            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                log.warn("CHAT — empty request body | userId={} | sessionId={}", userId, sessionId);
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                log.warn("CHAT — invalid JSON body | userId={} | sessionId={}", userId, sessionId);
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            if (!json.has("message") || json.get("message").isJsonNull()
                    || json.get("message").getAsString().trim().isEmpty()) {
                log.warn("CHAT — empty message field | userId={} | sessionId={}", userId, sessionId);
                ResponseUtil.sendError(response, 400, "Message is required");
                return;
            }

            String message = json.get("message").getAsString().trim();
            log.debug("CHAT MESSAGE | userId={} | sessionId={} | msgLen={}", userId, sessionId, message.length());

            // 4. Save user message to DB
            MessageDao.create(sessionId, "user", message);
            log.debug("CHAT — user message saved | sessionId={}", sessionId);

            // 5. Get conversation history for AI context
            List<ChatMessage> history = MessageDao.findBySessionId(sessionId);
            log.debug("CHAT — history loaded | sessionId={} | historySize={}", sessionId, history.size());

            // 6. Start async context for SSE streaming
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(300000); // 5 minute timeout

            // 7. Set SSE headers
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
            // 8. Forward to Flask Agent Server using a bounded executor
            Runnable streamTask = () -> {
                long streamStart = System.currentTimeMillis();
                AtomicBoolean clientDisconnected = new AtomicBoolean(false);
                log.info("CHAT STREAM START | userId={} | sessionId={} | thread={}",
                        userId, sessionId, Thread.currentThread().getName());

                try {
                    PrintWriter writer = response.getWriter();
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.flushBuffer();
                    StringBuilder fullResponse = new StringBuilder();
                    // Capture the done payload for schedule_task detection
                    final JsonObject[] donePayloadHolder = new JsonObject[1];

                    FlaskAgentClient.streamChat(userId, sessionId, message, history,
                            (eventType, eventData) -> {
                                if (!"done".equals(eventType)) {
                                    synchronized (writer) {
                                        writer.write("event: " + eventType + "\n");
                                        writer.write("data: " + eventData + "\n\n");
                                        flushWriter(writer, clientDisconnected);
                                    }
                                }

                                if ("token".equals(eventType)) {
                                    try {
                                        JsonObject tokenData = JsonUtil.parse(eventData);
                                        if (tokenData.has("content")) {
                                            fullResponse.append(tokenData.get("content").getAsString());
                                        }
                                    } catch (Exception e) {
                                        fullResponse.append(eventData);
                                    }
                                } else if ("done".equals(eventType)) {
                                    try {
                                        JsonObject donePayload = JsonUtil.parse(eventData);
                                        donePayloadHolder[0] = donePayload;
                                        if (fullResponse.length() == 0 && donePayload.has("full_response")) {
                                            fullResponse.append(donePayload.get("full_response").getAsString());
                                        }
                                    } catch (Exception e) {
                                        // Ignore parsing errors on done payload
                                    }
                                }
                            }
                    );

                    // 9. Save complete assistant message to DB
                    long messageId = MessageDao.create(sessionId, "assistant", fullResponse.toString());
                    log.debug("CHAT — assistant message saved | sessionId={} | messageId={} | responseLen={}",
                            sessionId, messageId, fullResponse.length());

                    // Note: Task persistence is now handled by the MCP team on the agent side.
                    // No local DB writes for scheduled tasks.

                    // 10. Update session title if this is the first exchange
                    ChatSession session = SessionDao.findById(sessionId);
                    String sessionTitle = "New conversation";
                    if (session != null) {
                        if ("New conversation".equals(session.getTitle())) {
                            String newTitle = message.length() > 50
                                    ? message.substring(0, 50) + "..."
                                    : message;
                            SessionDao.updateTitle(sessionId, newTitle);
                            sessionTitle = newTitle;
                            log.debug("CHAT — session title updated | sessionId={} | newTitle={}",
                                    sessionId, newTitle);
                        } else {
                            sessionTitle = session.getTitle();
                        }
                    }

                    // 11. Send our 'done' event with DB metadata
                    JsonObject doneData = new JsonObject();
                    doneData.addProperty("message_id",    messageId);
                    doneData.addProperty("session_title", sessionTitle);

                    synchronized (writer) {
                        writer.write("event: done\n");
                        writer.write("data: " + doneData.toString() + "\n\n");
                        flushWriter(writer, clientDisconnected);
                    }

                    long elapsed = System.currentTimeMillis() - streamStart;
                    log.info("CHAT STREAM DONE | userId={} | sessionId={} | messageId={} | elapsed={}ms",
                            userId, sessionId, messageId, elapsed);

                    safeComplete(asyncContext);

                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - streamStart;
                    if (clientDisconnected.get()) {
                        log.info("CHAT STREAM CLOSED BY CLIENT | userId={} | sessionId={} | elapsed={}ms",
                                userId, sessionId, elapsed);
                        safeComplete(asyncContext);
                        return;
                    }
                    log.error("CHAT STREAM ERROR | userId={} | sessionId={} | elapsed={}ms | error={}",
                            userId, sessionId, elapsed, e.getMessage(), e);
                    try {
                        PrintWriter writer = response.getWriter();
                        JsonObject errorData = new JsonObject();
                        errorData.addProperty("error", "Something went wrong. Please try again.");

                        synchronized (writer) {
                            writer.write("event: error\n");
                            writer.write("data: " + errorData.toString() + "\n\n");
                            flushWriter(writer, clientDisconnected);
                        }
                        safeComplete(asyncContext);
                    } catch (IOException ignored) {
                        safeComplete(asyncContext);
                    }
                }
            };

            try {
                CHAT_STREAM_EXECUTOR.execute(streamTask);
            } catch (RejectedExecutionException e) {
                log.warn("CHAT — executor saturated | userId={} | sessionId={}", userId, sessionId);
                ResponseUtil.sendError(response, 503, "Chat server is busy. Please retry.");
                asyncContext.complete();
            }

        } catch (Exception e) {
            log.error("CHAT — unexpected error | error={}", e.getMessage(), e);
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    private static void flushWriter(PrintWriter writer, AtomicBoolean clientDisconnected) throws IOException {
        writer.flush();
        if (writer.checkError()) {
            clientDisconnected.set(true);
            throw new IOException("Client disconnected during SSE stream");
        }
    }

    private static void safeComplete(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // Context may already be completed after a client disconnect.
        }
    }

    

    @Override
    public void destroy() {
        CHAT_STREAM_EXECUTOR.shutdownNow();
    }

    private static int parseInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(AppConfig.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
