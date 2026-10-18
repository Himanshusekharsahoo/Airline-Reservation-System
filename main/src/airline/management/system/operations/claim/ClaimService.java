package airline.management.system.operations.claim;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Service contract for passenger baggage irregularity claims, operational review,
 * status transitions, and compensation settlement execution.
 */
public interface ClaimService {

    /**
     * Files an authoritative passenger baggage claim linked to an active Property Irregularity Report.
     * Enforces tenant isolation, input validation, and prevents duplicate active claims for the same PIR.
     */
    ClaimCase createClaim(long pirId, BigDecimal requestedAmount, String currency, String claimReason) throws SQLException;

    /**
     * Operationally reviews a claim, transitioning its status (e.g. UNDER_REVIEW, APPROVED, REJECTED),
     * enforcing state machine rules, setting approved compensation amount, and logging audit events.
     */
    ClaimCase reviewClaim(long claimId, ClaimStatus newStatus, BigDecimal approvedAmount, String reviewNotes) throws SQLException;

    /**
     * Transactionally executes compensation settlement for an APPROVED claim.
     * Verifies financial permissions, creates a distinct settlement record, updates claim status to SETTLED,
     * and prevents duplicate settlements via row locking.
     */
    ClaimSettlementRecord settleClaim(long claimId, String paymentMethod, String settlementReason) throws SQLException;

    /**
     * Looks up a claim by its primary database ID within the active tenant.
     */
    Optional<ClaimCase> findById(long claimId) throws SQLException;

    /**
     * Looks up a claim by its unique reference code (e.g., CLMXXXXXXXX) within the active tenant.
     */
    Optional<ClaimCase> findByReference(String claimReference) throws SQLException;

    /**
     * Finds the active (non-closed) claim for a PIR, if one exists.
     */
    Optional<ClaimCase> findActiveByPirId(long pirId) throws SQLException;

    /**
     * Searches claims matching query across reference, PIR reference, baggage tag, passenger, or PNR.
     */
    List<ClaimCase> searchClaims(String query, ClaimStatus statusFilter) throws SQLException;

    /**
     * Retrieves the settlement record for a claim if settled.
     */
    Optional<ClaimSettlementRecord> getSettlementByClaimId(long claimId) throws SQLException;

    /**
     * Retrieves the chronological event audit history for a claim.
     */
    List<ClaimEventRecord> getClaimEvents(long claimId) throws SQLException;

    /**
     * Singleton accessor for the claim service implementation.
     */
    static ClaimService getInstance() {
        return ClaimServiceImpl.INSTANCE;
    }
}
