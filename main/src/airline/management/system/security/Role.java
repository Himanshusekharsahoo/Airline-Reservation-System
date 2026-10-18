package airline.management.system.security;

/**
 * AeroDesk Airline Operations Roles.
 * Supports operational hierarchy from super-administrator down to specialized airport staff roles.
 */
public enum Role {
    SUPERADMIN("Super Administrator", "Platform and multi-tenant system administration"),
    AIRLINEADMIN("Airline Administrator", "Airline-level operations, staff, and flight administration"),
    RESERVATIONAGENT("Reservation Agent", "Passenger management, booking desk, and ticketing operations"),
    CHECKINAGENT("Check-in Agent", "Passenger verification, seat confirmation, and boarding pass issuance"),
    GATEAGENT("Gate Agent", "Gate operations, passenger boarding verification, and no-show tracking"),
    OPERATIONSMANAGER("Operations Manager", "Flight operations dispatch, delay tracking, and gate monitoring"),
    FINANCEOFFICER("Finance Officer", "Payment reconciliations, refunds, and financial reporting"),
    AUDITOR("Auditor", "Read-only compliance verification and audit log inspection");

    private final String displayName;
    private final String description;

    Role(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Case-insensitive, delimiter-tolerant role resolver.
     * Maps legacy database role 'ADMIN' seamlessly to AIRLINEADMIN.
     */
    public static Role fromString(String roleStr) {
        if (roleStr == null || roleStr.trim().isEmpty()) {
            return RESERVATIONAGENT;
        }
        String normalized = roleStr.trim().replace(" ", "").replace("_", "").replace("-", "").toUpperCase();
        if ("ADMIN".equals(normalized) || "ADMINISTRATOR".equals(normalized)) {
            return AIRLINEADMIN;
        }
        for (Role role : values()) {
            String roleNorm = role.name().replace("_", "");
            if (roleNorm.equalsIgnoreCase(normalized)) {
                return role;
            }
        }
        return RESERVATIONAGENT;
    }
}
