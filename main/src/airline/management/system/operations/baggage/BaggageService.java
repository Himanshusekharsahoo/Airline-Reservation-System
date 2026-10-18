package airline.management.system.operations.baggage;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Service contract for airline baggage operations, registration, status transitions,
 * tracking event persistence, and operational intelligence.
 */
public interface BaggageService {

    record BaggageSummary(
        int totalRegistered,
        int accepted,
        int loaded,
        int inTransit,
        int delivered,
        int delayed,
        int missing,
        int cancelled,
        BigDecimal totalWeightKg,
        BigDecimal averageWeightKg
    ) {}

    /**
     * Registers a checked baggage unit against a confirmed, checked-in passenger reservation.
     * Generates a unique baggage tag and records initial lifecycle events.
     */
    BaggageRecord registerBaggage(String pnr, BigDecimal weightKg, BaggageType type, String remarks) throws SQLException;

    /**
     * Transactionally transitions baggage lifecycle status, verifies state machine rules,
     * logs tracking events, and creates audit records.
     */
    BaggageRecord updateBaggageStatus(long baggageId, BaggageStatus newStatus, String locationCode, String remarks) throws SQLException;

    /**
     * Looks up a baggage record by its unique tag number within the active tenant.
     */
    Optional<BaggageRecord> findByTagNumber(String tagNumber) throws SQLException;

    /**
     * Looks up a baggage record by its primary key ID within the active tenant.
     */
    Optional<BaggageRecord> findById(long baggageId) throws SQLException;

    /**
     * Retrieves all baggage records associated with the specified reservation ID.
     */
    List<BaggageRecord> getBaggageByReservation(long reservationId) throws SQLException;

    /**
     * Retrieves all baggage records associated with the specified PNR within active tenant.
     */
    List<BaggageRecord> getBaggageByPnr(String pnr) throws SQLException;

    /**
     * Retrieves all baggage records associated with the specified checkin ID.
     */
    List<BaggageRecord> getBaggageByCheckin(long checkinId) throws SQLException;

    /**
     * Searches baggage records across tag numbers, PNRs, passenger names, and flights.
     */
    List<BaggageRecord> searchBaggage(String query, BaggageStatus statusFilter, Long flightIdFilter) throws SQLException;

    /**
     * Searches baggage records across tag numbers, PNRs, passenger names, and flights scoped to an operational station.
     */
    List<BaggageRecord> searchBaggage(String query, BaggageStatus statusFilter, Long flightIdFilter, String stationCode) throws SQLException;

    /**
     * Retrieves the chronological tracking history of events for a baggage unit.
     */
    List<BaggageEventRecord> getBaggageHistory(long baggageId) throws SQLException;

    /**
     * Computes tenant-scoped SQL aggregated summary metrics for baggage.
     */
    BaggageSummary getBaggageSummary(Long flightIdFilter) throws SQLException;

    /**
     * Computes tenant-scoped SQL aggregated summary metrics for baggage scoped to an operational station.
     */
    BaggageSummary getBaggageSummary(Long flightIdFilter, String stationCode) throws SQLException;

    /**
     * Singleton accessor for the baggage service implementation.
     */
    static BaggageService getInstance() {
        return BaggageServiceImpl.INSTANCE;
    }
}
