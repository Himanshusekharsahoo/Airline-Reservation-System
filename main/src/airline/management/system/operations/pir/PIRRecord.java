package airline.management.system.operations.pir;

import airline.management.system.operations.baggage.BaggageStatus;

import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authoritative Property Irregularity Report.
 */
public record PIRRecord(
    long id,
    long organizationId,
    String pirReference,
    long baggageId,
    String baggageTag,
    String baggageType,
    BaggageStatus baggageStatus,
    long passengerId,
    String passengerName,
    String passengerPhone,
    String passengerEmail,
    long reservationId,
    String pnr,
    long flightId,
    String flightNumber,
    String route,
    PIRType type,
    PIRStatus status,
    LocalDateTime incidentTime,
    String stationCode,
    String description,
    String investigationNotes,
    Long createdByUserId,
    LocalDateTime resolvedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public PIRRecord(
        long id, long organizationId, String pirReference, long baggageId, String baggageTag,
        BaggageStatus baggageStatus, long passengerId, String passengerName, String passengerPhone,
        String passengerEmail, long reservationId, String pnr, long flightId, String flightNumber,
        String route, PIRType type, PIRStatus status, LocalDateTime incidentTime, String stationCode,
        String description, String investigationNotes, Long createdByUserId, LocalDateTime resolvedAt,
        LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(id, organizationId, pirReference, baggageId, baggageTag, "CHECKED", baggageStatus,
             passengerId, passengerName, passengerPhone, passengerEmail, reservationId, pnr,
             flightId, flightNumber, route, type, status, incidentTime, stationCode, description,
             investigationNotes, createdByUserId, resolvedAt, createdAt, updatedAt);
    }

    public boolean isTerminal() {
        return status == PIRStatus.CLOSED;
    }

    public boolean isActive() {
        return status != PIRStatus.CLOSED;
    }
}
