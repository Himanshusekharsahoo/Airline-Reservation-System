package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.operations.boarding.BoardingService;
import airline.management.system.operations.boarding.BoardingValidationResult;
import airline.management.system.operations.checkin.CheckinResult;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.flight.FlightOperationsService;
import airline.management.system.operations.flight.RebookingFoundationService;
import airline.management.system.operations.report.OperationalReportService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.Role;
import airline.management.system.tenant.MultitenantMigration;
import airline.management.system.tenant.Tenant;
import airline.management.system.tenant.TenantContext;
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
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14 Authoritative Integration Tests:
 * 1. Multi-tenant database schema migration idempotency and column verification.
 * 2. Strict cross-tenant rejection for reservations, cancellations, check-in, and boarding.
 * 3. Flight disruption workflow (DELAY, CANCEL) and audit trails.
 * 4. Disruption passenger safety (reservations & payments never deleted on cancellation).
 * 5. Atomic, transactional rebooking foundation with identity preservation and cross-tenant isolation.
 * 6. Tenant-scoped operational intelligence reporting across 5 business categories.
 * 7. Verification that production database project4 remains completely untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase14MultitenantOperationsTest {
    private static final String TEST_DB = "project4test";
    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdOrgIds = Collections.synchronizedSet(new LinkedHashSet<>());

    private static Tenant tenantA;
    private static Tenant tenantB;

    private static long flightA1;
    private static long flightB1;
    private static long passengerA1;
    private static long passengerB1;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("DB_NAME", TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();
        try (Connection c = DBConnection.getConnection()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
        }

        executeScript("schema.sql");
        if (!activeSeatColumnExists()) {
            executeScript("migration_3b.sql");
        }
        executeScript("v2_phase11_operations_schema.sql");

        // Apply Phase 14 multi-tenant migration
        try (Connection c = DBConnection.getConnection()) {
            MultitenantMigration.apply(c);
        }

        clearTestData();

        // Create test tenants
        tenantA = new Tenant(1L, "AD", "AeroDesk Air", true);
        long orgBId;
        try (Connection c = DBConnection.getConnection();
             PreparedStatement check = c.prepareStatement("SELECT id FROM organizations WHERE org_code = 'SKY'")) {
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    orgBId = rs.getLong(1);
                } else {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT INTO organizations (org_code, org_name, contact_email) VALUES ('SKY', 'SkyWays Partner', 'ops@skyways.test')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        p.executeUpdate();
                        try (ResultSet rsGen = p.getGeneratedKeys()) {
                            assertTrue(rsGen.next());
                            orgBId = rsGen.getLong(1);
                        }
                    }
                }
            }
        }
        createdOrgIds.add(orgBId);
        tenantB = new Tenant(orgBId, "SKY", "SkyWays Partner", true);

        // Create test admin user in project4test
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO app_users (id, username, password_hash, role, active) VALUES (950, 'phase14_admin', 'hash', 'SUPERADMIN', TRUE) " +
                 "ON DUPLICATE KEY UPDATE role = 'SUPERADMIN', active = TRUE")) {
            p.executeUpdate();
        }

        // Create baseline flight & passenger for Tenant A
        flightA1 = insertFlight(tenantA.organizationId(), "T14_AD101", "DEL", "BOM", 50, 50, new BigDecimal("150.00"));
        passengerA1 = insertPassenger(tenantA.organizationId(), "Alice TenantA", "9876543210", "alice@tenantA.test");

        // Create baseline flight & passenger for Tenant B
        flightB1 = insertFlight(tenantB.organizationId(), "SKY202", "DEL", "BOM", 50, 50, new BigDecimal("180.00"));
        passengerB1 = insertPassenger(tenantB.organizationId(), "Bob TenantB", "9123456780", "bob@tenantB.test");

        UserSession.login(950L, "phase14_admin", Role.SUPERADMIN.name());
        TenantContext.setSessionTenant(tenantA);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TenantContext.resetSession();
        UserSession.logout();

        clearTestData();
    }

    @Test
    @Order(1)
    void migrationAppliesNonDestructivelyAndEnforcesColumns() throws Exception {
        try (Connection c = DBConnection.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            List<String> coreTables = List.of("flights", "passengers", "reservations", "payments", "cancellations", "flight_status_history");
            for (String tbl : coreTables) {
                try (ResultSet rs = meta.getColumns(null, null, tbl, "organization_id")) {
                    assertTrue(rs.next(), "Table " + tbl + " missing organization_id column!");
                }
            }

            // Verify idempotency
            assertDoesNotThrow(() -> MultitenantMigration.apply(c));
        }
    }

    @Test
    @Order(2)
    void crossTenantReservationRejection() {
        // Tenant A active: cannot book on Tenant B's flight
        TenantContext.setCurrentTenant(tenantA);
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.book(flightB1, passengerA1, "1A", "Card")
        );

        // Tenant A active: cannot book for Tenant B's passenger
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.book(flightA1, passengerB1, "1A", "Card")
        );

        // Switch to Tenant B: cannot book on Tenant A's flight
        TenantContext.setCurrentTenant(tenantB);
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.book(flightA1, passengerB1, "2B", "UPI")
        );

        // Valid booking within Tenant A
        TenantContext.setCurrentTenant(tenantA);
        assertDoesNotThrow(() -> {
            String pnr = ReservationService.book(flightA1, passengerA1, "1A", "UPI");
            assertNotNull(pnr);
        });
    }

    @Test
    @Order(3)
    void crossTenantCancellationRejection() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        String pnrA = ReservationService.book(flightA1, passengerA1, "2A", "Card");
        assertNotNull(pnrA);

        // Tenant B cannot cancel Tenant A's reservation
        TenantContext.setCurrentTenant(tenantB);
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.cancel(pnrA, "Attempted Cross-Tenant Cancel")
        );

        // Tenant A can cancel its own reservation
        TenantContext.setCurrentTenant(tenantA);
        assertDoesNotThrow(() -> ReservationService.cancel(pnrA, "Legitimate Tenant A Cancel"));
    }

    @Test
    @Order(4)
    void crossTenantCheckinAndBoardingIsolation() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        String pnr = ReservationService.book(flightA1, passengerA1, "3A", "UPI");

        // Tenant B cannot see, check in, or board Tenant A reservation
        TenantContext.setCurrentTenant(tenantB);
        assertNull(CheckinService.getInstance().lookupReservation(pnr));
        CheckinResult checkinRes = CheckinService.getInstance().processCheckin(pnr, "3A");
        assertFalse(checkinRes.success(), "Tenant B should not be able to check in Tenant A passenger.");
        BoardingValidationResult boardingRes = BoardingService.getInstance().validateAndBoard(pnr, flightA1);
        assertFalse(boardingRes.valid(), "Tenant B should not be able to board Tenant A passenger.");

        // Tenant A can successfully check in and board
        TenantContext.setCurrentTenant(tenantA);
        assertNotNull(CheckinService.getInstance().lookupReservation(pnr));
        CheckinResult aCheckin = CheckinService.getInstance().processCheckin(pnr, "3A");
        assertTrue(aCheckin.success(), "Tenant A should successfully check in passenger.");
        BoardingValidationResult aBoarding = BoardingService.getInstance().validateAndBoard(pnr, flightA1);
        assertTrue(aBoarding.valid(), "Tenant A should successfully board passenger.");
    }

    @Test
    @Order(5)
    void flightDisruptionWorkflowAndAuditLogging() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        long disruptFlight = insertFlight(tenantA.organizationId(), "AD999", "DEL", "GOI", 20, 20, new BigDecimal("120.00"));

        // Delay flight
        FlightOperationsService.transition(disruptFlight, FlightOperationsService.Status.DELAYED, "ATC Delay 45m");
        // Cancel flight
        FlightOperationsService.transition(disruptFlight, FlightOperationsService.Status.CANCELLED, "Dense Fog Grounding");

        // Verify status in DB
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT status FROM flights WHERE id = ?")) {
            p.setLong(1, disruptFlight);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("CANCELLED", rs.getString("status"));
            }
        }

        // Verify flight_status_history has 2 entries with tenant ownership
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM flight_status_history WHERE flight_id = ? AND organization_id = ?")) {
            p.setLong(1, disruptFlight);
            p.setLong(2, tenantA.organizationId());
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }

        // Verify audit log entries
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE action = 'FLIGHT_DISRUPTED' AND target_id = ?")) {
            p.setString(1, String.valueOf(disruptFlight));
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }
        }
    }

    @Test
    @Order(6)
    void disruptionPassengerSafetyAndAffectedRetrieval() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        long flt = insertFlight(tenantA.organizationId(), "AD404", "DEL", "BOM", 30, 30, new BigDecimal("100.00"));
        long pax = insertPassenger(tenantA.organizationId(), "Stranded Traveler", "9988776655", "pax@del.test");
        String pnr = ReservationService.book(flt, pax, "4A", "UPI");

        // Cancel flight
        FlightOperationsService.transition(flt, FlightOperationsService.Status.CANCELLED, "Maintenance Disruption");

        // Safety check: Reservations and payments must NEVER be deleted
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT status FROM reservations WHERE pnr = ?")) {
            p.setString(1, pnr);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next(), "Reservation record must be preserved on flight cancellation!");
                assertEquals("CONFIRMED", rs.getString("status"));
            }
        }

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "SELECT p.payment_status FROM payments p JOIN reservations r ON r.id = p.reservation_id WHERE r.pnr = ?")) {
            p.setString(1, pnr);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next(), "Payment record must be preserved on flight cancellation!");
                assertEquals("SUCCESS", rs.getString("payment_status"));
            }
        }

        // Retrieve affected passengers
        List<FlightOperationsService.AffectedPassenger> affected = FlightOperationsService.getAffectedPassengers(flt);
        assertEquals(1, affected.size());
        FlightOperationsService.AffectedPassenger record = affected.get(0);
        assertEquals(pnr, record.pnr());
        assertEquals("Stranded Traveler", record.passengerName());
        assertEquals("SUCCESS", record.paymentStatus());
    }

    @Test
    @Order(7)
    void transactionalRebookingFoundation() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        long fltDisrupted = insertFlight(tenantA.organizationId(), "AD505", "DEL", "BOM", 20, 20, new BigDecimal("100.00"));
        long fltAltTenantA = insertFlight(tenantA.organizationId(), "AD506", "DEL", "BOM", 20, 20, new BigDecimal("110.00"));
        long fltAltTenantB = insertFlight(tenantB.organizationId(), "SKY507", "DEL", "BOM", 20, 20, new BigDecimal("115.00"));

        long pax = insertPassenger(tenantA.organizationId(), "Rebook Pax", "9911223344", "rebook@del.test");
        String origPnr = ReservationService.book(fltDisrupted, pax, "5A", "UPI");
        long origResId = getReservationId(origPnr);

        // Cancel the flight to trigger rebooking eligibility
        FlightOperationsService.transition(fltDisrupted, FlightOperationsService.Status.CANCELLED, "Mechanical Failure");

        // Alternative flight discovery must only return Tenant A flight, never Tenant B
        List<RebookingFoundationService.AlternativeFlight> alts = RebookingFoundationService.findEligibleAlternativeFlights(fltDisrupted);
        assertTrue(alts.stream().anyMatch(a -> a.flightId() == fltAltTenantA));
        assertFalse(alts.stream().anyMatch(a -> a.flightId() == fltAltTenantB), "Tenant B flights must not appear in Tenant A alternatives!");

        // Rebooking cross-tenant flight must fail with AccessDeniedException
        assertThrows(AccessDeniedException.class, () ->
            RebookingFoundationService.rebookPassenger(origResId, fltAltTenantB, "5A")
        );

        // Rebook onto valid alternative flight within Tenant A
        RebookingFoundationService.RebookingResult res = RebookingFoundationService.rebookPassenger(origResId, fltAltTenantA, "5A");
        assertTrue(res.success(), "Rebooking should succeed: " + res.message());
        assertNotNull(res.newPnr());
        assertEquals("5A", res.assignedSeat());

        // Verify target flight available seats decremented from 20 to 19
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT available_seats FROM flights WHERE id = ?")) {
            p.setLong(1, fltAltTenantA);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(19, rs.getInt(1));
            }
        }

        // Verify original reservation was cancelled
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT status FROM reservations WHERE id = ?")) {
            p.setLong(1, origResId);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("CANCELLED", rs.getString("status"));
            }
        }

        // Verify new reservation is CONFIRMED with same passenger ID
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT passenger_id, status FROM reservations WHERE pnr = ?")) {
            p.setString(1, res.newPnr());
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(pax, rs.getLong("passenger_id"));
                assertEquals("CONFIRMED", rs.getString("status"));
            }
        }
    }

    @Test
    @Order(8)
    void tenantScopedOperationalReports() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // Generate reports across all 5 operational categories for Tenant A
        for (OperationalReportService.Category cat : OperationalReportService.Category.values()) {
            OperationalReportService.ReportFilter filter = new OperationalReportService.ReportFilter(
                cat,
                LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(30),
                null,
                null
            );
            OperationalReportService.ReportData data = OperationalReportService.generateReport(filter);
            assertNotNull(data);
            assertEquals(cat, data.category());
            assertNotNull(data.summaryMetrics());
            assertNotNull(data.tableModel());
        }

        // Verify audit log for REPORT_GENERATED
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE action = 'REPORT_GENERATED'")) {
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 5, "Audit log must contain REPORT_GENERATED events.");
            }
        }
    }

    // --- Helper utilities ---

    private static long insertFlight(long orgId, String flightNo, String src, String dest, int totalSeats, int availSeats, BigDecimal fare) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO flights (organization_id, flight_number, airline, source, destination, departure_time, arrival_time, total_seats, available_seats, fare, status) " +
                 "VALUES (?, ?, 'AeroDesk Airline', ?, ?, '2032-05-01 10:00:00', '2032-05-01 12:00:00', ?, ?, ?, 'SCHEDULED')",
                 Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, orgId);
            p.setString(2, flightNo);
            p.setString(3, src);
            p.setString(4, dest);
            p.setInt(5, totalSeats);
            p.setInt(6, availSeats);
            p.setBigDecimal(7, fare);
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                createdFlightIds.add(id);
                return id;
            }
        }
    }

    private static long insertPassenger(long orgId, String name, String phone, String email) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO passengers (organization_id, name, phone, email) VALUES (?, ?, ?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, orgId);
            p.setString(2, name);
            p.setString(3, phone);
            p.setString(4, email);
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                createdPassengerIds.add(id);
                return id;
            }
        }
    }

    private static long getReservationId(String pnr) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT id FROM reservations WHERE pnr = ?")) {
            p.setString(1, pnr);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static String idListOrZero(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return "0";
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static void executeScopedDelete(Statement s, String targetTable, String sql, List<Throwable> failures) {
        try {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            failures.add(new SQLException("Cleanup failed for table [" + targetTable + "]: " + e.getMessage(), e));
        }
    }

    private static void clearTestData() throws Exception {
        TestDatabaseGuard.ensureTestDatabase();
        List<Throwable> failures = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            String fltIds = idListOrZero(createdFlightIds);
            String paxIds = idListOrZero(createdPassengerIds);
            String orgIds = idListOrZero(createdOrgIds);

            String fltScope = "id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR (flight_number IN ('T14_AD101', 'SKY202', 'AD999', 'AD404', 'AD505', 'AD506', 'SKY507') AND departure_time = '2032-05-01 10:00:00')";
            String fltSub = "SELECT id FROM flights WHERE " + fltScope;
            String paxSub = "SELECT id FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@tenantA.test' OR email LIKE '%@tenantB.test' OR email LIKE '%@del.test'";
            String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")";
            String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")";
            String passSub = "SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")";

            // 1. Boarding events
            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE boarding_pass_id IN (" + passSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 2. Boarding passes
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 3. Checkins
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 4. Flight status history & gates
            executeScopedDelete(s, "flight_gates", "DELETE FROM flight_gates WHERE flight_id IN (" + fltSub + ")", failures);
            executeScopedDelete(s, "flight_status_history", "DELETE FROM flight_status_history WHERE flight_id IN (" + fltSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 5. Cancellations & Payments
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 6. Reservations
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 7. Passengers
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@tenantA.test' OR email LIKE '%@tenantB.test' OR email LIKE '%@del.test'", failures);
            // 8. Flights
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE " + fltScope, failures);
            // 9. Users & Audit logs
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id = 950", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE user_id = 950 OR username = 'phase14_admin' OR organization_id IN (" + orgIds + ") OR action IN ('FLIGHT_DISRUPTED', 'REPORT_GENERATED')", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE id = 950 OR username = 'phase14_admin'", failures);
            // 10. Partner tenant organization
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE id IN (" + orgIds + ") OR org_code = 'SKY'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
            createdOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase14MultitenantOperationsTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    private static void executeScript(String file) throws Exception {
        String sql = Files.readString(Path.of("..", "database", file))
                .replace("project4", TEST_DB)
                .replaceAll("(?m)^--.*(?:\\R|$)", "");
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    s.execute(part);
                }
            }
        }
    }

    private static boolean activeSeatColumnExists() throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'reservations' AND column_name = 'active_seat_number'")) {
            p.setString(1, TEST_DB);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }
}
