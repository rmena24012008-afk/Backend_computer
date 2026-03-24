package com.agent.servlet.chat;

import com.agent.dao.MessageDao;
import com.agent.dao.SessionDao;
import com.agent.model.ChatMessage;
import com.agent.model.ChatSession;
import com.agent.service.FlaskAgentClient;
import com.agent.util.JsonUtil;
import com.agent.util.ResponseUtil;
import com.google.gson.JsonObject;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
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

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            // 1. Extract session ID and user ID
            String pathInfo = request.getPathInfo(); // "/{sessionId}/send"
            if (pathInfo == null || !pathInfo.contains("/send")) {
                ResponseUtil.sendError(response, 400, "Invalid chat endpoint. Use /api/chat/{sessionId}/send");
                return;
            }

            String[] pathParts = pathInfo.split("/");
            if (pathParts.length < 3) {
                ResponseUtil.sendError(response, 400, "Session ID is required");
                return;
            }

            long sessionId;
            try {
                sessionId = Long.parseLong(pathParts[1]);
            } catch (NumberFormatException e) {
                ResponseUtil.sendError(response, 400, "Invalid session ID format");
                return;
            }

            long userId = (long) request.getAttribute("userId");

            // 2. Verify session belongs to user
            if (!SessionDao.belongsToUser(sessionId, userId)) {
                ResponseUtil.sendError(response, 404, "Session not found");
                return;
            }

            // 3. Parse message from request body
            String body = new String(request.getInputStream().readAllBytes());
            if (body == null || body.isBlank()) {
                ResponseUtil.sendError(response, 400, "Request body is required");
                return;
            }

            JsonObject json;
            try {
                json = JsonUtil.parse(body);
            } catch (Exception e) {
                ResponseUtil.sendError(response, 400, "Invalid JSON body");
                return;
            }

            if (!json.has("message") || json.get("message").isJsonNull()
                    || json.get("message").getAsString().trim().isEmpty()) {
                ResponseUtil.sendError(response, 400, "Message is required");
                return;
            }

            String message = json.get("message").getAsString().trim();

            // 4. Save user message to DB
            MessageDao.create(sessionId, "user", message);

            // 5. Get conversation history for AI context
            List<ChatMessage> history = MessageDao.findBySessionId(sessionId);

            // 6. Start async context for SSE streaming
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(300000); // 5 minute timeout

            // 7. Set SSE headers
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no"); // Disable nginx buffering if proxied
            response.flushBuffer();

            // 8. Forward to Flask Agent Server in a separate thread (non-blocking)
            Thread streamThread = new Thread(() -> {
                try {
                    PrintWriter writer = response.getWriter();
                    StringBuilder fullResponse = new StringBuilder();

                    // Call Flask Agent Server and proxy SSE events to frontend
                    // NOTE: We forward all events EXCEPT 'done' from Flask Agent,
                    // because we send our own 'done' event with DB metadata after saving.
                    FlaskAgentClient.streamChat(userId, sessionId, message, history,
                            (eventType, eventData) -> {
                                // Forward each SSE event to the frontend (skip Flask Agent's done — we send our own)
                                if (!"done".equals(eventType)) {
                                    synchronized (writer) {
                                        writer.write("event: " + eventType + "\n");
                                        writer.write("data: " + eventData + "\n\n");
                                        writer.flush();
                                    }
                                }

                                // Collect tokens to build the full assistant message
                                if ("token".equals(eventType)) {
                                    try {
                                        JsonObject tokenData = JsonUtil.parse(eventData);
                                        if (tokenData.has("content")) {
                                            fullResponse.append(tokenData.get("content").getAsString());
                                        }
                                    } catch (Exception e) {
                                        // If parsing fails, append raw data
                                        fullResponse.append(eventData);
                                    }
                                } else if ("done".equals(eventType) && fullResponse.length() == 0) {
                                    // Fallback: capture full_response from done event if no tokens were received
                                    try {
                                        JsonObject donePayload = JsonUtil.parse(eventData);
                                        if (donePayload.has("full_response")) {
                                            fullResponse.append(donePayload.get("full_response").getAsString());
                                        }
                                    } catch (Exception e) {
                                        // Ignore parsing errors on done payload
                                    }
                                }
                            }
                    );

                    // 9. Save complete assistant message to DB
                    long messageId = MessageDao.create(sessionId, "assistant",
                            fullResponse.toString());

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
                        } else {
                            sessionTitle = session.getTitle();
                        }
                    }

                    // 11. Send our single 'done' event with DB metadata

                    JsonObject doneData = new JsonObject();
                    doneData.addProperty("message_id", messageId);
                    doneData.addProperty("session_title", sessionTitle);

                    synchronized (writer) {
                        writer.write("event: done\n");
                        writer.write("data: " + doneData.toString() + "\n\n");
                        writer.flush();
                    }

                    asyncContext.complete();

                } catch (Exception e) {
                    try {
                        PrintWriter writer = response.getWriter();
                        JsonObject errorData = new JsonObject();
                        errorData.addProperty("error", "Something went wrong. Please try again.");

                        synchronized (writer) {
                            writer.write("event: error\n");
                            writer.write("data: " + errorData.toString() + "\n\n");
                            writer.flush();
                        }
                        asyncContext.complete();
                    } catch (IOException ignored) {
                        try {
                            asyncContext.complete();
                        } catch (Exception alsoIgnored) {
                            // Context may already be completed
                        }
                    }
                }
            });
            streamThread.setName("chat-stream-" + sessionId);
            streamThread.setDaemon(true);
            streamThread.start();

        } catch (Exception e) {
            ResponseUtil.sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
}
