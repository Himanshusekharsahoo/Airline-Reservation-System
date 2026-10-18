package airline.management.system.operations.irops;

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
import java.util.Optional;

/**
 * Enterprise service implementation for passenger disruption recovery vouchers.
 * Enforces transactional state transitions, multi-tenant scoping, and audit history.
 */
public class DisruptionVoucherServiceImpl implements DisruptionVoucherService {

    private static final DisruptionVoucherServiceImpl INSTANCE = new DisruptionVoucherServiceImpl();

    public static DisruptionVoucherServiceImpl getInstance() {
        return INSTANCE;
    }

    protected DisruptionVoucherServiceImpl() {}

    @Override
    public DisruptionVoucher issueVoucher(
        long disruptionId,
        long passengerId,
        long reservationId,
        VoucherType type,
        BigDecimal amount,
        String currency,
        String remarks
    ) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();
        Long currentUserId = UserSession.userId();

        if (type == null) throw new IllegalArgumentException("Voucher type cannot be null.");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Voucher amount must be non-negative.");
        }
        String curr = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "USD";

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Verify disruption
                FlightDisruption disruption = loadDisruption(conn, disruptionId, tenantOrgId);
                if (disruption.isTerminal()) {
                    throw new IllegalStateException("Cannot issue voucher for concluded disruption [" + disruption.disruptionReference() + "].");
                }

                // 2. Verify reservation and passenger belong to flight and tenant
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT flight_id, passenger_id, organization_id FROM reservations WHERE id = ?")) {
                    stmt.setLong(1, reservationId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new IllegalArgumentException("Reservation #" + reservationId + " not found.");
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            throw new AccessDeniedException("Cross-tenant access violation: Reservation does not belong to active tenant.");
                        }
                        if (rs.getLong("flight_id") != disruption.flightId()) {
                            throw new IllegalArgumentException("Reservation #" + reservationId + " does not belong to disrupted flight #" + disruption.flightId());
                        }
                        if (rs.getLong("passenger_id") != passengerId) {
                            throw new IllegalArgumentException("Reservation #" + reservationId + " does not belong to passenger #" + passengerId);
                        }
                    }
                }

                // 3. Generate unique voucher reference
                String voucherRef = generateVoucherReference();
                long voucherId;

                String insertSql =
                    "INSERT INTO disruption_vouchers (" +
                    "  organization_id, disruption_id, passenger_id, reservation_id, " +
                    "  voucher_reference, voucher_type, amount, currency, status, issued_by, remarks" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, disruptionId);
                    stmt.setLong(3, passengerId);
                    stmt.setLong(4, reservationId);
                    stmt.setString(5, voucherRef);
                    stmt.setString(6, type.name());
                    stmt.setBigDecimal(7, amount);
                    stmt.setString(8, curr);
                    if (currentUserId != null) stmt.setLong(9, currentUserId); else stmt.setNull(9, java.sql.Types.BIGINT);
                    stmt.setString(10, remarks);
                    stmt.executeUpdate();

                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            voucherId = rs.getLong(1);
                        } else {
                            throw new SQLException("Failed to obtain generated ID for disruption voucher.");
                        }
                    }
                }

                conn.commit();

                // 4. Audit
                AuditService.record(
                    AuditAction.VOUCHER_ISSUED,
                    "VOUCHER",
                    voucherRef,
                    "Issued " + type.name() + " voucher (" + curr + " " + amount + ") for passenger #" + passengerId + " under disruption " + disruption.disruptionReference()
                );

                return findById(voucherId).orElseThrow(() -> new IllegalStateException("Issued voucher not found."));
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public DisruptionVoucher redeemVoucher(long voucherId, String remarks) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                DisruptionVoucher current = loadVoucherById(conn, voucherId, tenantOrgId, true)
                    .orElseThrow(() -> new IllegalArgumentException("Voucher #" + voucherId + " not found."));

                if (current.status() != VoucherStatus.ISSUED) {
                    throw new IllegalStateException("Voucher [" + current.voucherReference() + "] is in status " + current.status() + " and cannot be redeemed.");
                }

                int updated;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE disruption_vouchers SET status = 'REDEEMED', redeemed_at = CURRENT_TIMESTAMP, remarks = COALESCE(?, remarks), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND organization_id = ? AND status = 'ISSUED'")) {
                    stmt.setString(1, remarks);
                    stmt.setLong(2, voucherId);
                    stmt.setLong(3, tenantOrgId);
                    updated = stmt.executeUpdate();
                }

                if (updated == 0) {
                    conn.rollback();
                    throw new IllegalStateException("Voucher #" + voucherId + " is no longer in ISSUED status and cannot be redeemed.");
                }

                conn.commit();

                AuditService.record(
                    AuditAction.VOUCHER_REDEEMED,
                    "VOUCHER",
                    current.voucherReference(),
                    "Redeemed " + current.type() + " voucher [" + current.voucherReference() + "] for " + current.currency() + " " + current.amount()
                );

                return findById(voucherId).orElseThrow(() -> new IllegalStateException("Updated voucher not found."));
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public DisruptionVoucher cancelVoucher(long voucherId, String reason) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                DisruptionVoucher current = loadVoucherById(conn, voucherId, tenantOrgId, true)
                    .orElseThrow(() -> new IllegalArgumentException("Voucher #" + voucherId + " not found."));

                if (current.status() != VoucherStatus.ISSUED) {
                    throw new IllegalStateException("Voucher [" + current.voucherReference() + "] is in status " + current.status() + " and cannot be cancelled.");
                }

                int updated;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE disruption_vouchers SET status = 'CANCELLED', remarks = COALESCE(?, remarks), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND organization_id = ? AND status = 'ISSUED'")) {
                    stmt.setString(1, reason);
                    stmt.setLong(2, voucherId);
                    stmt.setLong(3, tenantOrgId);
                    updated = stmt.executeUpdate();
                }

                if (updated == 0) {
                    conn.rollback();
                    throw new IllegalStateException("Voucher #" + voucherId + " is no longer in ISSUED status and cannot be cancelled.");
                }

                conn.commit();

                AuditService.record(
                    AuditAction.VOUCHER_CANCELLED,
                    "VOUCHER",
                    current.voucherReference(),
                    "Cancelled voucher [" + current.voucherReference() + "]. Reason: " + reason
                );

                return findById(voucherId).orElseThrow(() -> new IllegalStateException("Cancelled voucher not found."));
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public Optional<DisruptionVoucher> findById(long voucherId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        try (Connection conn = DBConnection.getConnection()) {
            return loadVoucherById(conn, voucherId, tenantOrgId);
        }
    }

    @Override
    public Optional<DisruptionVoucher> findByReference(String voucherReference) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        String sql = baseVoucherQuery() + "WHERE dv.voucher_reference = ? AND dv.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, voucherReference);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVoucher(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<DisruptionVoucher> getVouchersForDisruption(long disruptionId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        String sql = baseVoucherQuery() + "WHERE dv.disruption_id = ? AND dv.organization_id = ? ORDER BY dv.id ASC";
        List<DisruptionVoucher> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, disruptionId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapVoucher(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<DisruptionVoucher> getVouchersForPassenger(long passengerId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        String sql = baseVoucherQuery() + "WHERE dv.passenger_id = ? AND dv.organization_id = ? ORDER BY dv.id DESC";
        List<DisruptionVoucher> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, passengerId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapVoucher(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<DisruptionVoucher> getVouchersForReservation(long reservationId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        String sql = baseVoucherQuery() + "WHERE dv.reservation_id = ? AND dv.organization_id = ? ORDER BY dv.id ASC";
        List<DisruptionVoucher> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, reservationId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapVoucher(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<DisruptionVoucher> searchVouchers(String query, VoucherStatus statusFilter, VoucherType typeFilter) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_DISRUPTION_VOUCHERS);
        long tenantOrgId = getActiveTenantId();

        StringBuilder sql = new StringBuilder(baseVoucherQuery());
        sql.append("WHERE dv.organization_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (statusFilter != null) {
            sql.append("AND dv.status = ? ");
            params.add(statusFilter.name());
        }

        if (typeFilter != null) {
            sql.append("AND dv.voucher_type = ? ");
            params.add(typeFilter.name());
        }

        if (query != null && !query.trim().isEmpty()) {
            String pattern = "%" + query.trim() + "%";
            sql.append("AND (dv.voucher_reference LIKE ? OR r.pnr LIKE ? OR p.name LIKE ?) ");
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        sql.append("ORDER BY dv.id DESC LIMIT 100");

        List<DisruptionVoucher> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapVoucher(rs));
                }
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    private static String baseVoucherQuery() {
        return "SELECT dv.id, dv.organization_id, dv.disruption_id, fd.disruption_reference, " +
               "       dv.passenger_id, p.name AS passenger_name, " +
               "       dv.reservation_id, r.pnr, " +
               "       dv.voucher_reference, dv.voucher_type, dv.amount, dv.currency, dv.status, " +
               "       dv.issued_by, u.username AS issued_by_username, " +
               "       dv.issued_at, dv.redeemed_at, dv.remarks, dv.created_at, dv.updated_at " +
               "FROM disruption_vouchers dv " +
               "JOIN flight_disruptions fd ON fd.id = dv.disruption_id " +
               "JOIN passengers p ON p.id = dv.passenger_id " +
               "JOIN reservations r ON r.id = dv.reservation_id " +
               "LEFT JOIN app_users u ON u.id = dv.issued_by ";
    }

    private Optional<DisruptionVoucher> loadVoucherById(Connection conn, long id, long tenantOrgId) throws SQLException {
        return loadVoucherById(conn, id, tenantOrgId, false);
    }

    private Optional<DisruptionVoucher> loadVoucherById(Connection conn, long id, long tenantOrgId, boolean forUpdate) throws SQLException {
        String sql = baseVoucherQuery() + "WHERE dv.id = ? AND dv.organization_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVoucher(rs));
                }
            }
        }
        return Optional.empty();
    }

    private DisruptionVoucher mapVoucher(ResultSet rs) throws SQLException {
        Timestamp issuedAtTs = rs.getTimestamp("issued_at");
        Timestamp redeemedAtTs = rs.getTimestamp("redeemed_at");
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");

        long issuedByVal = rs.getLong("issued_by");
        Long issuedBy = rs.wasNull() ? null : issuedByVal;

        return new DisruptionVoucher(
            rs.getLong("id"),
            rs.getLong("organization_id"),
            rs.getLong("disruption_id"),
            rs.getString("disruption_reference"),
            rs.getLong("passenger_id"),
            rs.getString("passenger_name"),
            rs.getLong("reservation_id"),
            rs.getString("pnr"),
            rs.getString("voucher_reference"),
            VoucherType.valueOf(rs.getString("voucher_type")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            VoucherStatus.valueOf(rs.getString("status")),
            issuedBy,
            rs.getString("issued_by_username"),
            issuedAtTs != null ? issuedAtTs.toLocalDateTime() : LocalDateTime.now(),
            redeemedAtTs != null ? redeemedAtTs.toLocalDateTime() : null,
            rs.getString("remarks"),
            createdAtTs != null ? createdAtTs.toLocalDateTime() : LocalDateTime.now(),
            updatedAtTs != null ? updatedAtTs.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private FlightDisruption loadDisruption(Connection conn, long disruptionId, long tenantOrgId) throws SQLException {
        return DisruptionService.getInstance().findById(disruptionId)
            .orElseThrow(() -> new IllegalArgumentException("Disruption #" + disruptionId + " was not found."));
    }

    private static String generateVoucherReference() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        return "VCH-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toUpperCase();
    }

    private long getActiveTenantId() {
        Long orgId = TenantContext.getOrganizationId();
        return orgId != null ? orgId : 1L;
    }
}
