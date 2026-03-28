package com.agent.listener;

import com.agent.config.AppConfig;
import com.agent.config.DatabaseConfig;
import com.agent.monitor.ServerHealthMonitor;
import com.agent.util.AppLogger;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import org.slf4j.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.io.File;

/**
 * Application lifecycle listener.
 * Handles startup and shutdown of application-wide resources:
 *   - Logs app boot with environment summary
 *   - Ensures the log directory exists
 *   - Starts the ServerHealthMonitor for auto-restart on slowdown
 *   - Shuts down the DB connection pool and MySQL cleanup thread gracefully
 */
@WebListener
public class AppLifecycleListener implements ServletContextListener {

    private static final Logger log = AppLogger.get(AppLifecycleListener.class);

    /** Health monitor instance — started on init, stopped on destroy. */
    private ServerHealthMonitor healthMonitor;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        String contextPath = sce.getServletContext().getContextPath();
        if (contextPath == null || "/".equals(contextPath)) {
            contextPath = "";
        }
        if (System.getProperty("HEALTH_CONTEXT_PATH") == null
                && (System.getenv("HEALTH_CONTEXT_PATH") == null
                || System.getenv("HEALTH_CONTEXT_PATH").isBlank())) {
            System.setProperty("HEALTH_CONTEXT_PATH", contextPath);
        }

        // ── Ensure log directory exists ───────────────────────────────────
        String logDir = System.getenv("LOG_DIR");
        if (logDir == null || logDir.isBlank()) {
            String baseDir = System.getProperty("catalina.base",
                    System.getProperty("user.dir", "."));
            logDir = new File(baseDir, "logs").getAbsolutePath();
        }
        File logDirFile = new File(logDir);
        if (!logDirFile.exists()) {
            boolean created = logDirFile.mkdirs();
            if (created) {
                log.info("APP STARTUP — log directory created at: {}", logDirFile.getAbsolutePath());
            }
        }

        // ── Boot banner ───────────────────────────────────────────────────
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║        Agent Backend — STARTING UP               ║");
        log.info("╚══════════════════════════════════════════════════╝");
        log.info("APP STARTUP | flaskAgentUrl={} | frontendOrigin={} | contextPath={} | logDir={}",
                AppConfig.FLASK_AGENT_URL, AppConfig.FRONTEND_ORIGIN, contextPath, logDirFile.getAbsolutePath());
        AppLogger.audit("APP_START | flaskAgentUrl={} | frontendOrigin={} | contextPath={}",
                AppConfig.FLASK_AGENT_URL, AppConfig.FRONTEND_ORIGIN, contextPath);

        // ── Start ServerHealthMonitor for auto-restart on slowdown ────────
        boolean monitorEnabled = Boolean.parseBoolean(
                AppConfig.get("HEALTH_MONITOR_ENABLED", "true"));

        if (monitorEnabled) {
            try {
                healthMonitor = new ServerHealthMonitor();
                healthMonitor.start();
                log.info("APP STARTUP — ServerHealthMonitor started (auto-restart on slowdown enabled).");
            } catch (Exception e) {
                log.error("APP STARTUP — Failed to start ServerHealthMonitor: {}", e.getMessage(), e);
            }
        } else {
            log.info("APP STARTUP — ServerHealthMonitor DISABLED (HEALTH_MONITOR_ENABLED=false).");
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("APP SHUTDOWN — shutting down resources...");
        AppLogger.audit("APP_STOP");

        // ── Stop health monitor first ─────────────────────────────────────
        if (healthMonitor != null) {
            try {
                healthMonitor.stop();
                log.info("APP SHUTDOWN — ServerHealthMonitor stopped.");
            } catch (Exception e) {
                log.warn("APP SHUTDOWN — Error stopping ServerHealthMonitor: {}", e.getMessage());
            }
        }

        DatabaseConfig.shutdown();
        AbandonedConnectionCleanupThread.checkedShutdown();
        deregisterJdbcDrivers();

        log.info("APP SHUTDOWN — complete.");
    }

    /**
     * Tomcat expects webapps to unregister JDBC drivers they loaded so
     * repeated redeploys do not leave classloader leaks behind.
     */
    private void deregisterJdbcDrivers() {
        ClassLoader appClassLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();

        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != appClassLoader) {
                continue;
            }

            try {
                DriverManager.deregisterDriver(driver);
                log.info("APP SHUTDOWN — deregistered JDBC driver: {}", driver.getClass().getName());
            } catch (SQLException e) {
                log.warn("APP SHUTDOWN — failed to deregister JDBC driver {}: {}",
                        driver.getClass().getName(), e.getMessage());
            }
        }
    }
}
