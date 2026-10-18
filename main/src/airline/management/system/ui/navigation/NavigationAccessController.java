package airline.management.system.ui.navigation;

import airline.management.system.UserSession;
import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import airline.management.system.security.RolePermissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controller governing operational navigation accessibility based on operator roles and permissions.
 */
public final class NavigationAccessController {
    private NavigationAccessController() {}

    /**
     * Checks if the currently authenticated operator can access the specified navigation route.
     */
    public static boolean isRouteAccessible(NavRoute route) {
        if (route == null) {
            return false;
        }
        Role currentRole = UserSession.getRole();
        if (currentRole == null) {
            return false;
        }
        return isRouteAccessible(currentRole, route);
    }

    /**
     * Checks if a given role has the necessary permission to access the navigation route.
     */
    public static boolean isRouteAccessible(Role role, NavRoute route) {
        if (role == null || route == null) {
            return false;
        }
        if (route == NavRoute.PIR_CLAIMS) {
            return RolePermissions.hasPermission(role, Permission.MANAGE_PIR)
                || RolePermissions.hasPermission(role, Permission.MANAGE_CLAIMS)
                || RolePermissions.hasPermission(role, Permission.REVIEW_CLAIMS)
                || RolePermissions.hasPermission(role, Permission.MANAGE_CLAIM_SETTLEMENTS)
                || RolePermissions.hasPermission(role, Permission.VIEW_REPORTS);
        }
        if (route == NavRoute.IROPS) {
            return RolePermissions.hasPermission(role, Permission.MANAGE_DISRUPTIONS)
                || RolePermissions.hasPermission(role, Permission.VIEW_DISRUPTIONS)
                || RolePermissions.hasPermission(role, Permission.MANAGE_REACCOMMODATION)
                || RolePermissions.hasPermission(role, Permission.MANAGE_DISRUPTION_VOUCHERS)
                || RolePermissions.hasPermission(role, Permission.VIEW_REPORTS);
        }
        Permission required = route.getRequiredPermission();
        if (required == null) {
            return true;
        }
        return RolePermissions.hasPermission(role, required);
    }

    /**
     * Returns an ordered list of navigation routes that the specified role is authorized to view.
     */
    public static List<NavRoute> getAllowedRoutes(Role role) {
        if (role == null) {
            return Collections.emptyList();
        }
        List<NavRoute> allowed = new ArrayList<>();
        for (NavRoute route : NavRoute.values()) {
            if (isRouteAccessible(role, route)) {
                allowed.add(route);
            }
        }
        return Collections.unmodifiableList(allowed);
    }

    /**
     * Returns the default landing route for the specified role.
     */
    public static NavRoute getDefaultRoute(Role role) {
        if (isRouteAccessible(role, NavRoute.DASHBOARD)) {
            return NavRoute.DASHBOARD;
        }
        List<NavRoute> allowed = getAllowedRoutes(role);
        return allowed.isEmpty() ? NavRoute.DASHBOARD : allowed.get(0);
    }
}
