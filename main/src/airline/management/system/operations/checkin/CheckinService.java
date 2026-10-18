package airline.management.system.operations.checkin;

import java.sql.SQLException;
import java.util.List;

/**
 * Service interface for passenger check-in operations.
 */
public interface CheckinService {
    /**
     * Looks up reservation details, passenger info, flight info, and payment status by PNR.
     */
    CheckinPassengerDetails lookupReservation(String pnr) throws SQLException;

    /**
     * Searches reservations by passenger name, PNR, or flight number.
     */
    List<CheckinPassengerDetails> searchReservations(String query) throws SQLException;

    /**
     * Validates passenger eligibility and processes check-in.
     */
    CheckinResult processCheckin(String pnr, String seatNumber) throws SQLException;

    /**
     * Retrieves authoritative boarding pass record by PNR or boarding pass number.
     */
    BoardingPassRecord getBoardingPass(String pnr) throws SQLException;

    /**
     * Retrieves authoritative boarding pass record by checkin ID.
     */
    BoardingPassRecord getBoardingPassByCheckinId(long checkinId) throws SQLException;

    /**
     * Singleton accessor for the default CheckinService implementation.
     */
    static CheckinService getInstance() {
        return CheckinServiceImpl.INSTANCE;
    }
}
