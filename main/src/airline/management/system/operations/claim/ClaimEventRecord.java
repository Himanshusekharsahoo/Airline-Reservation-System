package airline.management.system.operations.claim;

import java.time.LocalDateTime;

/**
 * Immutable record representing an audit/event log entry for a PIR or Claim lifecycle transition.
 */
public record ClaimEventRecord(
    long id,
    long organizationId,
    String entityType,
    long entityId,
    String eventType,
    Long performedByUserId,
    String performedByUsername,
    String remarks,
    LocalDateTime createdAt
) {}
