package airline.management.system.operations.pir;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.claim.ClaimEventRecord;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.security.SecureRandom;
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
 * Authoritative, transactional implementation of Property Irregularity Report (PIR) operations.
 * Enforces strict multi-tenant isolation, state machine transition checks, and event persistence.
 */
public enum PIRServiceImpl implements PIRService {
    INSTANCE;

    private static final String REF_CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public PIRRecord createPIR(long baggageId, PIRType type, String stationCode, String description) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PIR);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (type == null) {
            throw new IllegalArgumentException("PIR incident type cannot be null.");
        }
        if (!type.isSupported()) {
            throw new UnsupportedOperationException("PIR type [" + type.name() + "] is planned for future phases and not supported in Phase 16.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("PIR incident description is required.");
        }

        String station = (stationCode != null && !stationCode.isBlank()) ? stationCode.trim().toUpperCase() : "STATION";
        String cleanDesc = description.trim();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lock and validate baggage, passenger, reservation, flight under row lock
                String lookupSql =
                    "SELECT b.id, b.organization_id, b.status AS bag_status, b.tag_number, b.baggage_type, " +
                    "       b.passenger_id, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, p.organization_id AS pass_org, " +
                    "       b.reservation_id, r.pnr, r.organization_id AS res_org, " +
                    "       b.flight_id, f.flight_number, f.source, f.destination, f.organization_id AS flt_org " +
                    "FROM baggage b " +
                    "JOIN passengers p ON p.id = b.passenger_id " +
                    "JOIN reservations r ON r.id = b.reservation_id " +
                    "JOIN flights f ON f.id = b.flight_id " +
                    "WHERE b.id = ? FOR UPDATE";

                String baggageTag;
                String baggageType;
                BaggageStatus bagStatus;
                long passengerId;
                String passengerName;
                String passengerPhone;
                String passengerEmail;
                long reservationId;
                String pnr;
                long flightId;
                String flightNumber;
                String route;

                try (PreparedStatement stmt = conn.prepareStatement(lookupSql)) {
                    stmt.setLong(1, baggageId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Baggage record not found for ID: " + baggageId);
                        }

                        long bagOrg = rs.getLong("organization_id");
                        long passOrg = rs.getLong("pass_org");
                        long resOrg = rs.getLong("res_org");
                        long fltOrg = rs.getLong("flt_org");

                        if (bagOrg != tenantOrgId || passOrg != tenantOrgId || resOrg != tenantOrgId || fltOrg != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Baggage [" + rs.getString("tag_number") + "] does not belong to active tenant.");
                        }

                        baggageTag = rs.getString("tag_number");
                        baggageType = rs.getString("baggage_type") != null ? rs.getString("baggage_type") : "CHECKED";
                        bagStatus = BaggageStatus.fromString(rs.getString("bag_status"));
                        passengerId = rs.getLong("passenger_id");
                        passengerName = rs.getString("pass_name");
                        passengerPhone = rs.getString("pass_phone");
                        passengerEmail = rs.getString("pass_email");
                        reservationId = rs.getLong("reservation_id");
                        pnr = rs.getString("pnr");
                        flightId = rs.getLong("flight_id");
                        flightNumber = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");

                        // Enforce prerequisite: Baggage must be in DELAYED or MISSING status
                        if (bagStatus != BaggageStatus.DELAYED && bagStatus != BaggageStatus.MISSING) {
                            conn.rollback();
                            throw new IllegalStateException(
                                "Cannot file Property Irregularity Report for baggage with status [" + bagStatus.name() +
                                "]. Baggage must be marked DELAYED or MISSING prior to filing a PIR."
                            );
                        }
                    }
                }

                // 2. Prevent duplicate active PIR for this baggage unit
                String checkDupSql =
                    "SELECT pir_reference FROM pir_reports WHERE organization_id = ? AND baggage_id = ? AND status != 'CLOSED' FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(checkDupSql)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, baggageId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String activeRef = rs.getString("pir_reference");
                            conn.rollback();
                            throw new IllegalStateException(
                                "Active Property Irregularity Report [" + activeRef +
                                "] already exists for baggage tag [" + baggageTag + "]."
                            );
                        }
                    }
                }

                // 3. Generate unique, collision-resistant PIR reference
                String pirReference = generateUniquePirReference(conn);
                Long userId = UserSession.userId();

                // 4. Insert PIR record
                String insertPirSql =
                    "INSERT INTO pir_reports (organization_id, pir_reference, baggage_id, passenger_id, reservation_id, flight_id, " +
                    "                        pir_type, status, station_code, description, created_by_user_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)";

                long pirId;
                try (PreparedStatement stmt = conn.prepareStatement(insertPirSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setString(2, pirReference);
                    stmt.setLong(3, baggageId);
                    stmt.setLong(4, passengerId);
                    stmt.setLong(5, reservationId);
                    stmt.setLong(6, flightId);
                    stmt.setString(7, type.name());
                    stmt.setString(8, station);
                    stmt.setString(9, cleanDesc);
                    if (userId != null) {
                        stmt.setLong(10, userId);
                    } else {
                        stmt.setNull(10, java.sql.Types.BIGINT);
                    }
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        pirId = rs.getLong(1);
                    }
                }

                // 5. Insert initial event in claim_events
                insertClaimEvent(conn, tenantOrgId, "PIR", pirId, "PIR_CREATED", userId,
                    "PIR filed for " + type.getDisplayName() + " at " + station + ": " + cleanDesc);

                // 6. Audit logging
                AuditService.record(
                    AuditAction.PIR_CREATED,
                    "PIR",
                    pirReference,
                    "PIR [" + pirReference + "] (" + type.name() + ") created for baggage [" + baggageTag + "], passenger [" + passengerName + "]"
                );

                conn.commit();

                return new PIRRecord(
                    pirId,
                    tenantOrgId,
                    pirReference,
                    baggageId,
                    baggageTag,
                    baggageType,
                    bagStatus,
                    passengerId,
                    passengerName,
                    passengerPhone,
                    passengerEmail,
                    reservationId,
                    pnr,
                    flightId,
                    flightNumber,
                    route,
                    type,
                    PIRStatus.OPEN,
                    LocalDateTime.now(),
                    station,
                    cleanDesc,
                    null,
                    userId,
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now()
                );
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public PIRRecord updatePIRStatus(long pirId, PIRStatus newStatus, String remarks) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PIR);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (newStatus == null) {
            throw new IllegalArgumentException("New PIR status cannot be null.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock and retrieve PIR record
                String selectSql =
                    "SELECT pr.id, pr.organization_id, pr.pir_reference, pr.status, pr.baggage_id, pr.passenger_id, " +
                    "       pr.reservation_id, pr.flight_id, pr.pir_type, pr.station_code, pr.description, pr.investigation_notes, " +
                    "       pr.created_by_user_id, pr.created_at, pr.incident_time, " +
                    "       b.tag_number, b.baggage_type, b.status AS bag_status, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, " +
                    "       r.pnr, f.flight_number, f.source, f.destination " +
                    "FROM pir_reports pr " +
                    "JOIN baggage b ON b.id = pr.baggage_id " +
                    "JOIN passengers p ON p.id = pr.passenger_id " +
                    "JOIN reservations r ON r.id = pr.reservation_id " +
                    "JOIN flights f ON f.id = pr.flight_id " +
                    "WHERE pr.id = ? FOR UPDATE";

                PIRStatus currentStatus;
                String pirRef;
                long baggageId;
                String baggageTag;
                String baggageType;
                BaggageStatus bagStatus;
                long passengerId;
                String passengerName;
                String passengerPhone;
                String passengerEmail;
                long reservationId;
                String pnr;
                long flightId;
                String flightNumber;
                String route;
                PIRType pirType;
                String stationCode;
                String description;
                String investigationNotes;
                Long createdByUserId;
                LocalDateTime incidentTime;
                LocalDateTime createdAt;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setLong(1, pirId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("PIR not found for ID: " + pirId);
                        }

                        long orgId = rs.getLong("organization_id");
                        if (orgId != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: PIR [" + rs.getString("pir_reference") + "] does not belong to active tenant.");
                        }

                        currentStatus = PIRStatus.fromString(rs.getString("status"));
                        pirRef = rs.getString("pir_reference");
                        baggageId = rs.getLong("baggage_id");
                        baggageTag = rs.getString("tag_number");
                        baggageType = rs.getString("baggage_type") != null ? rs.getString("baggage_type") : "CHECKED";
                        bagStatus = BaggageStatus.fromString(rs.getString("bag_status"));
                        passengerId = rs.getLong("passenger_id");
                        passengerName = rs.getString("pass_name");
                        passengerPhone = rs.getString("pass_phone");
                        passengerEmail = rs.getString("pass_email");
                        reservationId = rs.getLong("reservation_id");
                        pnr = rs.getString("pnr");
                        flightId = rs.getLong("flight_id");
                        flightNumber = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");
                        pirType = PIRType.fromString(rs.getString("pir_type"));
                        stationCode = rs.getString("station_code");
                        description = rs.getString("description");
                        investigationNotes = rs.getString("investigation_notes");
                        long cb = rs.getLong("created_by_user_id");
                        createdByUserId = rs.wasNull() ? null : cb;
                        incidentTime = rs.getTimestamp("incident_time").toLocalDateTime();
                        createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    }
                }

                // Validate state transition
                PIRLifecycleValidator.validateTransition(currentStatus, newStatus);

                boolean isResolving = (newStatus == PIRStatus.DELIVERED || newStatus == PIRStatus.CLOSED);
                String updateSql =
                    "UPDATE pir_reports SET status = ?, resolved_at = " + (isResolving ? "NOW()" : "resolved_at") + " WHERE id = ? AND organization_id = ?";

                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setString(1, newStatus.name());
                    stmt.setLong(2, pirId);
                    stmt.setLong(3, tenantOrgId);
                    stmt.executeUpdate();
                }

                Long userId = UserSession.userId();
                String eventRemarks = "Status transitioned from " + currentStatus.name() + " to " + newStatus.name();
                if (remarks != null && !remarks.isBlank()) {
                    eventRemarks += " — " + remarks.trim();
                }

                insertClaimEvent(conn, tenantOrgId, "PIR", pirId, "PIR_STATUS_CHANGED", userId, eventRemarks);

                AuditService.record(
                    AuditAction.PIR_STATUS_CHANGED,
                    "PIR",
                    pirRef,
                    "PIR [" + pirRef + "] transitioned from " + currentStatus.name() + " to " + newStatus.name() +
                    (remarks != null && !remarks.isBlank() ? " (" + remarks.trim() + ")" : "")
                );

                conn.commit();

                return new PIRRecord(
                    pirId,
                    tenantOrgId,
                    pirRef,
                    baggageId,
                    baggageTag,
                    baggageType,
                    bagStatus,
                    passengerId,
                    passengerName,
                    passengerPhone,
                    passengerEmail,
                    reservationId,
                    pnr,
                    flightId,
                    flightNumber,
                    route,
                    pirType,
                    newStatus,
                    incidentTime,
                    stationCode,
                    description,
                    investigationNotes,
                    createdByUserId,
                    isResolving ? LocalDateTime.now() : null,
                    createdAt,
                    LocalDateTime.now()
                );
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public PIRRecord addInvestigationNotes(long pirId, String notes) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PIR);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("Investigation notes cannot be empty.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String selectSql = "SELECT organization_id, pir_reference, investigation_notes FROM pir_reports WHERE id = ? FOR UPDATE";
                String pirRef;
                String currentNotes;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setLong(1, pirId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("PIR not found for ID: " + pirId);
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation.");
                        }
                        pirRef = rs.getString("pir_reference");
                        currentNotes = rs.getString("investigation_notes");
                    }
                }

                String userDisplay = UserSession.username() != null ? UserSession.username() : "Staff";
                String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String appendNote = "[" + timestamp + " - " + userDisplay + "]: " + notes.trim();
                String updatedNotes = (currentNotes == null || currentNotes.isBlank()) ? appendNote : currentNotes + "\n" + appendNote;

                try (PreparedStatement stmt = conn.prepareStatement("UPDATE pir_reports SET investigation_notes = ? WHERE id = ? AND organization_id = ?")) {
                    stmt.setString(1, updatedNotes);
                    stmt.setLong(2, pirId);
                    stmt.setLong(3, tenantOrgId);
                    stmt.executeUpdate();
                }

                insertClaimEvent(conn, tenantOrgId, "PIR", pirId, "PIR_NOTE_ADDED", UserSession.userId(), notes.trim());

                AuditService.record(
                    AuditAction.PIR_NOTE_ADDED,
                    "PIR",
                    pirRef,
                    "Investigation note added to PIR [" + pirRef + "]"
                );

                conn.commit();

                return findById(pirId).orElseThrow();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public Optional<PIRRecord> findById(long pirId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE pr.id = ? AND pr.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, pirId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPIR(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PIRRecord> findByReference(String pirReference) throws SQLException {
        requireViewAccess();
        if (pirReference == null || pirReference.isBlank()) {
            return Optional.empty();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE pr.pir_reference = ? AND pr.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pirReference.trim().toUpperCase());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPIR(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PIRRecord> findActiveByBaggageId(long baggageId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE pr.baggage_id = ? AND pr.organization_id = ? AND pr.status != 'CLOSED' ORDER BY pr.id DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, baggageId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPIR(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PIRRecord> findActiveByBaggageTag(String baggageTag) throws SQLException {
        requireViewAccess();
        if (baggageTag == null || baggageTag.isBlank()) {
            return Optional.empty();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE b.tag_number = ? AND pr.organization_id = ? AND pr.status != 'CLOSED' ORDER BY pr.id DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, baggageTag.trim().toUpperCase());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPIR(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PIRRecord> searchPIRs(String query, PIRStatus statusFilter) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        StringBuilder sql = new StringBuilder(basePIRSelect()).append(" WHERE pr.organization_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (statusFilter != null) {
            sql.append(" AND pr.status = ? ");
            params.add(statusFilter.name());
        }

        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim() + "%";
            sql.append(" AND (pr.pir_reference LIKE ? OR b.tag_number LIKE ? OR r.pnr LIKE ? OR p.name LIKE ? OR f.flight_number LIKE ? OR CAST(pr.id AS CHAR) LIKE ?) ");
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append(" ORDER BY pr.created_at DESC");

        List<PIRRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToPIR(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<PIRRecord> getPIRsByReservation(long reservationId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE pr.reservation_id = ? AND pr.organization_id = ? ORDER BY pr.id DESC";
        List<PIRRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, reservationId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToPIR(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<PIRRecord> getPIRsByPnr(String pnr) throws SQLException {
        requireViewAccess();
        if (pnr == null || pnr.isBlank()) {
            return List.of();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = basePIRSelect() + " WHERE r.pnr = ? AND pr.organization_id = ? ORDER BY pr.id DESC";
        List<PIRRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pnr.trim().toUpperCase());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToPIR(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<ClaimEventRecord> getPIREvents(long pirId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql =
            "SELECT ce.id, ce.organization_id, ce.entity_type, ce.entity_id, ce.event_type, " +
            "       ce.performed_by_user_id, u.username, ce.remarks, ce.created_at " +
            "FROM claim_events ce " +
            "LEFT JOIN app_users u ON u.id = ce.performed_by_user_id " +
            "WHERE ce.entity_type = 'PIR' AND ce.entity_id = ? AND ce.organization_id = ? " +
            "ORDER BY ce.created_at ASC";

        List<ClaimEventRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, pirId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long uid = rs.getLong("performed_by_user_id");
                    Long userId = rs.wasNull() ? null : uid;
                    Timestamp cat = rs.getTimestamp("created_at");
                    list.add(new ClaimEventRecord(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("event_type"),
                        userId,
                        rs.getString("username"),
                        rs.getString("remarks"),
                        cat != null ? cat.toLocalDateTime() : LocalDateTime.now()
                    ));
                }
            }
        }
        return list;
    }

    private static String basePIRSelect() {
        return "SELECT pr.id, pr.organization_id, pr.pir_reference, pr.baggage_id, pr.passenger_id, " +
               "       pr.reservation_id, pr.flight_id, pr.pir_type, pr.status, pr.incident_time, " +
               "       pr.station_code, pr.description, pr.investigation_notes, pr.created_by_user_id, " +
               "       pr.resolved_at, pr.created_at, pr.updated_at, " +
               "       b.tag_number, b.baggage_type, b.status AS bag_status, p.name AS pass_name, p.phone AS pass_phone, p.email AS pass_email, " +
               "       r.pnr, f.flight_number, f.source, f.destination " +
               "FROM pir_reports pr " +
               "JOIN baggage b ON b.id = pr.baggage_id " +
               "JOIN passengers p ON p.id = pr.passenger_id " +
               "JOIN reservations r ON r.id = pr.reservation_id " +
               "JOIN flights f ON f.id = pr.flight_id ";
    }

    private static PIRRecord mapResultSetToPIR(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long orgId = rs.getLong("organization_id");
        String pirRef = rs.getString("pir_reference");
        long baggageId = rs.getLong("baggage_id");
        String baggageTag = rs.getString("tag_number");
        String baggageType = rs.getString("baggage_type") != null ? rs.getString("baggage_type") : "CHECKED";
        BaggageStatus bagStatus = BaggageStatus.fromString(rs.getString("bag_status"));
        long passengerId = rs.getLong("passenger_id");
        String passName = rs.getString("pass_name");
        String passPhone = rs.getString("pass_phone");
        String passEmail = rs.getString("pass_email");
        long reservationId = rs.getLong("reservation_id");
        String pnr = rs.getString("pnr");
        long flightId = rs.getLong("flight_id");
        String flightNumber = rs.getString("flight_number");
        String route = rs.getString("source") + " → " + rs.getString("destination");
        PIRType type = PIRType.fromString(rs.getString("pir_type"));
        PIRStatus status = PIRStatus.fromString(rs.getString("status"));
        Timestamp incTime = rs.getTimestamp("incident_time");
        String station = rs.getString("station_code");
        String desc = rs.getString("description");
        String notes = rs.getString("investigation_notes");
        long uid = rs.getLong("created_by_user_id");
        Long createdBy = rs.wasNull() ? null : uid;
        Timestamp resAt = rs.getTimestamp("resolved_at");
        Timestamp cat = rs.getTimestamp("created_at");
        Timestamp uat = rs.getTimestamp("updated_at");

        return new PIRRecord(
            id,
            orgId,
            pirRef,
            baggageId,
            baggageTag,
            baggageType,
            bagStatus,
            passengerId,
            passName,
            passPhone,
            passEmail,
            reservationId,
            pnr,
            flightId,
            flightNumber,
            route,
            type,
            status,
            incTime != null ? incTime.toLocalDateTime() : LocalDateTime.now(),
            station,
            desc,
            notes,
            createdBy,
            resAt != null ? resAt.toLocalDateTime() : null,
            cat != null ? cat.toLocalDateTime() : LocalDateTime.now(),
            uat != null ? uat.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private static String generateUniquePirReference(Connection conn) throws SQLException {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("PIR");
            for (int i = 0; i < 8; i++) {
                sb.append(REF_CHARACTERS.charAt(RANDOM.nextInt(REF_CHARACTERS.length())));
            }
            String candidate = sb.toString();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM pir_reports WHERE pir_reference = ?")) {
                stmt.setString(1, candidate);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to generate a unique PIR reference after multiple attempts.");
    }

    public static void insertClaimEvent(Connection conn, long orgId, String entityType, long entityId,
                                        String eventType, Long userId, String remarks) throws SQLException {
        String sql =
            "INSERT INTO claim_events (organization_id, entity_type, entity_id, event_type, performed_by_user_id, remarks) " +
            "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orgId);
            stmt.setString(2, entityType);
            stmt.setLong(3, entityId);
            stmt.setString(4, eventType);
            if (userId != null) {
                stmt.setLong(5, userId);
            } else {
                stmt.setNull(5, java.sql.Types.BIGINT);
            }
            stmt.setString(6, remarks);
            stmt.executeUpdate();
        }
    }

    private static void requireViewAccess() {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        if (AuthorizationService.hasPermission(Permission.MANAGE_PIR)
                || AuthorizationService.hasPermission(Permission.MANAGE_CLAIMS)
                || AuthorizationService.hasPermission(Permission.REVIEW_CLAIMS)
                || AuthorizationService.hasPermission(Permission.MANAGE_CLAIM_SETTLEMENTS)
                || AuthorizationService.hasPermission(Permission.VIEW_REPORTS)) return;
        throw new AccessDeniedException("Access denied: PIR viewing permission is required.");
    }
}
