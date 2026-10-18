package airline.management.system;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DBConnectionPoolTest {

    private String originalDbName;
    private String originalPoolMax;
    private String originalPoolMin;
    private String originalConnTimeout;

    @BeforeEach
    void setUp() {
        originalDbName = System.getProperty("DB_NAME");
        originalPoolMax = System.getProperty("DB_POOL_MAX_SIZE");
        originalPoolMin = System.getProperty("DB_POOL_MIN_IDLE");
        originalConnTimeout = System.getProperty("DB_POOL_CONN_TIMEOUT");
        DBConnection.shutdown();
    }

    @AfterEach
    void tearDown() {
        restoreProperty("DB_NAME", originalDbName);
        restoreProperty("DB_POOL_MAX_SIZE", originalPoolMax);
        restoreProperty("DB_POOL_MIN_IDLE", originalPoolMin);
        restoreProperty("DB_POOL_CONN_TIMEOUT", originalConnTimeout);
        DBConnection.shutdown();
    }

    private void restoreProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    // ------------------------------------------------------------------------
    // 1. Pool Configuration & Conservative Desktop Defaults
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildHikariConfig applies conservative defaults suitable for desktop Swing application")
    void testDefaultPoolConfiguration() {
        DatabaseConfig dbConfig = DatabaseConfig.load();
        HikariConfig poolConfig = DBConnection.buildHikariConfig(dbConfig);

        assertNotNull(poolConfig);
        assertEquals(DBConnection.DEFAULT_POOL_NAME, poolConfig.getPoolName());
        assertEquals(dbConfig.url(), poolConfig.getJdbcUrl());
        assertEquals(dbConfig.user(), poolConfig.getUsername());
        assertEquals(dbConfig.password(), poolConfig.getPassword());

        assertEquals(DBConnection.DEFAULT_MAX_POOL_SIZE, poolConfig.getMaximumPoolSize());
        assertEquals(DBConnection.DEFAULT_MIN_IDLE, poolConfig.getMinimumIdle());
        assertEquals(DBConnection.DEFAULT_CONNECTION_TIMEOUT_MS, poolConfig.getConnectionTimeout());
        assertEquals(DBConnection.DEFAULT_VALIDATION_TIMEOUT_MS, poolConfig.getValidationTimeout());
        assertEquals(DBConnection.DEFAULT_IDLE_TIMEOUT_MS, poolConfig.getIdleTimeout());
        assertEquals(DBConnection.DEFAULT_MAX_LIFETIME_MS, poolConfig.getMaxLifetime());
        assertEquals(DBConnection.DEFAULT_CONNECTION_TIMEOUT_MS, poolConfig.getInitializationFailTimeout());

        // Check MySQL optimization properties
        assertEquals("true", poolConfig.getDataSourceProperties().getProperty("cachePrepStmts"));
        assertEquals("250", poolConfig.getDataSourceProperties().getProperty("prepStmtCacheSize"));
        assertEquals("2048", poolConfig.getDataSourceProperties().getProperty("prepStmtCacheSqlLimit"));
        assertEquals("true", poolConfig.getDataSourceProperties().getProperty("useServerPrepStmts"));
    }

    @Test
    @DisplayName("buildHikariConfig respects environment and system property overrides")
    void testCustomPoolConfigurationOverrides() {
        System.setProperty("DB_POOL_MAX_SIZE", "15");
        System.setProperty("DB_POOL_MIN_IDLE", "5");
        System.setProperty("DB_POOL_CONN_TIMEOUT", "8000");

        DatabaseConfig dbConfig = DatabaseConfig.load();
        HikariConfig poolConfig = DBConnection.buildHikariConfig(dbConfig);

        assertEquals(15, poolConfig.getMaximumPoolSize());
        assertEquals(5, poolConfig.getMinimumIdle());
        assertEquals(8000L, poolConfig.getConnectionTimeout());
    }

    @Test
    @DisplayName("buildHikariConfig clamps minimumIdle to maximumPoolSize if minIdle exceeds maxPoolSize")
    void testMinIdleClampedToMaxPoolSize() {
        System.setProperty("DB_POOL_MAX_SIZE", "4");
        System.setProperty("DB_POOL_MIN_IDLE", "10");

        DatabaseConfig dbConfig = DatabaseConfig.load();
        HikariConfig poolConfig = DBConnection.buildHikariConfig(dbConfig);

        assertEquals(4, poolConfig.getMaximumPoolSize());
        assertEquals(4, poolConfig.getMinimumIdle(), "minIdle must not exceed maxPoolSize");
    }

    @Test
    @DisplayName("buildHikariConfig rejects null DatabaseConfig")
    void testNullConfigThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            DBConnection.buildHikariConfig(null));
        assertEquals("DatabaseConfig cannot be null", ex.getMessage());
    }

    // ------------------------------------------------------------------------
    // 2. Pool Lifecycle, Idempotent Shutdown & Multi-Pool Prevention
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Pool shutdown is idempotent and safe when called repeatedly or when inactive")
    void testIdempotentPoolShutdown() {
        assertFalse(DBConnection.isPoolActive());
        assertNull(DBConnection.getDataSource());

        // Call shutdown on inactive pool
        assertDoesNotThrow(DBConnection::shutdown);
        assertFalse(DBConnection.isPoolActive());

        // Call resetPool alias
        assertDoesNotThrow(DBConnection::resetPool);
        assertFalse(DBConnection.isPoolActive());

        // Multiple consecutive calls
        assertDoesNotThrow(() -> {
            DBConnection.shutdown();
            DBConnection.shutdown();
            DBConnection.shutdown();
        });
        assertFalse(DBConnection.isPoolActive());
    }

    @Test
    @DisplayName("Test data source injection and clean lifecycle shutdown")
    void testDataSourceInjectionAndShutdown() {
        MockHikariDataSource mockDs = new MockHikariDataSource("jdbc:mysql://localhost:3306/project4test", null);

        DBConnection.setDataSourceForTesting(mockDs);
        assertTrue(DBConnection.isPoolActive());
        assertSame(mockDs, DBConnection.getDataSource());

        // Shutdown closes the pool and clears the reference
        DBConnection.shutdown();
        assertTrue(mockDs.isClosed(), "Data source close() must be invoked upon shutdown");
        assertFalse(DBConnection.isPoolActive());
        assertNull(DBConnection.getDataSource());
    }

    // ------------------------------------------------------------------------
    // 3. Logical Connection Return, Try-With-Resources & Resource Reclamation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Logical pooled connections return to pool and invoke close on try-with-resources completion")
    void testConnectionClosureAndReclamation() throws Exception {
        AtomicBoolean connClosed = new AtomicBoolean(false);

        Connection mockPhysicalConn = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    connClosed.set(true);
                    return null;
                }
                if ("isClosed".equals(method.getName())) {
                    return connClosed.get();
                }
                return null;
            }
        );

        String activeUrl = DatabaseConfig.load().url();
        MockHikariDataSource mockDs = new MockHikariDataSource(activeUrl, mockPhysicalConn);
        DBConnection.setDataSourceForTesting(mockDs);

        // Verify try-with-resources reclamation
        try (Connection conn = DBConnection.getConnection()) {
            assertNotNull(conn);
            assertEquals(1, mockDs.getConnCount.get());
            assertFalse(conn.isClosed());
        }

        assertTrue(connClosed.get(), "Connection close() must be called on exit of try-with-resources block");
    }

    // ------------------------------------------------------------------------
    // 4. Sanitized Error Handling (Never leak credentials)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Invalid configuration or connection failure throws sanitized SQLException without leaking credentials")
    void testSanitizedErrorHandling() {
        // Temporarily set an invalid port that fails DatabaseConfig validation
        System.setProperty("DB_PORT", "99999"); // invalid port > 65535

        SQLException ex = assertThrows(SQLException.class, DBConnection::getConnection);
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("Unable to load database configuration") ||
                   ex.getMessage().contains("Unable to connect to the database"));

        // Guarantee password is never leaked in the exception message
        DatabaseConfig cfg = null;
        try {
            cfg = DatabaseConfig.load();
        } catch (Exception ignored) {}
        if (cfg != null && cfg.password() != null && !cfg.password().isBlank()) {
            assertFalse(ex.getMessage().contains(cfg.password()), "Exception message must never contain raw password");
        }
    }

    @Test
    @DisplayName("Diagnostics report zero active connections when pool is inactive")
    void testDiagnosticsWhenPoolInactive() {
        assertFalse(DBConnection.isPoolActive());
        assertEquals(0, DBConnection.getActiveConnections());
        assertEquals(0, DBConnection.getIdleConnections());
        assertEquals(0, DBConnection.getTotalConnections());
    }

    // ------------------------------------------------------------------------
    // Mock / Subclass Helper
    // ------------------------------------------------------------------------

    private static class MockHikariDataSource extends HikariDataSource {
        private final AtomicBoolean isClosedFlag = new AtomicBoolean(false);
        final AtomicInteger getConnCount = new AtomicInteger(0);
        private final Connection connToReturn;
        private final String customUrl;

        MockHikariDataSource(String customUrl, Connection connToReturn) {
            super();
            this.customUrl = customUrl;
            this.connToReturn = connToReturn;
        }

        @Override
        public boolean isClosed() {
            return isClosedFlag.get();
        }

        @Override
        public void close() {
            isClosedFlag.set(true);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (isClosedFlag.get()) {
                throw new SQLException("DataSource is closed");
            }
            getConnCount.incrementAndGet();
            return connToReturn;
        }

        @Override
        public String getJdbcUrl() {
            return customUrl;
        }
    }
}
