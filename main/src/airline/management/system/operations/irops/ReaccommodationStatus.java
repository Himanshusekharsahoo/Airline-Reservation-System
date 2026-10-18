package airline.management.system.operations.irops;

/**
 * Authoritative lifecycle states for individual passenger re-accommodation records.
 */
public enum ReaccommodationStatus {
    PENDING("Pending", "Passenger identified on disrupted flight; queued for alternate routing"),
    SEARCHING("Searching", "Evaluating alternate flight inventory within carrier network"),
    OFFERED("Offered", "Eligible alternate flight option identified and proposed"),
    ACCEPTED("Accepted", "Passenger accepted proposed alternate itinerary"),
    REBOOKED("Rebooked", "Confirmed reservation and ticket issued on target flight"),
    DECLINED("Declined", "Passenger declined alternate routing; opted for refund or self-travel"),
    FAILED("Failed", "No alternate flight available with adequate seat capacity"),
    CANCELLED("Cancelled", "Disruption cancelled; re-accommodation aborted");

    private final String displayName;
    private final String description;

    ReaccommodationStatus(String displayName, String description) {
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
        return this == REBOOKED || this == DECLINED || this == FAILED || this == CANCELLED;
    }

    public boolean isSuccessful() {
        return this == REBOOKED;
    }

    public static ReaccommodationStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return PENDING;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (ReaccommodationStatus s : values()) {
            if (s.name().equals(clean)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown Reaccommodation Status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
