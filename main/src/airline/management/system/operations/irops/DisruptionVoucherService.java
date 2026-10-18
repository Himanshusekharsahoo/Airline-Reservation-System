package airline.management.system.operations.irops;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Service contract for IROPS passenger service recovery vouchers
 * (meal, hotel, transport, compensation, flight credit).
 */
public interface DisruptionVoucherService {

    static DisruptionVoucherService getInstance() {
        return DisruptionVoucherServiceImpl.getInstance();
    }

    /**
     * Issues an authoritative service recovery voucher for an affected passenger.
     */
    DisruptionVoucher issueVoucher(
        long disruptionId,
        long passengerId,
        long reservationId,
        VoucherType type,
        BigDecimal amount,
        String currency,
        String remarks
    ) throws SQLException;

    /**
     * Operationally redeems an active disruption voucher.
     */
    DisruptionVoucher redeemVoucher(long voucherId, String remarks) throws SQLException;

    /**
     * Operationally cancels an active disruption voucher.
     */
    DisruptionVoucher cancelVoucher(long voucherId, String reason) throws SQLException;

    /**
     * Finds a voucher by primary key within the active tenant.
     */
    Optional<DisruptionVoucher> findById(long voucherId) throws SQLException;

    /**
     * Finds a voucher by unique reference code within the active tenant.
     */
    Optional<DisruptionVoucher> findByReference(String voucherReference) throws SQLException;

    /**
     * Retrieves all vouchers issued for a given disruption.
     */
    List<DisruptionVoucher> getVouchersForDisruption(long disruptionId) throws SQLException;

    /**
     * Retrieves all disruption vouchers issued to a specific passenger.
     */
    List<DisruptionVoucher> getVouchersForPassenger(long passengerId) throws SQLException;

    /**
     * Retrieves all disruption vouchers associated with a specific reservation.
     */
    List<DisruptionVoucher> getVouchersForReservation(long reservationId) throws SQLException;

    /**
     * Searches vouchers with optional status and type filtering.
     */
    List<DisruptionVoucher> searchVouchers(String query, VoucherStatus statusFilter, VoucherType typeFilter) throws SQLException;
}
