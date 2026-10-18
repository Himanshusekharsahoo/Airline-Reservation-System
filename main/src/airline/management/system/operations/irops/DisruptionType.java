package airline.management.system.operations.irops;

/**
 * Authoritative classifications of flight disruptions and irregular operations.
 */
public enum DisruptionType {
    DELAY("Flight Delay", "Scheduled flight departure or arrival time postponed"),
    CANCELLATION("Flight Cancellation", "Scheduled flight operation cancelled"),
    DIVERSION("Flight Diversion", "Flight diverted to alternative airport destination"),
    AIRCRAFT_CHANGE("Aircraft Change", "Aircraft swap resulting in seat/capacity variance"),
    MAJOR_OPERATIONAL_DISRUPTION("Major Operational Disruption", "Widespread network or terminal irregular operations");

    private final String displayName;
    private final String description;

    DisruptionType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static DisruptionType fromString(String text) {
        if (text == null || text.isBlank()) {
            return DELAY;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (DisruptionType type : values()) {
            if (type.name().equals(clean)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Disruption Type: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
