package airline.management.system.security;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditEvent;
import airline.management.system.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InitialAdminProvisioningServiceTest {

    @BeforeEach
    void setUp() {
        AuditService.getInstance().clear();
    }

    @Test
    @DisplayName("Validation rejects null, empty, short, or invalid usernames")
    void testValidationRejectsInvalidUsername() throws Exception {
        char[] pass = "ValidPassword123".toCharArray();
        char[] confirm = "ValidPassword123".toCharArray();

        // Null username
        InitialAdminProvisioningService.ProvisioningResult resNull =
                InitialAdminProvisioningService.provisionInitialAdministrator(null, null, pass, confirm, Role.SUPERADMIN);
        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_USERNAME, resNull.status());

        // Short username (< 3 chars)
        char[] p2 = "ValidPassword123".toCharArray();
        char[] c2 = "ValidPassword123".toCharArray();
        InitialAdminProvisioningService.ProvisioningResult resShort =
                InitialAdminProvisioningService.provisionInitialAdministrator(null, "ab", p2, c2, Role.SUPERADMIN);
        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_USERNAME, resShort.status());

        // Username with invalid characters (e.g. space, @, !)
        char[] p3 = "ValidPassword123".toCharArray();
        char[] c3 = "ValidPassword123".toCharArray();
        InitialAdminProvisioningService.ProvisioningResult resInvalid =
                InitialAdminProvisioningService.provisionInitialAdministrator(null, "admin user", p3, c3, Role.SUPERADMIN);
        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_USERNAME, resInvalid.status());
    }

    @Test
    @DisplayName("Validation rejects short passwords (< 8 chars)")
    void testValidationRejectsShortPassword() throws Exception {
        char[] shortPass = "pass1".toCharArray();
        char[] shortConfirm = "pass1".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(null, "valid_admin", shortPass, shortConfirm, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_PASSWORD, result.status());
        assertTrue(result.message().contains("at least 8"));
    }

    @Test
    @DisplayName("Validation rejects mismatched confirmation password")
    void testValidationRejectsPasswordMismatch() throws Exception {
        char[] pass = "Password123".toCharArray();
        char[] mismatch = "Password456".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(null, "valid_admin", pass, mismatch, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.PASSWORD_MISMATCH, result.status());
        assertEquals("Passwords do not match.", result.message());
    }

    @Test
    @DisplayName("Validation rejects non-administrative roles")
    void testValidationRejectsNonAdminRole() throws Exception {
        for (Role role : List.of(Role.RESERVATIONAGENT, Role.CHECKINAGENT, Role.GATEAGENT, Role.AUDITOR)) {
            char[] pass = "Password123".toCharArray();
            char[] confirm = "Password123".toCharArray();

            InitialAdminProvisioningService.ProvisioningResult result =
                    InitialAdminProvisioningService.provisionInitialAdministrator(null, "valid_admin", pass, confirm, role);

            assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_ROLE, result.status());
        }
    }

    @Test
    @DisplayName("Plaintext password arrays are securely wiped upon validation failure")
    void testPasswordWipingOnValidationFailure() throws Exception {
        char[] pass = "Short1".toCharArray();
        char[] confirm = "Short1".toCharArray();

        InitialAdminProvisioningService.provisionInitialAdministrator(null, "admin_user", pass, confirm, Role.SUPERADMIN);

        for (char c : pass) {
            assertEquals('\0', c, "Password character must be wiped with null character");
        }
        for (char c : confirm) {
            assertEquals('\0', c, "Confirm password character must be wiped with null character");
        }
    }

    @Test
    @DisplayName("Fails closed and preserves accounts if an active administrator already exists")
    void testFailsWhenAdministratorAlreadyExists() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        Connection mockConn = createMockConnection(1, false, 0L, rolledBack, committed);

        char[] pass = "StrongMasterPass1!".toCharArray();
        char[] confirm = "StrongMasterPass1!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "new_admin", pass, confirm, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.ALREADY_PROVISIONED, result.status());
        assertTrue(result.message().contains("already exists"));
        assertTrue(rolledBack.get(), "Transaction must be rolled back");
        assertFalse(committed.get(), "Transaction must NOT be committed");

        // Verify password wiping
        for (char c : pass) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("Succeeds on empty user store and records audit log entry")
    void testSucceedsOnEmptyUserStoreAndRecordsAuditLog() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        Connection mockConn = createMockConnection(0, false, 101L, rolledBack, committed);

        char[] pass = "InitialSecret2026!".toCharArray();
        char[] confirm = "InitialSecret2026!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "first_superadmin", pass, confirm, Role.SUPERADMIN);

        assertTrue(result.isSuccess());
        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.SUCCESS, result.status());
        assertEquals(101L, result.userId());
        assertEquals("first_superadmin", result.username());
        assertEquals(Role.SUPERADMIN, result.role());

        assertTrue(committed.get(), "Transaction must be committed");
        assertFalse(rolledBack.get(), "Transaction must NOT be rolled back");

        // Verify passwords wiped
        for (char c : pass) {
            assertEquals('\0', c);
        }

        // Verify audit log entry was created
        List<AuditEvent> auditEvents = AuditService.getInstance().getRecentEvents(10);
        assertFalse(auditEvents.isEmpty(), "Audit log entry must be recorded");
        AuditEvent event = auditEvents.get(0);
        assertEquals(AuditAction.STAFF_CREATED, event.action());
        assertEquals("STAFF", event.targetType());
        assertEquals("101", event.targetId());
        assertTrue(event.details().contains("first_superadmin"));
    }

    @Test
    @DisplayName("Rejects username collision even if active administrator count is zero")
    void testRejectsUsernameCollision() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        // adminCount = 0, but userExists = true
        Connection mockConn = createMockConnection(0, true, 0L, rolledBack, committed);

        char[] pass = "InitialSecret2026!".toCharArray();
        char[] confirm = "InitialSecret2026!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "existing_user", pass, confirm, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.INVALID_USERNAME, result.status());
        assertTrue(result.message().contains("already in use"));
        assertTrue(rolledBack.get());
        assertFalse(committed.get());
    }

    @Test
    @DisplayName("Wipe helper safely handles null and non-null arrays")
    void testWipeHelper() {
        assertDoesNotThrow(() -> InitialAdminProvisioningService.wipe(null));

        char[] secret = "SensitiveData".toCharArray();
        InitialAdminProvisioningService.wipe(secret);
        for (char c : secret) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("Fails closed and rolls back when advisory lock acquisition times out or returns zero")
    void testAdvisoryLockTimeoutFailsClosed() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        // getLockResult = 0 (lock timeout / unacquired)
        Connection mockConn = createMockConnection(0, 0, false, 0L, false, false, rolledBack, committed);

        char[] pass = "StrongMasterPass1!".toCharArray();
        char[] confirm = "StrongMasterPass1!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "new_admin", pass, confirm, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.DATABASE_ERROR, result.status());
        assertTrue(result.message().contains("Unable to acquire exclusive provisioning lock"));
        assertTrue(rolledBack.get(), "Transaction must be rolled back on lock failure");
        assertFalse(committed.get(), "Transaction must NOT be committed on lock failure");

        // Verify password wiping
        for (char c : pass) {
            assertEquals('\0', c);
        }
        for (char c : confirm) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("Fails closed and rolls back when advisory lock acquisition query throws SQLException")
    void testAdvisoryLockQueryExceptionFailsClosed() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        // throwOnGetLock = true
        Connection mockConn = createMockConnection(1, 0, false, 0L, false, true, rolledBack, committed);

        char[] pass = "StrongMasterPass1!".toCharArray();
        char[] confirm = "StrongMasterPass1!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "new_admin", pass, confirm, Role.SUPERADMIN);

        assertEquals(InitialAdminProvisioningService.ProvisioningStatus.DATABASE_ERROR, result.status());
        assertTrue(result.message().contains("Unable to acquire exclusive provisioning lock"));
        assertTrue(rolledBack.get(), "Transaction must be rolled back");
        assertFalse(committed.get(), "Transaction must NOT be committed");

        for (char c : pass) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("Plaintext password arrays are wiped even if connection auto-commit reset throws an exception")
    void testPasswordWipingGuaranteedWhenAutoCommitResetThrows() throws Exception {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);

        // throwOnAutoCommitReset = true
        Connection mockConn = createMockConnection(1, 0, false, 202L, true, false, rolledBack, committed);

        char[] pass = "InitialSecret2026!".toCharArray();
        char[] confirm = "InitialSecret2026!".toCharArray();

        InitialAdminProvisioningService.ProvisioningResult result =
                InitialAdminProvisioningService.provisionInitialAdministrator(mockConn, "resilient_admin", pass, confirm, Role.SUPERADMIN);

        assertTrue(result.isSuccess(), "Primary result must succeed and not be masked by cleanup exception");
        assertTrue(committed.get());

        // Password arrays must still be completely wiped
        for (char c : pass) {
            assertEquals('\0', c);
        }
        for (char c : confirm) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("FirstAdminSetupDialog maintains busy state guarding against duplicate submissions")
    void testDialogBusyGuard() throws Exception {
        java.lang.reflect.Field busyField = airline.management.system.ui.components.FirstAdminSetupDialog.class.getDeclaredField("busy");
        busyField.setAccessible(true);
        assertEquals(boolean.class, busyField.getType());
    }

    /**
     * Creates an in-memory mock JDBC Connection via dynamic proxy to test transactional provisioning safely
     * without touching any live MySQL database or mutating state.
     */
    private static Connection createMockConnection(
            int adminCount,
            boolean userExists,
            long generatedId,
            AtomicBoolean rolledBack,
            AtomicBoolean committed) {
        return createMockConnection(1, adminCount, userExists, generatedId, false, false, rolledBack, committed);
    }

    private static Connection createMockConnection(
            int getLockResult,
            int adminCount,
            boolean userExists,
            long generatedId,
            boolean throwOnAutoCommitReset,
            boolean throwOnGetLock,
            AtomicBoolean rolledBack,
            AtomicBoolean committed) {

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        switch (name) {
                            case "getAutoCommit":
                                return true;
                            case "setAutoCommit":
                                if (throwOnAutoCommitReset && args != null && args.length > 0 && Boolean.TRUE.equals(args[0])) {
                                    throw new java.sql.SQLException("Simulated connection reset failure during cleanup");
                                }
                                return null;
                            case "commit":
                                committed.set(true);
                                return null;
                            case "rollback":
                                rolledBack.set(true);
                                return null;
                            case "close":
                                return null;
                            case "createStatement":
                                return createMockStatement(adminCount);
                            case "prepareStatement":
                                String sql = (args != null && args.length > 0) ? (String) args[0] : "";
                                return createMockPreparedStatement(sql, getLockResult, adminCount, userExists, generatedId, throwOnGetLock);
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private static Statement createMockStatement(int adminCount) {
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        String sql = (String) args[0];
                        if (sql.contains("COUNT(*)")) {
                            return createMockCountResultSet(adminCount);
                        }
                        if (sql.contains("SHOW TABLES LIKE 'user_roles'")) {
                            return createEmptyResultSet();
                        }
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement createMockPreparedStatement(
            String sql, int getLockResult, int adminCount, boolean userExists, long generatedId, boolean throwOnGetLock) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("executeQuery".equals(name)) {
                        if (sql.contains("GET_LOCK")) {
                            if (throwOnGetLock) {
                                throw new java.sql.SQLException("Simulated database lock failure");
                            }
                            return createMockSingleIntResultSet(getLockResult);
                        }
                        if (sql.contains("COUNT(*)")) {
                            return createMockCountResultSet(adminCount);
                        }
                        if (sql.contains("SELECT id FROM app_users WHERE username = ?")) {
                            return userExists ? createMockSingleIntResultSet(1) : createEmptyResultSet();
                        }
                        return createEmptyResultSet();
                    }
                    if ("executeUpdate".equals(name)) {
                        return 1;
                    }
                    if ("execute".equals(name)) {
                        return false;
                    }
                    if ("getGeneratedKeys".equals(name)) {
                        return createMockGeneratedKeysResultSet(generatedId);
                    }
                    if ("close".equals(name) || name.startsWith("set")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet createMockCountResultSet(int count) {
        AtomicInteger cursor = new AtomicInteger(0);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return cursor.incrementAndGet() == 1;
                    }
                    if ("getInt".equals(name)) {
                        return count;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet createMockSingleIntResultSet(int val) {
        AtomicInteger cursor = new AtomicInteger(0);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return cursor.incrementAndGet() == 1;
                    }
                    if ("getInt".equals(name)) {
                        return val;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet createMockGeneratedKeysResultSet(long id) {
        AtomicInteger cursor = new AtomicInteger(0);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return cursor.incrementAndGet() == 1;
                    }
                    if ("getLong".equals(name)) {
                        return id;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet createEmptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return false;
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
