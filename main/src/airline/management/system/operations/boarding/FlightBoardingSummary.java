package airline.management.system.operations.boarding;

import java.time.LocalDateTime;

/**
 * Operational flight overview for gate boarding agents.
 * Derived from authoritative database flights and confirmed reservations records.
 */
public record FlightBoardingSummary(
    Long flightId,
    String flightNumber,
    String airline,
    String source,
    String destination,
    LocalDateTime departureTime,
    int totalSeats,
    int availableSeats,
    int bookedPassengers,
    BoardingStatus status,
    String assignedGate
) {
    public FlightBoardingSummary(
        Long flightId, String flightNumber, String airline, String source, String destination,
        LocalDateTime departureTime, int totalSeats, int availableSeats, int bookedPassengers,
        BoardingStatus status
    ) {
        this(flightId, flightNumber, airline, source, destination, departureTime, totalSeats, availableSeats, bookedPassengers, status, null);
    }

    public String getRoute() {
        return source + " → " + destination;
    }

    public String getFormattedGate() {
        return (assignedGate != null && !assignedGate.isBlank()) ? assignedGate : "Not assigned";
    }

    @Override
    public String toString() {
        if (assignedGate != null && !assignedGate.isBlank()) {
            return flightNumber + " (" + getRoute() + ") [Gate: " + assignedGate + "]";
        }
        return flightNumber + " (" + getRoute() + ")";
    }
}
