package com.agent.config;

import com.agent.util.AppLogger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP DataSource singleton — provides connection pooling for MySQL.
 * All DAOs use DatabaseConfig.getConnection() to acquire connections.
 */
public class DatabaseConfig {

    private static final Logger log = AppLogger.get(DatabaseConfig.class);
	
    private static HikariDataSource dataSource;

    /**
     * Returns the singleton HikariCP DataSource, creating it on first call.
     */
    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            log.info("DB INIT — creating HikariCP connection pool...");
            HikariConfig config = new HikariConfig();

            String jdbcUrl = "jdbc:mysql://" +
                    AppConfig.DB_HOST + ":" +
                    AppConfig.DB_PORT + "/" +
                    AppConfig.DB_NAME +
                    "?sslMode=REQUIRED&enabledTLSProtocols=TLSv1.2&serverTimezone=UTC";

            log.info("DB INIT — jdbcUrl={} | user={}", jdbcUrl, AppConfig.DB_USER);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(AppConfig.DB_USER);
            config.setPassword(AppConfig.DB_PASSWORD);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Pool configuration — configurable for Render / container deployments.
            config.setMaximumPoolSize(parseInt("DB_MAX_POOL_SIZE", 30));
            config.setMinimumIdle(parseInt("DB_MIN_IDLE", 10));
            config.setIdleTimeout(parseLong("DB_IDLE_TIMEOUT_MS", 300000L));
            config.setMaxLifetime(parseLong("DB_MAX_LIFETIME_MS", 1500000L));
            config.setConnectionTimeout(parseLong("DB_CONNECTION_TIMEOUT_MS", 30000L));
            config.setLeakDetectionThreshold(parseLong("DB_LEAK_DETECTION_MS", 60000L));
            config.setValidationTimeout(parseLong("DB_VALIDATION_TIMEOUT_MS", 5000L));
            config.setKeepaliveTime(parseLong("DB_KEEPALIVE_TIME_MS", 120000L));
            config.setInitializationFailTimeout(parseLong("DB_INIT_FAIL_TIMEOUT_MS", 1L));
            config.setPoolName("agent-backend-hikari");

            // Performance optimizations
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            try {
                dataSource = new HikariDataSource(config);
                log.info("DB INIT — HikariCP pool created successfully | poolName={} | maxPoolSize={}",
                        config.getPoolName(), config.getMaximumPoolSize());
            } catch (Exception e) {
                log.error("DB INIT — failed to create HikariCP pool | error={}", e.getMessage(), e);
                throw e;
            }
        }
        return dataSource;
    }

    /**
     * Convenience method to get a pooled connection.
     * Always use try-with-resources to ensure proper cleanup.
     */
    public static Connection getConnection() throws SQLException {
        try {
            return getDataSource().getConnection();
        } catch (SQLException e) {
            log.error("DB CONNECTION — failed to obtain connection | error={}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Shutdown the connection pool — call on application shutdown.
     */
    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("DB SHUTDOWN — closing HikariCP connection pool...");
            dataSource.close();
            dataSource = null;
            log.info("DB SHUTDOWN — connection pool closed.");
        }
    }

    private static int parseInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(AppConfig.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String key, long defaultValue) {
        try {
            return Long.parseLong(AppConfig.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
