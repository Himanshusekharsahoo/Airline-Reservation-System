package airline.management.system.operations.checkin;

/**
 * Passenger check-in lifecycle and eligibility states.
 */
public enum CheckinStatus {
    ELIGIBLE("Eligible for Check-in", "Reservation is confirmed and payment completed"),
    CHECKED_IN("Checked In", "Passenger verified and operational check-in completed"),
    INELIGIBLE_PAYMENT("Payment Pending", "Requires completed payment before check-in can proceed"),
    INELIGIBLE_STATUS("Status Ineligible", "Reservation is not confirmed (cancelled or pending)"),
    NOT_FOUND("Reservation Not Found", "No booking record found for the provided PNR");

    private final String label;
    private final String description;

    CheckinStatus(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
