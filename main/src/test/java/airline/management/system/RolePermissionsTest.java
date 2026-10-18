package airline.management.system;

import airline.management.system.security.Permission;
import airline.management.system.security.Role;
import airline.management.system.security.RolePermissions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RolePermissionsTest {

    @Test
    void allEightRolesAreDefinedAndConfigured() {
        assertEquals(8, Role.values().length);
        for (Role role : Role.values()) {
            Set<Permission> perms = RolePermissions.getPermissions(role);
            assertNotNull(perms);
            assertFalse(perms.isEmpty(), "Role " + role + " must have at least one permission");
        }
    }

    @Test
    void superAdminAndAirlineAdminHaveAllPermissions() {
        Set<Permission> superPerms = RolePermissions.getPermissions(Role.SUPERADMIN);
        Set<Permission> adminPerms = RolePermissions.getPermissions(Role.AIRLINEADMIN);

        assertEquals(Permission.values().length, superPerms.size());
        assertEquals(Permission.values().length, adminPerms.size());

        for (Permission p : Permission.values()) {
            assertTrue(RolePermissions.hasPermission(Role.SUPERADMIN, p));
            assertTrue(RolePermissions.hasPermission(Role.AIRLINEADMIN, p));
        }
    }

    @Test
    void reservationAgentHasReservationDeskAndCancellationPermissions() {
        assertTrue(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_PASSENGERS));
        assertTrue(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_RESERVATIONS));
        assertTrue(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_PAYMENTS));
        assertTrue(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_CANCELLATIONS));

        // Reservation Agent must NOT have gate boarding or user management permissions
        assertFalse(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_BOARDING));
        assertFalse(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.MANAGE_USERS));
        assertFalse(RolePermissions.hasPermission(Role.RESERVATIONAGENT, Permission.VIEW_AUDIT_LOGS));
    }

    @Test
    void checkinAgentHasCheckinAndBoardingPassIssuance() {
        assertTrue(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.MANAGE_PASSENGERS));
        assertTrue(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.CHECKIN_PASSENGER));
        assertTrue(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.ISSUE_BOARDING_PASS));

        assertFalse(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.MANAGE_FLIGHTS));
        assertFalse(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.MANAGE_PAYMENTS));
        assertFalse(RolePermissions.hasPermission(Role.CHECKINAGENT, Permission.MANAGE_USERS));
    }

    @Test
    void gateAgentHasBoardingAndGatePermissions() {
        assertTrue(RolePermissions.hasPermission(Role.GATEAGENT, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.GATEAGENT, Permission.MANAGE_BOARDING));
        assertTrue(RolePermissions.hasPermission(Role.GATEAGENT, Permission.MANAGE_GATES));
        assertTrue(RolePermissions.hasPermission(Role.GATEAGENT, Permission.CHECKIN_PASSENGER));

        assertFalse(RolePermissions.hasPermission(Role.GATEAGENT, Permission.MANAGE_CANCELLATIONS));
        assertFalse(RolePermissions.hasPermission(Role.GATEAGENT, Permission.MANAGE_USERS));
    }

    @Test
    void operationsManagerHasFlightDispatchAndReports() {
        assertTrue(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.MANAGE_FLIGHTS));
        assertTrue(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.MANAGE_FLIGHT_OPERATIONS));
        assertTrue(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.MANAGE_GATES));
        assertTrue(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.VIEW_REPORTS));

        assertFalse(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.CHECKIN_PASSENGER));
        assertFalse(RolePermissions.hasPermission(Role.OPERATIONSMANAGER, Permission.MANAGE_PAYMENTS));
    }

    @Test
    void financeOfficerHasPaymentsAndRevenueReports() {
        assertTrue(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.MANAGE_PAYMENTS));
        assertTrue(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.MANAGE_CANCELLATIONS));
        assertTrue(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.VIEW_REPORTS));

        assertFalse(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.MANAGE_FLIGHTS));
        assertFalse(RolePermissions.hasPermission(Role.FINANCEOFFICER, Permission.MANAGE_BOARDING));
    }

    @Test
    void auditorHasReadOnlyAuditAndReports() {
        assertTrue(RolePermissions.hasPermission(Role.AUDITOR, Permission.VIEW_DASHBOARD));
        assertTrue(RolePermissions.hasPermission(Role.AUDITOR, Permission.VIEW_REPORTS));
        assertTrue(RolePermissions.hasPermission(Role.AUDITOR, Permission.VIEW_AUDIT_LOGS));

        assertFalse(RolePermissions.hasPermission(Role.AUDITOR, Permission.MANAGE_RESERVATIONS));
        assertFalse(RolePermissions.hasPermission(Role.AUDITOR, Permission.MANAGE_FLIGHTS));
        assertFalse(RolePermissions.hasPermission(Role.AUDITOR, Permission.CHECKIN_PASSENGER));
        assertFalse(RolePermissions.hasPermission(Role.AUDITOR, Permission.MANAGE_BOARDING));
    }

    @Test
    void legacyAdminStringResolvesToAirlineAdmin() {
        assertEquals(Role.AIRLINEADMIN, Role.fromString("ADMIN"));
        assertEquals(Role.AIRLINEADMIN, Role.fromString("admin"));
        assertEquals(Role.AIRLINEADMIN, Role.fromString("Administrator"));
        assertEquals(Role.AIRLINEADMIN, Role.fromString("AIRLINE_ADMIN"));
        assertEquals(Role.SUPERADMIN, Role.fromString("super_admin"));
        assertEquals(Role.CHECKINAGENT, Role.fromString("checkin_agent"));
        assertEquals(Role.GATEAGENT, Role.fromString("GATE_AGENT"));
    }
}
