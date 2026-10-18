package airline.management.system;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Authoritative database connection and connection pool management for AeroDesk.
 * Backed by a high-performance HikariCP connection pool configured for responsive
 * concurrent SwingWorker operations and strict fail-fast error boundaries.
 */
public final class DBConnection {

    public static final String DEFAULT_POOL_NAME = "AeroDesk-HikariPool";
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5000L;
    public static final long DEFAULT_VALIDATION_TIMEOUT_MS = 3000L;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 300000L;
    public static final long DEFAULT_MAX_LIFETIME_MS = 1800000L;

    private static final Object LOCK = new Object();
    private static volatile HikariDataSource dataSource;
    private static volatile String activePoolUrl;
    private static volatile boolean shutdownHookRegistered = false;

    private DBConnection() { }

    /**
     * Obtains a pooled logical Connection from the active HikariCP pool.
     * Returned connections are managed and close() returns them to the pool.
     */
    public static Connection getConnection() throws SQLException {
        DatabaseConfig config;
        try {
            config = DatabaseConfig.load();
        } catch (Exception ex) {
            throw new SQLException("Unable to load database configuration: " + ex.getMessage(), ex);
        }

        HikariDataSource ds = getOrCreateDataSource(config);
        try {
            return ds.getConnection();
        } catch (SQLException exception) {
            throw new SQLException("Unable to connect to the database. Please verify that MySQL/MariaDB is running and database configuration is correct.", exception);
        }
    }

    /**
     * Lazily acquires or creates the single shared HikariDataSource.
     * Detects configuration/catalog switches and recreates the pool cleanly.
     */
    static HikariDataSource getOrCreateDataSource(DatabaseConfig config) throws SQLException {
        HikariDataSource current = dataSource;
        String targetUrl = config.url();

        if (current != null && !current.isClosed() && targetUrl.equals(activePoolUrl)) {
            return current;
        }

        synchronized (LOCK) {
            current = dataSource;
            if (current != null && !current.isClosed() && targetUrl.equals(activePoolUrl)) {
                return current;
            }

            if (current != null && !current.isClosed()) {
                try {
                    current.close();
                } catch (Exception ignored) {
                }
                dataSource = null;
                activePoolUrl = null;
            }

            try {
                HikariConfig poolConfig = buildHikariConfig(config);
                HikariDataSource newDs = new HikariDataSource(poolConfig);
                dataSource = newDs;
                activePoolUrl = targetUrl;

                registerShutdownHookOnce();

                return newDs;
            } catch (Exception ex) {
                dataSource = null;
                activePoolUrl = null;
                throw new SQLException("Unable to connect to the database. Please verify that MySQL/MariaDB is running and database configuration is correct.", ex);
            }
        }
    }

    /**
     * Constructs a HikariConfig using the loaded DatabaseConfig and desktop-optimized defaults.
     */
    public static HikariConfig buildHikariConfig(DatabaseConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DatabaseConfig cannot be null");
        }

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(config.url());
        poolConfig.setUsername(config.user());
        poolConfig.setPassword(config.password());
        poolConfig.setPoolName(DEFAULT_POOL_NAME);

        int maxPoolSize = getIntConfig("DB_POOL_MAX_SIZE", DEFAULT_MAX_POOL_SIZE);
        int minIdle = getIntConfig("DB_POOL_MIN_IDLE", DEFAULT_MIN_IDLE);
        long connTimeout = getLongConfig("DB_POOL_CONN_TIMEOUT", DEFAULT_CONNECTION_TIMEOUT_MS);
        long valTimeout = getLongConfig("DB_POOL_VALIDATION_TIMEOUT", DEFAULT_VALIDATION_TIMEOUT_MS);
        long idleTimeout = getLongConfig("DB_POOL_IDLE_TIMEOUT", DEFAULT_IDLE_TIMEOUT_MS);
        long maxLifetime = getLongConfig("DB_POOL_MAX_LIFETIME", DEFAULT_MAX_LIFETIME_MS);

        if (minIdle > maxPoolSize) {
            minIdle = maxPoolSize;
        }

        poolConfig.setMaximumPoolSize(maxPoolSize);
        poolConfig.setMinimumIdle(minIdle);
        poolConfig.setConnectionTimeout(connTimeout);
        poolConfig.setValidationTimeout(valTimeout);
        poolConfig.setIdleTimeout(idleTimeout);
        poolConfig.setMaxLifetime(maxLifetime);
        poolConfig.setInitializationFailTimeout(connTimeout);

        // Performance optimizations for MySQL Connector/J
        poolConfig.addDataSourceProperty("cachePrepStmts", "true");
        poolConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        poolConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        poolConfig.addDataSourceProperty("useServerPrepStmts", "true");

        return poolConfig;
    }

    /**
     * Explicit, idempotent pool shutdown method.
     * Safely closes all pooled physical connections and releases resources.
     */
    public static void shutdown() {
        try {
            airline.management.system.audit.AuditService.shutdown();
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            if (dataSource != null) {
                try {
                    if (!dataSource.isClosed()) {
                        dataSource.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    dataSource = null;
                    activePoolUrl = null;
                }
            }
        }
    }

    /**
     * Convenience reset method for test isolation.
     */
    public static void resetPool() {
        shutdown();
    }

    /**
     * Checks whether the connection pool is currently active and open.
     */
    public static boolean isPoolActive() {
        HikariDataSource ds = dataSource;
        return ds != null && !ds.isClosed();
    }

    /**
     * Returns the active HikariDataSource, or null if not yet initialized.
     */
    public static HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Diagnostic: returns current count of active connections leased from the pool.
     */
    public static int getActiveConnections() {
        HikariDataSource ds = dataSource;
        if (ds != null && !ds.isClosed()) {
            HikariPoolMXBean mxBean = ds.getHikariPoolMXBean();
            if (mxBean != null) {
                return mxBean.getActiveConnections();
            }
        }
        return 0;
    }

    /**
     * Diagnostic: returns current count of idle connections waiting in the pool.
     */
    public static int getIdleConnections() {
        HikariDataSource ds = dataSource;
        if (ds != null && !ds.isClosed()) {
            HikariPoolMXBean mxBean = ds.getHikariPoolMXBean();
            if (mxBean != null) {
                return mxBean.getIdleConnections();
            }
        }
        return 0;
    }

    /**
     * Diagnostic: returns total count of connections (active + idle) in the pool.
     */
    public static int getTotalConnections() {
        HikariDataSource ds = dataSource;
        if (ds != null && !ds.isClosed()) {
            HikariPoolMXBean mxBean = ds.getHikariPoolMXBean();
            if (mxBean != null) {
                return mxBean.getTotalConnections();
            }
        }
        return 0;
    }

    /**
     * Allows test fixtures to supply a test or mock HikariDataSource.
     */
    public static void setDataSourceForTesting(HikariDataSource testDs) {
        synchronized (LOCK) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            dataSource = testDs;
            activePoolUrl = (testDs != null && !testDs.isClosed()) ? testDs.getJdbcUrl() : null;
        }
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered) {
            synchronized (LOCK) {
                if (!shutdownHookRegistered) {
                    try {
                        Runtime.getRuntime().addShutdownHook(new Thread(DBConnection::shutdown, "AeroDesk-HikariShutdownHook"));
                        shutdownHookRegistered = true;
                    } catch (IllegalStateException ignored) {
                        // JVM already shutting down
                    }
                }
            }
        }
    }

    private static int getIntConfig(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            val = System.getenv(key);
        }
        if (val != null && !val.isBlank()) {
            try {
                int parsed = Integer.parseInt(val.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static long getLongConfig(String key, long defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            val = System.getenv(key);
        }
        if (val != null && !val.isBlank()) {
            try {
                long parsed = Long.parseLong(val.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
