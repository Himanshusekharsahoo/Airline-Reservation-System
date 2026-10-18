package airline.management.system.operations.irops;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Service contract for flight disruptions, irregular operations declarations,
 * affected passenger discovery, and disruption resolution.
 */
public interface DisruptionService {

    /**
     * Retrieves the singleton instance of the DisruptionService.
     */
    static DisruptionService getInstance() {
        return DisruptionServiceImpl.getInstance();
    }

    /**
     * Declares an authoritative operational flight disruption.
     * Enforces tenant isolation, input validation, and prevents duplicate active disruptions for the same flight.
     */
    FlightDisruption declareDisruption(
        long flightId,
        DisruptionType type,
        DisruptionReason reason,
        DisruptionSeverity severity,
        Integer delayMinutes,
        String impactDescription,
        String remarks
    ) throws SQLException;

    /**
     * Updates an active disruption's severity, delay estimate, impact description, or operational notes.
     */
    FlightDisruption updateDisruption(
        long disruptionId,
        DisruptionSeverity severity,
        Integer delayMinutes,
        String impactDescription,
        String remarks
    ) throws SQLException;

    /**
     * Operationally transitions a disruption's lifecycle status, enforcing state machine rules.
     */
    FlightDisruption changeStatus(
        long disruptionId,
        DisruptionStatus newStatus,
        String remarks
    ) throws SQLException;

    /**
     * Concludes and marks a disruption as RESOLVED, recording resolution timestamp and operator.
     */
    FlightDisruption resolveDisruption(
        long disruptionId,
        String remarks
    ) throws SQLException;

    /**
     * Looks up a flight disruption by its primary database ID within the active tenant.
     */
    Optional<FlightDisruption> findById(long disruptionId) throws SQLException;

    /**
     * Looks up a flight disruption by its unique reference code within the active tenant.
     */
    Optional<FlightDisruption> findByReference(String disruptionReference) throws SQLException;

    /**
     * Finds the active (non-resolved, non-cancelled) disruption for a flight, if one exists.
     */
    Optional<FlightDisruption> findActiveByFlightId(long flightId) throws SQLException;

    /**
     * Searches disruptions matching query across reference, flight number, route, or reason.
     */
    List<FlightDisruption> searchDisruptions(String query, DisruptionStatus statusFilter) throws SQLException;

    /**
     * Retrieves the authoritative affected passenger manifest for a disruption,
     * compiling reservation, payment, check-in, baggage, PIR, and re-accommodation details.
     */
    List<DisruptedPassengerManifestItem> getAffectedPassengerManifest(long disruptionId) throws SQLException;
}
