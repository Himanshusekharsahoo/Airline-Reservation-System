package airline.management.system.operations.flight;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Authoritative lifecycle transitions and disruption management for operational flights.
 * Strictly enforces tenant boundaries and manages disruption history and affected passenger manifests.
 */
public final class FlightOperationsService {
    public enum Status { SCHEDULED, BOARDING, DEPARTED, ARRIVED, DELAYED, CANCELLED }

    private static final Map<Status, EnumSet<Status>> TRANSITIONS = Map.of(
        Status.SCHEDULED, EnumSet.of(Status.BOARDING, Status.DELAYED, Status.CANCELLED),
        Status.DELAYED, EnumSet.of(Status.SCHEDULED, Status.BOARDING, Status.CANCELLED),
        Status.BOARDING, EnumSet.of(Status.DEPARTED, Status.CANCELLED),
        Status.DEPARTED, EnumSet.of(Status.ARRIVED),
        Status.ARRIVED, EnumSet.noneOf(Status.class),
        Status.CANCELLED, EnumSet.noneOf(Status.class)
    );

    private FlightOperationsService() { }

    /**
     * Operational view record representing a passenger impacted by a flight delay or cancellation.
     */
    public record AffectedPassenger(
        long reservationId,
        String pnr,
        long passengerId,
        String passengerName,
        String phone,
        String seatNumber,
        String reservationStatus,
        String paymentStatus,
        String checkinStatus,
        String boardingPassNumber
    ) {}

    /**
     * Executes an authoritative operational flight status transition.
     */
    public static void transition(long flightId, Status next, String reason) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_FLIGHT_OPERATIONS);
        if (next == null) throw new IllegalArgumentException("Select an operational flight status.");
        if (reason != null && reason.length() > 255) throw new IllegalArgumentException("Status reason may contain at most 255 characters.");
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                Status previous;
                String flightNumber;
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT flight_number, status, organization_id FROM flights WHERE id=? FOR UPDATE")) {
                    p.setLong(1, flightId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Flight was not found.");
                        long flightOrg = r.getLong("organization_id");
                        if (flightOrg != tenantOrgId) {
                            throw new AccessDeniedException("Cross-tenant access violation: Flight #" + flightId + " does not belong to active tenant.");
                        }
                        flightNumber = r.getString("flight_number");
                        previous = normalize(r.getString("status"));
                    }
                }

                if (previous == next) {
                    c.rollback();
                    return;
                }

                if (!TRANSITIONS.get(previous).contains(next)) {
                    throw new IllegalStateException("Invalid operational transition: " + previous + " → " + next + ".");
                }

                try (PreparedStatement p = c.prepareStatement("UPDATE flights SET status=? WHERE id=? AND organization_id=?")) {
                    p.setString(1, next.name());
                    p.setLong(2, flightId);
                    p.setLong(3, tenantOrgId);
                    p.executeUpdate();
                }

                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO flight_status_history(organization_id, flight_id, previous_status, new_status, changed_by_user_id, reason) " +
                        "VALUES(?, ?, ?, ?, ?, ?)")) {
                    p.setLong(1, tenantOrgId);
                    p.setLong(2, flightId);
                    p.setString(3, previous.name());
                    p.setString(4, next.name());
                    if (UserSession.userId() == null) {
                        p.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        p.setLong(5, UserSession.userId());
                    }
                    p.setString(6, reason == null || reason.isBlank() ? null : reason.trim());
                    p.executeUpdate();
                }

                c.commit();

                AuditAction action;
                if (next == Status.DELAYED) {
                    action = AuditAction.FLIGHT_DELAYED;
                } else if (next == Status.CANCELLED) {
                    action = AuditAction.FLIGHT_CANCELLED;
                } else {
                    action = AuditAction.FLIGHT_STATUS_CHANGED;
                }

                AuditService.record(action, "FLIGHT", String.valueOf(flightId),
                        "Flight [" + flightNumber + "] transitioned from [" + previous + "] to [" + next + "]" +
                        (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));

                if (next == Status.DELAYED || next == Status.CANCELLED) {
                    AuditService.record(AuditAction.FLIGHT_DISRUPTED, "FLIGHT", String.valueOf(flightId),
                            "Flight disruption logged for [" + flightNumber + "]: " + next +
                            (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")"));
                }
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /**
     * Retrieves the authoritative manifest of passengers affected by a flight disruption.
     * Guaranteed to preserve existing reservations, payments, check-ins, and boarding passes.
     */
    public static List<AffectedPassenger> getAffectedPassengers(long flightId) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_FLIGHT_OPERATIONS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String query =
            "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, " +
            "       p.id AS pass_id, p.name AS pass_name, p.phone, " +
            "       COALESCE(pmt.payment_status, 'PENDING') AS pmt_status, " +
            "       COALESCE(c.status, 'NOT_CHECKED_IN') AS chk_status, " +
            "       COALESCE(c.seat_assigned, r.seat_number, '—') AS final_seat, " +
            "       COALESCE(bp.boarding_pass_number, '—') AS bp_number " +
            "FROM reservations r " +
            "JOIN passengers p ON p.id = r.passenger_id " +
            "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
            "LEFT JOIN checkins c ON c.reservation_id = r.id " +
            "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
            "WHERE r.flight_id = ? AND r.organization_id = ? " +
            "ORDER BY r.id ASC";

        List<AffectedPassenger> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, flightId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new AffectedPassenger(
                        rs.getLong("res_id"),
                        rs.getString("pnr"),
                        rs.getLong("pass_id"),
                        rs.getString("pass_name"),
                        rs.getString("phone"),
                        rs.getString("final_seat"),
                        rs.getString("res_status"),
                        rs.getString("pmt_status"),
                        rs.getString("chk_status"),
                        rs.getString("bp_number")
                    ));
                }
            }
        }
        return list;
    }

    private static Status normalize(String value) {
        if ("ACTIVE".equalsIgnoreCase(value)) return Status.SCHEDULED;
        try {
            return Status.valueOf(value.toUpperCase());
        } catch (Exception e) {
            throw new IllegalStateException("Flight has unsupported operational status: " + value);
        }
    }
}
