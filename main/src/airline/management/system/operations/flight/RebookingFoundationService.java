package airline.management.system.operations.flight;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Controlled, transactional operational rebooking foundation for disrupted flights.
 * Ensures strict tenant boundaries, atomic inventory updates, identity preservation,
 * and immutable audit tracking. Never produces fake operational states.
 */
public final class RebookingFoundationService {
    private RebookingFoundationService() {}

    /**
     * Eligible alternative flight record for affected passenger re-accommodation.
     */
    public record AlternativeFlight(
        long flightId,
        String flightNumber,
        String airline,
        String source,
        String destination,
        LocalDateTime departureTime,
        int availableSeats,
        BigDecimal fare
    ) {}

    /**
     * Transaction result for a completed rebooking operation.
     */
    public record RebookingResult(
        boolean success,
        String originalPnr,
        String newPnr,
        long targetFlightId,
        String targetFlightNumber,
        String assignedSeat,
        String message
    ) {
        public static RebookingResult success(String origPnr, String newPnr, long flightId, String flightNum, String seat) {
            return new RebookingResult(true, origPnr, newPnr, flightId, flightNum, seat, "Passenger successfully rebooked onto flight " + flightNum + " (Seat " + seat + ").");
        }
        public static RebookingResult failure(String origPnr, String message) {
            return new RebookingResult(false, origPnr, null, 0, null, null, message);
        }
    }

    /**
     * Discovers eligible alternative flights within the same tenant for a disrupted flight.
     */
    public static List<AlternativeFlight> findEligibleAlternativeFlights(long disruptedFlightId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.MANAGE_RESERVATIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String source = null;
        String destination = null;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT source, destination, organization_id FROM flights WHERE id = ?")) {
            stmt.setLong(1, disruptedFlightId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Disrupted flight was not found.");
                }
                if (rs.getLong("organization_id") != tenantOrgId) {
                    throw new AccessDeniedException("Cross-tenant access violation: Flight does not belong to active tenant.");
                }
                source = rs.getString("source");
                destination = rs.getString("destination");
            }
        }

        String sql =
            "SELECT id, flight_number, airline, source, destination, departure_time, available_seats, fare " +
            "FROM flights " +
            "WHERE organization_id = ? " +
            "  AND source = ? AND destination = ? " +
            "  AND id <> ? " +
            "  AND status IN ('SCHEDULED', 'ACTIVE') " +
            "  AND available_seats > 0 " +
            "ORDER BY departure_time ASC LIMIT 20";

        List<AlternativeFlight> results = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, tenantOrgId);
            stmt.setString(2, source);
            stmt.setString(3, destination);
            stmt.setLong(4, disruptedFlightId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("departure_time");
                    results.add(new AlternativeFlight(
                        rs.getLong("id"),
                        rs.getString("flight_number"),
                        rs.getString("airline"),
                        rs.getString("source"),
                        rs.getString("destination"),
                        ts != null ? ts.toLocalDateTime() : LocalDateTime.now(),
                        rs.getInt("available_seats"),
                        rs.getBigDecimal("fare")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Atomically and transactionally rebooks an affected passenger from a disrupted flight
     * onto an eligible alternative flight belonging to the same tenant.
     */
    public static RebookingResult rebookPassenger(long reservationId, long targetFlightId, String requestedSeat) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.MANAGE_RESERVATIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lock and verify original reservation
                String origPnr;
                long passengerId;
                long origFlightId;
                String origStatus;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT pnr, passenger_id, flight_id, status, organization_id FROM reservations WHERE id = ? FOR UPDATE")) {
                    stmt.setLong(1, reservationId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return RebookingResult.failure("—", "Original reservation was not found.");
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Reservation does not belong to active tenant.");
                        }
                        origPnr = rs.getString("pnr");
                        passengerId = rs.getLong("passenger_id");
                        origFlightId = rs.getLong("flight_id");
                        origStatus = rs.getString("status");
                    }
                }

                if ("CANCELLED".equalsIgnoreCase(origStatus)) {
                    conn.rollback();
                    return RebookingResult.failure(origPnr, "Reservation [" + origPnr + "] has already been cancelled.");
                }

                // 2. Verify disrupted flight status
                String origFlightStatus;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT status FROM flights WHERE id = ? FOR UPDATE")) {
                    stmt.setLong(1, origFlightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return RebookingResult.failure(origPnr, "Associated flight was not found.");
                        }
                        origFlightStatus = rs.getString("status");
                    }
                }

                if (!"CANCELLED".equalsIgnoreCase(origFlightStatus) && !"DELAYED".equalsIgnoreCase(origFlightStatus)) {
                    conn.rollback();
                    return RebookingResult.failure(origPnr, "Rebooking is only permitted for flights with CANCELLED or DELAYED status.");
                }

                // 3. Lock and verify target alternative flight
                String targetFlightNumber;
                BigDecimal targetFare;
                int targetAvailable;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT flight_number, fare, available_seats, status, organization_id FROM flights WHERE id = ? FOR UPDATE")) {
                    stmt.setLong(1, targetFlightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return RebookingResult.failure(origPnr, "Target flight was not found.");
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Target flight belongs to a different airline tenant.");
                        }
                        targetFlightNumber = rs.getString("flight_number");
                        targetFare = rs.getBigDecimal("fare");
                        targetAvailable = rs.getInt("available_seats");
                        String status = rs.getString("status");
                        if (!"SCHEDULED".equalsIgnoreCase(status) && !"ACTIVE".equalsIgnoreCase(status)) {
                            conn.rollback();
                            return RebookingResult.failure(origPnr, "Target flight is not in an active or scheduled status.");
                        }
                    }
                }

                if (targetAvailable <= 0) {
                    conn.rollback();
                    return RebookingResult.failure(origPnr, "Target flight has no remaining available seats.");
                }

                // 4. Audit initiation
                AuditService.record(
                    AuditAction.REBOOKING_INITIATED,
                    "RESERVATION",
                    origPnr,
                    "Initiated operational rebooking from flight #" + origFlightId + " to flight " + targetFlightNumber
                );

                // 5. Select seat
                String seatToAssign = (requestedSeat != null && requestedSeat.matches("[1-9][0-9]*[A-F]"))
                        ? requestedSeat : ("1" + (char)('A' + (int)(Math.random() * 6)));

                // 6. Create new reservation record for the same passenger
                String newPnr = generateRebookPnr();
                long newReservationId = 0;
                String insertResSql =
                    "INSERT INTO reservations (organization_id, pnr, passenger_id, flight_id, seat_number, fare, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED')";
                try (PreparedStatement stmt = conn.prepareStatement(insertResSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setString(2, newPnr);
                    stmt.setLong(3, passengerId);
                    stmt.setLong(4, targetFlightId);
                    stmt.setString(5, seatToAssign);
                    stmt.setBigDecimal(6, targetFare);
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) newReservationId = rs.getLong(1);
                    }
                }

                // 7. Decrement target flight seat count
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE flights SET available_seats = available_seats - 1 WHERE id = ? AND organization_id = ? AND available_seats > 0")) {
                    stmt.setLong(1, targetFlightId);
                    stmt.setLong(2, tenantOrgId);
                    if (stmt.executeUpdate() != 1) {
                        conn.rollback();
                        return RebookingResult.failure(origPnr, "Could not reserve seat on target flight.");
                    }
                }

                // 8. Record transfer payment for new reservation
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO payments (organization_id, reservation_id, amount, payment_method, payment_status, transaction_reference) " +
                        "VALUES (?, ?, ?, 'REBOOK_TRANSFER', 'SUCCESS', ?)")) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, newReservationId);
                    stmt.setBigDecimal(3, targetFare);
                    stmt.setString(4, "TX-REBOOK-" + newPnr);
                    stmt.executeUpdate();
                }

                // 9. Update original reservation to CANCELLED with rebooking reference
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE reservations SET status = 'CANCELLED' WHERE id = ? AND organization_id = ?")) {
                    stmt.setLong(1, reservationId);
                    stmt.setLong(2, tenantOrgId);
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO cancellations (organization_id, reservation_id, reason, refund_amount, status) " +
                        "VALUES (?, ?, ?, 0, 'COMPLETED')")) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, reservationId);
                    stmt.setString(3, "Operational rebooking to flight " + targetFlightNumber + " (" + newPnr + ")");
                    stmt.executeUpdate();
                }

                conn.commit();

                // 10. Audit completion
                AuditService.record(
                    AuditAction.REBOOKING_COMPLETED,
                    "RESERVATION",
                    newPnr,
                    "Operational rebooking completed for passenger #" + passengerId +
                    " from [" + origPnr + "] to [" + newPnr + "] on flight " + targetFlightNumber + " (Seat " + seatToAssign + ")"
                );

                return RebookingResult.success(origPnr, newPnr, targetFlightId, targetFlightNumber, seatToAssign);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String generateRebookPnr() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        return "RBK-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b).toUpperCase();
    }
}
