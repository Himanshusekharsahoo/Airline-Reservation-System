package airline.management.system.operations.pir;

/**
 * Categorization of Property Irregularity Reports for mishandled baggage.
 */
public enum PIRType {
    DELAYED_BAGGAGE("Delayed Baggage", "Checked luggage not received at arrival carousel upon flight completion", true),
    MISSING_BAGGAGE("Missing Baggage", "Checked luggage untracked during transit; investigative trace initiated", true),
    DAMAGED_BAGGAGE("Damaged Baggage (Planned / Future Support)", "Structural, cosmetic, or content baggage damage (planned for future phases)", false);

    private final String displayName;
    private final String description;
    private final boolean supported;

    PIRType(String displayName, String description, boolean supported) {
        this.displayName = displayName;
        this.description = description;
        this.supported = supported;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSupported() {
        return supported;
    }

    public static PIRType fromString(String text) {
        if (text == null || text.isBlank()) {
            return DELAYED_BAGGAGE;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (PIRType t : values()) {
            if (t.name().equals(clean)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown PIR type: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
