package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.User;
import com.agent.util.JsonUtil;
import com.google.gson.JsonObject;

import java.sql.*;

/**
 * Data Access Object for the {@code users} table.
 *
 * <p>
 * v1.1 changes:
 * <ul>
 * <li>{@link #mapRow(ResultSet)} reads the new {@code preferences} JSON
 * column.</li>
 * <li>{@link #updatePreferences(long, JsonObject)} persists updated
 * preferences.</li>
 * </ul>
 */
public class UserDao {

	/**
	 * Find a user by email address.
	 */
	public static User findByEmail(String email) {
		String sql = "SELECT * FROM users WHERE email = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, email);
			ResultSet rs = stmt.executeQuery();
			if (rs.next())
				return mapRow(rs);
			return null;
		} catch (SQLException e) {
			e.printStackTrace();
			throw new RuntimeException("DB error finding user by email", e);
		}
	}

	/**
	 * Find a user by username.
	 */
	public static User findByUsername(String username) {
		String sql = "SELECT * FROM users WHERE username = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return mapRow(rs);
			}
			return null;
		} catch (SQLException e) {
			throw new RuntimeException("DB error finding user by username", e);
		}
	}

	/**
	 * Find a user by ID.
	 */
	public static User findById(long id) {
		String sql = "SELECT * FROM users WHERE id = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setLong(1, id);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return mapRow(rs);
			}
			return null;
		} catch (SQLException e) {
			throw new RuntimeException("DB error finding user by id", e);
		}
	}

	/**
	 * Create a new user. Returns the auto-generated user ID.
	 *
	 * <p>
	 * The {@code preferences} column defaults to {@code NULL}. Use
	 * {@link #updatePreferences(long, JsonObject)} to set them later.
	 */
	public static long create(String username, String email, String passwordHash) {
		String sql = "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)";
		try (Connection conn = DatabaseConfig.getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			stmt.setString(1, username);
			stmt.setString(2, email);
			stmt.setString(3, passwordHash);
			stmt.executeUpdate();
			ResultSet keys = stmt.getGeneratedKeys();
			keys.next();
			return keys.getLong(1);
		} catch (SQLException e) {
			throw new RuntimeException("DB error creating user", e);
		}
	}

	/**
	 * Persist a user's preferences JSON object.
	 *
	 * <p>
	 * The entire preferences object is serialized to a JSON string and written to
	 * the {@code preferences} column. Pass {@code null} to reset the column to
	 * {@code NULL}.
	 *
	 * @param userId      the target user's primary key
	 * @param preferences the complete merged preferences object, or {@code null}
	 */
	public static void updatePreferences(long userId, JsonObject preferences) {
		String sql = "UPDATE users SET preferences = ? WHERE id = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			if (preferences == null) {
				stmt.setNull(1, Types.VARCHAR);
			} else {
				stmt.setString(1, JsonUtil.toJson(preferences));
			}
			stmt.setLong(2, userId);
			stmt.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException("DB error updating user preferences", e);
		}
	}

	/**
	 * Maps a {@link ResultSet} row to a {@link User} object.
	 *
	 * <p>
	 * The {@code preferences} column is read as a raw JSON string and parsed to a
	 * {@link JsonObject}. If the column is {@code NULL} (pre-migration users), the
	 * field is left {@code null} on the model.
	 */
	private static User mapRow(ResultSet rs) throws SQLException {
		User user = new User();
		user.setId(rs.getLong("id"));
		user.setUsername(rs.getString("username"));
		user.setEmail(rs.getString("email"));
		user.setPasswordHash(rs.getString("password_hash"));
		user.setCreatedAt(rs.getTimestamp("created_at"));

		// v1.1 — preferences column (may be NULL for pre-migration rows)
		String prefsJson = rs.getString("preferences");
		if (prefsJson != null && !prefsJson.isBlank()) {
			try {
				user.setPreferences(JsonUtil.parse(prefsJson));
			} catch (Exception ignored) {
				// Malformed JSON in DB — treat as no preferences stored
			}
		}

		return user;
	}
}
