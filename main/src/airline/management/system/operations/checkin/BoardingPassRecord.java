package airline.management.system.operations.checkin;

import java.time.LocalDateTime;

/**
 * Authoritative boarding pass entity record containing verified flight, seat, and gate information.
 */
public record BoardingPassRecord(
    Long boardingPassId,
    Long checkinId,
    String boardingPassNumber,
    String pnr,
    String passengerName,
    String flightNumber,
    String airline,
    String source,
    String destination,
    LocalDateTime departureTime,
    LocalDateTime arrivalTime,
    String seatNumber,
    String gate,
    String boardingGroup,
    String checkinStatus,
    LocalDateTime issuedAt
) {
    public String getRoute() {
        return source + " → " + destination;
    }

    public String getFormattedGate() {
        return (gate != null && !gate.isBlank()) ? gate : "Not assigned";
    }
}
