package airline.management.system.operations.claim;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authorized claim compensation settlement.
 */
public record ClaimSettlementRecord(
    long id,
    long organizationId,
    String settlementReference,
    long claimId,
    String claimReference,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String settlementStatus,
    String settlementReason,
    Long authorizedByUserId,
    String authorizedByUsername,
    LocalDateTime settledAt
) {}
