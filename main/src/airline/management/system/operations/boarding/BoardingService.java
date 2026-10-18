package airline.management.system.operations.boarding;

import java.sql.SQLException;
import java.util.List;

/**
 * Service interface governing gate operations, passenger boarding manifest inspection,
 * and boarding verification.
 */
public interface BoardingService {
    /**
     * Loads scheduled flights available for gate operations.
     */
    List<FlightBoardingSummary> getScheduledFlights() throws SQLException;

    /**
     * Loads scheduled flights available for gate operations filtered by departure station.
     */
    List<FlightBoardingSummary> getScheduledFlights(String stationCode) throws SQLException;

    /**
     * Loads the passenger manifest for the specified flight.
     */
    List<BoardingPassenger> getPassengerManifest(long flightId) throws SQLException;

    /**
     * Verifies passenger boarding pass and marks them as boarded.
     */
    BoardingValidationResult validateAndBoard(String pnr, long flightId) throws SQLException;

    /**
     * Sets gate boarding lifecycle state.
     */
    void updateFlightBoardingStatus(long flightId, BoardingStatus status);

    /**
     * Retrieves gate boarding lifecycle state.
     */
    BoardingStatus getFlightBoardingStatus(long flightId);

    /**
     * Singleton accessor for default implementation.
     */
    static BoardingService getInstance() {
        return BoardingServiceImpl.INSTANCE;
    }
}
