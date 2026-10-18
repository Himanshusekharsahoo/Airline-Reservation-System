package airline.management.system.operations.baggage;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.AuthorizationService;
import airline.management.system.security.Permission;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;

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
 * Authoritative, transactional baggage service implementation enforcing strict tenant isolation,
 * check-in validation, state machine integrity, and audit logging.
 */
public enum BaggageServiceImpl implements BaggageService {
    INSTANCE;

    private static final String TAG_CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public BaggageRecord registerBaggage(String pnr, BigDecimal weightKg, BaggageType type, String remarks) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_BAGGAGE);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (pnr == null || pnr.isBlank()) {
            throw new IllegalArgumentException("PNR reference cannot be blank.");
        }
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Baggage weight must be greater than 0 kg.");
        }
        if (weightKg.compareTo(new BigDecimal("70.00")) > 0) {
            throw new IllegalArgumentException("Baggage weight exceeds maximum permitted limit (70.00 kg).");
        }

        if (type == null) {
            throw new IllegalArgumentException("Baggage type is required.");
        }
        BaggageType bagType = type;
        String cleanPnr = pnr.trim();

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Validate reservation, passenger, flight, and check-in eligibility under row lock
                String lookupSql =
                    "SELECT r.id AS res_id, r.pnr, r.passenger_id, r.flight_id, r.status AS res_status, r.organization_id AS res_org, " +
                    "       p.name AS pass_name, p.organization_id AS pass_org, " +
                    "       f.flight_number, f.source, f.destination, f.organization_id AS flt_org, " +
                    "       c.id AS checkin_id, c.seat_assigned, c.status AS checkin_status, c.organization_id AS chk_org " +
                    "FROM reservations r " +
                    "JOIN passengers p ON p.id = r.passenger_id " +
                    "JOIN flights f ON f.id = r.flight_id " +
                    "LEFT JOIN checkins c ON c.reservation_id = r.id " +
                    "WHERE r.pnr = ? FOR UPDATE";

                long reservationId;
                long passengerId;
                String passengerName;
                long flightId;
                String flightNumber;
                String route;
                String seatNumber;
                Long checkinId;
                String checkinStatus;

                try (PreparedStatement stmt = conn.prepareStatement(lookupSql)) {
                    stmt.setString(1, cleanPnr);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Reservation not found for PNR: " + cleanPnr);
                        }

                        long resOrg = rs.getLong("res_org");
                        long passOrg = rs.getLong("pass_org");
                        long fltOrg = rs.getLong("flt_org");

                        if (resOrg != tenantOrgId || passOrg != tenantOrgId || fltOrg != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Reservation [" + cleanPnr + "] does not belong to active tenant.");
                        }

                        String resStatus = rs.getString("res_status");
                        if (!"CONFIRMED".equalsIgnoreCase(resStatus)) {
                            conn.rollback();
                            throw new IllegalStateException("Cannot register baggage for reservation with status [" + resStatus + "].");
                        }

                        reservationId = rs.getLong("res_id");
                        passengerId = rs.getLong("passenger_id");
                        passengerName = rs.getString("pass_name");
                        flightId = rs.getLong("flight_id");
                        flightNumber = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");

                        long cId = rs.getLong("checkin_id");
                        checkinId = rs.wasNull() ? null : cId;
                        checkinStatus = rs.getString("checkin_status");
                        seatNumber = rs.getString("seat_assigned");
                        if (seatNumber == null || seatNumber.isBlank()) {
                            seatNumber = "—";
                        }
                    }
                }

                // Enforce Part 6 Check-in requirement: Passenger must have completed check-in
                if (checkinId == null || (!"CHECKED_IN".equalsIgnoreCase(checkinStatus) && !"BOARDED".equalsIgnoreCase(checkinStatus))) {
                    conn.rollback();
                    throw new IllegalStateException("Passenger has not completed check-in. Checked baggage requires completed passenger check-in.");
                }

                // 2. Generate collision-resistant unique baggage tag
                String tagNumber = generateUniqueTag(conn);

                // 3. Insert baggage record in ACCEPTED status (received at counter)
                String insertBagSql =
                    "INSERT INTO baggage (organization_id, checkin_id, passenger_id, reservation_id, flight_id, tag_number, baggage_type, weight_kg, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')";

                long baggageId;
                try (PreparedStatement stmt = conn.prepareStatement(insertBagSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, tenantOrgId);
                    stmt.setLong(2, checkinId);
                    stmt.setLong(3, passengerId);
                    stmt.setLong(4, reservationId);
                    stmt.setLong(5, flightId);
                    stmt.setString(6, tagNumber);
                    stmt.setString(7, bagType.name());
                    stmt.setBigDecimal(8, weightKg.setScale(2, RoundingMode.HALF_UP));
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        rs.next();
                        baggageId = rs.getLong(1);
                    }
                }

                // 4. Record baggage tracking events
                Long userId = UserSession.userId();
                String locationCode = (route != null && route.contains("→")) ? route.split("→")[0].trim() : "CHECKIN_DESK";

                // Event 1: Registration
                insertEvent(conn, tenantOrgId, baggageId, BaggageEventType.BAG_REGISTERED, locationCode, userId, remarks);
                // Event 2: Acceptance at desk
                insertEvent(conn, tenantOrgId, baggageId, BaggageEventType.BAG_ACCEPTED, locationCode, userId, "Baggage received and accepted at check-in counter");

                // 5. Increment checkins.baggage_count
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE checkins SET baggage_count = baggage_count + 1 WHERE id = ?")) {
                    stmt.setLong(1, checkinId);
                    stmt.executeUpdate();
                }

                // 6. Audit logging
                AuditService.record(
                    AuditAction.BAGGAGE_REGISTERED,
                    "BAGGAGE",
                    tagNumber,
                    "Baggage tag [" + tagNumber + "] (" + weightKg + " kg) registered for PNR [" + cleanPnr + "] (" + passengerName + ")"
                );

                conn.commit();

                return new BaggageRecord(
                    baggageId,
                    tenantOrgId,
                    checkinId,
                    passengerId,
                    passengerName,
                    reservationId,
                    cleanPnr,
                    flightId,
                    flightNumber,
                    route,
                    seatNumber,
                    tagNumber,
                    bagType,
                    weightKg.setScale(2, RoundingMode.HALF_UP),
                    BaggageStatus.ACCEPTED,
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
    public BaggageRecord updateBaggageStatus(long baggageId, BaggageStatus newStatus, String locationCode, String remarks) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_BAGGAGE);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        if (newStatus == null) {
            throw new IllegalArgumentException("New baggage status cannot be null.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock and retrieve current status & tenant
                String selectSql =
                    "SELECT b.id, b.organization_id, b.status, b.tag_number, b.checkin_id, b.passenger_id, b.reservation_id, b.flight_id, " +
                    "       b.baggage_type, b.weight_kg, b.created_at, " +
                    "       p.name AS pass_name, r.pnr, f.flight_number, f.source, f.destination, c.seat_assigned " +
                    "FROM baggage b " +
                    "JOIN passengers p ON p.id = b.passenger_id " +
                    "JOIN reservations r ON r.id = b.reservation_id " +
                    "JOIN flights f ON f.id = b.flight_id " +
                    "LEFT JOIN checkins c ON c.id = b.checkin_id " +
                    "WHERE b.id = ? FOR UPDATE";

                BaggageStatus currentStatus;
                String tagNumber;
                String passengerName;
                String pnr;
                String flightNumber;
                String route;
                String seatNumber;
                long passengerId;
                long reservationId;
                long flightId;
                long checkinId;
                BaggageType bagType;
                BigDecimal weightKg;
                Timestamp createdAtTs;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setLong(1, baggageId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            throw new IllegalArgumentException("Baggage record not found for ID: " + baggageId);
                        }

                        long bagOrg = rs.getLong("organization_id");
                        if (bagOrg != tenantOrgId) {
                            conn.rollback();
                            throw new AccessDeniedException("Cross-tenant access violation: Baggage [" + rs.getString("tag_number") + "] does not belong to active tenant.");
                        }

                        currentStatus = BaggageStatus.fromString(rs.getString("status"));
                        tagNumber = rs.getString("tag_number");
                        passengerName = rs.getString("pass_name");
                        pnr = rs.getString("pnr");
                        flightNumber = rs.getString("flight_number");
                        route = rs.getString("source") + " → " + rs.getString("destination");
                        seatNumber = rs.getString("seat_assigned");
                        passengerId = rs.getLong("passenger_id");
                        reservationId = rs.getLong("reservation_id");
                        flightId = rs.getLong("flight_id");
                        checkinId = rs.getLong("checkin_id");
                        bagType = BaggageType.fromString(rs.getString("baggage_type"));
                        weightKg = rs.getBigDecimal("weight_kg");
                        createdAtTs = rs.getTimestamp("created_at");
                    }
                }

                // Validate transition using centralized state machine
                BaggageLifecycleValidator.validateTransition(currentStatus, newStatus);

                // Update baggage status
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE baggage SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    stmt.setString(1, newStatus.name());
                    stmt.setLong(2, baggageId);
                    stmt.executeUpdate();
                }

                // Map status to event type
                BaggageEventType eventType = mapStatusToEventType(newStatus);
                String loc = (locationCode != null && !locationCode.isBlank()) ? locationCode.trim() : "STATION";
                Long userId = UserSession.userId();

                insertEvent(conn, tenantOrgId, baggageId, eventType, loc, userId, remarks);

                // Audit logging
                AuditAction auditAction = switch (newStatus) {
                    case DELAYED -> AuditAction.BAGGAGE_DELAYED;
                    case MISSING -> AuditAction.BAGGAGE_MISSING;
                    default -> AuditAction.BAGGAGE_STATUS_CHANGED;
                };

                AuditService.record(
                    auditAction,
                    "BAGGAGE",
                    tagNumber,
                    "Baggage [" + tagNumber + "] transitioned from " + currentStatus + " to " + newStatus +
                    (remarks != null && !remarks.isBlank() ? " (" + remarks.trim() + ")" : "")
                );

                conn.commit();

                return new BaggageRecord(
                    baggageId,
                    tenantOrgId,
                    checkinId,
                    passengerId,
                    passengerName,
                    reservationId,
                    pnr,
                    flightId,
                    flightNumber,
                    route,
                    seatNumber != null ? seatNumber : "—",
                    tagNumber,
                    bagType,
                    weightKg,
                    newStatus,
                    createdAtTs != null ? createdAtTs.toLocalDateTime() : LocalDateTime.now(),
                    LocalDateTime.now()
                );
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    @Override
    public Optional<BaggageRecord> findByTagNumber(String tagNumber) throws SQLException {
        if (tagNumber == null || tagNumber.isBlank()) {
            return Optional.empty();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql = baseSelectSql() + " WHERE b.tag_number = ? AND b.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tagNumber.trim());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBaggageRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<BaggageRecord> findById(long baggageId) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseSelectSql() + " WHERE b.id = ? AND b.organization_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, baggageId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBaggageRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<BaggageRecord> getBaggageByReservation(long reservationId) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseSelectSql() + " WHERE b.reservation_id = ? AND b.organization_id = ? ORDER BY b.id ASC";
        List<BaggageRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, reservationId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBaggageRecord(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<BaggageRecord> getBaggageByPnr(String pnr) throws SQLException {
        if (pnr == null || pnr.isBlank()) {
            return List.of();
        }
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseSelectSql() + " WHERE r.pnr = ? AND b.organization_id = ? ORDER BY b.id ASC";
        List<BaggageRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pnr.trim());
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBaggageRecord(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<BaggageRecord> getBaggageByCheckin(long checkinId) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;
        String sql = baseSelectSql() + " WHERE b.checkin_id = ? AND b.organization_id = ? ORDER BY b.id ASC";
        List<BaggageRecord> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, checkinId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBaggageRecord(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<BaggageRecord> searchBaggage(String query, BaggageStatus statusFilter, Long flightIdFilter) throws SQLException {
        return searchBaggage(query, statusFilter, flightIdFilter, null);
    }

    @Override
    public List<BaggageRecord> searchBaggage(String query, BaggageStatus statusFilter, Long flightIdFilter, String stationCode) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return searchBaggage(conn, query, statusFilter, flightIdFilter, stationCode);
        }
    }

    public List<BaggageRecord> searchBaggage(Connection conn, String query, BaggageStatus statusFilter, Long flightIdFilter, String stationCode) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        StringBuilder sql = new StringBuilder(baseSelectSql());
        sql.append(" WHERE b.organization_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (query != null && !query.isBlank()) {
            sql.append(" AND (b.tag_number LIKE ? OR r.pnr LIKE ? OR p.name LIKE ? OR f.flight_number LIKE ?) ");
            String wildcard = "%" + query.trim() + "%";
            params.add(wildcard);
            params.add(wildcard);
            params.add(wildcard);
            params.add(wildcard);
        }

        if (statusFilter != null) {
            sql.append(" AND b.status = ? ");
            params.add(statusFilter.name());
        }

        if (flightIdFilter != null && flightIdFilter > 0) {
            sql.append(" AND b.flight_id = ? ");
            params.add(flightIdFilter);
        }

        if (stationCode != null && !stationCode.isBlank()) {
            Optional<OperationalStation> stationOpt = StationContext.findStation(stationCode.trim());
            if (stationOpt.isPresent()) {
                OperationalStation st = stationOpt.get();
                String code = st.getCode();
                String city = st.getCity().split(",")[0].trim();
                sql.append(" AND (f.source = ? OR f.source = ? OR f.destination = ? OR f.destination = ? " +
                           "      OR EXISTS (SELECT 1 FROM baggage_events be WHERE be.baggage_id = b.id AND be.organization_id = b.organization_id AND (be.location_code = ? OR be.location_code = ?))) ");
                params.add(code);
                params.add(city);
                params.add(code);
                params.add(city);
                params.add(code);
                params.add(city);
            } else {
                String unknown = stationCode.trim();
                sql.append(" AND (f.source = ? OR f.destination = ? " +
                           "      OR EXISTS (SELECT 1 FROM baggage_events be WHERE be.baggage_id = b.id AND be.organization_id = b.organization_id AND be.location_code = ?)) ");
                params.add(unknown);
                params.add(unknown);
                params.add(unknown);
            }
        }

        sql.append(" ORDER BY b.created_at DESC LIMIT 150");

        List<BaggageRecord> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBaggageRecord(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<BaggageEventRecord> getBaggageHistory(long baggageId) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql =
            "SELECT be.id, be.baggage_id, be.organization_id, be.event_type, be.location_code, " +
            "       be.scanned_by_user_id, be.remarks, be.scanned_at, " +
            "       b.tag_number, u.username " +
            "FROM baggage_events be " +
            "JOIN baggage b ON b.id = be.baggage_id " +
            "LEFT JOIN app_users u ON u.id = be.scanned_by_user_id " +
            "WHERE be.baggage_id = ? AND be.organization_id = ? " +
            "ORDER BY be.scanned_at ASC";

        List<BaggageEventRecord> events = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, baggageId);
            stmt.setLong(2, tenantOrgId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("scanned_at");
                    events.add(new BaggageEventRecord(
                        rs.getLong("id"),
                        rs.getLong("baggage_id"),
                        rs.getString("tag_number"),
                        rs.getLong("organization_id"),
                        BaggageEventType.fromString(rs.getString("event_type")),
                        rs.getString("location_code"),
                        rs.getObject("scanned_by_user_id") != null ? rs.getLong("scanned_by_user_id") : null,
                        rs.getString("username") != null ? rs.getString("username") : "System / Agent",
                        rs.getString("remarks") != null ? rs.getString("remarks") : "—",
                        ts != null ? ts.toLocalDateTime() : LocalDateTime.now()
                    ));
                }
            }
        }
        return events;
    }

    @Override
    public BaggageSummary getBaggageSummary(Long flightIdFilter) throws SQLException {
        return getBaggageSummary(flightIdFilter, null);
    }

    @Override
    public BaggageSummary getBaggageSummary(Long flightIdFilter, String stationCode) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            return getBaggageSummary(conn, flightIdFilter, stationCode);
        }
    }

    public BaggageSummary getBaggageSummary(Connection conn, Long flightIdFilter, String stationCode) throws SQLException {
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) AS `total`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'ACCEPTED' THEN 1 ELSE 0 END), 0) AS `accepted`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'LOADED' THEN 1 ELSE 0 END), 0) AS `loaded`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'IN_TRANSIT' THEN 1 ELSE 0 END), 0) AS `in_transit`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'DELIVERED' THEN 1 ELSE 0 END), 0) AS `delivered`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'DELAYED' THEN 1 ELSE 0 END), 0) AS `delayed`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'MISSING' THEN 1 ELSE 0 END), 0) AS `missing`, " +
            "       COALESCE(SUM(CASE WHEN b.status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS `cancelled`, " +
            "       COALESCE(SUM(b.weight_kg), 0.00) AS `total_weight`, " +
            "       COALESCE(AVG(b.weight_kg), 0.00) AS `avg_weight` " +
            "FROM baggage b " +
            "LEFT JOIN flights f ON f.id = b.flight_id " +
            "WHERE b.organization_id = ?"
        );

        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);

        if (flightIdFilter != null && flightIdFilter > 0) {
            sql.append(" AND b.flight_id = ?");
            params.add(flightIdFilter);
        }

        if (stationCode != null && !stationCode.isBlank()) {
            Optional<OperationalStation> stationOpt = StationContext.findStation(stationCode.trim());
            if (stationOpt.isPresent()) {
                OperationalStation st = stationOpt.get();
                String code = st.getCode();
                String city = st.getCity().split(",")[0].trim();
                sql.append(" AND (f.source = ? OR f.source = ? OR f.destination = ? OR f.destination = ? " +
                           "      OR EXISTS (SELECT 1 FROM baggage_events be WHERE be.baggage_id = b.id AND be.organization_id = b.organization_id AND (be.location_code = ? OR be.location_code = ?))) ");
                params.add(code);
                params.add(city);
                params.add(code);
                params.add(city);
                params.add(code);
                params.add(city);
            } else {
                String unknown = stationCode.trim();
                sql.append(" AND (f.source = ? OR f.destination = ? " +
                           "      OR EXISTS (SELECT 1 FROM baggage_events be WHERE be.baggage_id = b.id AND be.organization_id = b.organization_id AND be.location_code = ?)) ");
                params.add(unknown);
                params.add(unknown);
                params.add(unknown);
            }
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new BaggageSummary(
                        rs.getInt("total"),
                        rs.getInt("accepted"),
                        rs.getInt("loaded"),
                        rs.getInt("in_transit"),
                        rs.getInt("delivered"),
                        rs.getInt("delayed"),
                        rs.getInt("missing"),
                        rs.getInt("cancelled"),
                        rs.getBigDecimal("total_weight").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("avg_weight").setScale(2, RoundingMode.HALF_UP)
                    );
                }
            }
        }
        return new BaggageSummary(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // --- Private Helper Methods ---

    private static String baseSelectSql() {
        return "SELECT b.id, b.organization_id, b.checkin_id, b.passenger_id, b.reservation_id, b.flight_id, " +
               "       b.tag_number, b.baggage_type, b.weight_kg, b.status, b.created_at, b.updated_at, " +
               "       p.name AS pass_name, r.pnr, f.flight_number, f.source, f.destination, c.seat_assigned " +
               "FROM baggage b " +
               "JOIN passengers p ON p.id = b.passenger_id " +
               "JOIN reservations r ON r.id = b.reservation_id " +
               "JOIN flights f ON f.id = b.flight_id " +
               "LEFT JOIN checkins c ON c.id = b.checkin_id";
    }

    private static BaggageRecord mapBaggageRecord(ResultSet rs) throws SQLException {
        Timestamp cr = rs.getTimestamp("created_at");
        Timestamp up = rs.getTimestamp("updated_at");
        String seat = rs.getString("seat_assigned");
        return new BaggageRecord(
            rs.getLong("id"),
            rs.getLong("organization_id"),
            rs.getLong("checkin_id"),
            rs.getLong("passenger_id"),
            rs.getString("pass_name"),
            rs.getLong("reservation_id"),
            rs.getString("pnr"),
            rs.getLong("flight_id"),
            rs.getString("flight_number"),
            rs.getString("source") + " → " + rs.getString("destination"),
            seat != null ? seat : "—",
            rs.getString("tag_number"),
            BaggageType.fromString(rs.getString("baggage_type")),
            rs.getBigDecimal("weight_kg"),
            BaggageStatus.fromString(rs.getString("status")),
            cr != null ? cr.toLocalDateTime() : LocalDateTime.now(),
            up != null ? up.toLocalDateTime() : LocalDateTime.now()
        );
    }

    private static String generateUniqueTag(Connection conn) throws SQLException {
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder("BAG");
            for (int j = 0; j < 8; j++) {
                sb.append(TAG_CHARACTERS.charAt(RANDOM.nextInt(TAG_CHARACTERS.length())));
            }
            String candidate = sb.toString();
            try (PreparedStatement s = conn.prepareStatement("SELECT 1 FROM baggage WHERE tag_number = ?")) {
                s.setString(1, candidate);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        // Fallback with timestamp
        return "BAG" + Long.toHexString(System.currentTimeMillis()).toUpperCase();
    }

    private static void insertEvent(Connection conn, long orgId, long baggageId, BaggageEventType eventType,
                                   String locationCode, Long userId, String remarks) throws SQLException {
        String sql =
            "INSERT INTO baggage_events (organization_id, baggage_id, event_type, location_code, scanned_by_user_id, remarks) " +
            "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orgId);
            stmt.setLong(2, baggageId);
            stmt.setString(3, eventType.name());
            stmt.setString(4, locationCode != null ? locationCode : "STATION");
            if (userId != null && userId > 0) {
                stmt.setLong(5, userId);
            } else {
                stmt.setNull(5, java.sql.Types.BIGINT);
            }
            stmt.setString(6, remarks != null ? remarks.trim() : null);
            stmt.executeUpdate();
        }
    }

    private static BaggageEventType mapStatusToEventType(BaggageStatus status) {
        return switch (status) {
            case REGISTERED -> BaggageEventType.BAG_REGISTERED;
            case ACCEPTED -> BaggageEventType.BAG_ACCEPTED;
            case LOADED -> BaggageEventType.BAG_LOADED;
            case IN_TRANSIT -> BaggageEventType.BAG_IN_TRANSIT;
            case DELIVERED -> BaggageEventType.BAG_DELIVERED;
            case DELAYED -> BaggageEventType.BAG_DELAYED;
            case MISSING -> BaggageEventType.BAG_MISSING;
            case CANCELLED -> BaggageEventType.BAG_CANCELLED;
        };
    }
}
