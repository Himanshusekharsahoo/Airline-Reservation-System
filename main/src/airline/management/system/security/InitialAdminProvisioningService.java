package airline.management.system.security;

import airline.management.system.DBConnection;
import airline.management.system.PasswordUtils;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.tenant.TenantContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Secure Initial Administrator Provisioning Service for AeroDesk.
 *
 * <p>Enforces a fail-closed, atomic first-admin provisioning flow when no eligible
 * administrator account exists in the database. Protects against race conditions
 * via transactional verification and advisory locking.</p>
 */
public final class InitialAdminProvisioningService {

    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final String ADVISORY_LOCK_NAME = "aerodesk_initial_admin_setup";

    public enum ProvisioningStatus {
        SUCCESS,
        ALREADY_PROVISIONED,
        INVALID_USERNAME,
        INVALID_PASSWORD,
        PASSWORD_MISMATCH,
        INVALID_ROLE,
        DATABASE_ERROR
    }

    public record ProvisioningResult(
        ProvisioningStatus status,
        Long userId,
        String username,
        Role role,
        String message
    ) {
        public boolean isSuccess() {
            return status == ProvisioningStatus.SUCCESS;
        }
    }

    private InitialAdminProvisioningService() {}

    /**
     * Checks if at least one active administrator already exists in the configured database.
     */
    public static boolean hasActiveAdministrator() throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return hasActiveAdministrator(conn);
        }
    }

    /**
     * Checks if at least one active administrator exists on the provided connection.
     */
    public static boolean hasActiveAdministrator(Connection conn) throws SQLException {
        return countActiveAdministrators(conn) > 0;
    }

    /**
     * Counts active administrator accounts (SUPERADMIN, AIRLINEADMIN, ADMIN).
     */
    public static int countActiveAdministrators(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM app_users WHERE active = TRUE AND role IN ('SUPERADMIN', 'AIRLINEADMIN', 'ADMIN')";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    /**
     * Securely provisions the first administrator account if and only if no active administrator exists.
     * Plaintext password arrays are securely wiped with '\0' upon completion.
     */
    public static ProvisioningResult provisionInitialAdministrator(
            String username, char[] password, char[] confirmPassword, Role role) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return provisionInitialAdministrator(conn, username, password, confirmPassword, role);
        }
    }

    /**
     * Transactional implementation accepting a live Connection.
     */
    public static ProvisioningResult provisionInitialAdministrator(
            Connection conn, String username, char[] password, char[] confirmPassword, Role role) throws SQLException {

        // 1. Validation: Username
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            wipe(password);
            wipe(confirmPassword);
            return new ProvisioningResult(
                ProvisioningStatus.INVALID_USERNAME, null, username, role,
                "Username must be 3-50 characters long and contain only letters, numbers, dots, underscores, or hyphens."
            );
        }
        String cleanUsername = username.trim();

        // 2. Validation: Password length
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            wipe(password);
            wipe(confirmPassword);
            return new ProvisioningResult(
                ProvisioningStatus.INVALID_PASSWORD, null, cleanUsername, role,
                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long."
            );
        }

        // 3. Validation: Password confirmation match
        if (confirmPassword == null || !Arrays.equals(password, confirmPassword)) {
            wipe(password);
            wipe(confirmPassword);
            return new ProvisioningResult(
                ProvisioningStatus.PASSWORD_MISMATCH, null, cleanUsername, role,
                "Passwords do not match."
            );
        }

        // 4. Validation: Role (Must be SUPERADMIN or AIRLINEADMIN)
        Role targetRole = (role != null) ? role : Role.SUPERADMIN;
        if (targetRole != Role.SUPERADMIN && targetRole != Role.AIRLINEADMIN) {
            wipe(password);
            wipe(confirmPassword);
            return new ProvisioningResult(
                ProvisioningStatus.INVALID_ROLE, null, cleanUsername, targetRole,
                "Initial administrator role must be either SUPERADMIN or AIRLINEADMIN."
            );
        }

        // 5. Transactional Execution with Safe Concurrency Guard
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);

        boolean lockAcquired = false;
        try {
            lockAcquired = acquireAdvisoryLock(conn, ADVISORY_LOCK_NAME, 5);
            if (!lockAcquired) {
                conn.rollback();
                return new ProvisioningResult(
                    ProvisioningStatus.DATABASE_ERROR, null, cleanUsername, targetRole,
                    "Unable to acquire exclusive provisioning lock. Another setup process may be in progress. Please retry."
                );
            }

            // Invariant check: Fail closed if any administrator already exists
            if (hasActiveAdministrator(conn)) {
                conn.rollback();
                return new ProvisioningResult(
                    ProvisioningStatus.ALREADY_PROVISIONED, null, cleanUsername, targetRole,
                    "An active administrator account already exists. Existing accounts were preserved."
                );
            }

            // Check if username is already taken by any inactive user
            try (PreparedStatement checkUser = conn.prepareStatement(
                    "SELECT id FROM app_users WHERE username = ?")) {
                checkUser.setString(1, cleanUsername);
                try (ResultSet rs = checkUser.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return new ProvisioningResult(
                            ProvisioningStatus.INVALID_USERNAME, null, cleanUsername, targetRole,
                            "Username '" + cleanUsername + "' is already in use."
                        );
                    }
                }
            }

            // Hash password using PasswordUtils (PBKDF2WithHmacSHA256, 120,000 iterations)
            String passwordHash = PasswordUtils.hash(password);

            // Insert initial administrator into app_users
            long newUserId;
            try (PreparedStatement insertUser = conn.prepareStatement(
                    "INSERT INTO app_users (username, password_hash, role, active) VALUES (?, ?, ?, TRUE)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertUser.setString(1, cleanUsername);
                insertUser.setString(2, passwordHash);
                insertUser.setString(3, targetRole.name());
                insertUser.executeUpdate();
                try (ResultSet keys = insertUser.getGeneratedKeys()) {
                    if (!keys.next()) {
                        conn.rollback();
                        throw new SQLException("Failed to retrieve generated ID for initial administrator.");
                    }
                    newUserId = keys.getLong(1);
                }
            }

            // Map user_roles if roles table exists
            assignUserRoleIfSupported(conn, newUserId, targetRole);

            // Commit transaction
            conn.commit();

            // Record audit log entry
            try {
                AuditService.record(
                    AuditAction.STAFF_CREATED, "STAFF", String.valueOf(newUserId),
                    "Initial administrator [" + cleanUsername + "] provisioned securely with role [" + targetRole.name() + "]"
                );
            } catch (Exception ignored) {
                // Non-critical audit logging failure must not roll back successful setup
            }

            return new ProvisioningResult(
                ProvisioningStatus.SUCCESS, newUserId, cleanUsername, targetRole,
                "Initial administrator account [" + cleanUsername + "] provisioned successfully."
            );

        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                if (lockAcquired) {
                    releaseAdvisoryLock(conn, ADVISORY_LOCK_NAME);
                }
                conn.setAutoCommit(originalAutoCommit);
            } catch (Exception cleanupEx) {
                // Connection cleanup failures must not mask primary result or skip memory wiping
            } finally {
                wipe(password);
                wipe(confirmPassword);
            }
        }
    }

    private static void assignUserRoleIfSupported(Connection conn, long userId, Role role) {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SHOW TABLES LIKE 'user_roles'")) {
            if (rs.next()) {
                long orgId = TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId();
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT IGNORE INTO user_roles (user_id, role_id, organization_id) " +
                        "SELECT ?, id, ? FROM roles WHERE name = ?")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, orgId);
                    ps.setString(3, role.name());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
            // Optional role mapping gracefully ignored in minimal schemas
        }
    }

    private static boolean acquireAdvisoryLock(Connection conn, String lockName, int timeoutSeconds) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, lockName);
            ps.setInt(2, timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static void releaseAdvisoryLock(Connection conn, String lockName) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName);
            ps.execute();
        } catch (SQLException ignored) {
        }
    }

    public static void wipe(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }

    /**
     * Interactive terminal setup runner for headless / command-line environments.
     */
    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("  AeroDesk Initial Administrator Setup Utility   ");
        System.out.println("=================================================");

        int exitCode = 0;
        char[] pass = null;
        char[] confirm = null;
        try {
            if (hasActiveAdministrator()) {
                System.out.println("Status: An active administrator account is already configured. Setup is disabled.");
                return;
            }

            java.io.Console console = System.console();
            if (console == null) {
                System.err.println("Interactive console unavailable. Please run this utility in an interactive terminal or through the GUI.");
                exitCode = 1;
                return;
            }

            String username = console.readLine("Enter administrator username (3-50 alphanumeric characters): ");
            pass = console.readPassword("Enter password (minimum 8 characters): ");
            confirm = console.readPassword("Confirm password: ");

            ProvisioningResult result = provisionInitialAdministrator(username, pass, confirm, Role.SUPERADMIN);
            if (result.isSuccess()) {
                System.out.println("SUCCESS: " + result.message());
                exitCode = 0;
            } else {
                System.err.println("FAILED: " + result.message());
                exitCode = 1;
            }
        } catch (Exception e) {
            System.err.println("ERROR: Administrator setup failed due to a database or environment error. Please verify database connectivity and check system logs.");
            exitCode = 1;
        } finally {
            wipe(pass);
            wipe(confirm);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
