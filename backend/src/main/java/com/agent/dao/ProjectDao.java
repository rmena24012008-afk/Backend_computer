package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.Project;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for the `projects` table.
 */
public class ProjectDao {

    /**
     * Find all projects for a user, ordered by created_at DESC.
     */
    public static List<Project> findByUserId(long userId) {
        String sql = "SELECT * FROM projects WHERE user_id = ? ORDER BY created_at DESC";
        List<Project> projects = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                projects.add(mapRow(rs));
            }
            return projects;
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding projects by user", e);
        }
    }

    /**
     * Find a project by its project_id (e.g., "proj_xyz789").
     */
    public static Project findByProjectId(String projectId) {
        String sql = "SELECT * FROM projects WHERE project_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding project by projectId", e);
        }
    }

    /**
     * Create a new project record. Returns the auto-generated ID.
     */
    public static long create(long userId, Long sessionId, String projectId, String name,
                              String description, String filesJson) {
        String sql = "INSERT INTO projects (user_id, session_id, project_id, name, description, files) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, userId);
            if (sessionId != null) {
                stmt.setLong(2, sessionId);
            } else {
                stmt.setNull(2, Types.BIGINT);
            }
            stmt.setString(3, projectId);
            stmt.setString(4, name);
            stmt.setString(5, description);
            stmt.setString(6, filesJson);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating project", e);
        }
    }

    /**
     * Check if a project belongs to a specific user.
     */
    public static boolean belongsToUser(String projectId, long userId) {
        Project project = findByProjectId(projectId);
        return project != null && project.getUserId() == userId;
    }

    /**
     * Map a ResultSet row to a Project object.
     */
    private static Project mapRow(ResultSet rs) throws SQLException {
        Project project = new Project();
        project.setId(rs.getLong("id"));
        project.setUserId(rs.getLong("user_id"));
        long sessionId = rs.getLong("session_id");
        project.setSessionId(rs.wasNull() ? null : sessionId);
        project.setProjectId(rs.getString("project_id"));
        project.setName(rs.getString("name"));
        project.setDescription(rs.getString("description"));
        project.setFiles(rs.getString("files"));
        project.setCreatedAt(rs.getTimestamp("created_at"));
        return project;
    }
}
