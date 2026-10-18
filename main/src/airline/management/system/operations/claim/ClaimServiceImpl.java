package airline.management.system.operations.claim;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.operations.pir.PIRServiceImpl;
import airline.management.system.operations.pir.PIRStatus;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Authoritative, transactional implementation of passenger baggage claim operations.
 * Enforces tenant isolation, state machine validation, distinct financial settlements, and audit tracking.
 */
public enum ClaimServiceImpl implements ClaimService {
    INSTANCE;

    private static final String REF_CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public ClaimCase createClaim(long pirId, BigDecimal requestedAmount, String currency, String claimReason) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_CLAIMS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (requestedAmount == null || requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Claim requested compensation amount must be greater than zero.");
        }
        if (requestedAmount.compareTo(new BigDecimal("50000.00")) > 0) {
            throw new IllegalArgumentException("Requested compensation amount exceeds maximum threshold (50,000.00).");
        }
        if (claimReason == null || claimReason.isBlank()) {
            throw new IllegalArgumentException("Claim justification reason is required.");
        }

        String cur = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "USD";
        String cleanReason = claimReason.trim();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lock and validate PIR and its linked baggage, passenger, and reservation
                String lookupSql =
                    "SELECT pr.id, pr.organization_id, pr.pir_reference, pr.status AS pir_status, " +
                    "       pr.baggage_id, pr.passenger_id, pr.reservation_id, " +
                    "       b.tag_number, p.name AS pass_name, r.pnr " +
                    "FROM pir_reports pr " +
                    "JOIN baggage b ON b.id = pr.baggage_id " +
                    "JOIN passengers p ON p.id = pr.passenger_id " +
                    "JOIN reservations r ON r.id = pr.reservation_id " +
                    "WHERE pr.id = ? FOR UPDATE";

                String pirRef;
                PIRStatus pirStatus;
                long baggageId;
                String baggageTag;
                long passengerId;
                String passengerName;
                long reservationId;
                String pnr;

                try (PreparedStatement stmt = conn.prepareStatement(lookupSql)) {
                    stmt.setLong(1, pirId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Property Irregularity Report not found for ID: " + pirId);
                        }

                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: PIR does not belong to active tenant.");
                        }

                        pirRef = rs.getString("pir_reference");
                        pirStatus = PIRStatus.fromString(rs.getString("pir_status"));
                        baggageId = rs.getLong("baggage_id");
                        baggageTag = rs.getString("tag_number");
                        passengerId = rs.getLong("passenger_id");
                        passengerName = rs.getString("pass_name");
                        reservationId = rs.getLong("reservation_id");
                        pnr = rs.getString("pnr");

                        if (pirStatus == PIRStatus.CLOSED) {
                            conn.rollback();
                            throw new IllegalStateException("Cannot file a baggage claim against a CLOSED Property Irregularity Report.");
                        }
                    }
                }

                // 2. Prevent duplicate active claim for this PIR
                String checkDupSql =
                    "SELECT claim_reference FROM claim_cases " +
                    "WHERE organization_id = ? AND pir_id = ? AND status NOT IN ('CLOSED', 'REJECTED') FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(checkDupSql)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, pirId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String activeRef = rs.getString("claim_reference");
                            conn.rollback();
                            throw new IllegalStateException(
                                "An active claim [" + activeRef + "] already exists for PIR [" + pirRef + "]."
                            );
                        }
                    }
                }

                // 3. Generate unique claim reference (e.g. CLMXXXXXXXX)
                String claimRef = generateUniqueReference(conn, "claim_cases", "claim_reference", "CLM");
                Long userId = UserSession.userId();
                BigDecimal reqScaled = requestedAmount.setScale(2, RoundingMode.HALF_UP);

                // 4. Insert claim record
                String insertClaimSql =
                    "INSERT INTO claim_cases (organization_id, claim_reference, pir_id, baggage_id, passenger_id, " +
                    "                        reservation_id, status, currency, requested_amount, claim_reason, created_by_user_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?)";

                long claimId;
                try (PreparedStatement stmt = conn.prepareStatement(insertClaimSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setString(2, claimRef);
                    stmt.setLong(3, pirId);
                    stmt.setLong(4, baggageId);
                    stmt.setLong(5, passengerId);
                    stmt.setLong(6, reservationId);
                    stmt.setString(7, cur);
                    stmt.setBigDecimal(8, reqScaled);
                    stmt.setString(9, cleanReason);
                    if (userId != null) {
                        stmt.setLong(10, userId);
                    } else {
                        stmt.setNull(10, java.sql.Types.BIGINT);
                    }
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        claimId = rs.getLong(1);
                    }
                }

                // 5. Insert initial event in claim_events
                PIRServiceImpl.insertClaimEvent(conn, tenantOrgId, "CLAIM", claimId, "CLAIM_CREATED", userId,
                    "Claim submitted requesting " + cur + " " + reqScaled + ": " + cleanReason);

                // 6. Audit logging
                AuditService.record(
                    AuditAction.CLAIM_CREATED,
                    "CLAIM",
                    claimRef,
                    "Claim [" + claimRef + "] (" + cur + " " + reqScaled + ") filed for PIR [" + pirRef + "] (" + passengerName + ")"
                );

                conn.commit();

                return new ClaimCase(
                    claimId,
                    tenantOrgId,
                    claimRef,
                    pirId,
                    pirRef,
                    pirStatus,
                    baggageId,
                    baggageTag,
                    passengerId,
                    passengerName,
                    reservationId,
                    pnr,
                    ClaimStatus.SUBMITTED,
                    cur,
                    reqScaled,
                    null,
                    cleanReason,
                    null,
                    userId,
                    null,
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
    public ClaimCase reviewClaim(long claimId, ClaimStatus newStatus, BigDecimal approvedAmount, String reviewNotes) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.REVIEW_CLAIMS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (newStatus == null) {
            throw new IllegalArgumentException("New claim status cannot be null.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock and retrieve claim
                String selectSql =
                    "SELECT c.id, c.organization_id, c.claim_reference, c.pir_id, c.baggage_id, c.passenger_id, " +
                    "       c.reservation_id, c.status, c.currency, c.requested_amount, c.approved_amount, c.claim_reason, " +
                    "       c.review_notes, c.created_by_user_id, c.reviewed_by_user_id, c.settled_at, c.created_at, " +
                    "       pr.pir_reference, pr.status AS pir_status, b.tag_number, p.name AS pass_name, r.pnr " +
                    "FROM claim_cases c " +
                    "JOIN pir_reports pr ON pr.id = c.pir_id " +
                    "JOIN baggage b ON b.id = c.baggage_id " +
                    "JOIN passengers p ON p.id = c.passenger_id " +
                    "JOIN reservations r ON r.id = c.reservation_id " +
                    "WHERE c.id = ? FOR UPDATE";

                ClaimStatus currentStatus;
                String claimRef;
                long pirId;
                String pirRef;
                PIRStatus pirStatus;
                long baggageId;
                String baggageTag;
                long passengerId;
                String passengerName;
                long reservationId;
                String pnr;
                String currency;
                BigDecimal reqAmount;
                BigDecimal curApproved;
                String currentReason;
                String curNotes;
                Long createdBy;
                LocalDateTime settledAt;
                LocalDateTime createdAt;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setLong(1, claimId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Claim not found for ID: " + claimId);
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Claim does not belong to active tenant.");
                        }

                        currentStatus = ClaimStatus.fromString(rs.getString("status"));
                        claimRef = rs.getString("claim_reference");
                        pirId = rs.getLong("pir_id");
                        pirRef = rs.getString("pir_reference");
                        pirStatus = PIRStatus.fromString(rs.getString("pir_status"));
                        baggageId = rs.getLong("baggage_id");
                        baggageTag = rs.getString("tag_number");
                        passengerId = rs.getLong("passenger_id");
                        passengerName = rs.getString("pass_name");
                        reservationId = rs.getLong("reservation_id");
                        pnr = rs.getString("pnr");
                        currency = rs.getString("currency");
                        reqAmount = rs.getBigDecimal("requested_amount");
                        curApproved = rs.getBigDecimal("approved_amount");
                        currentReason = rs.getString("claim_reason");
                        curNotes = rs.getString("review_notes");
                        long cb = rs.getLong("created_by_user_id");
                        createdBy = rs.wasNull() ? null : cb;
                        Timestamp stAt = rs.getTimestamp("settled_at");
                        settledAt = stAt != null ? stAt.toLocalDateTime() : null;
                        createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    }
                }

                // Validate state machine transition
                ClaimLifecycleValidator.validateTransition(currentStatus, newStatus);

                BigDecimal finalApproved = curApproved;
                if (newStatus == ClaimStatus.APPROVED) {
                    if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        conn.rollback();
                        throw new IllegalArgumentException("Approved compensation amount must be greater than zero when approving a claim.");
                    }
                    if (approvedAmount.compareTo(reqAmount) > 0) {
                        conn.rollback();
                        throw new IllegalArgumentException("Approved amount (" + approvedAmount + ") cannot exceed requested compensation amount (" + reqAmount + ").");
                    }
                    finalApproved = approvedAmount.setScale(2, RoundingMode.HALF_UP);
                }

                Long reviewerId = UserSession.userId();
                String userDisplay = UserSession.username() != null ? UserSession.username() : "Reviewer";
                String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

                String newNote = null;
                if (reviewNotes != null && !reviewNotes.isBlank()) {
                    newNote = "[" + timestamp + " - " + userDisplay + " - " + newStatus.name() + "]: " + reviewNotes.trim();
                }
                String updatedNotes = (curNotes == null || curNotes.isBlank()) ? newNote : (newNote != null ? curNotes + "\n" + newNote : curNotes);

                String updateSql =
                    "UPDATE claim_cases SET status = ?, approved_amount = ?, review_notes = ?, reviewed_by_user_id = ? WHERE id = ? AND organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setString(1, newStatus.name());
                    if (finalApproved != null) {
                        stmt.setBigDecimal(2, finalApproved);
                    } else {
                        stmt.setNull(2, java.sql.Types.DECIMAL);
                    }
                    stmt.setString(3, updatedNotes);
                    if (reviewerId != null) {
                        stmt.setLong(4, reviewerId);
                    } else {
                        stmt.setNull(4, java.sql.Types.BIGINT);
                    }
                    stmt.setLong(5, claimId);
                    stmt.setLong(6, tenantOrgId);
                    stmt.executeUpdate();
                }

                // Record event
                String eventType = "CLAIM_STATUS_CHANGED";
                AuditAction auditAction = AuditAction.CLAIM_STATUS_CHANGED;
                if (newStatus == ClaimStatus.APPROVED) {
                    eventType = "CLAIM_APPROVED";
                    auditAction = AuditAction.CLAIM_APPROVED;
                } else if (newStatus == ClaimStatus.REJECTED) {
                    eventType = "CLAIM_REJECTED";
                    auditAction = AuditAction.CLAIM_REJECTED;
                } else if (newStatus == ClaimStatus.CLOSED) {
                    eventType = "CLAIM_CLOSED";
                    auditAction = AuditAction.CLAIM_CLOSED;
                }

                String eventRemarks = "Status: " + currentStatus.name() + " → " + newStatus.name() +
                    (finalApproved != null ? " (Approved: " + currency + " " + finalApproved + ")" : "") +
                    (reviewNotes != null && !reviewNotes.isBlank() ? " — " + reviewNotes.trim() : "");

                PIRServiceImpl.insertClaimEvent(conn, tenantOrgId, "CLAIM", claimId, eventType, reviewerId, eventRemarks);

                AuditService.record(
                    auditAction,
                    "CLAIM",
                    claimRef,
                    "Claim [" + claimRef + "] " + newStatus.name() + " for PIR [" + pirRef + "]" +
                    (finalApproved != null ? " with approved amount " + currency + " " + finalApproved : "")
                );

                conn.commit();

                return new ClaimCase(
                    claimId,
                    tenantOrgId,
                    claimRef,
                    pirId,
                    pirRef,
                    pirStatus,
                    baggageId,
                    baggageTag,
                    passengerId,
                    passengerName,
                    reservationId,
                    pnr,
                    newStatus,
                    currency,
                    reqAmount,
                    finalApproved,
                    currentReason,
                    updatedNotes,
                    createdBy,
                    reviewerId,
                    settledAt,
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
    public ClaimSettlementRecord settleClaim(long claimId, String paymentMethod, String settlementReason) throws SQLException {
        if (!UserSession.isAuthenticated()) {
            throw new AccessDeniedException("Unauthenticated access denied: Active session required.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_CLAIM_SETTLEMENTS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (settlementReason == null || settlementReason.isBlank()) {
            throw new IllegalArgumentException("Settlement justification reason is required.");
        }

        String method = (paymentMethod != null && !paymentMethod.isBlank()) ? paymentMethod.trim().toUpperCase() : "BANK_TRANSFER";
        String cleanReason = settlementReason.trim();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Lock claim case
                String selectSql =
                    "SELECT id, organization_id, claim_reference, status, approved_amount, currency " +
                    "FROM claim_cases WHERE id = ? FOR UPDATE";

                String claimRef;
                ClaimStatus status;
                BigDecimal approvedAmount;
                String currency;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setLong(1, claimId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Claim record not found for ID: " + claimId);
                        }
                        if (rs.getLong("organization_id") != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Claim does not belong to active tenant.");
                        }

                        claimRef = rs.getString("claim_reference");
                        status = ClaimStatus.fromString(rs.getString("status"));
                        approvedAmount = rs.getBigDecimal("approved_amount");
                        currency = rs.getString("currency");
                    }
                }

                // 2. Enforce status: Must be APPROVED and not SETTLED
                if (status == ClaimStatus.SETTLED) {
                    conn.rollback();
                    throw new IllegalStateException("Claim [" + claimRef + "] is already settled.");
                }
                if (status != ClaimStatus.APPROVED) {
                    conn.rollback();
                    throw new IllegalStateException("Only APPROVED claims can be settled. Current status: " + status.name());
                }
                if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    conn.rollback();
                    throw new IllegalStateException("Claim has no approved compensation amount.");
                }

                // 3. Prevent duplicate settlement in claim_settlements table
                String checkSettledSql = "SELECT settlement_reference FROM claim_settlements WHERE claim_id = ? FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(checkSettledSql)) {
                    stmt.setLong(1, claimId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String stlRef = rs.getString("settlement_reference");
                            conn.rollback();
                            throw new IllegalStateException("Settlement [" + stlRef + "] already exists for claim [" + claimRef + "].");
                        }
                    }
                }

                // 4. Generate unique settlement reference (e.g. STLXXXXXXXX)
                String settlementRef = generateUniqueReference(conn, "claim_settlements", "settlement_reference", "STL");
                Long userId = UserSession.userId();
                String username = UserSession.username() != null ? UserSession.username() : "Staff";

                // 5. Insert settlement record
                String insertSettlementSql =
                    "INSERT INTO claim_settlements (organization_id, settlement_reference, claim_id, amount, " +
                    "                              currency, payment_method, settlement_status, settlement_reason, authorized_by_user_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)";

                long settlementId;
                try (PreparedStatement stmt = conn.prepareStatement(insertSettlementSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setString(2, settlementRef);
                    stmt.setLong(3, claimId);
                    stmt.setBigDecimal(4, approvedAmount);
                    stmt.setString(5, currency);
                    stmt.setString(6, method);
                    stmt.setString(7, cleanReason);
                    if (userId != null) {
                        stmt.setLong(8, userId);
                    } else {
                        stmt.setNull(8, java.sql.Types.BIGINT);
                    }
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        settlementId = rs.getLong(1);
                    }
                }

                // 6. Transition claim status to SETTLED
                String updateClaimSql = "UPDATE claim_cases SET status = 'SETTLED', settled_at = NOW() WHERE id = ? AND organization_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateClaimSql)) {
                    stmt.setLong(1, claimId);
                    stmt.setLong(2, tenantOrgId);
                    stmt.executeUpdate();
                }

                // 7. Record event in claim_events
                PIRServiceImpl.insertClaimEvent(conn, tenantOrgId, "CLAIM", claimId, "CLAIM_SETTLED", userId,
                    "Settlement [" + settlementRef + "] executed for " + currency + " " + approvedAmount + " via " + method + ": " + cleanReason);

                // 8. Audit log
                AuditService.record(
                    AuditAction.CLAIM_SETTLED,
                    "SETTLEMENT",
                    settlementRef,
                    "Settlement [" + settlementRef + "] (" + currency + " " + approvedAmount + ") executed for claim [" + claimRef + "]"
                );

                conn.commit();

                return new ClaimSettlementRecord(
                    settlementId,
                    tenantOrgId,
                    settlementRef,
                    claimId,
                    claimRef,
                    approvedAmount,
                    currency,
                    method,
                    "COMPLETED",
                    cleanReason,
                    userId,
                    username,
                    LocalDateTime.now()
                );
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public Optional<ClaimCase> findById(long claimId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseClaimSelect() + " WHERE c.id = ? AND c.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, claimId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToClaim(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ClaimCase> findByReference(String claimReference) throws SQLException {
        requireViewAccess();
        if (claimReference == null || claimReference.isBlank()) {
            return Optional.empty();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseClaimSelect() + " WHERE c.claim_reference = ? AND c.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, claimReference.trim().toUpperCase());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToClaim(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ClaimCase> findActiveByPirId(long pirId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseClaimSelect() + " WHERE c.pir_id = ? AND c.organization_id = ? AND c.status NOT IN ('CLOSED', 'REJECTED') ORDER BY c.id DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, pirId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToClaim(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ClaimCase> searchClaims(String query, ClaimStatus statusFilter) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        StringBuilder sql = new StringBuilder(baseClaimSelect()).append(" WHERE c.organization_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (statusFilter != null) {
            sql.append(" AND c.status = ? ");
            params.add(statusFilter.name());
        }

        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim() + "%";
            sql.append(" AND (c.claim_reference LIKE ? OR pr.pir_reference LIKE ? OR b.tag_number LIKE ? OR r.pnr LIKE ? OR p.name LIKE ? OR CAST(c.id AS CHAR) LIKE ?) ");
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append(" ORDER BY c.created_at DESC");

        List<ClaimCase> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToClaim(rs));
                }
            }
        }
        return list;
    }

    @Override
    public Optional<ClaimSettlementRecord> getSettlementByClaimId(long claimId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql =
            "SELECT cs.id, cs.organization_id, cs.settlement_reference, cs.claim_id, c.claim_reference, " +
            "       cs.amount, cs.currency, cs.payment_method, cs.settlement_status, cs.settlement_reason, " +
            "       cs.authorized_by_user_id, u.username, cs.settled_at " +
            "FROM claim_settlements cs " +
            "JOIN claim_cases c ON c.id = cs.claim_id " +
            "LEFT JOIN app_users u ON u.id = cs.authorized_by_user_id " +
            "WHERE cs.claim_id = ? AND cs.organization_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, claimId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long uid = rs.getLong("authorized_by_user_id");
                    Long authId = rs.wasNull() ? null : uid;
                    Timestamp stAt = rs.getTimestamp("settled_at");
                    return Optional.of(new ClaimSettlementRecord(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("settlement_reference"),
                        rs.getLong("claim_id"),
                        rs.getString("claim_reference"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("payment_method"),
                        rs.getString("settlement_status"),
                        rs.getString("settlement_reason"),
                        authId,
                        rs.getString("username"),
                        stAt != null ? stAt.toLocalDateTime() : LocalDateTime.now()
                    ));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ClaimEventRecord> getClaimEvents(long claimId) throws SQLException {
        requireViewAccess();
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql =
            "SELECT ce.id, ce.organization_id, ce.entity_type, ce.entity_id, ce.event_type, " +
            "       ce.performed_by_user_id, u.username, ce.remarks, ce.created_at " +
            "FROM claim_events ce " +
            "LEFT JOIN app_users u ON u.id = ce.performed_by_user_id " +
            "WHERE ce.entity_type = 'CLAIM' AND ce.entity_id = ? AND ce.organization_id = ? " +
            "ORDER BY ce.created_at ASC";

        List<ClaimEventRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, claimId);
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

    private static String baseClaimSelect() {
        return "SELECT c.id, c.organization_id, c.claim_reference, c.pir_id, c.baggage_id, c.passenger_id, " +
               "       c.reservation_id, c.status, c.currency, c.requested_amount, c.approved_amount, c.claim_reason, " +
               "       c.review_notes, c.created_by_user_id, c.reviewed_by_user_id, c.settled_at, c.created_at, c.updated_at, " +
               "       pr.pir_reference, pr.status AS pir_status, b.tag_number, p.name AS pass_name, r.pnr " +
               "FROM claim_cases c " +
               "JOIN pir_reports pr ON pr.id = c.pir_id " +
               "JOIN baggage b ON b.id = c.baggage_id " +
               "JOIN passengers p ON p.id = c.passenger_id " +
               "JOIN reservations r ON r.id = c.reservation_id ";
    }

    private static ClaimCase mapResultSetToClaim(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long orgId = rs.getLong("organization_id");
        String claimRef = rs.getString("claim_reference");
        long pirId = rs.getLong("pir_id");
        String pirRef = rs.getString("pir_reference");
        PIRStatus pirStatus = PIRStatus.fromString(rs.getString("pir_status"));
        long baggageId = rs.getLong("baggage_id");
        String baggageTag = rs.getString("tag_number");
        long passengerId = rs.getLong("passenger_id");
        String passName = rs.getString("pass_name");
        long reservationId = rs.getLong("reservation_id");
        String pnr = rs.getString("pnr");
        ClaimStatus status = ClaimStatus.fromString(rs.getString("status"));
        String currency = rs.getString("currency");
        BigDecimal req = rs.getBigDecimal("requested_amount");
        BigDecimal app = rs.getBigDecimal("approved_amount");
        String reason = rs.getString("claim_reason");
        String notes = rs.getString("review_notes");
        long cb = rs.getLong("created_by_user_id");
        Long createdBy = rs.wasNull() ? null : cb;
        long rb = rs.getLong("reviewed_by_user_id");
        Long reviewedBy = rs.wasNull() ? null : rb;
        Timestamp sat = rs.getTimestamp("settled_at");
        Timestamp cat = rs.getTimestamp("created_at");
        Timestamp uat = rs.getTimestamp("updated_at");

        return new ClaimCase(
            id,
            orgId,
            claimRef,
            pirId,
            pirRef,
            pirStatus,
            baggageId,
            baggageTag,
            passengerId,
            passName,
            reservationId,
            pnr,
            status,
            currency,
            req,
            app,
            reason,
            notes,
            createdBy,
            reviewedBy,
            sat != null ? sat.toLocalDateTime() : null,
            cat != null ? cat.toLocalDateTime() : LocalDateTime.now(),
            uat != null ? uat.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private static String generateUniqueReference(Connection conn, String table, String column, String prefix) throws SQLException {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 8; i++) {
                sb.append(REF_CHARACTERS.charAt(RANDOM.nextInt(REF_CHARACTERS.length())));
            }
            String candidate = sb.toString();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE " + column + " = ?")) {
                stmt.setString(1, candidate);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to generate a unique " + prefix + " reference after multiple attempts.");
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
        throw new AccessDeniedException("Access denied: claim viewing permission is required.");
    }
}
