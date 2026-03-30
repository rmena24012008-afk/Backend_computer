package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.ChatMessage;
import com.agent.util.AppLogger;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageDao {

    private static final Logger log = AppLogger.get(MessageDao.class);

    /**
     * Find all messages for a session, ordered by created_at ASC (chronological).
     */
    public static List<ChatMessage> findBySessionId(long sessionId) {
        log.debug("MESSAGE FIND_BY_SESSION | sessionId={}", sessionId);
        String sql = "SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC";
        List<ChatMessage> messages = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                messages.add(mapRow(rs));
            }
            log.debug("MESSAGE FIND_BY_SESSION OK | sessionId={} | count={}", sessionId, messages.size());
            return messages;
        } catch (SQLException e) {
            log.error("MESSAGE FIND_BY_SESSION FAILED | sessionId={} | error={}", sessionId, e.getMessage(), e);
            throw new RuntimeException("DB error finding messages by session", e);
        }
    }

    /**
     * Create a new message. Returns the auto-generated message ID.
     *
     * @param sessionId   the chat session ID
     * @param role        "user" or "assistant"
     * @param content     the message text
     * @return the generated message ID
     */
    public static long create(long sessionId, String role, String content) {
        log.debug("MESSAGE CREATE | sessionId={} | role={} | contentLen={}", sessionId, role,
                content != null ? content.length() : 0);
        String sql = "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, sessionId);
            stmt.setString(2, role);
            stmt.setString(3, content);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            long messageId = keys.getLong(1);
            log.info("MESSAGE CREATE OK | sessionId={} | role={} | messageId={}", sessionId, role, messageId);
            return messageId;
        } catch (SQLException e) {
            log.error("MESSAGE CREATE FAILED | sessionId={} | role={} | error={}", sessionId, role, e.getMessage(), e);
            throw new RuntimeException("DB error creating message", e);
        }
    }

    /**
     * Find a message by its ID.
     */
    public static ChatMessage findById(long messageId) {
        log.debug("MESSAGE FIND_BY_ID | messageId={}", messageId);
        String sql = "SELECT * FROM chat_messages WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                log.debug("MESSAGE FOUND | messageId={}", messageId);
                return mapRow(rs);
            }
            log.debug("MESSAGE NOT FOUND | messageId={}", messageId);
            return null;
        } catch (SQLException e) {
            log.error("MESSAGE FIND_BY_ID FAILED | messageId={} | error={}", messageId, e.getMessage(), e);
            throw new RuntimeException("DB error finding message by id", e);
        }
    }

    /**
     * Map a ResultSet row to a ChatMessage object.
     */
    private static ChatMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage msg = new ChatMessage();
        msg.setId(rs.getLong("id"));
        msg.setSessionId(rs.getLong("session_id"));
        msg.setRole(rs.getString("role"));
        msg.setContent(rs.getString("content"));
        msg.setCreatedAt(rs.getTimestamp("created_at"));
        return msg;
    }
}
