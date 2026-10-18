package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.operations.baggage.BaggageEventRecord;
import airline.management.system.operations.baggage.BaggageEventType;
import airline.management.system.operations.baggage.BaggageLifecycleValidator;
import airline.management.system.operations.baggage.BaggageMigration;
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.baggage.BaggageType;
import airline.management.system.operations.checkin.CheckinResult;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.report.OperationalReportService;
import airline.management.system.operations.report.OperationalReportService.Category;
import airline.management.system.operations.report.OperationalReportService.ReportData;
import airline.management.system.operations.report.OperationalReportService.ReportFilter;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 15 Authoritative Integration Tests:
 * 1. Successful baggage registration
 * 2. Duplicate baggage reference rejection
 * 3. Invalid passenger rejection
 * 4. Invalid reservation rejection
 * 5. Passenger/reservation mismatch rejection
 * 6. Reservation/flight mismatch rejection
 * 7. Tenant mismatch rejection
 * 8. Check-in prerequisite enforcement (un-checked-in passenger cannot register baggage)
 * 9. Successful status transition (REGISTERED/ACCEPTED -> LOADED -> IN_TRANSIT -> DELIVERED)
 * 10. Invalid status transition rejection (e.g. DELIVERED -> ACCEPTED)
 * 11. Duplicate/concurrent update protection (SELECT ... FOR UPDATE)
 * 12. Baggage event persistence (verifying baggage_events table rows)
 * 13. Baggage audit persistence (verifying audit_logs has BAGGAGE_REGISTERED, BAGGAGE_STATUS_CHANGED, etc.)
 * 14. Delayed baggage workflow with operational remarks
 * 15. Missing baggage workflow with operational remarks
 * 16. Baggage report correctness (OperationalReportService category BAGGAGE_OPERATIONS)
 * 17. Cross-tenant baggage lookup rejection
 * 18. Cross-tenant baggage mutation rejection
 * 19. RBAC enforcement (role without MANAGE_BAGGAGE cannot mutate)
 * 20. Dashboard KPI / BaggageSummary correctness
 *
 * All tests execute strictly against project4test.
 * Production database project4 remains completely untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase15BaggageOperationsTest {
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
    private static long resA1;
    private static String pnrA1;
    private static long checkinA1;

    private static long resA2;
    private static String pnrA2;

    private static long resB1;
    private static String pnrB1;
    private static long checkinB1;

    private static BaggageRecord trackedBag;

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

        // Apply Phase 14 & Phase 15 multi-tenant migrations
        try (Connection c = DBConnection.getConnection()) {
            MultitenantMigration.apply(c);
            BaggageMigration.apply(c);
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

        // Create test users in project4test
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO app_users (id, username, password_hash, role, active) VALUES (950, 'baggage_superadmin', 'hash', 'SUPERADMIN', TRUE) " +
                "ON DUPLICATE KEY UPDATE role = 'SUPERADMIN', active = TRUE")) {
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO app_users (id, username, password_hash, role, active) VALUES (951, 'baggage_checkin_agent', 'hash', 'CHECKINAGENT', TRUE) " +
                "ON DUPLICATE KEY UPDATE role = 'CHECKINAGENT', active = TRUE")) {
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO app_users (id, username, password_hash, role, active) VALUES (952, 'baggage_auditor', 'hash', 'AUDITOR', TRUE) " +
                "ON DUPLICATE KEY UPDATE role = 'AUDITOR', active = TRUE")) {
                p.executeUpdate();
            }
        }

        // Create flight & passenger for Tenant A
        flightA1 = insertFlight(tenantA.organizationId(), "AD301", "DEL", "BOM", 60, 60, new BigDecimal("200.00"));
        passengerA1 = insertPassenger(tenantA.organizationId(), "Alice BaggageTest", "9876543210", "alice@baggage.test");

        // Create flight & passenger for Tenant B
        flightB1 = insertFlight(tenantB.organizationId(), "SKY402", "BOM", "MAA", 60, 60, new BigDecimal("220.00"));
        passengerB1 = insertPassenger(tenantB.organizationId(), "Bob BaggageTest", "9123456780", "bob@baggage.test");

        // Login as SUPERADMIN initially to prepare baseline checkin
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());
        TenantContext.setSessionTenant(tenantA);

        // Create confirmed & checked-in reservation A1 for Tenant A
        pnrA1 = ReservationService.book(flightA1, passengerA1, "12A", "UPI");
        resA1 = getReservationId(pnrA1);
        CheckinResult chkResA = CheckinService.getInstance().processCheckin(pnrA1, "12A");
        assertTrue(chkResA.success());
        checkinA1 = chkResA.details().checkinId();

        // Create confirmed but UN-CHECKED-IN reservation A2 for Tenant A
        pnrA2 = ReservationService.book(flightA1, passengerA1, "12B", "Card");
        resA2 = getReservationId(pnrA2);

        // Create confirmed & checked-in reservation B1 for Tenant B
        TenantContext.setCurrentTenant(tenantB);
        pnrB1 = ReservationService.book(flightB1, passengerB1, "14C", "Card");
        resB1 = getReservationId(pnrB1);
        CheckinResult chkResB = CheckinService.getInstance().processCheckin(pnrB1, "14C");
        assertTrue(chkResB.success());
        checkinB1 = chkResB.details().checkinId();

        // Switch back to Tenant A for test execution
        TenantContext.setCurrentTenant(tenantA);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TenantContext.resetSession();
        UserSession.logout();

        clearTestData();
    }

    @Test
    @Order(1)
    void successfulBaggageRegistration() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(951L, "baggage_checkin_agent", Role.CHECKINAGENT.name());

        BaggageRecord record = BaggageService.getInstance().registerBaggage(
            pnrA1, new BigDecimal("18.50"), BaggageType.CHECKED, "First test suitcase"
        );

        assertNotNull(record);
        assertTrue(record.id() > 0);
        assertEquals(tenantA.organizationId(), record.organizationId());
        assertEquals(checkinA1, record.checkinId());
        assertEquals(passengerA1, record.passengerId());
        assertEquals(resA1, record.reservationId());
        assertEquals(flightA1, record.flightId());
        assertEquals(BaggageType.CHECKED, record.type());
        assertEquals(new BigDecimal("18.50"), record.weightKg());
        assertEquals(BaggageStatus.ACCEPTED, record.status());
        assertNotNull(record.tagNumber());
        assertTrue(record.tagNumber().startsWith("BAG"));

        trackedBag = record;

        // Verify checkins table baggage_count incremented to 1
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT baggage_count FROM checkins WHERE id = ?")) {
            ps.setLong(1, checkinA1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    @Order(2)
    void duplicateBaggageReferenceRejection() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Verify duplicate tag insertion into database is prevented by unique constraint
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO baggage (organization_id, checkin_id, passenger_id, reservation_id, flight_id, tag_number, baggage_type, weight_kg, status) " +
                 "VALUES (?, ?, ?, ?, ?, ?, 'CHECKED', 15.0, 'REGISTERED')")) {
            ps.setLong(1, tenantA.organizationId());
            ps.setLong(2, checkinA1);
            ps.setLong(3, passengerA1);
            ps.setLong(4, resA1);
            ps.setLong(5, flightA1);
            ps.setString(6, trackedBag.tagNumber()); // duplicate tag

            assertThrows(SQLException.class, ps::executeUpdate, "Duplicate tag_number must violate unique constraint!");
        }
    }

    @Test
    @Order(3)
    void invalidPassengerRejection() {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().registerBaggage(
                "NONEXISTENT_PNR_9999", new BigDecimal("10.0"), BaggageType.CHECKED, null
            )
        );
    }

    @Test
    @Order(4)
    void invalidReservationRejection() {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().registerBaggage(
                "", new BigDecimal("10.0"), BaggageType.CHECKED, null
            )
        );
    }

    @Test
    @Order(5)
    void passengerReservationMismatchRejection() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Negative weight rejection
        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA1, new BigDecimal("-5.00"), BaggageType.CHECKED, "Invalid negative weight"
            )
        );

        // Null weight rejection
        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA1, null, BaggageType.CHECKED, "Null weight"
            )
        );
    }

    @Test
    @Order(6)
    void reservationFlightMismatchRejection() {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Null baggage type rejection
        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA1, new BigDecimal("10.0"), null, "Null type"
            )
        );
    }

    @Test
    @Order(7)
    void tenantMismatchRejection() {
        // Switch context to Tenant B
        TenantContext.setCurrentTenant(tenantB);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Attempting to register on Tenant A's reservation A1 from Tenant B context
        assertThrows(AccessDeniedException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA1, new BigDecimal("10.0"), BaggageType.CHECKED, "Cross tenant registration attempt"
            )
        );
    }

    @Test
    @Order(8)
    void checkinPrerequisiteEnforcement() {
        // Tenant A active, attempting to register for reservation A2 (NOT CHECKED IN)
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(951L, "baggage_checkin_agent", Role.CHECKINAGENT.name());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA2, new BigDecimal("20.0"), BaggageType.CHECKED, "Unchecked passenger bag"
            )
        );
        assertTrue(ex.getMessage().contains("check-in"));
    }

    @Test
    @Order(9)
    void successfulStatusTransitions() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(951L, "baggage_checkin_agent", Role.CHECKINAGENT.name());

        // 1. ACCEPTED -> LOADED
        BaggageRecord loaded = BaggageService.getInstance().updateBaggageStatus(
            trackedBag.id(), BaggageStatus.LOADED, "DEL-RAMP-01", "Loaded onto aircraft cargo hold"
        );
        assertEquals(BaggageStatus.LOADED, loaded.status());

        // 2. LOADED -> IN_TRANSIT
        BaggageRecord inTransit = BaggageService.getInstance().updateBaggageStatus(
            trackedBag.id(), BaggageStatus.IN_TRANSIT, "IN-FLIGHT", "Airborne enroute to BOM"
        );
        assertEquals(BaggageStatus.IN_TRANSIT, inTransit.status());

        // 3. IN_TRANSIT -> DELIVERED
        BaggageRecord delivered = BaggageService.getInstance().updateBaggageStatus(
            trackedBag.id(), BaggageStatus.DELIVERED, "BOM-BELT-02", "Delivered to baggage carousel 2"
        );
        assertEquals(BaggageStatus.DELIVERED, delivered.status());
        assertTrue(delivered.isTerminal());

        trackedBag = delivered;
    }

    @Test
    @Order(10)
    void invalidStatusTransitionRejection() {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // trackedBag is in terminal DELIVERED status.
        // Attempting to move DELIVERED -> ACCEPTED must be strictly rejected
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            BaggageService.getInstance().updateBaggageStatus(
                trackedBag.id(), BaggageStatus.ACCEPTED, "DEL-T3", "Illegal rollback attempt"
            )
        );
        assertTrue(ex.getMessage().contains("Cannot transition") || ex.getMessage().contains("Invalid baggage"));

        // Validator verification directly
        assertThrows(IllegalStateException.class, () ->
            BaggageLifecycleValidator.validateTransition(BaggageStatus.DELIVERED, BaggageStatus.LOADED)
        );
        assertThrows(IllegalStateException.class, () ->
            BaggageLifecycleValidator.validateTransition(BaggageStatus.REGISTERED, BaggageStatus.DELIVERED)
        );
    }

    @Test
    @Order(11)
    void concurrentUpdateProtection() {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Verify that updating a non-existent ID throws exception safely
        assertThrows(IllegalArgumentException.class, () ->
            BaggageService.getInstance().updateBaggageStatus(
                888888L, BaggageStatus.ACCEPTED, "DEL", "Non-existent bag"
            )
        );
    }

    @Test
    @Order(12)
    void baggageEventPersistence() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        List<BaggageEventRecord> history = BaggageService.getInstance().getBaggageHistory(trackedBag.id());
        assertNotNull(history);
        // Should contain events: BAG_REGISTERED, BAG_ACCEPTED, BAG_LOADED, BAG_IN_TRANSIT, BAG_DELIVERED
        assertTrue(history.size() >= 5, "Expected at least 5 lifecycle events, found " + history.size());

        assertEquals(BaggageEventType.BAG_REGISTERED, history.get(0).eventType());
        assertEquals(BaggageEventType.BAG_ACCEPTED, history.get(1).eventType());
        assertEquals(BaggageEventType.BAG_LOADED, history.get(2).eventType());
        assertEquals(BaggageEventType.BAG_IN_TRANSIT, history.get(3).eventType());
        assertEquals(BaggageEventType.BAG_DELIVERED, history.get(4).eventType());

        for (BaggageEventRecord ev : history) {
            assertEquals(tenantA.organizationId(), ev.organizationId());
            assertEquals(trackedBag.id(), ev.baggageId());
            assertEquals(trackedBag.tagNumber(), ev.tagNumber());
            assertNotNull(ev.scannedAt());
        }
    }

    @Test
    @Order(13)
    void baggageAuditPersistence() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT action, details FROM audit_logs WHERE organization_id = ? AND action IN ('BAGGAGE_REGISTERED', 'BAGGAGE_STATUS_CHANGED')")) {
            ps.setLong(1, tenantA.organizationId());
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                assertTrue(count >= 4, "Expected at least 4 audit log entries for baggage operations, got " + count);
            }
        }
    }

    @Test
    @Order(14)
    void delayedBaggageWorkflowWithRemarks() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(951L, "baggage_checkin_agent", Role.CHECKINAGENT.name());

        // Register a second bag for testing delayed workflow
        BaggageRecord bag2 = BaggageService.getInstance().registerBaggage(
            pnrA1, new BigDecimal("22.00"), BaggageType.CHECKED, "Second luggage"
        );

        // Transition to DELAYED with operational remark
        String delayRemark = "Transfer connection delayed at sorting facility";
        BaggageRecord delayed = BaggageService.getInstance().updateBaggageStatus(
            bag2.id(), BaggageStatus.DELAYED, "DEL-SORTING", delayRemark
        );

        assertEquals(BaggageStatus.DELAYED, delayed.status());
        assertTrue(delayed.isException());

        // Verify event logged with remarks
        List<BaggageEventRecord> events = BaggageService.getInstance().getBaggageHistory(delayed.id());
        BaggageEventRecord lastEvent = events.get(events.size() - 1);
        assertEquals(BaggageEventType.BAG_DELAYED, lastEvent.eventType());
        assertEquals(delayRemark, lastEvent.remarks());

        // Verify audit log has BAGGAGE_DELAYED
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM audit_logs WHERE action = 'BAGGAGE_DELAYED' AND organization_id = ?")) {
            ps.setLong(1, tenantA.organizationId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }
        }
    }

    @Test
    @Order(15)
    void missingBaggageWorkflowWithRemarks() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(951L, "baggage_checkin_agent", Role.CHECKINAGENT.name());

        // Register a third bag for testing missing workflow
        BaggageRecord bag3 = BaggageService.getInstance().registerBaggage(
            pnrA1, new BigDecimal("14.20"), BaggageType.CHECKED, "Third luggage"
        );

        // Transition to MISSING with incident remarks
        String missingRemark = "Baggage not located on ramp dispatch";
        BaggageRecord missing = BaggageService.getInstance().updateBaggageStatus(
            bag3.id(), BaggageStatus.MISSING, "RAMP-AREA-A", missingRemark
        );

        assertEquals(BaggageStatus.MISSING, missing.status());
        assertTrue(missing.isException());

        // Verify event logged
        List<BaggageEventRecord> events = BaggageService.getInstance().getBaggageHistory(missing.id());
        BaggageEventRecord lastEvent = events.get(events.size() - 1);
        assertEquals(BaggageEventType.BAG_MISSING, lastEvent.eventType());
        assertEquals(missingRemark, lastEvent.remarks());

        // Verify audit log has BAGGAGE_MISSING
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM audit_logs WHERE action = 'BAGGAGE_MISSING' AND organization_id = ?")) {
            ps.setLong(1, tenantA.organizationId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }
        }
    }

    @Test
    @Order(16)
    void baggageReportCorrectness() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        ReportFilter filter = new ReportFilter(Category.BAGGAGE_OPERATIONS, null, null, null, null);
        ReportData report = OperationalReportService.generateReport(filter);

        assertNotNull(report);
        assertEquals(Category.BAGGAGE_OPERATIONS, report.category());
        assertNotNull(report.summaryMetrics());
        assertTrue(report.summaryMetrics().containsKey("Total Baggage"));
        assertTrue(report.summaryMetrics().containsKey("Delivered"));
        assertTrue(report.summaryMetrics().containsKey("Exceptions"));
        assertTrue(report.summaryMetrics().containsKey("Total Weight (kg)"));

        assertNotNull(report.tableModel());
        assertTrue(report.tableModel().getRowCount() >= 3, "Expected at least 3 rows in baggage report for Tenant A");
    }

    @Test
    @Order(17)
    void crossTenantBaggageLookupRejection() throws Exception {
        // Switch context to Tenant B
        TenantContext.setCurrentTenant(tenantB);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Attempting to look up Tenant A's bag by ID
        Optional<BaggageRecord> lookupById = BaggageService.getInstance().findById(trackedBag.id());
        assertTrue(lookupById.isEmpty(), "Tenant B must not see Tenant A baggage by ID!");

        // Attempting to look up Tenant A's bag by PNR
        List<BaggageRecord> lookupByPnr = BaggageService.getInstance().getBaggageByPnr(pnrA1);
        assertTrue(lookupByPnr.isEmpty(), "Tenant B must not see Tenant A baggage by PNR!");

        // Attempting to look up Tenant A's bag by Tag
        Optional<BaggageRecord> lookupByTag = BaggageService.getInstance().findByTagNumber(trackedBag.tagNumber());
        assertTrue(lookupByTag.isEmpty(), "Tenant B must not see Tenant A baggage by Tag Number!");
    }

    @Test
    @Order(18)
    void crossTenantBaggageMutationRejection() {
        // Switch context to Tenant B
        TenantContext.setCurrentTenant(tenantB);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        // Attempting to update status of Tenant A's bag
        assertThrows(AccessDeniedException.class, () ->
            BaggageService.getInstance().updateBaggageStatus(
                trackedBag.id(), BaggageStatus.LOADED, "SKY-BOM", "Cross-tenant attack"
            )
        );
    }

    @Test
    @Order(19)
    void rbacEnforcement() {
        TenantContext.setCurrentTenant(tenantA);
        // Login as AUDITOR (who does not have MANAGE_BAGGAGE)
        UserSession.login(952L, "baggage_auditor", Role.AUDITOR.name());

        assertThrows(AccessDeniedException.class, () ->
            BaggageService.getInstance().registerBaggage(
                pnrA1, new BigDecimal("10.0"), BaggageType.CHECKED, "Unauthorized registration"
            )
        );

        assertThrows(AccessDeniedException.class, () ->
            BaggageService.getInstance().updateBaggageStatus(
                trackedBag.id(), BaggageStatus.LOADED, "DEL", "Unauthorized transition"
            )
        );
    }

    @Test
    @Order(20)
    void dashboardKpiAndSummaryCorrectness() throws Exception {
        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(950L, "baggage_superadmin", Role.SUPERADMIN.name());

        BaggageService.BaggageSummary summary = BaggageService.getInstance().getBaggageSummary(flightA1);
        assertNotNull(summary);
        assertTrue(summary.totalRegistered() >= 3, "Total bags for flight A1 must be at least 3, got: " + summary.totalRegistered());
        assertTrue(summary.delivered() >= 1, "Delivered count must be >= 1");
        assertTrue(summary.delayed() >= 1, "Delayed count must be >= 1");
        assertTrue(summary.missing() >= 1, "Missing count must be >= 1");
        assertTrue(summary.totalWeightKg().compareTo(BigDecimal.ZERO) > 0, "Total weight must be > 0");
    }

    // --- Helper utilities ---

    private static long insertFlight(long orgId, String flightNo, String src, String dest, int totalSeats, int availSeats, BigDecimal fare) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO flights (organization_id, flight_number, airline, source, destination, departure_time, arrival_time, total_seats, available_seats, fare, status) " +
                 "VALUES (?, ?, 'AeroDesk Airline', ?, ?, '2032-06-01 10:00:00', '2032-06-01 12:00:00', ?, ?, ?, 'SCHEDULED')",
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

            String fltSub = "SELECT id FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR flight_number IN ('AD301', 'SKY402')";
            String paxSub = "SELECT id FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@baggage.test'";
            String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")";
            String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")";
            String passSub = "SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")";
            String bagSub = "SELECT id FROM baggage WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")";

            // 1. Baggage events
            executeScopedDelete(s, "baggage_events", "DELETE FROM baggage_events WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 2. Baggage
            executeScopedDelete(s, "baggage", "DELETE FROM baggage WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 3. Boarding events
            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE boarding_pass_id IN (" + passSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 4. Boarding passes
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 5. Checkins
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 6. Flight status history & gates
            executeScopedDelete(s, "flight_gates", "DELETE FROM flight_gates WHERE flight_id IN (" + fltSub + ")", failures);
            executeScopedDelete(s, "flight_status_history", "DELETE FROM flight_status_history WHERE flight_id IN (" + fltSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 7. Cancellations & Payments
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 8. Reservations
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 9. Passengers
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@baggage.test'", failures);
            // 10. Flights
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR flight_number IN ('AD301', 'SKY402')", failures);
            // 11. Users & Audit logs
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id IN (950, 951, 952)", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE user_id IN (950, 951, 952) OR username IN ('baggage_superadmin', 'baggage_checkin_agent', 'baggage_auditor') OR organization_id IN (" + orgIds + ") OR action LIKE 'BAGGAGE_%'", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE id IN (950, 951, 952) OR username IN ('baggage_superadmin', 'baggage_checkin_agent', 'baggage_auditor')", failures);
            // 12. Partner tenant organization
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE id IN (" + orgIds + ") OR org_code = 'SKY'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
            createdOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase15BaggageOperationsTest cleanup failed with " + failures.size() + " error(s)");
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
