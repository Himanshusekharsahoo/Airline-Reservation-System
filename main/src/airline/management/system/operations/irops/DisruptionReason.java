package airline.management.system.operations.irops;

/**
 * Standardized operational taxonomy for root cause attribution of flight disruptions.
 */
public enum DisruptionReason {
    WEATHER("Weather Conditions", "Adverse meteorological conditions or severe weather warnings"),
    ATC("Air Traffic Control", "Air traffic management delays, slot restrictions, or airspace hold"),
    MECHANICAL("Technical / Aircraft Maintenance", "Unscheduled technical rectification or AOG event"),
    CREW("Crew Scheduling / Duty Limits", "Flight crew timeout, duty limitations, or repositioning delay"),
    OPERATIONAL("Airline Operations", "Turnaround delay, baggage handling, or catering delays"),
    SECURITY("Security Incident", "Security alert, bag re-screening, or law enforcement delay"),
    AIRPORT("Airport Infrastructure", "Runway closure, gate congestion, or ground equipment failure"),
    OTHER("Other Unforeseen Reason", "Miscellaneous operational exception");

    private final String displayName;
    private final String description;

    DisruptionReason(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static DisruptionReason fromString(String text) {
        if (text == null || text.isBlank()) {
            return OPERATIONAL;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        if ("TERMINAL".equals(clean) || "GATE".equals(clean) || "INFRASTRUCTURE".equals(clean)) {
            return AIRPORT;
        }
        if ("MAINTENANCE".equals(clean) || "TECHNICAL".equals(clean)) {
            return MECHANICAL;
        }
        for (DisruptionReason reason : values()) {
            if (reason.name().equals(clean)) {
                return reason;
            }
        }
        return OTHER;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
