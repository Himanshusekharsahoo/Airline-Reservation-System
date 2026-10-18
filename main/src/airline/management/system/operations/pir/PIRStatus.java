package airline.management.system.operations.pir;

/**
 * Authoritative lifecycle states for Property Irregularity Reports.
 */
public enum PIRStatus {
    OPEN("Open", "Irregularity reported and logged; awaiting operational investigation"),
    UNDER_INVESTIGATION("Under Investigation", "Ramp trace, airport cross-check, and flight bag tracking in progress"),
    LOCATED("Located", "Baggage located at airport station; awaiting forwarding or delivery"),
    DELIVERED("Delivered", "Baggage repatriated and delivered to passenger"),
    UNRESOLVED("Unresolved", "Trace investigation exhausted without locating baggage"),
    CLOSED("Closed", "Incident file concluded and archived");

    private final String displayName;
    private final String description;

    PIRStatus(String displayName, String description) {
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
        return this == CLOSED;
    }

    public boolean isActive() {
        return this != CLOSED;
    }

    public static PIRStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return OPEN;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (PIRStatus s : values()) {
            if (s.name().equals(clean)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown PIR status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
