package airline.management.system.operations.boarding;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative BoardingService implementation operating directly over MySQL relations:
 * flights, reservations, gates, flight_gates, checkins, boarding_passes, and boarding_events.
 */
public final class BoardingServiceImpl implements BoardingService {
    public static final BoardingServiceImpl INSTANCE = new BoardingServiceImpl();

    // In-memory gate boarding operational phase tracking
    private final Map<Long, BoardingStatus> flightStatuses = new ConcurrentHashMap<>();

    private BoardingServiceImpl() {}

    @Override
    public List<FlightBoardingSummary> getScheduledFlights() throws SQLException {
        OperationalStation active = StationContext.getCurrentStation();
        String code = active != null ? active.getCode() : "DEL";
        return getScheduledFlights(code);
    }

    @Override
    public List<FlightBoardingSummary> getScheduledFlights(String stationCode) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return getScheduledFlights(conn, stationCode);
        }
    }

    public List<FlightBoardingSummary> getScheduledFlights(Connection conn, String stationCode) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String targetCode = (stationCode != null && !stationCode.isBlank())
                ? stationCode.trim()
                : (StationContext.getCurrentStation() != null ? StationContext.getCurrentStation().getCode() : "DEL");

        Optional<OperationalStation> stationOpt = StationContext.findStation(targetCode);

        StringBuilder sql = new StringBuilder(
            "SELECT f.id, f.flight_number, f.airline, f.source, f.destination, " +
            "       f.departure_time, f.total_seats, f.available_seats, " +
            "       (SELECT COUNT(*) FROM reservations r WHERE r.flight_id = f.id AND r.status = 'CONFIRMED' AND r.organization_id = ?) AS booked_count, " +
            "       (SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id WHERE fg.flight_id = f.id AND fg.organization_id = ? ORDER BY fg.id DESC LIMIT 1) AS assigned_gate " +
            "FROM flights f " +
            "WHERE f.organization_id = ? AND f.status IN ('SCHEDULED', 'ACTIVE') "
        );

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);
        params.add(tenantOrgId);
        params.add(tenantOrgId);

        if (stationOpt.isPresent()) {
            OperationalStation st = stationOpt.get();
            String stCode = st.getCode();
            String stCity = st.getCity().split(",")[0].trim();
            sql.append("  AND (f.source = ? OR f.source = ?) ");
            params.add(stCode);
            params.add(stCity);
        } else {
            // Unknown station: fail closed by matching exact code only (will yield 0 results, never leaks all stations)
            sql.append("  AND f.source = ? ");
            params.add(targetCode);
        }

        sql.append("ORDER BY f.departure_time ASC LIMIT 50");

        List<FlightBoardingSummary> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long flightId = rs.getLong("id");
                    Timestamp dep = rs.getTimestamp("departure_time");
                    BoardingStatus status = flightStatuses.getOrDefault(flightId, BoardingStatus.SCHEDULED);
                    String assignedGate = rs.getString("assigned_gate");

                    list.add(new FlightBoardingSummary(
                        flightId,
                        rs.getString("flight_number"),
                        rs.getString("airline"),
                        rs.getString("source"),
                        rs.getString("destination"),
                        dep != null ? dep.toLocalDateTime() : LocalDateTime.now(),
                        rs.getInt("total_seats"),
                        rs.getInt("available_seats"),
                        rs.getInt("booked_count"),
                        status,
                        assignedGate
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public List<BoardingPassenger> getPassengerManifest(long flightId) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql =
            "SELECT r.pnr, p.name AS passenger_name, " +
            "       COALESCE(c.seat_assigned, r.seat_number, '—') AS display_seat, " +
            "       r.status AS res_status, " +
            "       c.id AS checkin_id, " +
            "       c.status AS checkin_status, " +
            "       bp.boarding_pass_number " +
            "FROM reservations r " +
            "JOIN passengers p ON p.id = r.passenger_id " +
            "LEFT JOIN checkins c ON c.reservation_id = r.id " +
            "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
            "WHERE r.flight_id = ? AND r.organization_id = ? AND r.status = 'CONFIRMED' " +
            "ORDER BY display_seat ASC, p.name ASC";

        List<BoardingPassenger> manifest = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, flightId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String pnr = rs.getString("pnr");
                    long cId = rs.getLong("checkin_id");
                    boolean checkedIn = !rs.wasNull() && cId > 0;
                    String checkinStatus = rs.getString("checkin_status");
                    boolean isBoarded = "BOARDED".equalsIgnoreCase(checkinStatus);
                    String bpNum = rs.getString("boarding_pass_number");

                    manifest.add(new BoardingPassenger(
                        pnr,
                        rs.getString("passenger_name"),
                        rs.getString("display_seat"),
                        rs.getString("res_status"),
                        isBoarded,
                        checkedIn,
                        bpNum
                    ));
                }
            }
        }
        return manifest;
    }

    @Override
    public BoardingValidationResult validateAndBoard(String query, long flightId) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_BOARDING);

        if (query == null || query.trim().isEmpty()) {
            return BoardingValidationResult.failure("—", "Search query or boarding pass is required.");
        }

        String cleanQuery = query.trim();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql =
                    "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, r.flight_id, " +
                    "       p.name AS passenger_name, " +
                    "       c.id AS checkin_id, c.seat_assigned, c.status AS checkin_status, " +
                    "       bp.id AS bp_id, bp.boarding_pass_number " +
                    "FROM reservations r " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "LEFT JOIN checkins c ON c.reservation_id = r.id " +
                    "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
                    "WHERE (r.pnr = ? OR (bp.boarding_pass_number IS NOT NULL AND bp.boarding_pass_number = ?)) " +
                    "  AND r.organization_id = ? " +
                    "FOR UPDATE";

                long resFlightId = 0;
                String pnr = cleanQuery;
                String passengerName = null;
                String seatNumber = null;
                String resStatus = null;
                Long checkinId = null;
                String checkinStatus = null;
                Long bpId = null;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, cleanQuery);
                    stmt.setString(2, cleanQuery);
                    stmt.setLong(3, tenantOrgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            resFlightId = rs.getLong("flight_id");
                            pnr = rs.getString("pnr");
                            passengerName = rs.getString("passenger_name");
                            resStatus = rs.getString("res_status");

                            long cId = rs.getLong("checkin_id");
                            if (!rs.wasNull() && cId > 0) {
                                checkinId = cId;
                                checkinStatus = rs.getString("checkin_status");
                                seatNumber = rs.getString("seat_assigned");
                            } else {
                                seatNumber = rs.getString("seat_number");
                            }

                            long bId = rs.getLong("bp_id");
                            if (!rs.wasNull() && bId > 0) {
                                bpId = bId;
                            }
                        } else {
                            conn.rollback();
                            return BoardingValidationResult.failure(cleanQuery, "Booking not found for identifier [" + cleanQuery + "].");
                        }
                    }
                }

                if (resFlightId != flightId) {
                    conn.rollback();
                    return BoardingValidationResult.failure(pnr,
                        "PNR [" + pnr + "] is booked for a different flight (Flight ID #" + resFlightId + "). Boarding denied.");
                }

                if (!"CONFIRMED".equalsIgnoreCase(resStatus)) {
                    conn.rollback();
                    return BoardingValidationResult.failure(pnr,
                        "Booking is " + resStatus + ". Only CONFIRMED reservations can be boarded.");
                }

                if (checkinId == null || bpId == null) {
                    conn.rollback();
                    return BoardingValidationResult.failure(pnr,
                        "Passenger with PNR [" + pnr + "] has not completed check-in. Check-in is required before boarding.");
                }

                if ("BOARDED".equalsIgnoreCase(checkinStatus)) {
                    conn.rollback();
                    return BoardingValidationResult.failure(pnr,
                        "Passenger with PNR [" + pnr + "] is already recorded as BOARDED.");
                }

                // 1. Insert into boarding_events
                String insertEventSql =
                    "INSERT INTO boarding_events (organization_id, boarding_pass_id, flight_id, gate_agent_user_id, outcome, remarks) " +
                    "VALUES (?, ?, ?, ?, 'BOARDED', ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertEventSql)) {
                    stmt.setLong(1, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
                    stmt.setLong(2, bpId);
                    stmt.setLong(3, flightId);
                    Long validUserId = resolveValidUserId(conn, UserSession.userId());
                    if (validUserId != null) {
                        stmt.setLong(4, validUserId);
                    } else {
                        stmt.setNull(4, java.sql.Types.BIGINT);
                    }
                    stmt.setString(5, "Boarded via gate console by agent [" + (UserSession.username() != null ? UserSession.username() : "SYSTEM") + "]");
                    stmt.executeUpdate();
                }

                // 2. Update checkins status to BOARDED
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE checkins SET status = 'BOARDED' WHERE id = ? AND status = 'CHECKED_IN'")) {
                    stmt.setLong(1, checkinId);
                    int updated = stmt.executeUpdate();
                    if (updated == 0) {
                        conn.rollback();
                        return BoardingValidationResult.failure(pnr,
                            "Passenger with PNR [" + pnr + "] is already recorded as BOARDED.");
                    }
                }

                conn.commit();

                String finalSeat = (seatNumber != null && !seatNumber.isBlank()) ? seatNumber : "UNASSIGNED";
                AuditService.record(
                    AuditAction.PASSENGER_BOARDED,
                    "CHECKIN",
                    String.valueOf(checkinId),
                    "Passenger [" + passengerName + "] boarded on flight ID #" + flightId + " (Seat: " + finalSeat + ")"
                );

                return BoardingValidationResult.success(pnr, passengerName, finalSeat,
                    "Passenger [" + passengerName + "] successfully verified and marked as BOARDED.");

            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new SQLException("Boarding operation error: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public void updateFlightBoardingStatus(long flightId, BoardingStatus status) {
        AuthorizationService.requirePermission(Permission.MANAGE_BOARDING);
        flightStatuses.put(flightId, status != null ? status : BoardingStatus.SCHEDULED);
        AuditService.record(
            AuditAction.GATE_ASSIGNMENT,
            "FLIGHT",
            String.valueOf(flightId),
            "Gate boarding status changed to [" + status + "]"
        );
    }

    @Override
    public BoardingStatus getFlightBoardingStatus(long flightId) {
        return flightStatuses.getOrDefault(flightId, BoardingStatus.SCHEDULED);
    }

    private static Long resolveValidUserId(Connection conn, Long userId) {
        if (userId == null) return null;
        try (PreparedStatement s = conn.prepareStatement("SELECT 1 FROM app_users WHERE id = ? LIMIT 1")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) return userId;
            }
        } catch (SQLException ignored) {}
        return null;
    }
}
