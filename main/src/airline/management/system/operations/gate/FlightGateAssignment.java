package airline.management.system.operations.gate;

import java.time.LocalDateTime;

/**
 * Authoritative flight-to-gate schedule allocation record.
 */
public record FlightGateAssignment(
    Long id,
    Long flightId,
    String flightNumber,
    String route,
    Long gateId,
    String gateNumber,
    String terminal,
    LocalDateTime scheduledStart,
    LocalDateTime scheduledEnd,
    String status
) {
    public String getFormattedGate() {
        return "Gate " + gateNumber + " (" + terminal + ")";
    }
}
