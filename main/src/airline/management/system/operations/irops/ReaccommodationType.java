package airline.management.system.operations.irops;

/**
 * Re-accommodation initiation classification.
 */
public enum ReaccommodationType {
    AUTO("Automated", "Algorithmically assigned via re-accommodation engine"),
    MANUAL("Manual", "Manually selected and assigned by airport operations agent");

    private final String displayName;
    private final String description;

    ReaccommodationType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static ReaccommodationType fromString(String text) {
        if (text == null || text.isBlank()) {
            return AUTO;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (ReaccommodationType t : values()) {
            if (t.name().equals(clean)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown Reaccommodation Type: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
