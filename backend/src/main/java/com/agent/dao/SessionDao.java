package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.ChatSession;
import com.agent.util.AppLogger;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SessionDao {

    private static final Logger log = AppLogger.get(SessionDao.class);

    /**
     * Find all sessions for a user, ordered by updated_at DESC.
     */
    public static List<ChatSession> findByUserId(long userId) {
        log.debug("SESSION FIND_BY_USER | userId={}", userId);
        String sql = "SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY updated_at DESC";
        List<ChatSession> sessions = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                sessions.add(mapRow(rs));
            }
            log.debug("SESSION FIND_BY_USER OK | userId={} | count={}", userId, sessions.size());
            return sessions;
        } catch (SQLException e) {
            log.error("SESSION FIND_BY_USER FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error finding sessions by user", e);
        }
    }

    /**
     * Find a session by its ID.
     */
    public static ChatSession findById(long sessionId) {
        log.debug("SESSION FIND_BY_ID | sessionId={}", sessionId);
        String sql = "SELECT * FROM chat_sessions WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            log.debug("SESSION NOT FOUND | sessionId={}", sessionId);
            return null;
        } catch (SQLException e) {
            log.error("SESSION FIND_BY_ID FAILED | sessionId={} | error={}", sessionId, e.getMessage(), e);
            throw new RuntimeException("DB error finding session by id", e);
        }
    }

    /**
     * Create a new chat session. Returns the created session with generated ID.
     *
     * <p>The {@code summary} column is omitted from the INSERT so it defaults
     * to {@code NULL}. Use {@link #updateSummary(long, String)} to populate it.
     */
    public static ChatSession create(long userId, String title) {
        log.info("SESSION CREATE | userId={} | title={}", userId, title);
        String sql = "INSERT INTO chat_sessions (user_id, title) VALUES (?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, userId);
            stmt.setString(2, title != null ? title : "New conversation");
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            long sessionId = keys.getLong(1);
            log.info("SESSION CREATE OK | userId={} | sessionId={}", userId, sessionId);

            return findById(sessionId);
        } catch (SQLException e) {
            log.error("SESSION CREATE FAILED | userId={} | error={}", userId, e.getMessage(), e);
            throw new RuntimeException("DB error creating session", e);
        }
    }

    /**
     * Update the title of a session.
     */
    public static void updateTitle(long sessionId, String title) {
        log.debug("SESSION UPDATE_TITLE | sessionId={} | title={}", sessionId, title);
        String sql = "UPDATE chat_sessions SET title = ? WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, title);
            stmt.setLong(2, sessionId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SESSION UPDATE_TITLE FAILED | sessionId={} | error={}", sessionId, e.getMessage(), e);
            throw new RuntimeException("DB error updating session title", e);
        }
    }

    /**
     * Update (or clear) the AI-generated summary for a session.
     *
     * <p>Called by the chat layer after:
     * <ul>
     *   <li>The first AI response in a session.</li>
     *   <li>Every 10th message exchange.</li>
     *   <li>Session close / switch events.</li>
     * </ul>
     *
     * @param sessionId the target session's primary key
     * @param summary   the new summary text, or {@code null} to clear it
     */
    public static void updateSummary(long sessionId, String summary) {
        log.debug("SESSION UPDATE_SUMMARY | sessionId={}", sessionId);
        String sql = "UPDATE chat_sessions SET summary = ? WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (summary == null) {
                stmt.setNull(1, Types.VARCHAR);
            } else {
                stmt.setString(1, summary);
            }
            stmt.setLong(2, sessionId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("SESSION UPDATE_SUMMARY FAILED | sessionId={} | error={}", sessionId, e.getMessage(), e);
            throw new RuntimeException("DB error updating session summary", e);
        }
    }

    /**
     * Delete a session (cascades to messages).
     */
    public static void delete(long sessionId) {
        log.info("SESSION DELETE | sessionId={}", sessionId);
        String sql = "DELETE FROM chat_sessions WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            stmt.executeUpdate();
            log.info("SESSION DELETE OK | sessionId={}", sessionId);
        } catch (SQLException e) {
            log.error("SESSION DELETE FAILED | sessionId={} | error={}", sessionId, e.getMessage(), e);
            throw new RuntimeException("DB error deleting session", e);
        }
    }

    /**
     * Check if a session belongs to a specific user.
     */
    public static boolean belongsToUser(long sessionId, long userId) {
        ChatSession session = findById(sessionId);
        return session != null && session.getUserId() == userId;
    }

    /**
     * Maps a {@link ResultSet} row to a {@link ChatSession} object.
     *
     * <p>The {@code summary} column is read directly. It will be {@code null}
     * for sessions created before the v1.1 migration or sessions that have not
     * yet had a summary generated.
     */
    private static ChatSession mapRow(ResultSet rs) throws SQLException {
        ChatSession session = new ChatSession();
        session.setId(rs.getLong("id"));
        session.setUserId(rs.getLong("user_id"));
        session.setTitle(rs.getString("title"));
        session.setSummary(rs.getString("summary"));
        session.setCreatedAt(rs.getTimestamp("created_at"));
        session.setUpdatedAt(rs.getTimestamp("updated_at"));
        return session;
    }
}
