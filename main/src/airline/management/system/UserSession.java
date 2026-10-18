package airline.management.system;

import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import airline.management.system.security.RolePermissions;

/**
 * Thread-safe global session representation for the currently authenticated operator.
 * Preserves legacy signatures while integrating typed Role and Permission capabilities.
 */
public final class UserSession {
    private static volatile Long userId;
    private static volatile String username;
    private static volatile String role;

    private UserSession() { }

    public static synchronized void login(long id, String name, String userRole) {
        userId = id;
        username = name;
        role = userRole;
    }

    public static synchronized void logout() {
        userId = null;
        username = null;
        role = null;
        airline.management.system.tenant.TenantContext.resetSession();
    }

    public static boolean isAuthenticated() {
        return userId != null;
    }

    public static Long userId() {
        return userId;
    }

    public static String username() {
        return username;
    }

    public static String role() {
        return role;
    }

    /**
     * Resolves the operator's current role as a typed Role enum.
     */
    public static Role getRole() {
        if (role == null) {
            return null;
        }
        return Role.fromString(role);
    }

    /**
     * Verifies if the authenticated operator holds the specified permission.
     */
    public static boolean hasPermission(Permission permission) {
        Role currentRole = getRole();
        if (currentRole == null || permission == null) {
            return false;
        }
        return RolePermissions.hasPermission(currentRole, permission);
    }
}
