package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.operations.boarding.BoardingPassenger;
import airline.management.system.operations.boarding.BoardingService;
import airline.management.system.operations.boarding.BoardingValidationResult;
import airline.management.system.operations.boarding.FlightBoardingSummary;
import airline.management.system.operations.checkin.BoardingPassRecord;
import airline.management.system.operations.checkin.CheckinPassengerDetails;
import airline.management.system.operations.checkin.CheckinResult;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.checkin.CheckinStatus;
import airline.management.system.operations.gate.FlightGateAssignment;
import airline.management.system.operations.gate.GateRecord;
import airline.management.system.operations.gate.GateService;
import airline.management.system.security.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authoritative Phase 12 persistent operations integration tests.
 * Validates check-in, boarding pass issuance, gate conflict detection, boarding events,
 * audit trail persistence, and strict RBAC isolation against project4test.
 * Verifies production database remains completely untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersistentOperationsTest {
    private static final String TEST_DB = "project4test";

    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());

    private static long testFlight1;
    private static long testFlight2;
    private static long testPassenger1;
    private static long testPassenger2;
    private static String testPnr1;
    private static String testPnr2;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("DB_NAME", TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();

        try (Connection c = DBConnection.getConnection()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
        }

        executeScript("schema.sql");
        if (!activeSeatColumnExists()) executeScript("migration_3b.sql");
        executeScript("v2_phase11_operations_schema.sql");

        // Clean any stale test fixtures before test execution
        cleanupTestFixtures();

        // Create test app_users in project4test
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT IGNORE INTO app_users (id, username, password_hash, role) VALUES " +
                 "(101, 'agent_sarah', 'hash', 'CHECKINAGENT'), " +
                 "(201, 'ops_lead', 'hash', 'OPERATIONSMANAGER'), " +
                 "(301, 'gate_dan', 'hash', 'GATEAGENT'), " +
                 "(999, 'auditor_bob', 'hash', 'AUDITOR')")) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            p.executeUpdate();
        }

        // Prepare test flights and passengers
        testFlight1 = createFlight("TEST101", 100);
        testFlight2 = createFlight("TEST202", 100);
        testPassenger1 = createPassenger("Alice Operator");
        testPassenger2 = createPassenger("Bob Traveler");

        // Book reservations under AIRLINEADMIN privileges
        UserSession.login(1L, "admin", Role.AIRLINEADMIN.name());
        testPnr1 = ReservationService.book(testFlight1, testPassenger1, "12A", "Card");
        testPnr2 = ReservationService.book(testFlight2, testPassenger2, "14B", "UPI");
        UserSession.logout();
    }

    @AfterAll
    static void cleanup() throws Exception {
        cleanupTestFixtures();
        UserSession.logout();
    }

    private static void executeScopedDelete(Statement s, String targetTable, String sql, List<Throwable> failures) {
        try {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            failures.add(new SQLException("Cleanup failed for table [" + targetTable + "]: " + e.getMessage(), e));
        }
    }

    private static void cleanupTestFixtures() throws Exception {
        TestDatabaseGuard.ensureTestDatabase();
        List<Throwable> failures = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            String fltIds = idListOrZero(createdFlightIds);
            String paxIds = idListOrZero(createdPassengerIds);
            String fltSub = "SELECT id FROM flights WHERE id IN (" + fltIds + ") OR airline = 'JUnit AeroDesk'";
            String paxSub = "SELECT id FROM passengers WHERE id IN (" + paxIds + ") OR email LIKE 'test%@aerodesk.com'";
            String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ")";
            String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ")";

            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE flight_id IN (" + fltSub + ") OR boarding_pass_id IN (SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + "))", failures);
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ")", failures);
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ")", failures);
            executeScopedDelete(s, "gates", "UPDATE gates SET status = 'AVAILABLE' WHERE id IN (SELECT gate_id FROM flight_gates WHERE flight_id IN (" + fltSub + "))", failures);
            executeScopedDelete(s, "flight_gates", "DELETE FROM flight_gates WHERE flight_id IN (" + fltSub + ")", failures);
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ")", failures);
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ")", failures);
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR email LIKE 'test%@aerodesk.com'", failures);
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR airline = 'JUnit AeroDesk'", failures);
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id IN (101, 201, 301, 999) OR user_id IN (SELECT id FROM app_users WHERE username IN ('agent_sarah', 'ops_lead', 'gate_dan', 'auditor_bob'))", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE username IN ('agent_sarah', 'ops_lead', 'gate_dan', 'auditor_bob')", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE id IN (101, 201, 301, 999) OR username IN ('agent_sarah', 'ops_lead', 'gate_dan', 'auditor_bob')", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("PersistentOperationsTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    @Test
    @Order(1)
    void successfulCheckinCreatesDatabaseRecordsAndBoardingPass() throws Exception {
        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());

        CheckinService checkinService = CheckinService.getInstance();
        CheckinResult result = checkinService.processCheckin(testPnr1, "12A");

        assertTrue(result.success(), "Expected successful checkin: " + result.message());
        assertNotNull(result.details());
        assertEquals(CheckinStatus.CHECKED_IN, result.details().checkinStatus());
        assertNotNull(result.details().checkinId());
        assertNotNull(result.details().boardingPassNumber());

        // Verify checkins row in MySQL
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT reservation_id, seat_assigned, status FROM checkins WHERE id = ?")) {
            stmt.setLong(1, result.details().checkinId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(result.details().reservationId(), rs.getLong("reservation_id"));
                assertEquals("12A", rs.getString("seat_assigned"));
                assertEquals("CHECKED_IN", rs.getString("status"));
            }
        }

        // Verify boarding_passes row in MySQL
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT boarding_pass_number, security_hash FROM boarding_passes WHERE checkin_id = ?")) {
            stmt.setLong(1, result.details().checkinId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(result.details().boardingPassNumber(), rs.getString("boarding_pass_number"));
                assertNotNull(rs.getString("security_hash"));
            }
        }
    }

    @Test
    @Order(2)
    void duplicateCheckinRejection() throws Exception {
        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());

        CheckinService checkinService = CheckinService.getInstance();
        CheckinResult duplicateResult = checkinService.processCheckin(testPnr1, "12A");

        assertFalse(duplicateResult.success());
        assertEquals(CheckinStatus.CHECKED_IN, duplicateResult.status());
        assertTrue(duplicateResult.message().contains("already checked in"), "Message should mention already checked in: " + duplicateResult.message());
    }

    @Test
    @Order(3)
    void cancelledReservationCheckinRejection() throws Exception {
        UserSession.login(1L, "admin", Role.AIRLINEADMIN.name());
        long flight = createFlight("CANC100", 50);
        long passenger = createPassenger("Cancelled Passenger");
        String pnr = ReservationService.book(flight, passenger, "1A", "Card");

        // Cancel reservation
        ReservationService.cancel(pnr, "Passenger requested cancellation");

        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());
        CheckinResult result = CheckinService.getInstance().processCheckin(pnr, "1A");

        assertFalse(result.success());
        assertEquals(CheckinStatus.INELIGIBLE_STATUS, result.status());
        assertTrue(result.message().contains("CANCELLED"));
    }

    @Test
    @Order(4)
    void unpaidReservationCheckinRejection() throws Exception {
        long flight = createFlight("UNPD100", 50);
        long passenger = createPassenger("Unpaid Passenger");

        // Create reservation with PENDING payment (omit virtual generated column active_seat_number)
        String pnr = "PNR-UNPAID-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO reservations (flight_id, passenger_id, seat_number, status, fare, pnr) " +
                 "VALUES (?, ?, '2B', 'CONFIRMED', 120.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, flight);
            stmt.setLong(2, passenger);
            stmt.setString(3, pnr);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                rs.next();
                long resId = rs.getLong(1);
                try (PreparedStatement pmtStmt = conn.prepareStatement(
                        "INSERT INTO payments (reservation_id, amount, payment_method, payment_status, transaction_reference) " +
                        "VALUES (?, 120.00, 'UPI', 'PENDING', 'TXN-PENDING')")) {
                    pmtStmt.setLong(1, resId);
                    pmtStmt.executeUpdate();
                }
            }
        }

        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());
        CheckinResult result = CheckinService.getInstance().processCheckin(pnr, "2B");

        assertFalse(result.success());
        assertEquals(CheckinStatus.INELIGIBLE_PAYMENT, result.status());
        assertTrue(result.message().contains("Payment status is 'PENDING'"));
    }

    @Test
    @Order(5)
    void invalidPnrCheckinRejection() throws Exception {
        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());
        CheckinResult result = CheckinService.getInstance().processCheckin("INVALID-NON-EXISTENT", "1A");

        assertFalse(result.success());
        assertEquals(CheckinStatus.NOT_FOUND, result.status());
    }

    @Test
    @Order(6)
    void boardingPassRetrievalAndFormattedValues() throws Exception {
        CheckinService checkinService = CheckinService.getInstance();
        BoardingPassRecord bp = checkinService.getBoardingPass(testPnr1);

        assertNotNull(bp);
        assertEquals(testPnr1, bp.pnr());
        assertEquals("12A", bp.seatNumber());
        assertEquals("Not assigned", bp.getFormattedGate());
        assertTrue(bp.boardingPassNumber().startsWith("BP-"));
        assertEquals("GROUP_3", bp.boardingGroup());
    }

    @Test
    @Order(7)
    void gateAssignmentAndConflictDetection() throws Exception {
        UserSession.login(201L, "ops_lead", Role.OPERATIONSMANAGER.name());
        GateService gateService = GateService.getInstance();

        List<GateRecord> gates = gateService.getAllGates();
        assertFalse(gates.isEmpty());
        long gateId = gates.get(0).id();
        String gateNumber = gates.get(0).gateNumber();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start1 = now.plusHours(1);
        LocalDateTime end1 = now.plusHours(2);

        // Assign gate to testFlight1
        FlightGateAssignment assign1 = gateService.assignFlightGate(testFlight1, gateId, start1, end1);
        assertNotNull(assign1);
        assertEquals(gateNumber, assign1.gateNumber());

        // Attempt overlapping assignment on testFlight2 to same gate -> Conflict expected
        LocalDateTime startOverlap = now.plusHours(1).plusMinutes(30);
        LocalDateTime endOverlap = now.plusHours(2).plusMinutes(30);

        Exception conflictEx = assertThrows(Exception.class, () ->
            gateService.assignFlightGate(testFlight2, gateId, startOverlap, endOverlap)
        );
        assertTrue(conflictEx.getMessage().contains("Gate conflict") || conflictEx.getMessage().contains("already allocated"),
            "Expected conflict message but got: " + conflictEx.getMessage());

        // Assign testFlight2 to gate 2 -> Should succeed
        long gate2Id = gates.get(1).id();
        FlightGateAssignment assign2 = gateService.assignFlightGate(testFlight2, gate2Id, startOverlap, endOverlap);
        assertNotNull(assign2);

        // Boarding pass for testPnr1 should now reflect the assigned gate
        BoardingPassRecord bp = CheckinService.getInstance().getBoardingPass(testPnr1);
        assertNotNull(bp);
        assertEquals(gateNumber, bp.getFormattedGate());
    }

    @Test
    @Order(8)
    void successfulBoardingAndEventCreation() throws Exception {
        UserSession.login(301L, "gate_dan", Role.GATEAGENT.name());
        BoardingService boardingService = BoardingService.getInstance();

        BoardingValidationResult result = boardingService.validateAndBoard(testPnr1, testFlight1);
        assertTrue(result.valid(), "Boarding should succeed: " + result.message());
        assertTrue(result.message().contains("marked as BOARDED"));

        // Verify checkins row updated to BOARDED
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT c.status FROM checkins c JOIN reservations r ON r.id = c.reservation_id WHERE r.pnr = ?")) {
            stmt.setString(1, testPnr1);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("BOARDED", rs.getString("status"));
            }
        }

        // Verify boarding_events row created
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT be.outcome FROM boarding_events be " +
                 "JOIN boarding_passes bp ON bp.id = be.boarding_pass_id " +
                 "JOIN checkins c ON c.id = bp.checkin_id " +
                 "JOIN reservations r ON r.id = c.reservation_id WHERE r.pnr = ?")) {
            stmt.setString(1, testPnr1);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("BOARDED", rs.getString("outcome"));
            }
        }
    }

    @Test
    @Order(9)
    void duplicateBoardingRejection() throws Exception {
        UserSession.login(301L, "gate_dan", Role.GATEAGENT.name());
        BoardingService boardingService = BoardingService.getInstance();

        BoardingValidationResult result = boardingService.validateAndBoard(testPnr1, testFlight1);
        assertFalse(result.valid());
        assertTrue(result.message().contains("already recorded as BOARDED"));
    }

    @Test
    @Order(10)
    void flightMismatchBoardingRejection() throws Exception {
        UserSession.login(301L, "gate_dan", Role.GATEAGENT.name());
        BoardingService boardingService = BoardingService.getInstance();

        // testPnr1 is booked on testFlight1, attempt boarding on testFlight2
        BoardingValidationResult result = boardingService.validateAndBoard(testPnr1, testFlight2);
        assertFalse(result.valid());
        assertTrue(result.message().contains("booked for a different flight"));
    }

    @Test
    @Order(11)
    void uncheckinPassengerCannotBoard() throws Exception {
        UserSession.login(301L, "gate_dan", Role.GATEAGENT.name());
        BoardingService boardingService = BoardingService.getInstance();

        // testPnr2 is booked on testFlight2, but not checked in
        BoardingValidationResult result = boardingService.validateAndBoard(testPnr2, testFlight2);
        assertFalse(result.valid());
        assertTrue(result.message().contains("not completed check-in"));
    }

    @Test
    @Order(12)
    void auditLogsArePersistedInDatabase() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT action, target_type, username FROM audit_logs WHERE target_id IN (?, ?) ORDER BY id ASC")) {
            stmt.setString(1, testPnr1);
            stmt.setString(2, String.valueOf(testFlight1));
            try (ResultSet rs = stmt.executeQuery()) {
                boolean hasAudit = false;
                while (rs.next()) {
                    hasAudit = true;
                    assertNotNull(rs.getString("action"));
                    assertNotNull(rs.getString("username"));
                }
                assertTrue(hasAudit, "Expected audit log rows in audit_logs table for operational actions.");
            }
        }
    }

    @Test
    @Order(13)
    void rolePermissionsEnforceStrictLeastPrivilege() {
        // Auditor cannot check in
        UserSession.login(999L, "auditor_bob", Role.AUDITOR.name());
        assertThrows(SecurityException.class, () -> CheckinService.getInstance().processCheckin(testPnr2, "14B"));

        // Check-in agent can check in, but cannot board
        UserSession.login(101L, "agent_sarah", Role.CHECKINAGENT.name());
        assertDoesNotThrow(() -> CheckinService.getInstance().processCheckin(testPnr2, "14B"));
        assertThrows(SecurityException.class, () -> BoardingService.getInstance().validateAndBoard(testPnr2, testFlight2));

        // Gate agent can board, but cannot cancel reservations
        UserSession.login(301L, "gate_dan", Role.GATEAGENT.name());
        assertDoesNotThrow(() -> BoardingService.getInstance().validateAndBoard(testPnr2, testFlight2));
        assertThrows(SecurityException.class, () -> ReservationService.cancel(testPnr2, "Unauthorized cancel"));

        // Operations manager has MANAGE_GATES, but cannot check in passengers
        UserSession.login(201L, "ops_lead", Role.OPERATIONSMANAGER.name());
        assertThrows(SecurityException.class, () -> CheckinService.getInstance().processCheckin(testPnr2, "14B"));
    }

    // Helper methods
    private static long createFlight(String code, int seats) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO flights(flight_number, airline, source, destination, departure_time, arrival_time, total_seats, available_seats, fare, status) " +
                 "VALUES(?, 'JUnit AeroDesk', 'Manila', 'Cebu', '2031-05-01 10:00:00', '2031-05-01 11:30:00', ?, ?, 150.00, 'SCHEDULED')",
                 Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, code + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase());
            p.setInt(2, seats);
            p.setInt(3, seats);
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                long id = r.getLong(1);
                createdFlightIds.add(id);
                return id;
            }
        }
    }

    private static long createPassenger(String name) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO passengers(name, phone, email) VALUES(?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            p.setString(1, name);
            p.setString(2, "+639" + id.replaceAll("[^0-9]", "9").substring(0, 8));
            p.setString(3, "test" + id + "@aerodesk.com");
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                long pid = r.getLong(1);
                createdPassengerIds.add(pid);
                return pid;
            }
        }
    }

    private static void executeScript(String file) throws Exception {
        String sql = Files.readString(Path.of("..", "database", file))
            .replace("project4", TEST_DB)
            .replaceAll("(?m)^--.*(?:\\R|$)", "");
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            for (String part : sql.split(";")) {
                if (!part.isBlank()) s.execute(part);
            }
        }
    }

    private static String idListOrZero(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return "0";
        StringBuilder sb = new StringBuilder();
        synchronized (ids) {
            for (Long id : ids) {
                if (id != null) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(id);
                }
            }
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private static boolean activeSeatColumnExists() throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "SELECT 1 FROM information_schema.columns WHERE table_schema=? AND table_name='reservations' AND column_name='active_seat_number'")) {
            p.setString(1, TEST_DB);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }
}
