package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

/** Atomic booking and cancellation operations; UI input is revalidated here. Enforces multi-tenant boundaries. */
public final class ReservationService {
    private ReservationService() { }

    public static String book(long flightId, long passengerId, String seat, String method) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_RESERVATIONS);
        if (seat == null || !seat.matches("[1-9][0-9]*[A-F]")) throw new IllegalArgumentException("Select a valid seat.");
        if (!("UPI".equals(method) || "Card".equals(method) || "Cash".equals(method))) throw new IllegalArgumentException("Select a payment method.");
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                BigDecimalFare flight = lockFlight(c, flightId, tenantOrgId);
                if (flight.available < 1 || !("SCHEDULED".equals(flight.status) || "ACTIVE".equals(flight.status))) {
                    throw new IllegalArgumentException("No seats are available.");
                }

                try (PreparedStatement p = c.prepareStatement("SELECT organization_id FROM passengers WHERE id=?")) {
                    p.setLong(1, passengerId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new IllegalArgumentException("Passenger not found.");
                        long passOrg = r.getLong("organization_id");
                        if (passOrg != tenantOrgId) {
                            throw new AccessDeniedException("Cross-tenant access violation: Passenger #" + passengerId + " does not belong to active tenant.");
                        }
                    }
                }

                String pnr = pnr();
                long reservationId = 0;
                for (int i = 0; i < 5; i++) {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT INTO reservations(organization_id, pnr, passenger_id, flight_id, seat_number, fare, status) " +
                            "VALUES(?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT')", Statement.RETURN_GENERATED_KEYS)) {
                        p.setLong(1, tenantOrgId);
                        p.setString(2, pnr);
                        p.setLong(3, passengerId);
                        p.setLong(4, flightId);
                        p.setString(5, seat);
                        p.setBigDecimal(6, flight.fare);
                        p.executeUpdate();
                        try (ResultSet r = p.getGeneratedKeys()) {
                            r.next();
                            reservationId = r.getLong(1);
                        }
                        break;
                    } catch (SQLIntegrityConstraintViolationException conflict) {
                        pnr = pnr();
                        if (i == 4) throw new IllegalArgumentException("Seat is no longer available.");
                    }
                }

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE flights SET available_seats=available_seats-1 WHERE id=? AND organization_id=? AND available_seats>0")) {
                    p.setLong(1, flightId);
                    p.setLong(2, tenantOrgId);
                    if (p.executeUpdate() != 1) throw new IllegalArgumentException("No seats are available.");
                }

                String ref = "SIM-" + pnr;
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO payments(organization_id, reservation_id, amount, payment_method, payment_status, transaction_reference) " +
                        "VALUES(?, ?, ?, ?, 'SUCCESS', ?)")) {
                    p.setLong(1, tenantOrgId);
                    p.setLong(2, reservationId);
                    p.setBigDecimal(3, flight.fare);
                    p.setString(4, method);
                    p.setString(5, ref);
                    p.executeUpdate();
                }

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE reservations SET status='CONFIRMED' WHERE id=? AND organization_id=?")) {
                    p.setLong(1, reservationId);
                    p.setLong(2, tenantOrgId);
                    p.executeUpdate();
                }

                c.commit();
                AuditService.record(AuditAction.RESERVATION_CREATE, "RESERVATION", pnr,
                        "Booked seat " + seat + " on flight #" + flightId + " via " + method);
                return pnr;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new SQLException("Booking could not be completed.", e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public static java.math.BigDecimal cancel(String pnr) throws SQLException {
        return cancel(pnr, "Customer Request", null);
    }

    public static java.math.BigDecimal cancel(String pnr, String reason) throws SQLException {
        return cancel(pnr, reason, null);
    }

    public static java.math.BigDecimal cancel(String pnr, String reason, java.math.BigDecimal explicitRefund) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_CANCELLATIONS);
        if (pnr == null || pnr.trim().isEmpty()) {
            throw new IllegalArgumentException("PNR is required.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Cancellation reason is required.");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > 255) {
            trimmedReason = trimmedReason.substring(0, 255);
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                long resId;
                long flightId;
                java.math.BigDecimal resFare = null;
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT id, flight_id, status, organization_id, fare FROM reservations WHERE pnr=? FOR UPDATE")) {
                    p.setString(1, pnr.trim());
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) {
                            throw new IllegalArgumentException("Booking not found.");
                        }
                        long resOrg = r.getLong("organization_id");
                        if (resOrg != tenantOrgId) {
                            throw new AccessDeniedException("Cross-tenant access violation: Reservation [" + pnr + "] does not belong to active tenant.");
                        }
                        if (!"CONFIRMED".equalsIgnoreCase(r.getString("status"))) {
                            throw new IllegalArgumentException("This booking has already been cancelled or is not confirmed.");
                        }
                        resId = r.getLong("id");
                        flightId = r.getLong("flight_id");
                        resFare = r.getBigDecimal("fare");
                    }
                }

                // Authoritative payment and refund resolution:
                long paymentId = 0;
                java.math.BigDecimal paymentAmount = null;
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT id, amount, payment_status FROM payments " +
                        "WHERE reservation_id=? AND organization_id=? AND payment_status IN ('SUCCESS', 'COMPLETED') " +
                        "ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
                    p.setLong(1, resId);
                    p.setLong(2, tenantOrgId);
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) {
                            paymentId = r.getLong("id");
                            java.math.BigDecimal amt = r.getBigDecimal("amount");
                            if (amt != null && amt.compareTo(java.math.BigDecimal.ZERO) > 0) {
                                paymentAmount = amt.setScale(2, java.math.RoundingMode.HALF_UP);
                            }
                        }
                    }
                }

                java.math.BigDecimal refundAmount;
                if (explicitRefund != null) {
                    if (explicitRefund.compareTo(java.math.BigDecimal.ZERO) < 0) {
                        throw new IllegalArgumentException("Refund amount cannot be negative.");
                    }
                    refundAmount = explicitRefund.setScale(2, java.math.RoundingMode.HALF_UP);
                } else if (paymentAmount != null && paymentAmount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    refundAmount = paymentAmount;
                } else if (resFare != null && resFare.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    refundAmount = resFare.setScale(2, java.math.RoundingMode.HALF_UP);
                } else {
                    refundAmount = java.math.BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
                }

                // 1. UPDATE reservation -> CANCELLED
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE reservations SET status='CANCELLED' WHERE id=? AND organization_id=?")) {
                    p.setLong(1, resId);
                    p.setLong(2, tenantOrgId);
                    p.executeUpdate();
                }

                // 2. INSERT cancellation record with user-selected reason and authoritative refund
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO cancellations(organization_id, reservation_id, reason, refund_amount, status) VALUES(?, ?, ?, ?, 'COMPLETED')")) {
                    p.setLong(1, tenantOrgId);
                    p.setLong(2, resId);
                    p.setString(3, trimmedReason);
                    p.setBigDecimal(4, refundAmount);
                    p.executeUpdate();
                }

                // 3. Restore flight inventory seat
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE flights SET available_seats=available_seats+1 WHERE id=? AND organization_id=?")) {
                    p.setLong(1, flightId);
                    p.setLong(2, tenantOrgId);
                    p.executeUpdate();
                }

                // 4. UPDATE payment -> REFUNDED if an authoritative refundable completed payment exists
                if (paymentId > 0) {
                    try (PreparedStatement p = c.prepareStatement(
                            "UPDATE payments SET payment_status='REFUNDED' WHERE id=? AND organization_id=?")) {
                        p.setLong(1, paymentId);
                        p.setLong(2, tenantOrgId);
                        p.executeUpdate();
                    }
                }

                c.commit();
                AuditService.record(AuditAction.RESERVATION_CANCEL, "RESERVATION", pnr,
                        "Cancelled reservation. Reason: " + trimmedReason + ", refund: " + refundAmount);
                return refundAmount;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new SQLException("Cancellation could not be completed.", e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static BigDecimalFare lockFlight(Connection c, long id, long tenantOrgId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT fare, available_seats, status, organization_id FROM flights WHERE id=? FOR UPDATE")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Flight not found.");
                long flightOrg = r.getLong("organization_id");
                if (flightOrg != tenantOrgId) {
                    throw new AccessDeniedException("Cross-tenant access violation: Flight #" + id + " does not belong to active tenant.");
                }
                return new BigDecimalFare(r.getBigDecimal(1), r.getInt(2), r.getString(3));
            }
        }
    }

    private static String pnr() {
        byte[] b = new byte[5];
        new SecureRandom().nextBytes(b);
        return "PNR-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b).toUpperCase();
    }

    private record BigDecimalFare(java.math.BigDecimal fare, int available, String status) {}
}
