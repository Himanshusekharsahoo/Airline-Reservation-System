package airline.management.system.operations.gate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service interface governing airport gate management and flight gate assignments.
 */
public interface GateService {
    List<GateRecord> getAllGates() throws SQLException;
    List<GateRecord> getGatesByAirport(String airportCode) throws SQLException;
    FlightGateAssignment getFlightGateAssignment(long flightId) throws SQLException;
    FlightGateAssignment assignFlightGate(long flightId, long gateId, LocalDateTime start, LocalDateTime end) throws SQLException;
    void releaseFlightGate(long flightId) throws SQLException;

    static GateService getInstance() {
        return GateServiceImpl.INSTANCE;
    }
}
