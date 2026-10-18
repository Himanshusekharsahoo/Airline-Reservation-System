package airline.management.system.ui.navigation;

import airline.management.system.security.Permission;

/**
 * AeroDesk Operations Navigation Routes.
 * Incorporates permissions and operational staff terminology.
 */
public enum NavRoute {
    DASHBOARD("Dashboard", "Operations overview, live flight status, and fleet metrics", Permission.VIEW_DASHBOARD, "OVERVIEW"),
    STAFF("Staff Management", "Staff accounts, roles, and access status", Permission.MANAGE_USERS, "SYSTEM"),
    FLIGHTS("Flight Operations", "Scheduled flight tracking, routes, and seat availability", Permission.MANAGE_FLIGHTS, "OPERATIONS"),
    BOOKING("Reservation Desk", "Passenger booking, seat allocation, and ticketing console", Permission.MANAGE_RESERVATIONS, "OPERATIONS"),
    PASSENGERS("Passengers", "Passenger profiles, contact records, and travel history", Permission.MANAGE_PASSENGERS, "OPERATIONS"),
    CHECKIN("Passenger Check-in", "Passenger verification, seat confirmation, and boarding passes", Permission.CHECKIN_PASSENGER, "OPERATIONS"),
    BAGGAGE("Baggage Operations", "Luggage tracking, tag issuance, and handling exceptions", Permission.MANAGE_BAGGAGE, "OPERATIONS"),
    PIR_CLAIMS("PIR & Claims", "Baggage irregularity reports, investigations, and claim settlements", Permission.MANAGE_PIR, "OPERATIONS"),
    IROPS("Disruptions & IROPS", "Flight disruptions, irregular operations, passenger re-accommodation, and vouchers", Permission.VIEW_DISRUPTIONS, "OPERATIONS"),
    BOARDING("Gate & Boarding", "Boarding gate console, boarding pass verification, and passenger boarding", Permission.MANAGE_BOARDING, "OPERATIONS"),
    TRIPS("Trip History", "Passenger journey records and confirmed itinerary details", Permission.MANAGE_RESERVATIONS, "OPERATIONS"),
    PAYMENTS("Payments & Settlement", "Transaction records, payment status, and financial reconciliation", Permission.MANAGE_PAYMENTS, "OPERATIONS"),
    CANCELLATIONS("Cancellation Management", "Reservation cancellation, seat release, and refund operations", Permission.MANAGE_CANCELLATIONS, "OPERATIONS"),
    REPORTS("Operational Reports", "Flight, passenger, reservation, and financial intelligence", Permission.VIEW_REPORTS, "OPERATIONS"),
    SETTINGS("Settings", "System configuration, database connectivity, and role status", Permission.MANAGE_USERS, "SYSTEM");

    private final String title;
    private final String subtitle;
    private final Permission requiredPermission;
    private final String section;

    NavRoute(String title, String subtitle, Permission requiredPermission, String section) {
        this.title = title;
        this.subtitle = subtitle;
        this.requiredPermission = requiredPermission;
        this.section = section;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public Permission getRequiredPermission() {
        return requiredPermission;
    }

    public String getSection() {
        return section;
    }

    public String getKey() {
        return name().toLowerCase();
    }
}
