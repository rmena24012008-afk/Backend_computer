package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.ChatMessage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the `chat_messages` table.
 */
public class MessageDao {

    /**
     * Find all messages for a session, ordered by created_at ASC (chronological).
     */
    public static List<ChatMessage> findBySessionId(long sessionId) {
        String sql = "SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC";
        List<ChatMessage> messages = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                messages.add(mapRow(rs));
            }
            return messages;
        } catch (SQLException e) {
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
        String sql = "INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, sessionId);
            stmt.setString(2, role);
            stmt.setString(3, content);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating message", e);
        }
    }

    /**
     * Find a message by its ID.
     */
    public static ChatMessage findById(long messageId) {
        String sql = "SELECT * FROM chat_messages WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, messageId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (SQLException e) {
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
