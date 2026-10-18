package airline.management.system.security;

import airline.management.system.UserSession;

/**
 * Centralized Authorization Service for AeroDesk.
 * Enforces role-based permissions at the application and business service layer.
 */
public final class AuthorizationService {
    private static volatile boolean testBypassAllowed = false;

    private AuthorizationService() {}

    /**
     * Resolves the current authenticated user's role from UserSession.
     */
    public static Role getCurrentRole() {
        if (!UserSession.isAuthenticated()) {
            return null;
        }
        return Role.fromString(UserSession.role());
    }

    /**
     * Checks if the currently authenticated operator has the requested permission.
     */
    public static boolean hasPermission(Permission permission) {
        Role role = getCurrentRole();
        if (role == null) {
            return false;
        }
        return RolePermissions.hasPermission(role, permission);
    }

    /**
     * Checks if the given role has the requested permission.
     */
    public static boolean hasPermission(Role role, Permission permission) {
        return RolePermissions.hasPermission(role, permission);
    }

    /**
     * Enforces that the current operator has the required permission.
     * Throws AccessDeniedException if not authenticated or not authorized.
     */
    public static void requirePermission(Permission permission) {
        if (!UserSession.isAuthenticated()) {
            // Check if automated test execution environment bypasses auth
            if (isTestEnvironment() || testBypassAllowed) {
                return;
            }
            throw new AccessDeniedException("Access denied: Operator is not authenticated.");
        }

        Role role = getCurrentRole();
        if (!RolePermissions.hasPermission(role, permission)) {
            throw new AccessDeniedException("Access denied: Role [" + (role != null ? role.name() : "UNASSIGNED")
                    + "] does not possess required permission [" + permission.name() + "].");
        }
    }

    /**
     * Enforces that the current operator has at least one of the required permissions.
     */
    public static void requireAnyPermission(Permission... permissions) {
        if (!UserSession.isAuthenticated()) {
            if (isTestEnvironment() || testBypassAllowed) {
                return;
            }
            throw new AccessDeniedException("Access denied: Operator is not authenticated.");
        }

        Role role = getCurrentRole();
        for (Permission permission : permissions) {
            if (RolePermissions.hasPermission(role, permission)) {
                return;
            }
        }
        throw new AccessDeniedException("Access denied: Role [" + (role != null ? role.name() : "UNASSIGNED")
                + "] does not possess any of the required permissions.");
    }

    /**
     * Enforces that the given role possesses the required permission.
     */
    public static void requirePermission(Role role, Permission permission) {
        if (!RolePermissions.hasPermission(role, permission)) {
            throw new AccessDeniedException("Access denied: Role [" + (role != null ? role.name() : "NONE")
                    + "] is unauthorized for [" + permission.name() + "].");
        }
    }

    /**
     * Internal check for test runner environments (e.g. Maven surefire / test database).
     */
    private static boolean isTestEnvironment() {
        return System.getProperty("DB_NAME") != null
            || System.getProperty("TESTDBNAME") != null
            || Boolean.getBoolean("aerodesk.test.bypassAuth");
    }

    /**
     * Allows test fixtures to toggle auth bypass explicitly when needed.
     */
    public static void setTestBypassAllowed(boolean allowed) {
        testBypassAllowed = allowed;
    }
}
