package airline.management.system.operations.irops;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable domain record representing an authorized disruption amenity or recovery voucher.
 */
public record DisruptionVoucher(
    long id,
    long organizationId,
    long disruptionId,
    String disruptionReference,
    long passengerId,
    String passengerName,
    long reservationId,
    String pnr,
    String voucherReference,
    VoucherType type,
    BigDecimal amount,
    String currency,
    VoucherStatus status,
    Long issuedByUserId,
    String issuedByUsername,
    LocalDateTime issuedAt,
    LocalDateTime redeemedAt,
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
