package airline.management.system.database;

import airline.management.system.DBConnection;
import airline.management.system.DatabaseConfig;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Startup Schema Pre-flight Guard for AeroDesk.
 *
 * <p>Validates database connectivity and schema readiness before the login workflow executes SQL
 * statements that assume the application schema exists. Performs strictly read-only inspection
 * against {@code information_schema} metadata without modifying the database or executing
 * automatic migrations.</p>
 */
public final class SchemaPreflightGuard {

    /**
     * Categorization of schema pre-flight readiness states.
     */
    public enum SchemaStatus {
        /** All required tables and essential schema objects are confirmed present. */
        READY,
        /** Database connection could not be established (service offline, network, or config failure). */
        CONNECTION_FAILURE,
        /** Database exists and is reachable, but required tables or schema objects are missing. */
        MISSING_OBJECTS,
        /** An unexpected error occurred while inspecting database schema metadata. */
        UNEXPECTED_ERROR
    }

    /**
     * Immutable outcome of a schema pre-flight inspection.
     */
    public record PreflightResult(
        SchemaStatus status,
        List<String> missingObjects,
        String message,
        Throwable cause
    ) {
        public boolean isReady() {
            return status == SchemaStatus.READY;
        }
    }

    /**
     * Descriptor for an essential column whose existence is verified in a parent table.
     */
    public record EssentialColumn(String table, String column, String migrationSource) {}

    // Complete registry of required tables mapped to their authoritative migration source
    public static final Map<String, String> REQUIRED_TABLES = new LinkedHashMap<>();
    static {
        // Base normalized schema (database/schema.sql)
        REQUIRED_TABLES.put("app_users", "database/schema.sql");
        REQUIRED_TABLES.put("flights", "database/schema.sql");
        REQUIRED_TABLES.put("passengers", "database/schema.sql");
        REQUIRED_TABLES.put("reservations", "database/schema.sql");
        REQUIRED_TABLES.put("payments", "database/schema.sql");
        REQUIRED_TABLES.put("cancellations", "database/schema.sql");

        // Operations, RBAC, fleet, gates, check-in, boarding, audit (database/v2_phase11_operations_schema.sql)
        REQUIRED_TABLES.put("organizations", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("roles", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("permissions", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("role_permissions", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("user_roles", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("aircraft", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("aircraft_seats", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("flight_status_history", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("gates", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("flight_gates", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("checkins", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("boarding_passes", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("boarding_events", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("baggage", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("baggage_events", "database/v2_phase11_operations_schema.sql");
        REQUIRED_TABLES.put("audit_logs", "database/v2_phase11_operations_schema.sql");

        // Property Irregularity Reports & claims management (database/v5_phase16_pir_claims_schema.sql)
        REQUIRED_TABLES.put("pir_reports", "database/v5_phase16_pir_claims_schema.sql");
        REQUIRED_TABLES.put("claim_cases", "database/v5_phase16_pir_claims_schema.sql");
        REQUIRED_TABLES.put("claim_events", "database/v5_phase16_pir_claims_schema.sql");
        REQUIRED_TABLES.put("claim_settlements", "database/v5_phase16_pir_claims_schema.sql");

        // Irregular Operations (IROPS) & service recovery (database/v6_phase17_irops_schema.sql)
        REQUIRED_TABLES.put("flight_disruptions", "database/v6_phase17_irops_schema.sql");
        REQUIRED_TABLES.put("passenger_reaccommodations", "database/v6_phase17_irops_schema.sql");
        REQUIRED_TABLES.put("disruption_vouchers", "database/v6_phase17_irops_schema.sql");
    }

    // Essential schema columns validated when their containing table is present
    public static final List<EssentialColumn> ESSENTIAL_COLUMNS = List.of(
        new EssentialColumn("reservations", "active_seat_number", "database/migration_3b.sql"),
        new EssentialColumn("flights", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("passengers", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("reservations", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("payments", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("cancellations", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("flight_status_history", "organization_id", "database/v3_phase14_multitenant_schema.sql"),
        new EssentialColumn("baggage", "organization_id", "database/v4_phase15_baggage_schema.sql"),
        new EssentialColumn("baggage_events", "organization_id", "database/v4_phase15_baggage_schema.sql")
    );

    // Run from PowerShell at the repository root after creating/selecting the catalog separately.
    public static final List<String> MANUAL_MIGRATION_STEPS = List.of(
        "1. Base tables:            Get-Content -Raw database/schema.sql | mysql -u root -p --database=project4",
        "2. Active seat uniqueness: Get-Content -Raw database/migration_3b.sql | mysql -u root -p --database=project4",
        "3. Operations & RBAC:      Get-Content -Raw database/v2_phase11_operations_schema.sql | mysql -u root -p --database=project4",
        "4. Multi-tenant columns:   Get-Content -Raw database/v3_phase14_multitenant_schema.sql | mysql -u root -p --database=project4",
        "5. Baggage handling:       Get-Content -Raw database/v4_phase15_baggage_schema.sql | mysql -u root -p --database=project4",
        "6. PIR & Claims:           Get-Content -Raw database/v5_phase16_pir_claims_schema.sql | mysql -u root -p --database=project4",
        "7. IROPS & Disruption:     Get-Content -Raw database/v6_phase17_irops_schema.sql | mysql -u root -p --database=project4"
    );

    private static volatile boolean schemaVerified = false;

    private SchemaPreflightGuard() {}

    /**
     * Indicates whether the schema has already been verified as ready in this JVM session.
     */
    public static boolean isSchemaVerified() {
        return schemaVerified;
    }

    /**
     * Marks the schema as verified.
     */
    public static void markVerified() {
        schemaVerified = true;
    }

    /**
     * Resets the verified state, forcing re-verification on the next guard invocation.
     */
    public static void resetVerification() {
        schemaVerified = false;
    }

    /**
     * Performs a read-only metadata check against the configured MySQL database.
     *
     * @return the {@link PreflightResult} indicating readiness, connection failure,
     *         missing schema objects, or an unexpected error.
     */
    public static PreflightResult checkReadiness() {
        DatabaseConfig config = safeLoadConfig();
        String targetCatalog = (config != null) ? config.name() : "project4";

        try (Connection conn = DBConnection.getConnection()) {
            if (conn == null || conn.isClosed()) {
                return new PreflightResult(
                    SchemaStatus.CONNECTION_FAILURE,
                    Collections.emptyList(),
                    "Database connection is null or closed.",
                    null
                );
            }

            String activeCatalog = conn.getCatalog();
            if (activeCatalog == null || activeCatalog.isBlank()) {
                activeCatalog = targetCatalog;
            }

            // 1. Query existing tables from information_schema.TABLES
            Set<String> existingTables = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
                stmt.setString(1, activeCatalog);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tbl = rs.getString("TABLE_NAME");
                        if (tbl != null) {
                            existingTables.add(tbl.trim());
                        }
                    }
                }
            }

            // 2. Query existing columns from information_schema.COLUMNS
            Set<String> existingColumns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?")) {
                stmt.setString(1, activeCatalog);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tbl = rs.getString("TABLE_NAME");
                        String col = rs.getString("COLUMN_NAME");
                        if (tbl != null && col != null) {
                            existingColumns.add(tbl.trim() + "." + col.trim());
                        }
                    }
                }
            }

            // 3. Evaluate required tables
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, String> entry : REQUIRED_TABLES.entrySet()) {
                String table = entry.getKey();
                String source = entry.getValue();
                if (!existingTables.contains(table)) {
                    missing.add("Table '" + table + "' (" + source + ")");
                }
            }

            // 4. Evaluate essential columns (only for tables that are present)
            for (EssentialColumn col : ESSENTIAL_COLUMNS) {
                if (existingTables.contains(col.table())) {
                    String fullCol = col.table() + "." + col.column();
                    if (!existingColumns.contains(fullCol)) {
                        missing.add("Column '" + fullCol + "' (" + col.migrationSource() + ")");
                    }
                }
            }

            if (!missing.isEmpty()) {
                return new PreflightResult(
                    SchemaStatus.MISSING_OBJECTS,
                    Collections.unmodifiableList(missing),
                    "Database '" + activeCatalog + "' is missing " + missing.size() + " required schema object(s).",
                    null
                );
            }

            schemaVerified = true;
            return new PreflightResult(
                SchemaStatus.READY,
                Collections.emptyList(),
                "Schema readiness verified for database '" + activeCatalog + "'. All required tables and schema objects are present.",
                null
            );

        } catch (SQLException ex) {
            String msg = ex.getMessage();
            boolean isConnFail = false;

            if (msg != null && msg.contains("Unable to connect to the database")) {
                isConnFail = true;
            }

            String sqlState = ex.getSQLState();
            if (sqlState != null && (sqlState.startsWith("08") || ("42000".equals(sqlState) && ex.getErrorCode() == 1049))) {
                isConnFail = true;
            }

            Throwable cause = ex.getCause();
            if (cause instanceof SQLException sqlCause) {
                String causeSqlState = sqlCause.getSQLState();
                if (causeSqlState != null && causeSqlState.startsWith("08")) {
                    isConnFail = true;
                }
            }

            if (isConnFail) {
                String host = (config != null) ? config.host() : "localhost";
                String port = (config != null) ? config.port() : "3306";
                return new PreflightResult(
                    SchemaStatus.CONNECTION_FAILURE,
                    Collections.emptyList(),
                    "Unable to connect to MySQL database '" + targetCatalog + "' at " + host + ":" + port + ". " +
                    "Please verify MySQL availability and configuration.",
                    ex
                );
            }

            return new PreflightResult(
                SchemaStatus.UNEXPECTED_ERROR,
                Collections.emptyList(),
                "Unexpected database error during schema readiness pre-flight check: " + sanitize(ex.getMessage()),
                ex
            );
        } catch (Exception ex) {
            return new PreflightResult(
                SchemaStatus.UNEXPECTED_ERROR,
                Collections.emptyList(),
                "Unexpected error during schema readiness pre-flight check: " + sanitize(ex.getMessage()),
                ex
            );
        }
    }

    /**
     * Validates schema readiness and, if not ready, displays an actionable modal error dialog.
     *
     * @param parent parent Swing component or {@code null}
     * @return {@code true} if the schema is fully ready; {@code false} otherwise
     */
    public static boolean checkAndNotify(Component parent) {
        PreflightResult result = checkReadiness();
        if (result.isReady()) {
            return true;
        }
        showDiagnosticDialog(parent, result);
        return false;
    }

    /**
     * Renders a clear, actionable Swing error dialog for schema or connectivity failures.
     * Safely executes on the Swing Event Dispatch Thread (EDT) or writes to standard error
     * in headless environments.
     *
     * @param parent parent Swing component or {@code null}
     * @param result the pre-flight result to display
     */
    public static void showDiagnosticDialog(Component parent, PreflightResult result) {
        if (result == null || result.isReady()) {
            return;
        }

        String title;
        String message;

        switch (result.status()) {
            case CONNECTION_FAILURE -> {
                title = "AeroDesk — Database Connection Error";
                message = formatConnectionFailureMessage(result);
            }
            case MISSING_OBJECTS -> {
                title = "AeroDesk — Database Schema Incomplete";
                message = formatMissingObjectsMessage(result);
            }
            default -> {
                title = "AeroDesk — Schema Verification Error";
                message = formatUnexpectedErrorMessage(result);
            }
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("=== " + title + " ===");
            System.err.println(message);
            return;
        }

        Runnable showDialogRunnable = () -> {
            JTextArea textArea = new JTextArea(message);
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            textArea.setCaretPosition(0);
            textArea.setWrapStyleWord(true);
            textArea.setLineWrap(true);

            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(680, 400));
            scrollPane.setBorder(null);

            JOptionPane.showMessageDialog(
                parent,
                scrollPane,
                title,
                JOptionPane.ERROR_MESSAGE
            );
        };

        if (SwingUtilities.isEventDispatchThread()) {
            showDialogRunnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(showDialogRunnable);
            } catch (Exception ex) {
                System.err.println("=== " + title + " ===");
                System.err.println(message);
            }
        }
    }

    private static String formatConnectionFailureMessage(PreflightResult result) {
        DatabaseConfig config = safeLoadConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("AeroDesk Startup Schema Pre-flight Guard — Database Connection Failure\n\n");
        sb.append("Unable to establish a connection to the MySQL database server.\n\n");
        sb.append("Connection Target:\n");
        if (config != null) {
            sb.append("  • Database: ").append(config.name()).append("\n");
            sb.append("  • Host:     ").append(config.host()).append("\n");
            sb.append("  • Port:     ").append(config.port()).append("\n");
        } else {
            sb.append("  • Database configuration could not be loaded.\n");
        }
        sb.append("\nRecommended Actions:\n");
        sb.append("  1. Verify that the MySQL / MariaDB service is active and running.\n");
        sb.append("  2. Verify that network host, port, and credentials in config.properties\n");
        sb.append("     or environment variables (DB_HOST, DB_PORT, DB_NAME, DB_USER) are correct.\n");
        sb.append("  3. Ensure the target database exists and the configured user has access privileges.\n\n");
        sb.append("Refer to the Configuration section in README.md for connection setup instructions.");
        return sb.toString();
    }

    private static String formatMissingObjectsMessage(PreflightResult result) {
        DatabaseConfig config = safeLoadConfig();
        String dbName = (config != null) ? config.name() : "project4";
        StringBuilder sb = new StringBuilder();
        sb.append("AeroDesk Startup Schema Pre-flight Guard — Database Schema Incomplete\n\n");
        sb.append("The target database '").append(dbName).append("' is missing required schema objects.\n");
        sb.append("The application cannot start until all required tables and columns are created.\n\n");
        sb.append("Missing Schema Objects (").append(result.missingObjects().size()).append(" missing):\n");
        for (String item : result.missingObjects()) {
            sb.append("  • ").append(item).append("\n");
        }
        sb.append("\nAction Required (Manual Migration Instructions):\n");
        sb.append("AeroDesk enforces manual database migrations. Please execute the SQL migration\n");
        sb.append("scripts in order from the repository root using PowerShell and the MySQL CLI.\n");
        sb.append("Create each catalog separately when needed (project4 for local runs; project4test for tests):\n");
        sb.append("  mysql -u root -p -e \"CREATE DATABASE IF NOT EXISTS project4 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci\"\n");
        sb.append("  mysql -u root -p -e \"CREATE DATABASE IF NOT EXISTS project4test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci\"\n");
        sb.append("Use --database=project4 for local runs and --database=project4test only for test setup below.\n\n");
        for (String step : MANUAL_MIGRATION_STEPS) {
            sb.append("  ").append(step).append("\n");
        }
        sb.append("\nFor detailed migration instructions, see 'Schema Application Sequence' in README.md.");
        return sb.toString();
    }

    private static String formatUnexpectedErrorMessage(PreflightResult result) {
        DatabaseConfig config = safeLoadConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("AeroDesk Startup Schema Pre-flight Guard — Schema Inspection Error\n\n");
        sb.append("Schema readiness could not be verified due to an unexpected error:\n");
        sb.append("  ").append(result.message()).append("\n\n");
        if (config != null) {
            sb.append("Target: Database '").append(config.name()).append("' at ")
              .append(config.host()).append(":").append(config.port()).append("\n\n");
        }
        sb.append("Please verify database accessibility and user permissions on information_schema,\n");
        sb.append("then retry starting AeroDesk.");
        return sb.toString();
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "No additional details available.";
        }
        return message.replaceAll("(?i)password=[^&;\\s]*", "password=***")
                      .replaceAll("(?i)user=[^&;\\s]*", "user=***")
                      .trim();
    }

    private static DatabaseConfig safeLoadConfig() {
        try {
            return DatabaseConfig.load();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Standalone CLI entry point for independent schema readiness verification.
     * Exit code 0 indicates readiness; exit code 1 indicates failure.
     */
    public static void main(String[] args) {
        System.out.println("Running AeroDesk Schema Pre-flight Guard...");
        PreflightResult result = checkReadiness();
        if (result.isReady()) {
            System.out.println("PASS: " + result.message());
            System.exit(0);
        } else {
            System.err.println("FAIL [" + result.status() + "]: " + result.message());
            showDiagnosticDialog(null, result);
            System.exit(1);
        }
    }
}
