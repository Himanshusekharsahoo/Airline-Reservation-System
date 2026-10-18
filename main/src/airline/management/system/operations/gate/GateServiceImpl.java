package airline.management.system.operations.gate;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.layout.StationContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service implementation executing authoritative gate management and scheduling in MySQL.
 */
public final class GateServiceImpl implements GateService {
    public static final GateServiceImpl INSTANCE = new GateServiceImpl();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private GateServiceImpl() {}

    @Override
    public List<GateRecord> getAllGates() throws SQLException {
        String sql = "SELECT id, organization_id, airport_code, terminal, gate_number, status FROM gates WHERE organization_id=? ORDER BY terminal ASC, gate_number ASC";
        List<GateRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId());
            try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(new GateRecord(
                    rs.getLong("id"),
                    rs.getLong("organization_id"),
                    rs.getString("airport_code"),
                    rs.getString("terminal"),
                    rs.getString("gate_number"),
                    rs.getString("status")
                ));
            }
            }
        }
        return list;
    }

    @Override
    public List<GateRecord> getGatesByAirport(String airportCode) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return getGatesByAirport(conn, airportCode);
        }
    }

    public List<GateRecord> getGatesByAirport(Connection conn, String airportCode) throws SQLException {
        String targetCode = (airportCode != null && !airportCode.isBlank())
                ? airportCode.trim()
                : (StationContext.getCurrentStation() != null ? StationContext.getCurrentStation().getCode() : "DEL");

        String sql = "SELECT id, organization_id, airport_code, terminal, gate_number, status " +
                     "FROM gates WHERE organization_id = ? AND UPPER(airport_code) = UPPER(?) " +
                     "ORDER BY terminal ASC, gate_number ASC";
        List<GateRecord> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId());
            stmt.setString(2, targetCode);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new GateRecord(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("airport_code"),
                        rs.getString("terminal"),
                        rs.getString("gate_number"),
                        rs.getString("status")
                    ));
                }
            }
        }
        return list;
    }

    @Override
    public FlightGateAssignment getFlightGateAssignment(long flightId) throws SQLException {
        String sql =
            "SELECT fg.id, fg.flight_id, f.flight_number, CONCAT(f.source, ' → ', f.destination) AS route, " +
            "       fg.gate_id, g.gate_number, g.terminal, fg.scheduled_start, fg.scheduled_end, g.status " +
            "FROM flight_gates fg " +
            "JOIN gates g ON g.id = fg.gate_id " +
            "JOIN flights f ON f.id = fg.flight_id " +
            "WHERE fg.flight_id = ? AND fg.organization_id = ? " +
            "ORDER BY fg.id DESC LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, flightId);
            stmt.setLong(2, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp sTs = rs.getTimestamp("scheduled_start");
                    Timestamp eTs = rs.getTimestamp("scheduled_end");
                    return new FlightGateAssignment(
                        rs.getLong("id"),
                        rs.getLong("flight_id"),
                        rs.getString("flight_number"),
                        rs.getString("route"),
                        rs.getLong("gate_id"),
                        rs.getString("gate_number"),
                        rs.getString("terminal"),
                        sTs != null ? sTs.toLocalDateTime() : LocalDateTime.now(),
                        eTs != null ? eTs.toLocalDateTime() : LocalDateTime.now(),
                        rs.getString("status")
                    );
                }
            }
        }
        return null;
    }

    @Override
    public FlightGateAssignment assignFlightGate(long flightId, long gateId, LocalDateTime start, LocalDateTime end) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_GATES);

        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("Invalid gate allocation schedule: Departure window end must be after start.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Verify flight exists
                long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
                String flightNo;
                String route;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT flight_number, source, destination, organization_id FROM flights WHERE id = ? FOR UPDATE")) {
                    stmt.setLong(1, flightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Flight #" + flightId + " not found.");
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            throw new airline.management.system.security.AccessDeniedException("Cross-tenant access violation: Flight #" + flightId + " does not belong to active tenant.");
                        }
                        flightNo = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");
                    }
                }

                // 2. Verify gate exists
                String gateNo;
                String terminal;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT gate_number, terminal FROM gates WHERE id = ? AND organization_id = ? FOR UPDATE")) {
                    stmt.setLong(1, gateId);
                    stmt.setLong(2, tenantOrgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Gate #" + gateId + " not found.");
                        }
                        gateNo = rs.getString("gate_number");
                        terminal = rs.getString("terminal");
                    }
                }

                // 3. Conflict detection: Check overlapping flight allocations on the same gate
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT fg.id, f.flight_number, fg.scheduled_start, fg.scheduled_end " +
                        "FROM flight_gates fg " +
                        "JOIN flights f ON f.id = fg.flight_id " +
                        "WHERE fg.gate_id = ? AND fg.flight_id != ? AND fg.organization_id = ? " +
                        "  AND fg.scheduled_start < ? AND fg.scheduled_end > ? " +
                        "LIMIT 1 FOR UPDATE")) {
                    stmt.setLong(1, gateId);
                    stmt.setLong(2, flightId);
                    stmt.setLong(3, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
                    stmt.setTimestamp(4, Timestamp.valueOf(end));
                    stmt.setTimestamp(5, Timestamp.valueOf(start));
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            Timestamp confStart = rs.getTimestamp("scheduled_start");
                            Timestamp confEnd = rs.getTimestamp("scheduled_end");
                            String cFlight = rs.getString("flight_number");
                            throw new IllegalStateException(
                                "Gate conflict: Gate " + gateNo + " (" + terminal + ") is already allocated to flight " +
                                cFlight + " between " + confStart.toLocalDateTime().format(TIME_FORMAT) +
                                " and " + confEnd.toLocalDateTime().format(TIME_FORMAT) + "."
                            );
                        }
                    }
                }

                // 4. Insert flight_gates record
                long newFgId;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO flight_gates (organization_id, flight_id, gate_id, assigned_by_user_id, scheduled_start, scheduled_end) " +
                        "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
                    stmt.setLong(2, flightId);
                    stmt.setLong(3, gateId);
                    Long validUserId = resolveValidUserId(conn, UserSession.userId());
                    if (validUserId != null) {
                        stmt.setLong(4, validUserId);
                    } else {
                        stmt.setNull(4, java.sql.Types.BIGINT);
                    }
                    stmt.setTimestamp(5, Timestamp.valueOf(start));
                    stmt.setTimestamp(6, Timestamp.valueOf(end));
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        newFgId = rs.getLong(1);
                    }
                }

                // 5. Update gate status
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE gates SET status = 'OCCUPIED' WHERE id = ?")) {
                    stmt.setLong(1, gateId);
                    stmt.executeUpdate();
                }

                conn.commit();

                AuditService.record(
                    AuditAction.GATE_ASSIGNED,
                    "FLIGHT",
                    String.valueOf(flightId),
                    "Flight [" + flightNo + "] assigned to Gate [" + gateNo + "] (" + terminal + ")"
                );

                return new FlightGateAssignment(
                    newFgId,
                    flightId,
                    flightNo,
                    route,
                    gateId,
                    gateNo,
                    terminal,
                    start,
                    end,
                    "OCCUPIED"
                );

            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new SQLException("Gate assignment failed: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public void releaseFlightGate(long flightId) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_GATES);
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long gateId; String flightNumber; String gateNumber; String flightStatus;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT fg.gate_id, f.flight_number, f.status, g.gate_number FROM flight_gates fg " +
                        "JOIN flights f ON f.id=fg.flight_id JOIN gates g ON g.id=fg.gate_id " +
                        "WHERE fg.flight_id=? AND fg.organization_id=? ORDER BY fg.id DESC LIMIT 1 FOR UPDATE")) {
                    stmt.setLong(1, flightId);
                    stmt.setLong(2, TenantContext.getOrganizationId() == null ? 1L : TenantContext.getOrganizationId());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("This flight has no gate assignment to release.");
                        gateId=rs.getLong(1); flightNumber=rs.getString(2); flightStatus=rs.getString(3); gateNumber=rs.getString(4);
                    }
                }
                if (!("DEPARTED".equalsIgnoreCase(flightStatus) || "ARRIVED".equalsIgnoreCase(flightStatus) || "CANCELLED".equalsIgnoreCase(flightStatus))) {
                    throw new IllegalStateException("A gate can be released only after the flight has departed, arrived, or been cancelled.");
                }
                long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
                try (PreparedStatement stmt=conn.prepareStatement("UPDATE flight_gates SET actual_boarding_closed=COALESCE(actual_boarding_closed,NOW()) WHERE flight_id=? AND gate_id=? AND organization_id=?")) { stmt.setLong(1,flightId);stmt.setLong(2,gateId);stmt.setLong(3,tenantOrgId);stmt.executeUpdate(); }
                try (PreparedStatement stmt=conn.prepareStatement("UPDATE gates SET status='AVAILABLE' WHERE id=? AND organization_id=?")) { stmt.setLong(1,gateId);stmt.setLong(2,tenantOrgId);stmt.executeUpdate(); }
                conn.commit();
                AuditService.record(AuditAction.GATE_RELEASED,"FLIGHT",String.valueOf(flightId),"Released Gate ["+gateNumber+"] for flight ["+flightNumber+"]");
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); }
        }
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
