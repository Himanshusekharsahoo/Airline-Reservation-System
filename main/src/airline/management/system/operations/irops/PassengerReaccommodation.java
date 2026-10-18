package airline.management.system.operations.irops;

import java.time.LocalDateTime;

/**
 * Immutable domain record representing an individual passenger re-accommodation workflow.
 */
public record PassengerReaccommodation(
    long id,
    long organizationId,
    long disruptionId,
    String disruptionReference,
    long originalReservationId,
    String originalPnr,
    long passengerId,
    String passengerName,
    String passengerPhone,
    long originalFlightId,
    String originalFlightNumber,
    Long newFlightId,
    String newFlightNumber,
    Long newReservationId,
    String newPnr,
    String newSeatNumber,
    ReaccommodationStatus status,
    ReaccommodationType reaccommodationType,
    NotificationStatus notificationStatus,
    Long initiatedByUserId,
    String initiatedByUsername,
    LocalDateTime completedAt,
    String remarks,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isSuccessful() {
        return status != null && status.isSuccessful();
    }
}
