package com.agent.dao;

import com.agent.config.DatabaseConfig;
import com.agent.model.User;
import com.agent.util.AppLogger;
import com.agent.util.JsonUtil;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

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

	private static final Logger log = AppLogger.get(UserDao.class);

	/**
	 * Find a user by email address.
	 */
	public static User findByEmail(String email) {
		log.debug("USER FIND_BY_EMAIL | email={}", email);
		String sql = "SELECT * FROM users WHERE email = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, email);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				log.debug("USER FOUND | email={}", email);
				return mapRow(rs);
			}
			log.debug("USER NOT FOUND | email={}", email);
			return null;
		} catch (SQLException e) {
			log.error("USER FIND_BY_EMAIL FAILED | email={} | error={}", email, e.getMessage(), e);
			throw new RuntimeException("DB error finding user by email", e);
		}
	}

	/**
	 * Find a user by username.
	 */
	public static User findByUsername(String username) {
		log.debug("USER FIND_BY_USERNAME | username={}", username);
		String sql = "SELECT * FROM users WHERE username = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, username);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				log.debug("USER FOUND | username={}", username);
				return mapRow(rs);
			}
			log.debug("USER NOT FOUND | username={}", username);
			return null;
		} catch (SQLException e) {
			log.error("USER FIND_BY_USERNAME FAILED | username={} | error={}", username, e.getMessage(), e);
			throw new RuntimeException("DB error finding user by username", e);
		}
	}

	/**
	 * Find a user by ID.
	 */
	public static User findById(long id) {
		log.debug("USER FIND_BY_ID | id={}", id);
		String sql = "SELECT * FROM users WHERE id = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setLong(1, id);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return mapRow(rs);
			}
			log.debug("USER NOT FOUND | id={}", id);
			return null;
		} catch (SQLException e) {
			log.error("USER FIND_BY_ID FAILED | id={} | error={}", id, e.getMessage(), e);
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
		log.info("USER CREATE | username={} | email={}", username, email);
		String sql = "INSERT INTO users (username, email, password_hash) VALUES (?, ?, ?)";
		try (Connection conn = DatabaseConfig.getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			stmt.setString(1, username);
			stmt.setString(2, email);
			stmt.setString(3, passwordHash);
			stmt.executeUpdate();
			ResultSet keys = stmt.getGeneratedKeys();
			keys.next();
			long userId = keys.getLong(1);
			log.info("USER CREATE OK | userId={} | username={} | email={}", userId, username, email);
			return userId;
		} catch (SQLException e) {
			log.error("USER CREATE FAILED | username={} | email={} | error={}", username, email, e.getMessage(), e);
			throw new RuntimeException("DB error creating user", e);
		}
	}

	/**
	 * Update just the theme column for a user.
	 *
	 * <p>This is a lightweight update intended for the frontend theme-switcher,
	 * so only the dedicated {@code theme} column is written — the
	 * {@code preferences} JSON blob is <em>not</em> touched.
	 *
	 * @param userId the target user's primary key
	 * @param theme  the new theme value (e.g. "light", "dark")
	 * @return {@code true} if exactly one row was updated
	 */
	public static boolean updateTheme(long userId, String theme) {
		log.debug("USER UPDATE_THEME | userId={} | theme={}", userId, theme);
		String sql = "UPDATE users SET theme = ? WHERE id = ?";
		try (Connection conn = DatabaseConfig.getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, theme);
			stmt.setLong(2, userId);
			boolean updated = stmt.executeUpdate() == 1;
			log.debug("USER UPDATE_THEME {} | userId={}", updated ? "OK" : "NO_ROWS", userId);
			return updated;
		} catch (SQLException e) {
			log.error("USER UPDATE_THEME FAILED | userId={} | error={}", userId, e.getMessage(), e);
			throw new RuntimeException("DB error updating user theme", e);
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
		log.debug("USER UPDATE_PREFERENCES | userId={}", userId);
		String sql = "UPDATE users SET preferences = ? WHERE id = ?";
		try (Connection conn = DatabaseConfig.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
			if (preferences == null) {
				stmt.setNull(1, Types.VARCHAR);
			} else {
				stmt.setString(1, JsonUtil.toJson(preferences));
			}
			stmt.setLong(2, userId);
			stmt.executeUpdate();
			log.debug("USER UPDATE_PREFERENCES OK | userId={}", userId);
		} catch (SQLException e) {
			log.error("USER UPDATE_PREFERENCES FAILED | userId={} | error={}", userId, e.getMessage(), e);
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

		// v1.3 — dedicated theme column (may be NULL for pre-migration rows)
		String themeVal = rs.getString("theme");
		user.setTheme(themeVal != null ? themeVal : "light");

		// v1.1 — preferences column (may be NULL for pre-migration rows)
		String prefsJson = rs.getString("preferences");
		if (prefsJson != null && !prefsJson.isBlank()) {
			try {
				user.setPreferences(JsonUtil.parse(prefsJson));
			} catch (Exception e) {
				log.warn("USER mapRow — failed to parse preferences JSON | userId={} | error={}",
						user.getId(), e.getMessage());
			}
		}

		return user;
	}
}
