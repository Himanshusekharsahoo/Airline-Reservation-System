package airline.management.system;

import airline.management.system.security.Role;
import airline.management.system.ui.navigation.NavRoute;
import airline.management.system.ui.navigation.NavigationAccessController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavigationAccessControllerTest {

    @AfterEach
    void tearDown() {
        UserSession.logout();
    }

    @Test
    void superAdminAndAirlineAdminCanAccessAllRoutes() {
        for (NavRoute route : NavRoute.values()) {
            assertTrue(NavigationAccessController.isRouteAccessible(Role.SUPERADMIN, route),
                "SUPERADMIN should access " + route.name());
            assertTrue(NavigationAccessController.isRouteAccessible(Role.AIRLINEADMIN, route),
                "AIRLINEADMIN should access " + route.name());
        }
        assertEquals(NavRoute.values().length, NavigationAccessController.getAllowedRoutes(Role.AIRLINEADMIN).size());
    }

    @Test
    void reservationAgentRouteAccess() {
        Role role = Role.RESERVATIONAGENT;
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.DASHBOARD));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.PASSENGERS));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.BOOKING));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.TRIPS));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.PAYMENTS));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.CANCELLATIONS));

        // Inaccessible routes for Reservation Agent
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.CHECKIN));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.BOARDING));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.FLIGHTS));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.SETTINGS));
    }

    @Test
    void checkinAgentRouteAccess() {
        Role role = Role.CHECKINAGENT;
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.DASHBOARD));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.PASSENGERS));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.CHECKIN));

        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.BOOKING));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.BOARDING));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.FLIGHTS));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.CANCELLATIONS));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.SETTINGS));
    }

    @Test
    void gateAgentRouteAccess() {
        Role role = Role.GATEAGENT;
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.DASHBOARD));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.BOARDING));
        assertTrue(NavigationAccessController.isRouteAccessible(role, NavRoute.CHECKIN));

        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.BOOKING));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.FLIGHTS));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.CANCELLATIONS));
        assertFalse(NavigationAccessController.isRouteAccessible(role, NavRoute.PAYMENTS));
    }

    @Test
    void defaultLandingRouteIsDashboardWhenPermitted() {
        assertEquals(NavRoute.DASHBOARD, NavigationAccessController.getDefaultRoute(Role.AIRLINEADMIN));
        assertEquals(NavRoute.DASHBOARD, NavigationAccessController.getDefaultRoute(Role.RESERVATIONAGENT));
        assertEquals(NavRoute.DASHBOARD, NavigationAccessController.getDefaultRoute(Role.CHECKINAGENT));
        assertEquals(NavRoute.DASHBOARD, NavigationAccessController.getDefaultRoute(Role.GATEAGENT));
    }

    @Test
    void sessionAwareRouteAccessibility() {
        UserSession.login(201L, "checkin_user", "CHECKINAGENT");
        assertTrue(NavigationAccessController.isRouteAccessible(NavRoute.CHECKIN));
        assertFalse(NavigationAccessController.isRouteAccessible(NavRoute.BOOKING));
        assertFalse(NavigationAccessController.isRouteAccessible(NavRoute.CANCELLATIONS));

        UserSession.logout();
        assertFalse(NavigationAccessController.isRouteAccessible(NavRoute.CHECKIN));
    }
}
