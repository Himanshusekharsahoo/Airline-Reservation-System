package airline.management.system.operations.checkin;

/**
 * Result model returned upon check-in processing.
 * Clearly separates operational outcome from pending database schema capabilities.
 */
public record CheckinResult(
    boolean success,
    CheckinStatus status,
    CheckinPassengerDetails details,
    String message,
    String schemaNotice
) {
    public static final String SCHEMA_NOTICE =
        "Phase 11 Operations Architecture: Verified and processed in operational memory. " +
        "Permanent database persistence and automated barcode boarding pass generation " +
        "require the 'checkins' and 'boarding_passes' tables (scheduled for Phase 12 migration).";

    public static CheckinResult success(CheckinPassengerDetails details, String message) {
        return new CheckinResult(true, CheckinStatus.CHECKED_IN, details, message, SCHEMA_NOTICE);
    }

    public static CheckinResult failure(CheckinStatus status, CheckinPassengerDetails details, String message) {
        return new CheckinResult(false, status, details, message, SCHEMA_NOTICE);
    }
}
