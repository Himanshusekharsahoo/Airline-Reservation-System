package airline.management.system.operations.irops;

/**
 * Operational impact severity classification for flight irregular operations.
 */
public enum DisruptionSeverity {
    LOW("Low", "Minor impact; flight delay under 60 minutes with minimal connection risks"),
    MEDIUM("Medium", "Moderate impact; delay between 60 and 180 minutes with localized passenger impact"),
    HIGH("High", "Severe impact; delay exceeding 180 minutes or multiple passenger misconnections"),
    CRITICAL("Critical", "Critical impact; flight cancellation, diversion, or station-wide grounding");

    private final String displayName;
    private final String description;

    DisruptionSeverity(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static DisruptionSeverity fromString(String text) {
        if (text == null || text.isBlank()) {
            return MEDIUM;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (DisruptionSeverity s : values()) {
            if (s.name().equals(clean)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown Disruption Severity: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
