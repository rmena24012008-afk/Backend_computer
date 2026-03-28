package com.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Application configuration — resolves values in this priority order:
 *   1. Environment variable  (highest — for production / CI overrides)
 *   2. System property        (useful for Tomcat context params)
 *   3. secrets.properties     (classpath — local development defaults)
 *   4. Hardcoded fallback     (only safe, non-sensitive placeholders)
 *
 * Sensitive credentials are stored in secrets.properties which is
 * .gitignore'd and must NEVER be committed to version control.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ── secrets.properties loaded once at class-init ────────────────────
    private static final Properties SECRETS = new Properties();

    static {
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("secrets.properties")) {
            if (in != null) {
                SECRETS.load(in);
                log.info("secrets.properties loaded from classpath.");
            } else {
                log.warn("secrets.properties NOT found on classpath — "
                        + "falling back to environment variables / defaults.");
            }
        } catch (Exception e) {
            log.error("Failed to load secrets.properties", e);
        }
    }

    // ── Database ────────────────────────────────────────────────────────
    public static final String DB_HOST     = get("DB_HOST",     "MISSING_DB_HOST");
    public static final String DB_PORT     = get("DB_PORT",     "MISSING_DB_PORT");
    public static final String DB_NAME     = get("DB_NAME",     "MISSING_DB_NAME");
    public static final String DB_USER     = get("DB_USER",     "MISSING_DB_USER");
    public static final String DB_PASSWORD = get("DB_PASSWORD", "MISSING_DB_PASSWORD");

    // ── JWT ─────────────────────────────────────────────────────────────
    public static final String JWT_SECRET = get("JWT_SECRET", "MISSING_JWT_SECRET");

    // ── Encryption (AES-256-GCM at-rest) ────────────────────────────────
    // ⚠️  MUST be overridden via ENCRYPTION_SECRET env var in production.
    public static final String ENCRYPTION_SECRET = get("ENCRYPTION_SECRET", "MISSING_ENCRYPTION_SECRET");

    // ── External Services ───────────────────────────────────────────────
    public static final String FLASK_AGENT_URL     = get("FLASK_AGENT_URL",     "http://localhost:5000");
    public static final String TASK_EXECUTOR_WS_URL = get("TASK_EXECUTOR_WS_URL", "ws://localhost:6000/ws");

    // ── CORS ────────────────────────────────────────────────────────────
    public static final String FRONTEND_ORIGIN = get("FRONTEND_ORIGIN", "*");

    /**
     * Resolve a configuration value using the priority chain:
     *   env var → system property → secrets.properties → default.
     *
     * @param key          the configuration key
     * @param defaultValue the fallback (should be a safe placeholder for secrets)
     * @return the resolved value
     */
    public static String get(String key, String defaultValue) {
        // 1. Environment variable (highest priority — production overrides)
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        // 2. System property (useful for Tomcat context params)
        value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        // 3. secrets.properties file (local development defaults)
        value = SECRETS.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        // 4. Hardcoded fallback (non-sensitive only)
        return defaultValue;
    }

    /**
     * Returns true when a config value is effectively unset, including the
     * placeholder defaults used in local fallback paths.
     */
    public static boolean isUnset(String value) {
        return value == null || value.isBlank() || value.startsWith("MISSING_");
    }

    /**
     * Validates that a secret is configured and meets a minimum byte length.
     */
    public static String requireSecret(String key, String value, int minLengthBytes) {
        if (isUnset(value)) {
            throw new IllegalStateException(key + " is not configured");
        }

        int actualLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (actualLength < minLengthBytes) {
            throw new IllegalStateException(
                    key + " must be at least " + minLengthBytes + " bytes; got " + actualLength
            );
        }

        return value;
    }
}
