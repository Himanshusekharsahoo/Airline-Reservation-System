package airline.management.system.operations.pir;

import airline.management.system.operations.claim.ClaimEventRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Service contract for Property Irregularity Report (PIR) management,
 * investigation workflow, tracking event logging, and status transitions.
 */
public interface PIRService {

    /**
     * Creates an authoritative Property Irregularity Report against a mishandled baggage item.
     * Enforces tenant isolation, baggage status prerequisites, and prevents duplicate active PIRs.
     */
    PIRRecord createPIR(long baggageId, PIRType type, String stationCode, String description) throws SQLException;

    /**
     * Transitions PIR lifecycle status, verifies state machine rules, records event, and audits change.
     */
    PIRRecord updatePIRStatus(long pirId, PIRStatus newStatus, String remarks) throws SQLException;

    /**
     * Appends operational staff notes to an existing PIR investigation record.
     */
    PIRRecord addInvestigationNotes(long pirId, String notes) throws SQLException;

    /**
     * Looks up a PIR by its internal database ID within the active tenant.
     */
    Optional<PIRRecord> findById(long pirId) throws SQLException;

    /**
     * Looks up a PIR by its unique reference code (e.g., PIRXXXXXXXX) within the active tenant.
     */
    Optional<PIRRecord> findByReference(String pirReference) throws SQLException;

    /**
     * Finds the currently active (non-closed) PIR for a baggage unit, if one exists.
     */
    Optional<PIRRecord> findActiveByBaggageId(long baggageId) throws SQLException;

    /**
     * Finds the currently active (non-closed) PIR for a baggage unit by baggage tag, if one exists.
     */
    Optional<PIRRecord> findActiveByBaggageTag(String baggageTag) throws SQLException;

    /**
     * Searches PIR records matching search query across reference, baggage tag, passenger, or PNR.
     */
    List<PIRRecord> searchPIRs(String query, PIRStatus statusFilter) throws SQLException;

    /**
     * Retrieves all PIRs associated with a reservation ID within the active tenant.
     */
    List<PIRRecord> getPIRsByReservation(long reservationId) throws SQLException;

    /**
     * Retrieves all PIRs associated with a PNR within the active tenant.
     */
    List<PIRRecord> getPIRsByPnr(String pnr) throws SQLException;

    /**
     * Retrieves the chronological event audit history for a PIR.
     */
    List<ClaimEventRecord> getPIREvents(long pirId) throws SQLException;

    /**
     * Singleton accessor for the PIR service implementation.
     */
    static PIRService getInstance() {
        return PIRServiceImpl.INSTANCE;
    }
}
