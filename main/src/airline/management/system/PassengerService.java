package airline.management.system;

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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Service governing Passenger domain persistence, lifecycle operations,
 * input validation, multi-tenant isolation, and operational audit event logging.
 */
public final class PassengerService {

    /**
     * Immutable domain record representing a passenger directory entry.
     */
    public record PassengerRecord(
        long id,
        String legacyPnr,
        String displayPnr,
        String name,
        Integer age,
        String gender,
        String phone,
        String email,
        String address,
        Timestamp createdAt,
        int reservationCount
    ) {}

    /**
     * Immutable domain record representing an operational reservation booked for a passenger.
     */
    public record PassengerReservationRecord(
        String flightNumber,
        String pnr,
        String route,
        String seatNumber,
        double fare,
        String status,
        Timestamp bookingDate
    ) {}

    private PassengerService() {}

    /**
     * Validates passenger input constraints according to enterprise airline standards.
     *
     * @throws IllegalArgumentException if any field violates format, range, or required constraints
     */
    public static void validatePassengerData(String name, String phone, String email, Integer age) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Passenger name is required.");
        }
        if (name.trim().length() > 100) {
            throw new IllegalArgumentException("Passenger name cannot exceed 100 characters.");
        }

        if (phone == null || phone.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter a valid phone number.");
        }
        String ph = phone.trim();
        if (ph.length() > 30) {
            throw new IllegalArgumentException("Phone number cannot exceed 30 characters.");
        }
        if (!ph.matches("[+0-9() -]{7,30}")) {
            throw new IllegalArgumentException("Please enter a valid phone number.");
        }

        if (email != null && !email.trim().isEmpty()) {
            String em = email.trim();
            if (em.length() > 254) {
                throw new IllegalArgumentException("Email address cannot exceed 254 characters.");
            }
            if (!em.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new IllegalArgumentException("Please enter a valid email address.");
            }
        }

        if (age != null) {
            if (age < 0 || age > 120) {
                throw new IllegalArgumentException("Age must be between 0 and 120.");
            }
        }
    }

    /**
     * Creates and persists a new passenger record within the active tenant context.
     *
     * @return the auto-generated passenger primary key
     * @throws SQLException if a database error occurs or duplicate email constraint is violated
     */
    public static long createPassenger(String name, String phone, String email, Integer age, String gender, String address) throws SQLException {
        return createPassenger(null, name, phone, email, age, gender, address);
    }

    /**
     * Connection-aware overload for creating a passenger record.
     */
    public static long createPassenger(Connection externalConn, String name, String phone, String email, Integer age, String gender, String address) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);
        validatePassengerData(name, phone, email, age);

        String trimmedName = name.trim();
        String trimmedPhone = phone.trim();
        String emailVal = (email != null && !email.trim().isEmpty()) ? email.trim() : null;
        String genderVal = (gender != null && !gender.trim().isEmpty() && !gender.equalsIgnoreCase("Select Gender")) ? gender.trim() : null;
        String addressVal = (address != null && !address.trim().isEmpty()) ? address.trim() : null;

        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        String sql = "INSERT INTO passengers (organization_id, name, phone, email, age, gender, address) VALUES (?, ?, ?, ?, ?, ?, ?)";

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try {
            long passengerId;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, tenantOrgId);
                ps.setString(2, trimmedName);
                ps.setString(3, trimmedPhone);
                if (emailVal == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, emailVal);
                if (age == null) ps.setNull(5, Types.TINYINT); else ps.setInt(5, age);
                if (genderVal == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, genderVal);
                if (addressVal == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, addressVal);

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        passengerId = rs.getLong(1);
                    } else {
                        throw new SQLException("Creating passenger failed, no ID obtained.");
                    }
                }
            } catch (SQLIntegrityConstraintViolationException ex) {
                throw new SQLIntegrityConstraintViolationException("A passenger with this email address already exists.", ex.getSQLState(), ex.getErrorCode(), ex);
            } catch (SQLException ex) {
                if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                    throw new SQLIntegrityConstraintViolationException("A passenger with this email address already exists.", ex.getSQLState(), ex.getErrorCode(), ex);
                }
                throw ex;
            }

            AuditService.record(AuditAction.PASSENGER_CREATE, "PASSENGER", String.valueOf(passengerId), "Registered passenger: " + trimmedName);
            return passengerId;
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Updates an existing passenger record within the active tenant boundary.
     *
     * @throws SQLException if a database error occurs or duplicate email constraint is violated
     */
    public static void updatePassenger(long id, String name, String phone, String email, Integer age, String gender, String address) throws SQLException {
        updatePassenger(null, id, name, phone, email, age, gender, address);
    }

    /**
     * Connection-aware overload for updating a passenger record.
     */
    public static void updatePassenger(Connection externalConn, long id, String name, String phone, String email, Integer age, String gender, String address) throws SQLException {
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid passenger ID.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);
        validatePassengerData(name, phone, email, age);

        String trimmedName = name.trim();
        String trimmedPhone = phone.trim();
        String emailVal = (email != null && !email.trim().isEmpty()) ? email.trim() : null;
        String genderVal = (gender != null && !gender.trim().isEmpty() && !gender.equalsIgnoreCase("Select Gender")) ? gender.trim() : null;
        String addressVal = (address != null && !address.trim().isEmpty()) ? address.trim() : null;

        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try {
            // Verify tenant boundary and passenger existence
            try (PreparedStatement check = conn.prepareStatement("SELECT organization_id FROM passengers WHERE id = ?")) {
                check.setLong(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Passenger not found or already deleted.");
                    }
                    long passOrg = rs.getLong("organization_id");
                    if (passOrg != 0 && passOrg != tenantOrgId) {
                        throw new AccessDeniedException("Cross-tenant access violation: Passenger #" + id + " does not belong to active tenant.");
                    }
                }
            }

            String sql = "UPDATE passengers SET name=?, phone=?, email=?, age=?, gender=?, address=? WHERE id=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, trimmedName);
                ps.setString(2, trimmedPhone);
                if (emailVal == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, emailVal);
                if (age == null) ps.setNull(4, Types.TINYINT); else ps.setInt(4, age);
                if (genderVal == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, genderVal);
                if (addressVal == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, addressVal);
                ps.setLong(7, id);

                int updated = ps.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("Passenger not found or already deleted.");
                }
            } catch (SQLIntegrityConstraintViolationException ex) {
                throw new SQLIntegrityConstraintViolationException("A passenger with this email address already exists.", ex.getSQLState(), ex.getErrorCode(), ex);
            } catch (SQLException ex) {
                if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                    throw new SQLIntegrityConstraintViolationException("A passenger with this email address already exists.", ex.getSQLState(), ex.getErrorCode(), ex);
                }
                throw ex;
            }

            AuditService.record(AuditAction.PASSENGER_UPDATE, "PASSENGER", String.valueOf(id), "Updated passenger: " + trimmedName);
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Safely deletes a passenger record only if no reservation history exists.
     *
     * @throws IllegalStateException if reservations are linked to the passenger
     * @throws IllegalArgumentException if passenger does not exist
     * @throws SQLException if a database error occurs
     */
    public static void deletePassenger(long id) throws SQLException {
        deletePassenger(null, id);
    }

    /**
     * Connection-aware overload for deleting a passenger record.
     */
    public static void deletePassenger(Connection externalConn, long id) throws SQLException {
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid passenger ID.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);

        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try {
            // Verify tenant boundary and passenger existence
            try (PreparedStatement check = conn.prepareStatement("SELECT organization_id FROM passengers WHERE id = ?")) {
                check.setLong(1, id);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Passenger not found or already deleted.");
                    }
                    long passOrg = rs.getLong("organization_id");
                    if (passOrg != 0 && passOrg != tenantOrgId) {
                        throw new AccessDeniedException("Cross-tenant access violation: Passenger #" + id + " does not belong to active tenant.");
                    }
                }
            }

            // Authoritative check: block deletion if any reservation exists
            try (PreparedStatement checkRes = conn.prepareStatement("SELECT 1 FROM reservations WHERE passenger_id = ? LIMIT 1")) {
                checkRes.setLong(1, id);
                try (ResultSet rs = checkRes.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("This passenger cannot be deleted because reservation history exists.");
                    }
                }
            }

            // Execute deletion
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM passengers WHERE id = ?")) {
                del.setLong(1, id);
                int affected = del.executeUpdate();
                if (affected == 0) {
                    throw new IllegalArgumentException("Passenger not found or already deleted.");
                }
            }
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Searches passengers matching the given query string and reservation filter.
     */
    public static List<PassengerRecord> searchPassengers(String query, String selectedFilter) throws SQLException {
        return searchPassengers(null, query, selectedFilter);
    }

    /**
     * Connection-aware overload for searching passengers.
     */
    public static List<PassengerRecord> searchPassengers(Connection externalConn, String query, String selectedFilter) throws SQLException {
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);
        long tenantOrgId = TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L;

        StringBuilder sql = new StringBuilder(
            "SELECT p.id, p.legacy_pnr, " +
            "COALESCE(p.legacy_pnr, (SELECT r.pnr FROM reservations r WHERE r.passenger_id = p.id ORDER BY r.booking_date DESC LIMIT 1)) AS display_pnr, " +
            "p.name, p.age, p.gender, p.phone, p.email, p.address, p.created_at, " +
            "(SELECT COUNT(*) FROM reservations r WHERE r.passenger_id = p.id) AS reservation_count " +
            "FROM passengers p WHERE (p.organization_id = ? OR (p.organization_id IS NULL AND ? = 1))"
        );
        List<Object> params = new ArrayList<>();
        params.add(tenantOrgId);
        params.add(tenantOrgId);

        String trimmedQuery = (query != null) ? query.trim() : "";
        if (!trimmedQuery.isEmpty()) {
            sql.append(" AND (p.name LIKE ? OR p.phone LIKE ? OR p.email LIKE ? OR p.legacy_pnr LIKE ? OR CAST(p.id AS CHAR) LIKE ? OR EXISTS (SELECT 1 FROM reservations r WHERE r.passenger_id = p.id AND r.pnr LIKE ?))");
            String like = "%" + trimmedQuery + "%";
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }

        if ("With Reservations".equalsIgnoreCase(selectedFilter)) {
            sql.append(" AND EXISTS (SELECT 1 FROM reservations r WHERE r.passenger_id = p.id)");
        } else if ("No Reservations".equalsIgnoreCase(selectedFilter)) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.passenger_id = p.id)");
        }

        sql.append(" ORDER BY p.name ASC");

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            List<PassengerRecord> records = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int ageVal = rs.getInt("age");
                    Integer age = rs.wasNull() ? null : ageVal;
                    records.add(new PassengerRecord(
                        rs.getLong("id"),
                        rs.getString("legacy_pnr"),
                        rs.getString("display_pnr"),
                        rs.getString("name"),
                        age,
                        rs.getString("gender"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("address"),
                        rs.getTimestamp("created_at"),
                        rs.getInt("reservation_count")
                    ));
                }
            }
            return records;
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Retrieves the reservation booking history for a specific passenger.
     */
    public static List<PassengerReservationRecord> getReservationHistory(long passengerId) throws SQLException {
        return getReservationHistory(null, passengerId);
    }

    /**
     * Connection-aware overload for retrieving reservation booking history.
     */
    public static List<PassengerReservationRecord> getReservationHistory(Connection externalConn, long passengerId) throws SQLException {
        if (passengerId <= 0) {
            throw new IllegalArgumentException("Invalid passenger ID.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);

        String sql = "SELECT r.pnr, f.flight_number, f.airline, f.source, f.destination, r.seat_number, r.fare, r.status, r.booking_date " +
                     "FROM reservations r JOIN flights f ON f.id = r.flight_id WHERE r.passenger_id = ? ORDER BY r.booking_date DESC";

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, passengerId);
            List<PassengerReservationRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String route = rs.getString("source") + " → " + rs.getString("destination");
                    list.add(new PassengerReservationRecord(
                        rs.getString("flight_number"),
                        rs.getString("pnr"),
                        route,
                        rs.getString("seat_number"),
                        rs.getDouble("fare"),
                        rs.getString("status"),
                        rs.getTimestamp("booking_date")
                    ));
                }
            }
            return list;
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Retrieves an individual passenger record by primary key.
     */
    public static Optional<PassengerRecord> getPassengerById(long passengerId) throws SQLException {
        return getPassengerById(null, passengerId);
    }

    /**
     * Connection-aware overload for retrieving a passenger by primary key.
     */
    public static Optional<PassengerRecord> getPassengerById(Connection externalConn, long passengerId) throws SQLException {
        if (passengerId <= 0) {
            throw new IllegalArgumentException("Invalid passenger ID.");
        }
        AuthorizationService.requirePermission(Permission.MANAGE_PASSENGERS);

        String sql = "SELECT p.id, p.legacy_pnr, " +
                     "COALESCE(p.legacy_pnr, (SELECT r.pnr FROM reservations r WHERE r.passenger_id = p.id ORDER BY r.booking_date DESC LIMIT 1)) AS display_pnr, " +
                     "p.name, p.age, p.gender, p.phone, p.email, p.address, p.created_at, " +
                     "(SELECT COUNT(*) FROM reservations r WHERE r.passenger_id = p.id) AS reservation_count " +
                     "FROM passengers p WHERE p.id = ?";

        Connection conn = externalConn;
        boolean closeAfter = false;
        if (conn == null) {
            conn = DBConnection.getConnection();
            closeAfter = true;
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, passengerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int ageVal = rs.getInt("age");
                    Integer age = rs.wasNull() ? null : ageVal;
                    return Optional.of(new PassengerRecord(
                        rs.getLong("id"),
                        rs.getString("legacy_pnr"),
                        rs.getString("display_pnr"),
                        rs.getString("name"),
                        age,
                        rs.getString("gender"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("address"),
                        rs.getTimestamp("created_at"),
                        rs.getInt("reservation_count")
                    ));
                }
            }
            return Optional.empty();
        } finally {
            if (closeAfter && conn != null) {
                conn.close();
            }
        }
    }
}
