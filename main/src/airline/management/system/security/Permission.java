package airline.management.system.security;

/**
 * Granular permissions for AeroDesk airline operations capabilities.
 */
public enum Permission {
    VIEW_DASHBOARD("View Dashboard", "Access the operations dashboard and real-time operational overview"),
    MANAGE_FLIGHTS("Manage Flights", "Create, update, and manage scheduled flights"),
    MANAGE_PASSENGERS("Manage Passengers", "Register and update passenger profiles and travel records"),
    MANAGE_RESERVATIONS("Manage Reservations", "Operate the reservation desk, create bookings and allocate seats"),
    MANAGE_PAYMENTS("Manage Payments", "Process payments, inspect transactions, and perform financial reconciliation"),
    MANAGE_CANCELLATIONS("Manage Cancellations", "Cancel reservations, release reserved seats, and execute refunds"),
    CHECKIN_PASSENGER("Check-in Passenger", "Verify passenger identity, confirm seat assignment, and execute check-in"),
    ISSUE_BOARDING_PASS("Issue Boarding Pass", "Generate and issue physical and digital boarding passes"),
    MANAGE_BOARDING("Manage Boarding", "Verify boarding passes, board passengers at gate, and track no-shows"),
    MANAGE_GATES("Manage Gates", "Assign gates, manage gate boarding status, and airport gate operations"),
    MANAGE_FLIGHT_OPERATIONS("Manage Flight Operations", "Manage flight dispatch, delay logging, and operational statuses"),
    VIEW_REPORTS("View Reports", "Access operational, passenger volume, and revenue reports"),
    MANAGE_USERS("Manage Users", "Administer staff accounts, roles, and access credentials"),
    VIEW_AUDIT_LOGS("View Audit Logs", "Inspect compliance logs, security events, and operational audit trail"),
    MANAGE_BAGGAGE("Manage Baggage", "Register baggage, update status, and manage baggage operations"),
    MANAGE_PIR("Manage PIR", "Create and update Property Irregularity Reports and investigate baggage incidents"),
    MANAGE_CLAIMS("Manage Claims", "File and update passenger baggage claims"),
    REVIEW_CLAIMS("Review Claims", "Review, approve, or reject passenger baggage claims"),
    MANAGE_CLAIM_SETTLEMENTS("Manage Claim Settlements", "Authorize and record financial settlement for approved baggage claims"),
    MANAGE_DISRUPTIONS("Manage Disruptions", "Declare, update, and resolve flight disruptions and irregular operations"),
    VIEW_DISRUPTIONS("View Disruptions", "View flight disruptions, affected passenger manifests, and IROPS desk"),
    MANAGE_REACCOMMODATION("Manage Re-accommodation", "Rebook disrupted passengers onto alternate flights automatically or manually"),
    MANAGE_DISRUPTION_VOUCHERS("Manage Vouchers", "Issue, view, and manage disruption recovery vouchers for passengers");

    private final String label;
    private final String description;

    Permission(String label, String description) {
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
