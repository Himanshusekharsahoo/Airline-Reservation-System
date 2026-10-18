package airline.management.system.operations.baggage;

/**
 * Operational classification of passenger baggage.
 */
public enum BaggageType {
    CHECKED("Checked Baggage"),
    CABIN("Cabin Baggage");

    private final String displayName;

    BaggageType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static BaggageType fromString(String text) {
        if (text == null || text.isBlank()) {
            return CHECKED;
        }
        String clean = text.trim().toUpperCase();
        for (BaggageType t : values()) {
            if (t.name().equals(clean)) {
                return t;
            }
        }
        return CHECKED;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
