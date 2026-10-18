package airline.management.system.operations.irops;

/**
 * Authoritative lifecycle states for flight disruptions and irregular operations.
 */
public enum DisruptionStatus {
    DECLARED("Declared", "Disruption officially recorded; assessing affected passenger manifest"),
    INVESTIGATING("Investigating", "Operational analysis and aircraft recovery options in progress"),
    REACCOMMODATING("Re-accommodating", "Active passenger rebooking and alternate flight assignment"),
    MITIGATED("Mitigated", "Alternate flights assigned and passenger recovery services disbursed"),
    RESOLVED("Resolved", "Disruption closed; all affected passengers rebooked or refunded"),
    CANCELLED("Cancelled", "Disruption declaration invalidated, retracted, or superseded");

    private final String displayName;
    private final String description;

    DisruptionStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == RESOLVED || this == CANCELLED;
    }

    public boolean isActive() {
        return !isTerminal();
    }

    public static DisruptionStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return DECLARED;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (DisruptionStatus status : values()) {
            if (status.name().equals(clean)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown Disruption Status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
