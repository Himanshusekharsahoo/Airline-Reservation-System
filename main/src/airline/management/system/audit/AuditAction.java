package airline.management.system.audit;

/**
 * Categorized operational audit actions for airline operations tracking and compliance.
 */
public enum AuditAction {
    LOGIN("Operator Login", "Authentication session initiated"),
    LOGOUT("Operator Logout", "Authentication session terminated"),
    RESERVATION_CREATE("Reservation Created", "New flight booking and seat allocation completed"),
    RESERVATION_CANCEL("Reservation Cancelled", "Booking cancelled, seat released, and refund issued"),
    PAYMENT_PROCESS("Payment Processed", "Customer payment processed and verified"),
    REFUND_PROCESS("Refund Processed", "Cancellation refund processed and recorded"),
    PASSENGER_CREATE("Passenger Registered", "New passenger client record created"),
    PASSENGER_UPDATE("Passenger Updated", "Passenger profile or contact data modified"),
    CHECKIN_ATTEMPT("Check-in Executed", "Passenger check-in verification and seat confirmation"),
    CHECKIN_COMPLETED("Check-in Completed", "Passenger check-in committed to operational database"),
    BOARDING_PASS_ISSUED("Boarding Pass Issued", "Authoritative boarding pass record generated"),
    BOARDING_EVENT("Boarding Event", "Passenger validated and marked as boarded at gate"),
    BOARDING_VALIDATED("Boarding Pass Validated", "Boarding pass scanned and verified at departure gate"),
    PASSENGER_BOARDED("Passenger Boarded", "Passenger gate clearance committed to database"),
    GATE_ASSIGNED("Gate Assigned", "Gate allocated to scheduled flight departure"),
    GATE_RELEASED("Gate Released", "Gate released after the flight operational lifecycle completed"),
    GATE_ASSIGNMENT("Gate Assignment", "Gate assignment modified"),
    FLIGHT_UPDATE("Flight Updated", "Flight schedule, delay status, or capacity changed"),
    FLIGHT_STATUS_CHANGED("Flight Status Changed", "Flight operational status transitioned"),
    FLIGHT_DELAYED("Flight Delayed", "Flight was marked delayed by operations"),
    FLIGHT_CANCELLED("Flight Cancelled", "Flight was cancelled by operations"),
    FLIGHT_DISRUPTED("Flight Disrupted", "Flight operational disruption logged"),
    REBOOKING_INITIATED("Rebooking Initiated", "Passenger rebooking workflow initiated"),
    REBOOKING_COMPLETED("Rebooking Completed", "Passenger successfully rebooked to alternative flight"),
    REPORT_GENERATED("Report Generated", "Operational intelligence report generated"),
    TENANT_DATA_MIGRATED("Tenant Data Migrated", "Multi-tenant ownership mapped to organization"),
    STAFF_CREATED("Staff Created", "Staff account created without exposing credentials"),
    STAFF_ENABLED("Staff Enabled", "Staff account access enabled"),
    STAFF_DISABLED("Staff Disabled", "Staff account access disabled"),
    ROLE_ASSIGNED("Role Assigned", "Staff role assigned"),
    ROLE_CHANGED("Role Changed", "Staff role changed"),
    USER_ROLE_CHANGED("User Role Changed", "Operator role or permission matrix modified"),
    STAFF_PERMISSION_CHANGE("Permission Modified", "Operator role or permission matrix modified"),
    BAGGAGE_REGISTERED("Baggage Registered", "Checked baggage registered and tag issued"),
    BAGGAGE_STATUS_CHANGED("Baggage Status Changed", "Baggage lifecycle status transitioned"),
    BAGGAGE_DELAYED("Baggage Delayed", "Baggage marked delayed with operational remark"),
    BAGGAGE_MISSING("Baggage Missing", "Baggage marked missing with operational remark"),
    PIR_CREATED("PIR Created", "Property Irregularity Report created for baggage incident"),
    PIR_STATUS_CHANGED("PIR Status Changed", "Property Irregularity Report status transitioned"),
    PIR_NOTE_ADDED("PIR Note Added", "Investigation note added to Property Irregularity Report"),
    CLAIM_CREATED("Claim Created", "Passenger baggage claim submitted"),
    CLAIM_STATUS_CHANGED("Claim Status Changed", "Passenger baggage claim status transitioned"),
    CLAIM_APPROVED("Claim Approved", "Passenger baggage claim approved for settlement"),
    CLAIM_REJECTED("Claim Rejected", "Passenger baggage claim rejected with remarks"),
    CLAIM_SETTLED("Claim Settled", "Passenger baggage claim compensation settlement executed"),
    CLAIM_CLOSED("Claim Closed", "Passenger baggage claim file finalized and closed"),
    DISRUPTION_DECLARED("Disruption Declared", "Flight disruption officially declared by operations"),
    DISRUPTION_UPDATED("Disruption Updated", "Flight disruption details, severity, or estimated times updated"),
    DISRUPTION_RESOLVED("Disruption Resolved", "Flight disruption marked resolved; passengers re-accommodated"),
    REACCOMMODATION_INITIATED("Re-accommodation Initiated", "Passenger alternate flight search and re-accommodation initiated"),
    REACCOMMODATION_COMPLETED("Re-accommodation Completed", "Passenger successfully re-accommodated on alternate flight"),
    REACCOMMODATION_FAILED("Re-accommodation Failed", "Passenger re-accommodation failed due to seat shortage or constraint"),
    VOUCHER_ISSUED("Voucher Issued", "Passenger disruption recovery voucher issued"),
    VOUCHER_CANCELLED("Voucher Cancelled", "Disruption voucher invalidated or cancelled"),
    VOUCHER_REDEEMED("Voucher Redeemed", "Disruption voucher marked as redeemed");

    private final String title;
    private final String description;

    AuditAction(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
