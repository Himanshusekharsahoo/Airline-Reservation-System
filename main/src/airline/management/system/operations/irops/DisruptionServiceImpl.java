package airline.management.system.operations.irops;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * Authoritative production implementation of DisruptionService.
 * Enforces multi-tenant boundaries, RBAC authorization, ACID transactions,
 * and immutable operational audit trail logging.
 */
public final class DisruptionServiceImpl implements DisruptionService {

    private static final DisruptionServiceImpl INSTANCE = new DisruptionServiceImpl();
    private static final SecureRandom RANDOM = new SecureRandom();

    private DisruptionServiceImpl() {}

    public static DisruptionServiceImpl getInstance() {
        return INSTANCE;
    }

    private void requireManageAccess() {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required: No active user session.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_DISRUPTIONS);
    }

    private void requireViewAccess() {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required: No active user session.");
        }
        if (!AuthorizationService.hasPermission(Permission.VIEW_DISRUPTIONS)
                && !AuthorizationService.hasPermission(Permission.MANAGE_DISRUPTIONS)
                && !AuthorizationService.hasPermission(Permission.VIEW_REPORTS)
                && !AuthorizationService.hasPermission(Permission.VIEW_AUDIT_LOGS)) {
            throw new AccessDeniedException("Access denied: User does not possess disruption view permissions.");
        }
    }

    @Override
    public FlightDisruption declareDisruption(
        long flightId,
        DisruptionType type,
        DisruptionReason reason,
        DisruptionSeverity severity,
        Integer delayMinutes,
        String impactDescription,
        String remarks
    ) throws SQLException {
        requireManageAccess();

        if (type == null) {
            throw new IllegalArgumentException("Disruption type is required.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("Disruption reason code is required.");
        }
        if (impactDescription == null || impactDescription.isBlank()) {
            throw new IllegalArgumentException("Impact description is required.");
        }

        DisruptionSeverity sev = severity != null ? severity : DisruptionSeverity.MEDIUM;
        int delay = delayMinutes != null ? Math.max(0, delayMinutes) : 0;
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lock and verify flight ownership and status
                String flightNumber;
                String route;
                Timestamp scheduledDep;
                String currentFlightStatus;

                String flightSql =
                    "SELECT id, organization_id, flight_number, source, destination, departure_time, status " +
                    "FROM flights WHERE id = ? FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(flightSql)) {
                    stmt.setLong(1, flightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Flight was not found for ID: " + flightId);
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Flight does not belong to active tenant.");
                        }
                        flightNumber = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");
                        scheduledDep = rs.getTimestamp("departure_time");
                        currentFlightStatus = rs.getString("status");
                    }
                }

                // 2. Prevent duplicate active disruptions on this flight
                String checkSql =
                    "SELECT disruption_reference FROM flight_disruptions " +
                    "WHERE organization_id = ? AND flight_id = ? AND status NOT IN ('RESOLVED', 'CANCELLED') FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, flightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String activeRef = rs.getString("disruption_reference");
                            conn.rollback();
                            throw new IllegalStateException(
                                "An active disruption [" + activeRef + "] already exists for flight " + flightNumber + "."
                            );
                        }
                    }
                }

                // 3. Count affected passengers (active confirmed/pending reservations)
                int affectedCount = 0;
                String countSql =
                    "SELECT COUNT(*) FROM reservations " +
                    "WHERE organization_id = ? AND flight_id = ? AND status <> 'CANCELLED'";
                try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, flightId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            affectedCount = rs.getInt(1);
                        }
                    }
                }

                // 4. Update flight status in flights table
                Timestamp estDep = scheduledDep;
                if (type == DisruptionType.CANCELLATION) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE flights SET status = 'CANCELLED' WHERE id = ? AND organization_id = ?")) {
                        stmt.setLong(1, flightId);
                        stmt.setLong(2, tenantOrgId);
                        stmt.executeUpdate();
                    }
                } else if (type == DisruptionType.DELAY) {
                    if (delay > 0 && scheduledDep != null) {
                        estDep = Timestamp.valueOf(scheduledDep.toLocalDateTime().plusMinutes(delay));
                    }
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE flights SET status = 'DELAYED' WHERE id = ? AND organization_id = ?")) {
                        stmt.setLong(1, flightId);
                        stmt.setLong(2, tenantOrgId);
                        stmt.executeUpdate();
                    }
                }

                // Record flight status history if status changed
                if ((type == DisruptionType.CANCELLATION && !"CANCELLED".equalsIgnoreCase(currentFlightStatus)) ||
                    (type == DisruptionType.DELAY && !"DELAYED".equalsIgnoreCase(currentFlightStatus))) {
                    String newSt = (type == DisruptionType.CANCELLATION) ? "CANCELLED" : "DELAYED";
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO flight_status_history (organization_id, flight_id, previous_status, new_status, changed_by_user_id, reason) " +
                            "VALUES (?, ?, ?, ?, ?, ?)")) {
                        stmt.setLong(1, tenantOrgId);
                        stmt.setLong(2, flightId);
                        stmt.setString(3, currentFlightStatus);
                        stmt.setString(4, newSt);
                        if (UserSession.userId() != null) stmt.setLong(5, UserSession.userId());
                        else stmt.setNull(5, java.sql.Types.BIGINT);
                        stmt.setString(6, "Disruption: " + type.getDisplayName() + " — " + reason.getDisplayName());
                        stmt.executeUpdate();
                    }
                }

                // 5. Generate collision-resistant reference: DSP- + 8 alphanumeric chars
                String ref = generateUniqueReference(conn, "flight_disruptions", "disruption_reference", "DSP-");
                Long userId = UserSession.userId();

                // 6. Insert flight_disruptions record
                String insertSql =
                    "INSERT INTO flight_disruptions (" +
                    "    organization_id, flight_id, disruption_reference, disruption_type, reason_code, severity, " +
                    "    declared_at, declared_by_user_id, scheduled_departure, estimated_departure, delay_minutes, " +
                    "    impact_description, passenger_impact_count, status, remarks" +
                    ") VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, 'DECLARED', ?)";

                long disruptionId;
                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, flightId);
                    stmt.setString(3, ref);
                    stmt.setString(4, type.name());
                    stmt.setString(5, reason.name());
                    stmt.setString(6, sev.name());
                    if (userId != null) stmt.setLong(7, userId);
                    else stmt.setNull(7, java.sql.Types.BIGINT);
                    stmt.setTimestamp(8, scheduledDep);
                    stmt.setTimestamp(9, estDep);
                    stmt.setInt(10, delay);
                    stmt.setString(11, impactDescription.trim());
                    stmt.setInt(12, affectedCount);
                    stmt.setString(13, remarks != null ? remarks.trim() : null);
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        disruptionId = rs.getLong(1);
                    }
                }

                conn.commit();

                // 7. Audit log
                AuditService.record(
                    AuditAction.DISRUPTION_DECLARED,
                    "DISRUPTION",
                    ref,
                    "Disruption [" + ref + "] declared for flight " + flightNumber + " (" + type.name() + " - " + reason.name() + "): " +
                    affectedCount + " passengers impacted."
                );

                return new FlightDisruption(
                    disruptionId,
                    tenantOrgId,
                    flightId,
                    flightNumber,
                    route,
                    ref,
                    type,
                    reason,
                    sev,
                    LocalDateTime.now(),
                    userId,
                    UserSession.username(),
                    scheduledDep != null ? scheduledDep.toLocalDateTime() : null,
                    estDep != null ? estDep.toLocalDateTime() : null,
                    delay,
                    impactDescription.trim(),
                    affectedCount,
                    DisruptionStatus.DECLARED,
                    null,
                    null,
                    null,
                    remarks != null ? remarks.trim() : null,
                    LocalDateTime.now(),
                    LocalDateTime.now()
                );
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public FlightDisruption updateDisruption(
        long disruptionId,
        DisruptionSeverity severity,
        Integer delayMinutes,
        String impactDescription,
        String remarks
    ) throws SQLException {
        requireManageAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                FlightDisruption current = getLockedDisruption(conn, disruptionId, tenantOrgId);
                if (current.isTerminal()) {
                    conn.rollback();
                    throw new IllegalStateException("Cannot update disruption in terminal status: " + current.status().name());
                }

                DisruptionSeverity newSev = severity != null ? severity : current.severity();
                int newDelay = delayMinutes != null ? Math.max(0, delayMinutes) : current.delayMinutes();
                String newDesc = (impactDescription != null && !impactDescription.isBlank()) ? impactDescription.trim() : current.impactDescription();
                String newRemarks = remarks != null ? remarks.trim() : current.remarks();

                String updateSql =
                    "UPDATE flight_disruptions SET severity = ?, delay_minutes = ?, impact_description = ?, remarks = ? " +
                    "WHERE id = ? AND organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setString(1, newSev.name());
                    stmt.setInt(2, newDelay);
                    stmt.setString(3, newDesc);
                    stmt.setString(4, newRemarks);
                    stmt.setLong(5, disruptionId);
                    stmt.setLong(6, tenantOrgId);
                    stmt.executeUpdate();
                }

                conn.commit();

                AuditService.record(
                    AuditAction.DISRUPTION_UPDATED,
                    "DISRUPTION",
                    current.disruptionReference(),
                    "Disruption [" + current.disruptionReference() + "] updated: severity=" + newSev.name() + ", delay=" + newDelay + "m."
                );

                return findById(disruptionId).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public FlightDisruption changeStatus(
        long disruptionId,
        DisruptionStatus newStatus,
        String remarks
    ) throws SQLException {
        requireManageAccess();
        if (newStatus == null) {
            throw new IllegalArgumentException("New disruption status is required.");
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                FlightDisruption current = getLockedDisruption(conn, disruptionId, tenantOrgId);
                DisruptionLifecycleValidator.validateTransition(current.status(), newStatus);

                boolean isResolving = (newStatus == DisruptionStatus.RESOLVED);
                Long userId = UserSession.userId();

                String updateSql =
                    "UPDATE flight_disruptions SET status = ?, " +
                    (isResolving ? "resolved_at = NOW(), resolved_by_user_id = ?, " : "") +
                    "remarks = COALESCE(?, remarks) " +
                    "WHERE id = ? AND organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    int idx = 1;
                    stmt.setString(idx++, newStatus.name());
                    if (isResolving) {
                        if (userId != null) stmt.setLong(idx++, userId);
                        else stmt.setNull(idx++, java.sql.Types.BIGINT);
                    }
                    stmt.setString(idx++, remarks != null ? remarks.trim() : null);
                    stmt.setLong(idx++, disruptionId);
                    stmt.setLong(idx++, tenantOrgId);
                    stmt.executeUpdate();
                }

                conn.commit();

                AuditAction action = isResolving ? AuditAction.DISRUPTION_RESOLVED : AuditAction.DISRUPTION_UPDATED;
                AuditService.record(
                    action,
                    "DISRUPTION",
                    current.disruptionReference(),
                    "Disruption [" + current.disruptionReference() + "] transitioned: " +
                    current.status().name() + " → " + newStatus.name() + (remarks != null ? " (" + remarks.trim() + ")" : "")
                );

                return findById(disruptionId).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public FlightDisruption resolveDisruption(long disruptionId, String remarks) throws SQLException {
        return changeStatus(disruptionId, DisruptionStatus.RESOLVED, remarks);
    }

    @Override
    public Optional<FlightDisruption> findById(long disruptionId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql = baseDisruptionSelect() + " WHERE fd.id = ? AND fd.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, disruptionId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapDisruption(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<FlightDisruption> findByReference(String disruptionReference) throws SQLException {
        requireViewAccess();
        if (disruptionReference == null || disruptionReference.isBlank()) {
            return Optional.empty();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql = baseDisruptionSelect() + " WHERE fd.disruption_reference = ? AND fd.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, disruptionReference.trim());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapDisruption(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<FlightDisruption> findActiveByFlightId(long flightId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql = baseDisruptionSelect() +
            " WHERE fd.flight_id = ? AND fd.organization_id = ? AND fd.status NOT IN ('RESOLVED', 'CANCELLED') " +
            " ORDER BY fd.id DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, flightId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapDisruption(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<FlightDisruption> searchDisruptions(String query, DisruptionStatus statusFilter) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        StringBuilder sql = new StringBuilder(baseDisruptionSelect());
        sql.append(" WHERE fd.organization_id = ?");

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (statusFilter != null) {
            sql.append(" AND fd.status = ?");
            params.add(statusFilter.name());
        }

        if (query != null && !query.trim().isEmpty()) {
            String q = "%" + query.trim() + "%";
            sql.append(" AND (fd.disruption_reference LIKE ? OR f.flight_number LIKE ? OR f.source LIKE ? OR f.destination LIKE ? OR fd.reason_code LIKE ? OR CAST(fd.id AS CHAR) LIKE ?)");
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }

        sql.append(" ORDER BY fd.declared_at DESC, fd.id DESC LIMIT 100");

        List<FlightDisruption> results = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapDisruption(rs));
                }
            }
        }
        return results;
    }

    @Override
    public List<DisruptedPassengerManifestItem> getAffectedPassengerManifest(long disruptionId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        // Verify disruption exists and fetch its flight_id
        long flightId;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT flight_id, organization_id FROM flight_disruptions WHERE id = ?")) {
            stmt.setLong(1, disruptionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Disruption not found for ID: " + disruptionId);
                }
                if (rs.getLong("organization_id") != tenantOrgId) {
                    throw new AccessDeniedException("Cross-tenant access violation: Disruption does not belong to active tenant.");
                }
                flightId = rs.getLong("flight_id");
            }
        }

        try (Connection conn = DBConnection.getConnection()) {
            boolean hasBaggage = tableExists(conn, "baggage");
            boolean hasPir = tableExists(conn, "pir_reports");
            boolean hasReacc = tableExists(conn, "passenger_reaccommodations");
            boolean hasVouchers = tableExists(conn, "disruption_vouchers");

            String sql =
                "SELECT r.id AS res_id, r.pnr, r.seat_number, r.status AS res_status, " +
                "       p.id AS pass_id, p.name AS pass_name, p.phone, p.email, " +
                "       f.id AS flight_id, f.flight_number, f.source, f.destination, " +
                "       COALESCE(pmt.payment_status, 'PENDING') AS pmt_status, " +
                "       COALESCE(c.status, 'NOT_CHECKED_IN') AS chk_status, " +
                "       COALESCE(bp.boarding_pass_number, '—') AS bp_number " +
                (hasReacc ?
                ", pr.id AS reacc_id, pr.status AS reacc_status, pr.new_pnr, nf.flight_number AS new_flt_num " :
                ", NULL AS reacc_id, NULL AS reacc_status, NULL AS new_pnr, NULL AS new_flt_num ") +
                (hasVouchers ?
                ", (SELECT COUNT(*) FROM disruption_vouchers dv WHERE dv.reservation_id = r.id AND dv.organization_id = ?) AS voucher_count " :
                ", 0 AS voucher_count ") +
                (hasBaggage ?
                ", (SELECT COUNT(*) FROM baggage b WHERE b.reservation_id = r.id AND b.organization_id = ?) AS bag_count, " +
                "  (SELECT b2.status FROM baggage b2 WHERE b2.reservation_id = r.id AND b2.organization_id = ? ORDER BY b2.id DESC LIMIT 1) AS bag_status " :
                ", 0 AS bag_count, NULL AS bag_status ") +
                (hasPir ?
                ", (SELECT prr.pir_reference FROM pir_reports prr WHERE prr.reservation_id = r.id AND prr.organization_id = ? AND prr.status <> 'CLOSED' ORDER BY prr.id DESC LIMIT 1) AS pir_ref, " +
                "  (SELECT prr2.status FROM pir_reports prr2 WHERE prr2.reservation_id = r.id AND prr2.organization_id = ? AND prr2.status <> 'CLOSED' ORDER BY prr2.id DESC LIMIT 1) AS pir_status " :
                ", NULL AS pir_ref, NULL AS pir_status ") +
                "FROM reservations r " +
                "JOIN passengers p ON p.id = r.passenger_id " +
                "JOIN flights f ON f.id = r.flight_id " +
                "LEFT JOIN payments pmt ON pmt.reservation_id = r.id " +
                "LEFT JOIN checkins c ON c.reservation_id = r.id " +
                "LEFT JOIN boarding_passes bp ON bp.checkin_id = c.id " +
                (hasReacc ?
                "LEFT JOIN passenger_reaccommodations pr ON pr.original_reservation_id = r.id AND pr.disruption_id = ? " +
                "LEFT JOIN flights nf ON nf.id = pr.new_flight_id " : "") +
                "WHERE r.flight_id = ? AND r.organization_id = ? " +
                "ORDER BY r.id ASC";

            List<DisruptedPassengerManifestItem> manifest = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int paramIdx = 1;
                if (hasVouchers) stmt.setLong(paramIdx++, tenantOrgId);
                if (hasBaggage) {
                    stmt.setLong(paramIdx++, tenantOrgId);
                    stmt.setLong(paramIdx++, tenantOrgId);
                }
                if (hasPir) {
                    stmt.setLong(paramIdx++, tenantOrgId);
                    stmt.setLong(paramIdx++, tenantOrgId);
                }
                if (hasReacc) stmt.setLong(paramIdx++, disruptionId);
                stmt.setLong(paramIdx++, flightId);
                stmt.setLong(paramIdx++, tenantOrgId);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String reaccStatusStr = rs.getString("reacc_status");
                        ReaccommodationStatus reaccStatus = reaccStatusStr != null ? ReaccommodationStatus.fromString(reaccStatusStr) : null;
                        Long reaccId = rs.getObject("reacc_id") != null ? rs.getLong("reacc_id") : null;

                        manifest.add(new DisruptedPassengerManifestItem(
                            rs.getLong("res_id"),
                            rs.getString("pnr"),
                            rs.getLong("pass_id"),
                            rs.getString("pass_name"),
                            rs.getString("phone"),
                            rs.getString("email"),
                            rs.getLong("flight_id"),
                            rs.getString("flight_number"),
                            rs.getString("source") + " → " + rs.getString("destination"),
                            rs.getString("seat_number"),
                            rs.getString("res_status"),
                            rs.getString("pmt_status"),
                            rs.getString("chk_status"),
                            rs.getString("bp_number"),
                            rs.getInt("bag_count"),
                            rs.getString("bag_status"),
                            rs.getString("pir_ref"),
                            rs.getString("pir_status"),
                            reaccId,
                            reaccStatus,
                            rs.getString("new_flt_num"),
                            rs.getString("new_pnr"),
                            rs.getInt("voucher_count")
                        ));
                    }
                }
            }
            return manifest;
        }
    }

    private FlightDisruption getLockedDisruption(Connection conn, long disruptionId, long orgId) throws SQLException {
        String sql = baseDisruptionSelect() + " WHERE fd.id = ? AND fd.organization_id = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, disruptionId);
            stmt.setLong(2, orgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Disruption record not found for ID: " + disruptionId);
                }
                return mapDisruption(rs);
            }
        }
    }

    private String baseDisruptionSelect() {
        return "SELECT fd.id, fd.organization_id, fd.flight_id, fd.disruption_reference, " +
               "       fd.disruption_type, fd.reason_code, fd.severity, fd.declared_at, " +
               "       fd.declared_by_user_id, fd.scheduled_departure, fd.estimated_departure, " +
               "       fd.delay_minutes, fd.impact_description, fd.passenger_impact_count, " +
               "       fd.status, fd.resolved_at, fd.resolved_by_user_id, fd.remarks, " +
               "       fd.created_at, fd.updated_at, " +
               "       f.flight_number, f.source, f.destination, " +
               "       u1.username AS declared_by_username, u2.username AS resolved_by_username " +
               "FROM flight_disruptions fd " +
               "JOIN flights f ON f.id = fd.flight_id " +
               "LEFT JOIN app_users u1 ON u1.id = fd.declared_by_user_id " +
               "LEFT JOIN app_users u2 ON u2.id = fd.resolved_by_user_id ";
    }

    private FlightDisruption mapDisruption(ResultSet rs) throws SQLException {
        Timestamp decTs = rs.getTimestamp("declared_at");
        Timestamp schTs = rs.getTimestamp("scheduled_departure");
        Timestamp estTs = rs.getTimestamp("estimated_departure");
        Timestamp resTs = rs.getTimestamp("resolved_at");
        Timestamp crtTs = rs.getTimestamp("created_at");
        Timestamp updTs = rs.getTimestamp("updated_at");

        Long decUser = rs.getObject("declared_by_user_id") != null ? rs.getLong("declared_by_user_id") : null;
        Long resUser = rs.getObject("resolved_by_user_id") != null ? rs.getLong("resolved_by_user_id") : null;

        return new FlightDisruption(
            rs.getLong("id"),
            rs.getLong("organization_id"),
            rs.getLong("flight_id"),
            rs.getString("flight_number"),
            rs.getString("source") + " → " + rs.getString("destination"),
            rs.getString("disruption_reference"),
            DisruptionType.fromString(rs.getString("disruption_type")),
            DisruptionReason.fromString(rs.getString("reason_code")),
            DisruptionSeverity.fromString(rs.getString("severity")),
            decTs != null ? decTs.toLocalDateTime() : LocalDateTime.now(),
            decUser,
            rs.getString("declared_by_username"),
            schTs != null ? schTs.toLocalDateTime() : null,
            estTs != null ? estTs.toLocalDateTime() : null,
            rs.getInt("delay_minutes"),
            rs.getString("impact_description"),
            rs.getInt("passenger_impact_count"),
            DisruptionStatus.fromString(rs.getString("status")),
            resTs != null ? resTs.toLocalDateTime() : null,
            resUser,
            rs.getString("resolved_by_username"),
            rs.getString("remarks"),
            crtTs != null ? crtTs.toLocalDateTime() : LocalDateTime.now(),
            updTs != null ? updTs.toLocalDateTime() : LocalDateTime.now()
        );
    }

    public static String generateUniqueReference(Connection conn, String tableName, String columnName, String prefix) throws SQLException {
        for (int i = 0; i < 10; i++) {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            String candidate = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toUpperCase().replaceAll("[^A-Z0-9]", "X").substring(0, 8);
            String checkSql = "SELECT 1 FROM " + tableName + " WHERE " + columnName + " = ?";
            try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                stmt.setString(1, candidate);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        return prefix + System.currentTimeMillis();
    }

    private static boolean tableExists(Connection conn, String tableName) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
}
