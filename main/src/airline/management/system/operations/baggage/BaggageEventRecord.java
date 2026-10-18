package airline.management.system.operations.baggage;

import java.time.LocalDateTime;

/**
 * Immutable domain record representing an operational baggage tracking event.
 */
public record BaggageEventRecord(
    long id,
    long baggageId,
    String tagNumber,
    long organizationId,
    BaggageEventType eventType,
    String locationCode,
    Long scannedByUserId,
    String scannedByUsername,
    String remarks,
    LocalDateTime scannedAt
) {}
