package airline.management.system.operations.checkin;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authoritative, database-backed CheckinService executing transactional check-in
 * operations, boarding pass issuance, and operational audit logging in MySQL.
 */
public final class CheckinServiceImpl implements CheckinService {
    static final CheckinServiceImpl INSTANCE = new CheckinServiceImpl();

    private CheckinServiceImpl() {}

    @Override
    public CheckinPassengerDetails lookupReservation(String pnr) throws SQLException {
        if (pnr == null || pnr.trim().isEmpty()) {
            return null;
        }

        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String query =
            "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, r.fare, " +
            "       p.id AS pass_id, p.name AS pass_name, p.phone, p.email, " +
            "       f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, " +
            "       f.departure_time, f.arrival_time, " +
            "       pmt.payment_status, pmt.payment_method, pmt.transaction_reference, " +
            "       c.id AS checkin_id, c.seat_assigned, c.status AS checkin_status_str, c.checkin_time, " +
            "       bp.boarding_pass_number, bp.gate AS bp_gate, " +
            "       (SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id WHERE fg.flight_id = f.id ORDER BY fg.id DESC LIMIT 1) AS live_gate " +
            "FROM reservations r " +
            "JOIN passengers p ON p.id = r.passenger_id " +
            "JOIN flights f ON f.id = r.flight_id " +
            "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
            "LEFT JOIN checkins c ON c.reservation_id = r.id " +
            "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
            "WHERE r.pnr = ? AND r.organization_id = ? LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, pnr.trim());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    @Override
    public List<CheckinPassengerDetails> searchReservations(String query) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql =
            "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, r.fare, " +
            "       p.id AS pass_id, p.name AS pass_name, p.phone, p.email, " +
            "       f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, " +
            "       f.departure_time, f.arrival_time, " +
            "       pmt.payment_status, pmt.payment_method, pmt.transaction_reference, " +
            "       c.id AS checkin_id, c.seat_assigned, c.status AS checkin_status_str, c.checkin_time, " +
            "       bp.boarding_pass_number, bp.gate AS bp_gate, " +
            "       (SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id WHERE fg.flight_id = f.id ORDER BY fg.id DESC LIMIT 1) AS live_gate " +
            "FROM reservations r " +
            "JOIN passengers p ON p.id = r.passenger_id " +
            "JOIN flights f ON f.id = r.flight_id " +
            "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
            "LEFT JOIN checkins c ON c.reservation_id = r.id " +
            "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
            "WHERE r.organization_id = ? AND (r.pnr LIKE ? OR p.name LIKE ? OR f.flight_number LIKE ?) " +
            "ORDER BY r.booking_date DESC LIMIT 20";

        List<CheckinPassengerDetails> list = new ArrayList<>();
        String wildcard = "%" + query.trim() + "%";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tenantOrgId);
            stmt.setString(2, wildcard);
            stmt.setString(3, wildcard);
            stmt.setString(4, wildcard);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public CheckinResult processCheckin(String pnr, String seatNumber) throws SQLException {
        AuthorizationService.requirePermission(Permission.CHECKIN_PASSENGER);

        if (pnr == null || pnr.trim().isEmpty()) {
            return CheckinResult.failure(CheckinStatus.NOT_FOUND, null, "PNR is required for check-in.");
        }
        String cleanPnr = pnr.trim();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Look up reservation under row lock
                CheckinPassengerDetails details = null;
                String selectSql =
                    "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, r.fare, " +
                    "       p.id AS pass_id, p.name AS pass_name, p.phone, p.email, " +
                    "       f.id AS flight_id, f.flight_number, f.airline, f.source, f.destination, " +
                    "       f.departure_time, f.arrival_time, " +
                    "       pmt.payment_status, pmt.payment_method, pmt.transaction_reference " +
                    "FROM reservations r " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
                    "WHERE r.pnr = ? AND r.organization_id = ? FOR UPDATE";

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setString(1, cleanPnr);
                    stmt.setLong(2, tenantOrgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            details = mapReservationRow(rs);
                        }
                    }
                }

                if (details == null) {
                    conn.rollback();
                    return CheckinResult.failure(CheckinStatus.NOT_FOUND, null, "No reservation found for PNR: " + cleanPnr);
                }

                if (!details.isConfirmed()) {
                    conn.rollback();
                    return CheckinResult.failure(CheckinStatus.INELIGIBLE_STATUS, details,
                        "Cannot check in: Reservation status is '" + details.reservationStatus() + "' (must be CONFIRMED).");
                }

                if (!details.isPaymentCompleted()) {
                    conn.rollback();
                    return CheckinResult.failure(CheckinStatus.INELIGIBLE_PAYMENT, details,
                        "Cannot check in: Payment status is '" + details.paymentStatus() + "' (requires SUCCESS/COMPLETED).");
                }

                // 2. Check if already checked in
                long existingCheckinId = 0;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM checkins WHERE reservation_id = ? FOR UPDATE")) {
                    stmt.setLong(1, details.reservationId());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            existingCheckinId = rs.getLong("id");
                        }
                    }
                }

                if (existingCheckinId > 0) {
                    conn.rollback();
                    return CheckinResult.failure(CheckinStatus.CHECKED_IN, details,
                        "Passenger with PNR [" + cleanPnr + "] is already checked in.");
                }

                // 3. Resolve seat assignment
                String assignedSeat = (seatNumber != null && !seatNumber.trim().isEmpty())
                    ? seatNumber.trim()
                    : (details.seatNumber() != null && !details.seatNumber().isBlank() ? details.seatNumber() : "12A");

                // 4. Query assigned gate from flight_gates if available
                String assignedGate = null;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id " +
                        "WHERE fg.flight_id = ? ORDER BY fg.id DESC LIMIT 1")) {
                    stmt.setLong(1, details.flightId());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            assignedGate = rs.getString("gate_number");
                        }
                    }
                }

                // 5. Insert checkins record
                long newCheckinId;
                String insertCheckinSql =
                    "INSERT INTO checkins (organization_id, reservation_id, passenger_id, flight_id, seat_assigned, checked_in_by_user_id, checkin_channel, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'DESK', 'CHECKED_IN')";
                try (PreparedStatement stmt = conn.prepareStatement(insertCheckinSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
                    stmt.setLong(2, details.reservationId());
                    stmt.setLong(3, details.passengerId());
                    stmt.setLong(4, details.flightId());
                    stmt.setString(5, assignedSeat);
                    Long validUserId = resolveValidUserId(conn, UserSession.userId());
                    if (validUserId != null) {
                        stmt.setLong(6, validUserId);
                    } else {
                        stmt.setNull(6, java.sql.Types.BIGINT);
                    }
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        newCheckinId = rs.getLong(1);
                    }
                }

                // 6. Generate authoritative boarding pass
                String normPnr = cleanPnr.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                String normFlight = details.flightNumber().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                String bpNumber = "BP-" + normPnr + "-" + normFlight;
                String barcodePayload = normPnr + "|" + normFlight + "|" + assignedSeat + "|" + bpNumber;
                String securityHash = computeSha256(cleanPnr + ":" + details.flightNumber() + ":" + assignedSeat + ":" + newCheckinId);

                String insertBpSql =
                    "INSERT INTO boarding_passes (organization_id, checkin_id, boarding_pass_number, barcode_payload, security_hash, boarding_group, gate) " +
                    "VALUES (?, ?, ?, ?, ?, 'GROUP_3', ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertBpSql)) {
                    stmt.setLong(1, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
                    stmt.setLong(2, newCheckinId);
                    stmt.setString(3, bpNumber);
                    stmt.setString(4, barcodePayload);
                    stmt.setString(5, securityHash);
                    stmt.setString(6, assignedGate);
                    stmt.executeUpdate();
                }

                conn.commit();

                LocalDateTime now = LocalDateTime.now();
                CheckinPassengerDetails updated = new CheckinPassengerDetails(
                    details.reservationId(),
                    details.pnr(),
                    details.passengerId(),
                    details.passengerName(),
                    details.phone(),
                    details.email(),
                    details.flightId(),
                    details.flightNumber(),
                    details.airline(),
                    details.source(),
                    details.destination(),
                    details.departureTime(),
                    details.arrivalTime(),
                    assignedSeat,
                    details.reservationStatus(),
                    details.fare(),
                    details.paymentStatus(),
                    details.paymentMethod(),
                    details.transactionReference(),
                    CheckinStatus.CHECKED_IN,
                    newCheckinId,
                    bpNumber,
                    assignedGate,
                    now
                );

                AuditService.record(
                    AuditAction.CHECKIN_COMPLETED,
                    "CHECKIN",
                    String.valueOf(newCheckinId),
                    "Passenger [" + details.passengerName() + "] checked in for flight [" + details.flightNumber() + "] seat [" + assignedSeat + "]"
                );

                AuditService.record(
                    AuditAction.BOARDING_PASS_ISSUED,
                    "BOARDING_PASS",
                    bpNumber,
                    "Boarding pass [" + bpNumber + "] issued for PNR [" + details.pnr() + "] Seat [" + assignedSeat + "] Gate [" + (assignedGate != null ? assignedGate : "Not assigned") + "]"
                );

                return CheckinResult.success(updated, "Passenger verified and check-in successfully recorded in database.");

            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new SQLException("Check-in processing error: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public BoardingPassRecord getBoardingPass(String pnr) throws SQLException {
        if (pnr == null || pnr.trim().isEmpty()) return null;
        String clean = pnr.trim();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql =
            "SELECT bp.id AS bp_id, bp.checkin_id, bp.boarding_pass_number, bp.boarding_group, bp.gate AS bp_gate, bp.issued_at, " +
            "       c.seat_assigned, c.status AS checkin_status, " +
            "       r.pnr, p.name AS pass_name, " +
            "       f.flight_number, f.airline, f.source, f.destination, f.departure_time, f.arrival_time, " +
            "       (SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id WHERE fg.flight_id = f.id ORDER BY fg.id DESC LIMIT 1) AS live_gate " +
            "FROM boarding_passes bp " +
            "JOIN checkins c ON c.id = bp.checkin_id " +
            "JOIN reservations r ON r.id = c.reservation_id " +
            "JOIN passengers p ON p.id = c.passenger_id " +
            "JOIN flights f ON f.id = c.flight_id " +
            "WHERE (r.pnr = ? OR bp.boarding_pass_number = ?) AND bp.organization_id = ? " +
            "ORDER BY bp.id DESC LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, clean);
            stmt.setString(2, clean);
            stmt.setLong(3, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBoardingPass(rs);
                }
            }
        }
        return null;
    }

    @Override
    public BoardingPassRecord getBoardingPassByCheckinId(long checkinId) throws SQLException {
        String sql =
            "SELECT bp.id AS bp_id, bp.checkin_id, bp.boarding_pass_number, bp.boarding_group, bp.gate AS bp_gate, bp.issued_at, " +
            "       c.seat_assigned, c.status AS checkin_status, " +
            "       r.pnr, p.name AS pass_name, " +
            "       f.flight_number, f.airline, f.source, f.destination, f.departure_time, f.arrival_time, " +
            "       (SELECT g.gate_number FROM flight_gates fg JOIN gates g ON g.id = fg.gate_id WHERE fg.flight_id = f.id ORDER BY fg.id DESC LIMIT 1) AS live_gate " +
            "FROM boarding_passes bp " +
            "JOIN checkins c ON c.id = bp.checkin_id " +
            "JOIN reservations r ON r.id = c.reservation_id " +
            "JOIN passengers p ON p.id = c.passenger_id " +
            "JOIN flights f ON f.id = c.flight_id " +
            "WHERE bp.checkin_id = ? " +
            "ORDER BY bp.id DESC LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, checkinId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapBoardingPass(rs);
                }
            }
        }
        return null;
    }

    private CheckinPassengerDetails mapRow(ResultSet rs) throws SQLException {
        String pnr = rs.getString("pnr");
        String resStatus = rs.getString("res_status");
        String pmtStatus = rs.getString("payment_status");
        if (pmtStatus == null) pmtStatus = "PENDING";

        long cId = rs.getLong("checkin_id");
        Long checkinId = rs.wasNull() ? null : cId;
        String seatAssigned = rs.getString("seat_assigned");
        String checkinStatusStr = rs.getString("checkin_status_str");
        Timestamp checkinTs = rs.getTimestamp("checkin_time");
        String bpNumber = rs.getString("boarding_pass_number");
        String bpGate = rs.getString("bp_gate");
        String liveGate = rs.getString("live_gate");
        String assignedGate = (bpGate != null && !bpGate.isBlank()) ? bpGate : liveGate;

        CheckinStatus status;
        if (checkinId != null || "CHECKED_IN".equalsIgnoreCase(checkinStatusStr) || "BOARDED".equalsIgnoreCase(checkinStatusStr)) {
            status = CheckinStatus.CHECKED_IN;
        } else if (!"CONFIRMED".equalsIgnoreCase(resStatus)) {
            status = CheckinStatus.INELIGIBLE_STATUS;
        } else if (!"SUCCESS".equalsIgnoreCase(pmtStatus) && !"COMPLETED".equalsIgnoreCase(pmtStatus)) {
            status = CheckinStatus.INELIGIBLE_PAYMENT;
        } else {
            status = CheckinStatus.ELIGIBLE;
        }

        Timestamp dep = rs.getTimestamp("departure_time");
        Timestamp arr = rs.getTimestamp("arrival_time");
        String effectiveSeat = (seatAssigned != null && !seatAssigned.isBlank())
            ? seatAssigned
            : rs.getString("seat_number");

        return new CheckinPassengerDetails(
            rs.getLong("res_id"),
            pnr,
            rs.getLong("pass_id"),
            rs.getString("pass_name"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getLong("flight_id"),
            rs.getString("flight_number"),
            rs.getString("airline"),
            rs.getString("source"),
            rs.getString("destination"),
            dep != null ? dep.toLocalDateTime() : LocalDateTime.now(),
            arr != null ? arr.toLocalDateTime() : LocalDateTime.now(),
            effectiveSeat,
            resStatus,
            rs.getBigDecimal("fare"),
            pmtStatus,
            rs.getString("payment_method"),
            rs.getString("transaction_reference"),
            status,
            checkinId,
            bpNumber,
            assignedGate,
            checkinTs != null ? checkinTs.toLocalDateTime() : null
        );
    }

    private CheckinPassengerDetails mapReservationRow(ResultSet rs) throws SQLException {
        String pnr = rs.getString("pnr");
        String resStatus = rs.getString("res_status");
        String pmtStatus = rs.getString("payment_status");
        if (pmtStatus == null) pmtStatus = "PENDING";

        Timestamp dep = rs.getTimestamp("departure_time");
        Timestamp arr = rs.getTimestamp("arrival_time");

        CheckinStatus status;
        if (!"CONFIRMED".equalsIgnoreCase(resStatus)) {
            status = CheckinStatus.INELIGIBLE_STATUS;
        } else if (!"SUCCESS".equalsIgnoreCase(pmtStatus) && !"COMPLETED".equalsIgnoreCase(pmtStatus)) {
            status = CheckinStatus.INELIGIBLE_PAYMENT;
        } else {
            status = CheckinStatus.ELIGIBLE;
        }

        return new CheckinPassengerDetails(
            rs.getLong("res_id"),
            pnr,
            rs.getLong("pass_id"),
            rs.getString("pass_name"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getLong("flight_id"),
            rs.getString("flight_number"),
            rs.getString("airline"),
            rs.getString("source"),
            rs.getString("destination"),
            dep != null ? dep.toLocalDateTime() : LocalDateTime.now(),
            arr != null ? arr.toLocalDateTime() : LocalDateTime.now(),
            rs.getString("seat_number"),
            resStatus,
            rs.getBigDecimal("fare"),
            pmtStatus,
            rs.getString("payment_method"),
            rs.getString("transaction_reference"),
            status
        );
    }

    private BoardingPassRecord mapBoardingPass(ResultSet rs) throws SQLException {
        Timestamp dep = rs.getTimestamp("departure_time");
        Timestamp arr = rs.getTimestamp("arrival_time");
        Timestamp issued = rs.getTimestamp("issued_at");

        String bpGate = rs.getString("bp_gate");
        String liveGate = rs.getString("live_gate");
        String gate = (bpGate != null && !bpGate.isBlank()) ? bpGate : liveGate;

        return new BoardingPassRecord(
            rs.getLong("bp_id"),
            rs.getLong("checkin_id"),
            rs.getString("boarding_pass_number"),
            rs.getString("pnr"),
            rs.getString("pass_name"),
            rs.getString("flight_number"),
            rs.getString("airline"),
            rs.getString("source"),
            rs.getString("destination"),
            dep != null ? dep.toLocalDateTime() : LocalDateTime.now(),
            arr != null ? arr.toLocalDateTime() : LocalDateTime.now(),
            rs.getString("seat_assigned"),
            gate,
            rs.getString("boarding_group"),
            rs.getString("checkin_status"),
            issued != null ? issued.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
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
