package airline.management.system.security;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized authorization matrix mapping Roles to their authorized Permissions.
 */
public final class RolePermissions {
    private static final Map<Role, Set<Permission>> PERMISSIONS_MAP = new EnumMap<>(Role.class);

    static {
        // SUPERADMIN: Unrestricted access across all operational and platform modules
        PERMISSIONS_MAP.put(Role.SUPERADMIN, EnumSet.allOf(Permission.class));

        // AIRLINEADMIN: Full operational, staff, and airline management
        PERMISSIONS_MAP.put(Role.AIRLINEADMIN, EnumSet.allOf(Permission.class));

        // RESERVATIONAGENT: Front desk reservation, passenger profiling, ticketing, and refunds
        PERMISSIONS_MAP.put(Role.RESERVATIONAGENT, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.MANAGE_PASSENGERS,
            Permission.MANAGE_RESERVATIONS,
            Permission.MANAGE_PAYMENTS,
            Permission.MANAGE_CANCELLATIONS,
            Permission.VIEW_DISRUPTIONS,
            Permission.MANAGE_REACCOMMODATION
        ));

        // CHECKINAGENT: Check-in counters, passenger identity verification, boarding pass issuance, baggage registration, PIR creation
        PERMISSIONS_MAP.put(Role.CHECKINAGENT, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.MANAGE_PASSENGERS,
            Permission.CHECKIN_PASSENGER,
            Permission.ISSUE_BOARDING_PASS,
            Permission.MANAGE_BAGGAGE,
            Permission.MANAGE_PIR,
            Permission.VIEW_DISRUPTIONS
        ));

        // GATEAGENT: Gate validation, boarding pass scanning, boarding execution, no-show tracking
        PERMISSIONS_MAP.put(Role.GATEAGENT, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.MANAGE_BOARDING,
            Permission.MANAGE_GATES,
            Permission.CHECKIN_PASSENGER,
            Permission.ISSUE_BOARDING_PASS,
            Permission.VIEW_DISRUPTIONS
        ));

        // OPERATIONSMANAGER: Flight operations, delay management, gate dispatch, operational reports, baggage & PIR/claims investigation
        PERMISSIONS_MAP.put(Role.OPERATIONSMANAGER, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.MANAGE_FLIGHTS,
            Permission.MANAGE_FLIGHT_OPERATIONS,
            Permission.MANAGE_GATES,
            Permission.VIEW_REPORTS,
            Permission.MANAGE_BAGGAGE,
            Permission.MANAGE_PIR,
            Permission.MANAGE_CLAIMS,
            Permission.REVIEW_CLAIMS,
            Permission.MANAGE_DISRUPTIONS,
            Permission.VIEW_DISRUPTIONS,
            Permission.MANAGE_REACCOMMODATION,
            Permission.MANAGE_DISRUPTION_VOUCHERS
        ));

        // FINANCEOFFICER: Payment records, refund verification, settlement reconciliation, revenue reports, claim settlements
        PERMISSIONS_MAP.put(Role.FINANCEOFFICER, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.MANAGE_PAYMENTS,
            Permission.MANAGE_CANCELLATIONS,
            Permission.VIEW_REPORTS,
            Permission.REVIEW_CLAIMS,
            Permission.MANAGE_CLAIM_SETTLEMENTS,
            Permission.VIEW_DISRUPTIONS,
            Permission.MANAGE_DISRUPTION_VOUCHERS
        ));

        // AUDITOR: Read-only compliance inspection, operational reports, security audit trail
        PERMISSIONS_MAP.put(Role.AUDITOR, EnumSet.of(
            Permission.VIEW_DASHBOARD,
            Permission.VIEW_REPORTS,
            Permission.VIEW_AUDIT_LOGS,
            Permission.VIEW_DISRUPTIONS
        ));
    }

    private RolePermissions() {}

    /**
     * Retrieves an unmodifiable set of permissions assigned to the given role.
     */
    public static Set<Permission> getPermissions(Role role) {
        if (role == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(PERMISSIONS_MAP.getOrDefault(role, Collections.emptySet()));
    }

    /**
     * Verifies if the specified role has the requested permission.
     */
    public static boolean hasPermission(Role role, Permission permission) {
        if (role == null || permission == null) {
            return false;
        }
        Set<Permission> perms = PERMISSIONS_MAP.get(role);
        return perms != null && perms.contains(permission);
    }
}
