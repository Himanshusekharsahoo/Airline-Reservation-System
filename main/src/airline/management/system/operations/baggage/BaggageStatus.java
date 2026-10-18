package airline.management.system.operations.baggage;

/**
 * Authoritative lifecycle states for passenger baggage.
 */
public enum BaggageStatus {
    REGISTERED("Registered", "Tag issued and recorded in reservation manifest"),
    ACCEPTED("Accepted", "Received and cleared at airport baggage check-in desk"),
    LOADED("Loaded", "Loaded securely onto aircraft baggage cargo hold"),
    IN_TRANSIT("In Transit", "Aircraft in transit to arrival destination"),
    DELIVERED("Delivered", "Delivered to arrival carousel and claimed by passenger"),
    DELAYED("Delayed", "Held or delayed due to security screening or flight disruption"),
    MISSING("Missing", "Reported untracked; investigative trace initiated"),
    CANCELLED("Cancelled", "Baggage registration cancelled by passenger or dispatch");

    private final String displayName;
    private final String description;

    BaggageStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static BaggageStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return REGISTERED;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        // Gracefully handle legacy CHECKED state
        if ("CHECKED".equals(clean)) {
            return ACCEPTED;
        }
        for (BaggageStatus s : values()) {
            if (s.name().equals(clean)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown baggage status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
