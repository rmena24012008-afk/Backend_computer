package com.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP DataSource singleton — provides connection pooling for MySQL.
 * All DAOs use DatabaseConfig.getConnection() to acquire connections.
 */
public class DatabaseConfig {
	
    private static HikariDataSource dataSource;

    /**
     * Returns the singleton HikariCP DataSource, creating it on first call.
     */
    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();

            String jdbcUrl = "jdbc:mysql://" +
                    AppConfig.DB_HOST + ":" +
                    AppConfig.DB_PORT + "/" +
                    AppConfig.DB_NAME +
                    "?sslMode=REQUIRED&enabledTLSProtocols=TLSv1.2&serverTimezone=UTC";

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(AppConfig.DB_USER);
            config.setPassword(AppConfig.DB_PASSWORD);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Pool configuration
            config.setMaximumPoolSize(35);
            config.setMinimumIdle(10);
            config.setIdleTimeout(300000);
            config.setMaxLifetime(1500000);
            config.setConnectionTimeout(10000);

            // Performance optimizations
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /**
     * Convenience method to get a pooled connection.
     * Always use try-with-resources to ensure proper cleanup.
     */
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /**
     * Shutdown the connection pool — call on application shutdown.
     */
    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }
}
