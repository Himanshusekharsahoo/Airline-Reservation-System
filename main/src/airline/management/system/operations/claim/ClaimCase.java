package airline.management.system.operations.claim;

import airline.management.system.operations.pir.PIRStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authoritative passenger baggage claim.
 */
public record ClaimCase(
    long id,
    long organizationId,
    String claimReference,
    long pirId,
    String pirReference,
    PIRStatus pirStatus,
    long baggageId,
    String baggageTag,
    long passengerId,
    String passengerName,
    long reservationId,
    String pnr,
    ClaimStatus status,
    String currency,
    BigDecimal requestedAmount,
    BigDecimal approvedAmount,
    String claimReason,
    String reviewNotes,
    Long createdByUserId,
    Long reviewedByUserId,
    LocalDateTime settledAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public boolean isTerminal() {
        return status == ClaimStatus.CLOSED;
    }

    public boolean isApproved() {
        return status == ClaimStatus.APPROVED;
    }

    public boolean isSettled() {
        return status == ClaimStatus.SETTLED;
    }
}
