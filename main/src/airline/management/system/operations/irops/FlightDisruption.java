package airline.management.system.operations.irops;

import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authoritative flight disruption declaration.
 */
public record FlightDisruption(
    long id,
    long organizationId,
    long flightId,
    String flightNumber,
    String route,
    String disruptionReference,
    DisruptionType type,
    DisruptionReason reason,
    DisruptionSeverity severity,
    LocalDateTime declaredAt,
    Long declaredByUserId,
    String declaredByUsername,
    LocalDateTime scheduledDeparture,
    LocalDateTime estimatedDeparture,
    int delayMinutes,
    String impactDescription,
    int passengerImpactCount,
    DisruptionStatus status,
    LocalDateTime resolvedAt,
    Long resolvedByUserId,
    String resolvedByUsername,
    String remarks,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isActive() {
        return status != null && status.isActive();
    }
}
