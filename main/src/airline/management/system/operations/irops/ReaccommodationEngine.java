package airline.management.system.operations.irops;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.operations.flight.RebookingFoundationService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Reaccommodation Engine for IROPS (Irregular Operations).
 * Handles alternate flight search, single & batch auto/manual passenger reaccommodation,
 * integration with RebookingFoundationService, state lifecycle management, and notification tracking.
 */
public class ReaccommodationEngine {

    private static final ReaccommodationEngine INSTANCE = new ReaccommodationEngine();

    public static ReaccommodationEngine getInstance() {
        return INSTANCE;
    }

    protected ReaccommodationEngine() {}

    /**
     * Batch reaccommodation summary record.
     */
    public record BatchReaccommodationResult(
        int totalCandidates,
        int rebookedCount,
        int failedCount,
        List<PassengerReaccommodation> items
    ) {}

    /**
     * Discovers eligible alternate flights for a disrupted flight.
     */
    public List<RebookingFoundationService.AlternativeFlight> searchEligibleAlternateFlights(long disruptedFlightId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        return RebookingFoundationService.findEligibleAlternativeFlights(disruptedFlightId);
    }

    /**
     * Proposes a reaccommodation for a disrupted passenger without finalizing the rebooking yet.
     */
    public PassengerReaccommodation proposeReaccommodation(
        long disruptionId,
        long originalReservationId,
        Long targetFlightId,
        String requestedSeat,
        ReaccommodationType type,
        String remarks
    ) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();
        Long currentUserId = UserSession.userId();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Verify disruption
                FlightDisruption disruption = loadDisruption(conn, disruptionId, tenantOrgId);
                if (disruption.isTerminal()) {
                    throw new IllegalStateException("Cannot propose reaccommodation for resolved or cancelled disruption [" + disruption.disruptionReference() + "].");
                }

                // 2. Verify original reservation
                OriginalReservationInfo origRes = loadOriginalReservation(conn, originalReservationId, tenantOrgId);
                if (origRes.flightId() != disruption.flightId()) {
                    throw new IllegalArgumentException("Reservation " + origRes.pnr() + " does not belong to disrupted flight #" + disruption.flightId());
                }

                // 3. Insert or update reaccommodation proposal
                long reaccId;
                String insertSql =
                    "INSERT INTO passenger_reaccommodations (" +
                    "  organization_id, disruption_id, original_reservation_id, original_pnr, " +
                    "  passenger_id, original_flight_id, new_flight_id, new_seat_number, status, " +
                    "  reaccommodation_type, notification_status, initiated_by, remarks" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OFFERED', ?, 'PENDING', ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, disruptionId);
                    stmt.setLong(3, originalReservationId);
                    stmt.setString(4, origRes.pnr());
                    stmt.setLong(5, origRes.passengerId());
                    stmt.setLong(6, origRes.flightId());
                    if (targetFlightId != null) stmt.setLong(7, targetFlightId); else stmt.setNull(7, java.sql.Types.BIGINT);
                    stmt.setString(8, requestedSeat);
                    stmt.setString(9, (type != null ? type : ReaccommodationType.MANUAL).name());
                    if (currentUserId != null) stmt.setLong(10, currentUserId); else stmt.setNull(10, java.sql.Types.BIGINT);
                    stmt.setString(11, remarks);
                    stmt.executeUpdate();

                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) reaccId = rs.getLong(1);
                        else throw new SQLException("Failed to obtain generated ID for reaccommodation proposal.");
                    }
                }

                conn.commit();

                // 4. Audit
                AuditService.record(
                    AuditAction.REACCOMMODATION_INITIATED,
                    "REACCOMMODATION",
                    origRes.pnr(),
                    "Proposed " + (type != null ? type : ReaccommodationType.MANUAL) + " reaccommodation for passenger #" + origRes.passengerId() + " (Res #" + originalReservationId + ")"
                );

                return findById(reaccId).orElseThrow(() -> new IllegalStateException("Created reaccommodation not found."));
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Reaccommodates an individual passenger onto an alternate flight transactionally.
     * Uses RebookingFoundationService to safely transfer reservation, update seat inventory, and cancel original.
     */
    public PassengerReaccommodation reaccommodatePassenger(
        long disruptionId,
        long originalReservationId,
        long targetFlightId,
        String requestedSeat,
        ReaccommodationType type,
        String remarks
    ) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();
        Long currentUserId = UserSession.userId();
        String currentUsername = UserSession.username();

        // 1. Verify disruption state
        FlightDisruption disruption;
        try (Connection conn = DBConnection.getConnection()) {
            disruption = loadDisruption(conn, disruptionId, tenantOrgId);
            if (disruption.isTerminal()) {
                throw new IllegalStateException("Cannot reaccommodate passengers for concluded disruption [" + disruption.disruptionReference() + "].");
            }
        }

        // 2. Perform transactional rebooking via RebookingFoundationService
        RebookingFoundationService.RebookingResult rebookingResult =
            RebookingFoundationService.rebookPassenger(originalReservationId, targetFlightId, requestedSeat);

        if (!rebookingResult.success()) {
            // Record failed reaccommodation attempt
            recordFailedReaccommodation(disruptionId, originalReservationId, targetFlightId, type, currentUserId, rebookingResult.message());
            AuditService.record(
                AuditAction.REACCOMMODATION_FAILED,
                "REACCOMMODATION",
                rebookingResult.originalPnr(),
                "Reaccommodation failed: " + rebookingResult.message()
            );
            throw new IllegalStateException("Reaccommodation failed: " + rebookingResult.message());
        }

        // 3. Rebooking succeeded - record successful passenger_reaccommodations
        long reaccommodationId;
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Find newly generated reservation ID
                Long newResId = null;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM reservations WHERE pnr = ? AND organization_id = ?")) {
                    stmt.setString(1, rebookingResult.newPnr());
                    stmt.setLong(2, tenantOrgId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            newResId = rs.getLong("id");
                        }
                    }
                }

                OriginalReservationInfo origRes = loadOriginalReservation(conn, originalReservationId, tenantOrgId);

                // Upsert passenger_reaccommodations row
                String insertSql =
                    "INSERT INTO passenger_reaccommodations (" +
                    "  organization_id, disruption_id, original_reservation_id, original_pnr, " +
                    "  passenger_id, original_flight_id, new_flight_id, new_reservation_id, " +
                    "  new_pnr, new_seat_number, status, reaccommodation_type, notification_status, " +
                    "  initiated_by, completed_at, remarks" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REBOOKED', ?, 'PENDING', ?, CURRENT_TIMESTAMP, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, disruptionId);
                    stmt.setLong(3, originalReservationId);
                    stmt.setString(4, origRes.pnr());
                    stmt.setLong(5, origRes.passengerId());
                    stmt.setLong(6, origRes.flightId());
                    stmt.setLong(7, targetFlightId);
                    if (newResId != null) stmt.setLong(8, newResId); else stmt.setNull(8, java.sql.Types.BIGINT);
                    stmt.setString(9, rebookingResult.newPnr());
                    stmt.setString(10, rebookingResult.assignedSeat());
                    stmt.setString(11, (type != null ? type : ReaccommodationType.MANUAL).name());
                    if (currentUserId != null) stmt.setLong(12, currentUserId); else stmt.setNull(12, java.sql.Types.BIGINT);
                    stmt.setString(13, remarks != null ? remarks : rebookingResult.message());
                    stmt.executeUpdate();

                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            reaccommodationId = rs.getLong(1);
                        } else {
                            throw new SQLException("Failed to retrieve generated ID for passenger_reaccommodation.");
                        }
                    }
                }

                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        // 4. Audit
        AuditService.record(
            AuditAction.REACCOMMODATION_COMPLETED,
            "REACCOMMODATION",
            rebookingResult.newPnr(),
            "Passenger re-accommodated to flight #" + targetFlightId + " (" + rebookingResult.targetFlightNumber() +
            "), seat " + rebookingResult.assignedSeat() + " (Orig PNR: " + rebookingResult.originalPnr() + ", New PNR: " + rebookingResult.newPnr() + ")"
        );

        return findById(reaccommodationId).orElseThrow(() -> new IllegalStateException("Reaccommodation saved but could not be retrieved."));
    }

    /**
     * Batch auto-reaccommodates all affected confirmed passengers on a disrupted flight.
     * Iterates through available alternate flights in order of earliest departure and available seats.
     */
    public BatchReaccommodationResult autoReaccommodateAll(long disruptionId, String remarks) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        FlightDisruption disruption;
        try (Connection conn = DBConnection.getConnection()) {
            disruption = loadDisruption(conn, disruptionId, tenantOrgId);
            if (disruption.isTerminal()) {
                throw new IllegalStateException("Cannot auto-reaccommodate for concluded disruption [" + disruption.disruptionReference() + "].");
            }
        }

        // 1. Fetch eligible reservations for disrupted flight
        List<Long> eligibleReservationIds = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT r.id FROM reservations r " +
                 "WHERE r.organization_id = ? AND r.flight_id = ? " +
                 "  AND r.status = 'CONFIRMED' " +
                 "  AND NOT EXISTS (" +
                 "      SELECT 1 FROM passenger_reaccommodations pr " +
                 "      WHERE pr.original_reservation_id = r.id AND pr.status = 'REBOOKED'" +
                 "  ) " +
                 "ORDER BY r.id ASC")) {
            stmt.setLong(1, tenantOrgId);
            stmt.setLong(2, disruption.flightId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    eligibleReservationIds.add(rs.getLong("id"));
                }
            }
        }

        int total = eligibleReservationIds.size();
        int rebooked = 0;
        int failed = 0;
        List<PassengerReaccommodation> items = new ArrayList<>();

        if (total == 0) {
            return new BatchReaccommodationResult(0, 0, 0, items);
        }

        // 2. Discover alternate flights
        List<RebookingFoundationService.AlternativeFlight> alternates =
            searchEligibleAlternateFlights(disruption.flightId());

        if (alternates.isEmpty()) {
            // Record failure for all candidates
            for (Long resId : eligibleReservationIds) {
                try {
                    recordFailedReaccommodation(disruptionId, resId, null, ReaccommodationType.AUTO, UserSession.userId(), "No alternative flights found for route.");
                    failed++;
                } catch (Exception ignored) {}
            }
            return new BatchReaccommodationResult(total, 0, failed, items);
        }

        // 3. Attempt reaccommodation for each passenger
        int alternateIndex = 0;
        for (Long resId : eligibleReservationIds) {
            boolean success = false;
            while (alternateIndex < alternates.size()) {
                RebookingFoundationService.AlternativeFlight candidate = alternates.get(alternateIndex);
                try {
                    PassengerReaccommodation outcome = reaccommodatePassenger(
                        disruptionId,
                        resId,
                        candidate.flightId(),
                        null,
                        ReaccommodationType.AUTO,
                        remarks != null ? remarks : "Batch auto-reaccommodation"
                    );
                    items.add(outcome);
                    rebooked++;
                    success = true;
                    break;
                } catch (Exception e) {
                    // Try next alternate flight if seat capacity or status failed on this flight
                    alternateIndex++;
                }
            }

            if (!success) {
                failed++;
                try {
                    recordFailedReaccommodation(disruptionId, resId, null, ReaccommodationType.AUTO, UserSession.userId(), "No remaining seat capacity on eligible alternate flights.");
                } catch (Exception ignored) {}
            }
        }

        return new BatchReaccommodationResult(total, rebooked, failed, items);
    }

    /**
     * Changes the status of an existing reaccommodation (e.g., PROPOSED -> ACCEPTED, DECLINED, CANCELLED).
     */
    public PassengerReaccommodation transitionStatus(long reaccommodationId, ReaccommodationStatus newStatus, String remarks) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PassengerReaccommodation current = loadReaccommodationById(conn, reaccommodationId, tenantOrgId)
                    .orElseThrow(() -> new IllegalArgumentException("Reaccommodation #" + reaccommodationId + " not found."));

                ReaccommodationLifecycleValidator.validateTransition(current.status(), newStatus);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE passenger_reaccommodations SET status = ?, remarks = COALESCE(?, remarks), updated_at = CURRENT_TIMESTAMP WHERE id = ? AND organization_id = ?")) {
                    stmt.setString(1, newStatus.name());
                    stmt.setString(2, remarks);
                    stmt.setLong(3, reaccommodationId);
                    stmt.setLong(4, tenantOrgId);
                    stmt.executeUpdate();
                }

                conn.commit();

                AuditService.record(
                    AuditAction.REACCOMMODATION_COMPLETED,
                    "REACCOMMODATION",
                    current.originalPnr(),
                    "Reaccommodation #" + reaccommodationId + " status changed from " + current.status() + " to " + newStatus
                );

                return findById(reaccommodationId).orElseThrow(() -> new IllegalStateException("Updated reaccommodation not found."));
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Updates notification status for a passenger reaccommodation.
     */
    public PassengerReaccommodation updateNotificationStatus(long reaccommodationId, NotificationStatus notificationStatus) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        if (notificationStatus == null) throw new IllegalArgumentException("Notification status cannot be null.");

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "UPDATE passenger_reaccommodations SET notification_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND organization_id = ?")) {
            stmt.setString(1, notificationStatus.name());
            stmt.setLong(2, reaccommodationId);
            stmt.setLong(3, tenantOrgId);
            int updated = stmt.executeUpdate();
            if (updated == 0) throw new IllegalArgumentException("Reaccommodation #" + reaccommodationId + " not found.");
        }

        return findById(reaccommodationId).orElseThrow(() -> new IllegalStateException("Updated reaccommodation not found."));
    }

    /**
     * Finds reaccommodation by ID.
     */
    public Optional<PassengerReaccommodation> findById(long reaccommodationId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        try (Connection conn = DBConnection.getConnection()) {
            return loadReaccommodationById(conn, reaccommodationId, tenantOrgId);
        }
    }

    /**
     * Finds reaccommodations associated with a disruption.
     */
    public List<PassengerReaccommodation> findByDisruptionId(long disruptionId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        String sql = baseReaccommodationQuery() +
            "WHERE pr.disruption_id = ? AND pr.organization_id = ? " +
            "ORDER BY pr.id ASC";

        List<PassengerReaccommodation> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, disruptionId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSet(rs));
                }
            }
        }
        return list;
    }

    /**
     * Finds reaccommodation for a specific original reservation.
     */
    public Optional<PassengerReaccommodation> findByOriginalReservationId(long originalReservationId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        String sql = baseReaccommodationQuery() +
            "WHERE pr.original_reservation_id = ? AND pr.organization_id = ? " +
            "ORDER BY pr.id DESC LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, originalReservationId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds reaccommodations for a disrupted flight.
     */
    public List<PassengerReaccommodation> getReaccommodationsForFlight(long flightId) throws SQLException {
        AuthorizationService.requireAnyPermission(Permission.VIEW_DISRUPTIONS, Permission.MANAGE_REACCOMMODATION);
        long tenantOrgId = getActiveTenantId();

        String sql = baseReaccommodationQuery() +
            "WHERE pr.original_flight_id = ? AND pr.organization_id = ? " +
            "ORDER BY pr.id ASC";

        List<PassengerReaccommodation> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, flightId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSet(rs));
                }
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    private void recordFailedReaccommodation(
        long disruptionId,
        long originalReservationId,
        Long targetFlightId,
        ReaccommodationType type,
        Long userId,
        String failureReason
    ) {
        long tenantOrgId = getActiveTenantId();
        String sql =
            "INSERT INTO passenger_reaccommodations (" +
            "  organization_id, disruption_id, original_reservation_id, original_pnr, " +
            "  passenger_id, original_flight_id, new_flight_id, status, " +
            "  reaccommodation_type, notification_status, initiated_by, remarks" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, 'FAILED', ?, 'FAILED', ?, ?)";

        try (Connection conn = DBConnection.getConnection()) {
            OriginalReservationInfo origRes = loadOriginalReservation(conn, originalReservationId, tenantOrgId);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, tenantOrgId);
                stmt.setLong(2, disruptionId);
                stmt.setLong(3, originalReservationId);
                stmt.setString(4, origRes.pnr());
                stmt.setLong(5, origRes.passengerId());
                stmt.setLong(6, origRes.flightId());
                if (targetFlightId != null) stmt.setLong(7, targetFlightId); else stmt.setNull(7, java.sql.Types.BIGINT);
                stmt.setString(8, (type != null ? type : ReaccommodationType.MANUAL).name());
                if (userId != null) stmt.setLong(9, userId); else stmt.setNull(9, java.sql.Types.BIGINT);
                stmt.setString(10, failureReason);
                stmt.executeUpdate();
            }
        } catch (Exception ignored) {
            // Log/ignore fallback failure persistence
        }
    }

    private static String baseReaccommodationQuery() {
        return "SELECT pr.id, pr.organization_id, pr.disruption_id, fd.disruption_reference, " +
               "       pr.original_reservation_id, pr.original_pnr, pr.passenger_id, " +
               "       p.name AS passenger_name, p.phone AS passenger_phone, " +
               "       pr.original_flight_id, f_orig.flight_number AS original_flight_number, " +
               "       pr.new_flight_id, f_new.flight_number AS new_flight_number, " +
               "       pr.new_reservation_id, pr.new_pnr, pr.new_seat_number, " +
               "       pr.status, pr.reaccommodation_type, pr.notification_status, " +
               "       pr.initiated_by, u.username AS initiated_by_username, " +
               "       pr.completed_at, pr.remarks, pr.created_at, pr.updated_at " +
               "FROM passenger_reaccommodations pr " +
               "JOIN flight_disruptions fd ON fd.id = pr.disruption_id " +
               "JOIN passengers p ON p.id = pr.passenger_id " +
               "JOIN flights f_orig ON f_orig.id = pr.original_flight_id " +
               "LEFT JOIN flights f_new ON f_new.id = pr.new_flight_id " +
               "LEFT JOIN app_users u ON u.id = pr.initiated_by ";
    }

    private Optional<PassengerReaccommodation> loadReaccommodationById(Connection conn, long id, long tenantOrgId) throws SQLException {
        String sql = baseReaccommodationQuery() + "WHERE pr.id = ? AND pr.organization_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }

    private PassengerReaccommodation mapResultSet(ResultSet rs) throws SQLException {
        Timestamp completedAtTs = rs.getTimestamp("completed_at");
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");

        long newFlightIdVal = rs.getLong("new_flight_id");
        Long newFlightId = rs.wasNull() ? null : newFlightIdVal;

        long newResIdVal = rs.getLong("new_reservation_id");
        Long newResId = rs.wasNull() ? null : newResIdVal;

        long initByVal = rs.getLong("initiated_by");
        Long initBy = rs.wasNull() ? null : initByVal;

        String notifStr = rs.getString("notification_status");
        NotificationStatus notifStatus = NotificationStatus.PENDING;
        if (notifStr != null) {
            try {
                notifStatus = NotificationStatus.valueOf(notifStr);
            } catch (Exception ignored) {}
        }

        return new PassengerReaccommodation(
            rs.getLong("id"),
            rs.getLong("organization_id"),
            rs.getLong("disruption_id"),
            rs.getString("disruption_reference"),
            rs.getLong("original_reservation_id"),
            rs.getString("original_pnr"),
            rs.getLong("passenger_id"),
            rs.getString("passenger_name"),
            rs.getString("passenger_phone"),
            rs.getLong("original_flight_id"),
            rs.getString("original_flight_number"),
            newFlightId,
            rs.getString("new_flight_number"),
            newResId,
            rs.getString("new_pnr"),
            rs.getString("new_seat_number"),
            ReaccommodationStatus.valueOf(rs.getString("status")),
            ReaccommodationType.valueOf(rs.getString("reaccommodation_type")),
            notifStatus,
            initBy,
            rs.getString("initiated_by_username"),
            completedAtTs != null ? completedAtTs.toLocalDateTime() : null,
            rs.getString("remarks"),
            createdAtTs != null ? createdAtTs.toLocalDateTime() : LocalDateTime.now(),
            updatedAtTs != null ? updatedAtTs.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private FlightDisruption loadDisruption(Connection conn, long disruptionId, long tenantOrgId) throws SQLException {
        return DisruptionService.getInstance().findById(disruptionId)
            .orElseThrow(() -> new IllegalArgumentException("Disruption #" + disruptionId + " was not found."));
    }

    private record OriginalReservationInfo(long id, String pnr, long passengerId, long flightId, String status) {}

    private OriginalReservationInfo loadOriginalReservation(Connection conn, long resId, long tenantOrgId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, pnr, passenger_id, flight_id, status, organization_id FROM reservations WHERE id = ?")) {
            stmt.setLong(1, resId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Reservation #" + resId + " not found.");
                if (rs.getLong("organization_id") != tenantOrgId) {
                    throw new AccessDeniedException("Cross-tenant access violation: Reservation does not belong to active tenant.");
                }
                return new OriginalReservationInfo(
                    rs.getLong("id"),
                    rs.getString("pnr"),
                    rs.getLong("passenger_id"),
                    rs.getLong("flight_id"),
                    rs.getString("status")
                );
            }
        }
    }

    private long getActiveTenantId() {
        Long orgId = TenantContext.getOrganizationId();
        return orgId != null ? orgId : 1L;
    }
}
