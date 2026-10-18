package airline.management.system.operations.baggage;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authoritative checked baggage unit.
 */
public record BaggageRecord(
    long id,
    long organizationId,
    long checkinId,
    long passengerId,
    String passengerName,
    long reservationId,
    String pnr,
    long flightId,
    String flightNumber,
    String route,
    String seatNumber,
    String tagNumber,
    BaggageType type,
    BigDecimal weightKg,
    BaggageStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public boolean isTerminal() {
        return status == BaggageStatus.DELIVERED || status == BaggageStatus.CANCELLED;
    }

    public boolean isException() {
        return status == BaggageStatus.DELAYED || status == BaggageStatus.MISSING;
    }
}
