package airline.management.system.operations.claim;

/**
 * Authoritative lifecycle states for passenger baggage irregularity claims.
 */
public enum ClaimStatus {
    SUBMITTED("Submitted", "Passenger claim submitted for review and evaluation"),
    UNDER_REVIEW("Under Review", "Operational and financial review in progress"),
    APPROVED("Approved", "Claim approved for financial compensation settlement"),
    REJECTED("Rejected", "Claim formally rejected with operational justification"),
    SETTLED("Settled", "Settlement payment completed and reconciled"),
    CLOSED("Closed", "Claim file finalized and closed");

    private final String displayName;
    private final String description;

    ClaimStatus(String displayName, String description) {
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

    public static ClaimStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return SUBMITTED;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (ClaimStatus s : values()) {
            if (s.name().equals(clean)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown claim status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
