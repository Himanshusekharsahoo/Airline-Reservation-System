package airline.management.system.operations.boarding;

/**
 * Result of boarding pass verification and passenger gate clearance.
 */
public record BoardingValidationResult(
    boolean valid,
    String pnr,
    String passengerName,
    String seatNumber,
    String message,
    String schemaNotice
) {
    public static final String SCHEMA_NOTICE =
        "Phase 11 Operations Architecture: Gate validation executed and updated in active memory. " +
        "Hardware barcode turnstile integration, gate device polling, and permanent boarding event " +
        "history require the 'gates', 'flight_gates', and 'boarding_events' tables (scheduled for Phase 12).";

    public static BoardingValidationResult success(String pnr, String passengerName, String seatNumber, String message) {
        return new BoardingValidationResult(true, pnr, passengerName, seatNumber, message, SCHEMA_NOTICE);
    }

    public static BoardingValidationResult failure(String pnr, String message) {
        return new BoardingValidationResult(false, pnr, null, null, message, SCHEMA_NOTICE);
    }
}
