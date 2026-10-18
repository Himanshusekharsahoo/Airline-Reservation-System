package airline.management.system.operations.boarding;

/**
 * Passenger entry on the active flight boarding manifest.
 */
public record BoardingPassenger(
    String pnr,
    String passengerName,
    String seatNumber,
    String status,
    boolean boarded,
    boolean checkedIn,
    String boardingPassNumber
) {
    public BoardingPassenger(String pnr, String passengerName, String seatNumber, String status, boolean boarded) {
        this(pnr, passengerName, seatNumber, status, boarded, boarded, null);
    }
}
