package airline.management.system;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fail-closed test safety guard.
 * Strictly guarantees that test execution and test fixtures only interact with 'project4test'
 * and never touch or fall back to production 'project4'.
 */
public final class TestDatabaseGuard {
    public static final String REQUIRED_TEST_DB = "project4test";
    public static final String FORBIDDEN_PROD_DB = "project4";

    private TestDatabaseGuard() {}

    /**
     * Enforces that the JVM system property DB_NAME is set to project4test.
     * Fails closed immediately if DB_NAME is missing, points to project4, or points to anything other than project4test.
     */
    public static void ensureTestDatabase() {
        String currentDb = System.getProperty("DB_NAME");
        if (currentDb == null || currentDb.isBlank()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: JVM system property 'DB_NAME' is missing! Test execution aborted to prevent production fallback.");
        }

        String trimmed = currentDb.trim();
        if (FORBIDDEN_PROD_DB.equalsIgnoreCase(trimmed)) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Test execution attempted against production database 'project4'! Execution aborted.");
        }

        if (!REQUIRED_TEST_DB.equalsIgnoreCase(trimmed)) {
            throw new IllegalStateException("SAFETY GUARD: Test execution requires database '" + REQUIRED_TEST_DB + "', but DB_NAME was '" + trimmed + "'. Execution aborted.");
        }

        DatabaseConfig cfg = DatabaseConfig.load();
        if (cfg.url().contains("/" + FORBIDDEN_PROD_DB + "?") || cfg.url().contains("/" + FORBIDDEN_PROD_DB + "/")) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Resolved JDBC URL targets production database 'project4'! URL: " + cfg.url());
        }
    }

    /**
     * Validates that an active JDBC connection is strictly connected to the project4test catalog.
     */
    public static void validateConnectionCatalog(Connection conn) throws SQLException {
        ensureTestDatabase();
        if (conn == null || conn.isClosed()) {
            throw new IllegalStateException("SAFETY GUARD: Connection is null or closed.");
        }
        String catalog = conn.getCatalog();
        if (catalog == null || !REQUIRED_TEST_DB.equalsIgnoreCase(catalog.trim())) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Live JDBC connection is connected to catalog '" + catalog + "', expected '" + REQUIRED_TEST_DB + "'! Execution aborted.");
        }
    }
}
