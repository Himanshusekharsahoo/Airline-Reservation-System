package airline.management.system;

import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    @BeforeEach
    void setUp() {
        UserSession.logout();
    }

    @AfterEach
    void tearDown() {
        UserSession.logout();
    }

    @Test
    void unauthenticatedUserHasNoRoleAndNoPermissions() {
        assertFalse(UserSession.isAuthenticated());
        assertNull(AuthorizationService.getCurrentRole());
        assertFalse(AuthorizationService.hasPermission(Permission.VIEW_DASHBOARD));
        assertFalse(AuthorizationService.hasPermission(Permission.MANAGE_RESERVATIONS));
    }

    @Test
    void authenticatedUserInheritsAssignedRolePermissions() {
        UserSession.login(101L, "agent_smith", "RESERVATIONAGENT");
        assertTrue(UserSession.isAuthenticated());
        assertEquals(Role.RESERVATIONAGENT, UserSession.getRole());

        assertTrue(AuthorizationService.hasPermission(Permission.MANAGE_RESERVATIONS));
        assertTrue(AuthorizationService.hasPermission(Permission.MANAGE_CANCELLATIONS));
        assertFalse(AuthorizationService.hasPermission(Permission.MANAGE_USERS));
        assertFalse(AuthorizationService.hasPermission(Permission.MANAGE_BOARDING));
    }

    @Test
    void requirePermissionSucceedsWhenAuthorized() {
        UserSession.login(102L, "gate_agent_sarah", "GATEAGENT");
        assertDoesNotThrow(() -> AuthorizationService.requirePermission(Permission.MANAGE_BOARDING));
        assertDoesNotThrow(() -> AuthorizationService.requirePermission(Permission.MANAGE_GATES));
    }

    @Test
    void requirePermissionThrowsAccessDeniedWhenUnauthorized() {
        UserSession.login(103L, "finance_bob", "FINANCEOFFICER");

        // Finance officer attempting flight operations or boarding
        AccessDeniedException ex = assertThrows(
            AccessDeniedException.class,
            () -> AuthorizationService.requirePermission(Permission.MANAGE_BOARDING)
        );
        assertTrue(ex.getMessage().contains("FINANCEOFFICER"));
        assertTrue(ex.getMessage().contains("MANAGE_BOARDING"));
    }

    @Test
    void explicitRolePermissionCheck() {
        assertTrue(AuthorizationService.hasPermission(Role.CHECKINAGENT, Permission.CHECKIN_PASSENGER));
        assertFalse(AuthorizationService.hasPermission(Role.CHECKINAGENT, Permission.MANAGE_FLIGHTS));

        assertDoesNotThrow(() -> AuthorizationService.requirePermission(Role.OPERATIONSMANAGER, Permission.MANAGE_FLIGHTS));
        assertThrows(AccessDeniedException.class,
            () -> AuthorizationService.requirePermission(Role.AUDITOR, Permission.MANAGE_RESERVATIONS));
    }
}
