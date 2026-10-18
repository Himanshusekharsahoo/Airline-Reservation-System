package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.operations.baggage.BaggageMigration;
import airline.management.system.operations.flight.RebookingFoundationService;
import airline.management.system.operations.irops.DisruptedPassengerManifestItem;
import airline.management.system.operations.irops.DisruptionReason;
import airline.management.system.operations.irops.DisruptionService;
import airline.management.system.operations.irops.DisruptionSeverity;
import airline.management.system.operations.irops.DisruptionStatus;
import airline.management.system.operations.irops.DisruptionType;
import airline.management.system.operations.irops.DisruptionVoucher;
import airline.management.system.operations.irops.DisruptionVoucherService;
import airline.management.system.operations.irops.FlightDisruption;
import airline.management.system.operations.irops.IROPSMigration;
import airline.management.system.operations.irops.NotificationStatus;
import airline.management.system.operations.irops.PassengerReaccommodation;
import airline.management.system.operations.irops.ReaccommodationEngine;
import airline.management.system.operations.irops.ReaccommodationStatus;
import airline.management.system.operations.irops.ReaccommodationType;
import airline.management.system.operations.irops.VoucherStatus;
import airline.management.system.operations.irops.VoucherType;
import airline.management.system.operations.pir.PIRClaimsMigration;
import airline.management.system.operations.report.OperationalReportService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.security.Role;
import airline.management.system.tenant.MultitenantMigration;
import airline.management.system.tenant.Tenant;
import airline.management.system.tenant.TenantContext;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 17 Authoritative Integration & Security Test Suite:
 * Flight Disruption & Irregular Operations (IROPS) Management.
 * Validates flight disruptions, affected passenger discovery, single/batch re-accommodation,
 * service recovery vouchers, RBAC authorization, multi-tenant boundaries, and executive reporting.
 *
 * All operations execute strictly against project4test; project4 remains untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase17IROPSOperationsTest {

    private static final String TEST_DB = "project4test";
    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdOrgIds = Collections.synchronizedSet(new LinkedHashSet<>());

    private static Tenant tenantA;
    private static Tenant tenantB;

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("DB_NAME", TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();
        try (Connection c = DBConnection.getConnection()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
        }

        runMigrationScript("schema.sql");
        if (!hasActiveSeatColumn()) {
            runMigrationScript("migration_3b.sql");
        }
        runMigrationScript("v2_phase11_operations_schema.sql");

        try (Connection c = DBConnection.getConnection()) {
            MultitenantMigration.apply(c);
            BaggageMigration.apply(c);
            PIRClaimsMigration.apply(c);
            IROPSMigration.apply(c);
        }

        clearTestData();

        tenantA = new Tenant(1L, "AD", "AeroDesk", true);
        long bOrgId = createOrganization("P17B");
        tenantB = new Tenant(bOrgId, "P17B", "Phase 17 Tenant B", true);

        createUser(1701, "irops_admin", Role.SUPERADMIN.name());
        createUser(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        createUser(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        createUser(1704, "irops_auditor", Role.AUDITOR.name());
        createUser(1705, "irops_unauth", Role.CHECKINAGENT.name());

        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(1701, "irops_admin", Role.SUPERADMIN.name());
    }

    @AfterAll
    static void cleanup() throws Exception {
        TenantContext.resetSession();
        UserSession.logout();
        clearTestData();
    }

    // =========================================================================
    // 1-10: DISRUPTION DECLARATION, LIFECYCLE & MANIFEST TESTS
    // =========================================================================

    @Test @Order(1)
    void test01_declareDisruptionCancellation() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-101", "DEL", "BOM", "SCHEDULED", 50);
        createReservationFixture(tenantA, flightId, "P17-01A", "CONFIRMED");
        createReservationFixture(tenantA, flightId, "P17-01B", "CONFIRMED");

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(
            flightId,
            DisruptionType.CANCELLATION,
            DisruptionReason.WEATHER,
            DisruptionSeverity.HIGH,
            0,
            "Monsoon typhoon grounded operations",
            "Initial cancellation declaration"
        );

        assertNotNull(d);
        assertTrue(d.disruptionReference().startsWith("DSP-"));
        assertEquals(DisruptionType.CANCELLATION, d.type());
        assertEquals(DisruptionReason.WEATHER, d.reason());
        assertEquals(DisruptionSeverity.HIGH, d.severity());
        assertEquals(DisruptionStatus.DECLARED, d.status());
        assertEquals(2, d.passengerImpactCount());

        // Verify flight status in database transitioned to CANCELLED
        assertEquals("CANCELLED", getFlightStatus(flightId));
    }

    @Test @Order(2)
    void test02_declareDisruptionDelay() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-102", "BOM", "BLR", "SCHEDULED", 80);
        createReservationFixture(tenantA, flightId, "P17-02A", "CONFIRMED");

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(
            flightId,
            DisruptionType.DELAY,
            DisruptionReason.MECHANICAL,
            DisruptionSeverity.MEDIUM,
            120,
            "Hydraulic pump inspection required",
            "Delay estimate 2 hours"
        );

        assertNotNull(d);
        assertEquals(DisruptionType.DELAY, d.type());
        assertEquals(120, d.delayMinutes());
        assertEquals("DELAYED", getFlightStatus(flightId));
    }

    @Test @Order(3)
    void test03_preventDuplicateActiveDisruptionOnSameFlight() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-103", "DEL", "DXB", "SCHEDULED", 100);
        DisruptionService service = DisruptionService.getInstance();

        service.declareDisruption(flightId, DisruptionType.DELAY, DisruptionReason.ATC, DisruptionSeverity.LOW, 45, "ATC slot constraint", null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.declareDisruption(flightId, DisruptionType.CANCELLATION, DisruptionReason.SECURITY, DisruptionSeverity.CRITICAL, 0, "Duplicate attempt", null)
        );
        assertTrue(ex.getMessage().contains("already exists"), "Must prevent duplicate active disruption for same flight");
    }

    @Test @Order(4)
    void test04_disruptionLifecycleProgression() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-104", "CCU", "DEL", "SCHEDULED", 60);
        DisruptionService service = DisruptionService.getInstance();

        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.DELAY, DisruptionReason.CREW, DisruptionSeverity.MEDIUM, 60, "Crew duty hours exceeded", null);
        assertEquals(DisruptionStatus.DECLARED, d.status());

        FlightDisruption d2 = service.changeStatus(d.id(), DisruptionStatus.INVESTIGATING, "Assessing crew replacement availability");
        assertEquals(DisruptionStatus.INVESTIGATING, d2.status());

        FlightDisruption d3 = service.changeStatus(d.id(), DisruptionStatus.REACCOMMODATING, "Reserve crew dispatched");
        assertEquals(DisruptionStatus.REACCOMMODATING, d3.status());

        FlightDisruption d4 = service.changeStatus(d.id(), DisruptionStatus.MITIGATED, "Replacement crew arrived at gate");
        assertEquals(DisruptionStatus.MITIGATED, d4.status());

        FlightDisruption d5 = service.resolveDisruption(d.id(), "Flight ready for pushback");
        assertEquals(DisruptionStatus.RESOLVED, d5.status());
        assertNotNull(d5.resolvedAt());
        assertTrue(d5.isTerminal());
    }

    @Test @Order(5)
    void test05_invalidDisruptionTransitionRejection() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-105", "DEL", "HYD", "SCHEDULED", 40);
        DisruptionService service = DisruptionService.getInstance();

        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.LOW, 30, "Foggy conditions", null);

        // Cannot jump directly from DECLARED to RESOLVED
        assertThrows(IllegalStateException.class, () ->
            service.resolveDisruption(d.id(), "Direct resolve should fail")
        );

        // Progress to terminal state CANCELLED
        service.changeStatus(d.id(), DisruptionStatus.CANCELLED, "Cancelled");

        // Attempting to reopen cancelled disruption must fail
        assertThrows(IllegalStateException.class, () ->
            service.changeStatus(d.id(), DisruptionStatus.DECLARED, "Illegal reopen attempt")
        );
    }

    @Test @Order(6)
    void test06_updateDisruptionDetails() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-106", "MAA", "DEL", "SCHEDULED", 50);
        DisruptionService service = DisruptionService.getInstance();

        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.DELAY, DisruptionReason.MECHANICAL, DisruptionSeverity.LOW, 30, "Minor sensor fault", null);

        FlightDisruption updated = service.updateDisruption(d.id(), DisruptionSeverity.HIGH, 180, "Sensor unit requires total replacement", "Escalated by maintenance crew");
        assertEquals(DisruptionSeverity.HIGH, updated.severity());
        assertEquals(180, updated.delayMinutes());
        assertEquals("Sensor unit requires total replacement", updated.impactDescription());
    }

    @Test @Order(7)
    void test07_cancelDisruptionDeclaration() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-107", "BLR", "GOI", "SCHEDULED", 45);
        DisruptionService service = DisruptionService.getInstance();

        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.DELAY, DisruptionReason.SECURITY, DisruptionSeverity.MEDIUM, 45, "Security sweep", null);
        FlightDisruption cancelled = service.changeStatus(d.id(), DisruptionStatus.CANCELLED, "False security alarm; flight cleared");
        assertEquals(DisruptionStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.isTerminal());
    }

    @Test @Order(8)
    void test08_terminalDisruptionCannotBeUpdated() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-108", "DEL", "SXR", "SCHEDULED", 30);
        DisruptionService service = DisruptionService.getInstance();

        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.CANCELLATION, DisruptionReason.AIRPORT, DisruptionSeverity.CRITICAL, 0, "Runway resurfacing", null);
        service.changeStatus(d.id(), DisruptionStatus.CANCELLED, "Closed out");

        assertThrows(IllegalStateException.class, () ->
            service.updateDisruption(d.id(), DisruptionSeverity.LOW, 0, "Attempted update after resolution", null)
        );
    }

    @Test @Order(9)
    void test09_affectedPassengerManifestRichData() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "AI-109", "DEL", "COK", "SCHEDULED", 90);
        long resId1 = createReservationFixture(tenantA, flightId, "P17-09A", "CONFIRMED");
        long resId2 = createReservationFixture(tenantA, flightId, "P17-09B", "CONFIRMED");

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(flightId, DisruptionType.CANCELLATION, DisruptionReason.OTHER, DisruptionSeverity.HIGH, 0, "Engine inspection after bird strike", null);

        List<DisruptedPassengerManifestItem> manifest = service.getAffectedPassengerManifest(d.id());
        assertNotNull(manifest);
        assertEquals(2, manifest.size());

        DisruptedPassengerManifestItem item1 = manifest.stream().filter(m -> m.pnr().equals("P17-09A")).findFirst().orElseThrow();
        assertEquals(resId1, item1.reservationId());
        assertEquals("CONFIRMED", item1.reservationStatus());
        assertEquals("SUCCESS", item1.paymentStatus());
        assertEquals("AI-109", item1.flightNumber());
        assertNotNull(item1.passengerName());
    }

    @Test @Order(10)
    void test10_searchDisruptions() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        DisruptionService service = DisruptionService.getInstance();
        List<FlightDisruption> results = service.searchDisruptions("DEL", null);
        assertNotNull(results);
        assertTrue(results.size() >= 3, "Should return multiple disruptions matching DEL");

        List<FlightDisruption> resolvedOnly = service.searchDisruptions(null, DisruptionStatus.RESOLVED);
        assertNotNull(resolvedOnly);
        assertTrue(resolvedOnly.stream().allMatch(x -> x.status() == DisruptionStatus.RESOLVED));
    }

    // =========================================================================
    // 11-20: REACCOMMODATION ENGINE TESTS (AUTO & MANUAL)
    // =========================================================================

    @Test @Order(11)
    void test11_searchEligibleAlternateFlights() throws Exception {
        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long disruptedFlightId = createFlightFixture(tenantA, "AI-201", "DEL", "BOM", "CANCELLED", 0);
        long alt1 = createFlightFixture(tenantA, "AI-202", "DEL", "BOM", "SCHEDULED", 25);
        long alt2 = createFlightFixture(tenantA, "AI-203", "DEL", "BOM", "ACTIVE", 10);
        long altOtherRoute = createFlightFixture(tenantA, "AI-204", "DEL", "BLR", "SCHEDULED", 40);

        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        List<RebookingFoundationService.AlternativeFlight> alternates = engine.searchEligibleAlternateFlights(disruptedFlightId);

        assertNotNull(alternates);
        assertTrue(alternates.stream().anyMatch(a -> a.flightId() == alt1));
        assertTrue(alternates.stream().anyMatch(a -> a.flightId() == alt2));
        assertFalse(alternates.stream().anyMatch(a -> a.flightId() == altOtherRoute), "Should only match same route (DEL -> BOM)");
    }

    @Test @Order(12)
    void test12_proposeReaccommodation() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-205", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-12", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-206", "DEL", "BOM", "SCHEDULED", 30);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Cancelled", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation proposed = engine.proposeReaccommodation(d.id(), origRes, altFlight, "3B", ReaccommodationType.MANUAL, "Candidate proposal");

        assertNotNull(proposed);
        assertEquals(ReaccommodationStatus.OFFERED, proposed.status());
        assertEquals("P17-12", proposed.originalPnr());
        assertEquals(NotificationStatus.PENDING, proposed.notificationStatus());
        assertEquals("3B", proposed.newSeatNumber());
    }

    @Test @Order(13)
    void test13_singleManualReaccommodationSuccess() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-207", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-13", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-208", "DEL", "BOM", "SCHEDULED", 20);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.MECHANICAL, DisruptionSeverity.HIGH, 0, "Maintenance issue", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation reacc = engine.reaccommodatePassenger(
            d.id(),
            origRes,
            altFlight,
            "4C",
            ReaccommodationType.MANUAL,
            "Rebooked onto morning replacement flight"
        );

        assertNotNull(reacc);
        assertEquals(ReaccommodationStatus.REBOOKED, reacc.status());
        assertTrue(reacc.isSuccessful());
        assertNotNull(reacc.newPnr());
        assertTrue(reacc.newPnr().startsWith("RBK-"));
        assertEquals("4C", reacc.newSeatNumber());

        // Verify alternate flight available seats was decremented by 1
        assertEquals(19, getAvailableSeats(altFlight));

        // Verify original reservation was marked CANCELLED
        assertEquals("CANCELLED", getReservationStatus(origRes));
    }

    @Test @Order(14)
    void test14_reaccommodationNoDoubleCharging() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-209", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-14", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-210", "DEL", "BOM", "SCHEDULED", 15);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.CREW, DisruptionSeverity.MEDIUM, 0, "Crew expired", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation reacc = engine.reaccommodatePassenger(d.id(), origRes, altFlight, "5A", ReaccommodationType.MANUAL, "Zero-charge recovery");

        // Verify transfer payment in database
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT payment_method, payment_status FROM payments WHERE reservation_id = ?")) {
            stmt.setLong(1, reacc.newReservationId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "Payment record must exist for rebooked reservation");
                assertEquals("REBOOK_TRANSFER", rs.getString("payment_method"), "Must use internal REBOOK_TRANSFER without customer re-charge");
                assertEquals("SUCCESS", rs.getString("payment_status"));
            }
        }
    }

    @Test @Order(15)
    void test15_reaccommodationPreservesPassengerIdentity() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-211", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-15", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-212", "DEL", "BOM", "SCHEDULED", 10);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Weather", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation reacc = engine.reaccommodatePassenger(d.id(), origRes, altFlight, "6D", ReaccommodationType.MANUAL, "Preserve identity");

        long origPassengerId = getReservationPassengerId(origRes);
        long newPassengerId = getReservationPassengerId(reacc.newReservationId());
        assertEquals(origPassengerId, newPassengerId, "Rebooked reservation must preserve the original passenger ID");
    }

    @Test @Order(16)
    void test16_reaccommodationFailsWhenNoSeatsAvailable() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-213", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-16", "CONFIRMED");
        long fullFlight = createFlightFixture(tenantA, "AI-214", "DEL", "BOM", "SCHEDULED", 0); // 0 seats

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Weather", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            engine.reaccommodatePassenger(d.id(), origRes, fullFlight, "1A", ReaccommodationType.MANUAL, "Attempt onto full flight")
        );
        assertTrue(ex.getMessage().contains("seats"), "Should fail due to zero available seats");

        // Verify original reservation remains CONFIRMED (not cancelled)
        assertEquals("CONFIRMED", getReservationStatus(origRes));
    }

    @Test @Order(17)
    void test17_reaccommodationLifecycleTransitions() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-215", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-17", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-216", "DEL", "BOM", "SCHEDULED", 10);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Weather", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation proposed = engine.proposeReaccommodation(d.id(), origRes, altFlight, "2A", ReaccommodationType.MANUAL, "Proposed");
        assertEquals(ReaccommodationStatus.OFFERED, proposed.status());

        PassengerReaccommodation accepted = engine.transitionStatus(proposed.id(), ReaccommodationStatus.ACCEPTED, "Passenger accepted proposed flight");
        assertEquals(ReaccommodationStatus.ACCEPTED, accepted.status());

        PassengerReaccommodation declined = engine.transitionStatus(accepted.id(), ReaccommodationStatus.DECLINED, "Passenger changed mind and declined");
        assertEquals(ReaccommodationStatus.DECLINED, declined.status());
    }

    @Test @Order(18)
    void test18_invalidReaccommodationTransition() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-217", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-18", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-218", "DEL", "BOM", "SCHEDULED", 10);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Weather", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation reacc = engine.reaccommodatePassenger(d.id(), origRes, altFlight, "1B", ReaccommodationType.MANUAL, "Rebooked");

        // REBOOKED is terminal, cannot transition back to OFFERED
        assertThrows(IllegalStateException.class, () ->
            engine.transitionStatus(reacc.id(), ReaccommodationStatus.OFFERED, "Illegal revert")
        );
    }

    @Test @Order(19)
    void test19_updatePassengerNotificationStatus() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-219", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P17-19", "CONFIRMED");
        long altFlight = createFlightFixture(tenantA, "AI-220", "DEL", "BOM", "SCHEDULED", 10);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Weather", null);

        UserSession.login(1703, "irops_resagent", Role.RESERVATIONAGENT.name());
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        PassengerReaccommodation reacc = engine.reaccommodatePassenger(d.id(), origRes, altFlight, "1C", ReaccommodationType.MANUAL, "Rebooked");

        assertEquals(NotificationStatus.PENDING, reacc.notificationStatus());

        PassengerReaccommodation sent = engine.updateNotificationStatus(reacc.id(), NotificationStatus.SENT);
        assertEquals(NotificationStatus.SENT, sent.notificationStatus());

        PassengerReaccommodation ack = engine.updateNotificationStatus(reacc.id(), NotificationStatus.ACKNOWLEDGED);
        assertEquals(NotificationStatus.ACKNOWLEDGED, ack.notificationStatus());
    }

    @Test @Order(20)
    void test20_batchAutoReaccommodationAllPassengers() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "AI-221", "DEL", "BOM", "CANCELLED", 0);
        createReservationFixture(tenantA, origFlight, "P17-20A", "CONFIRMED");
        createReservationFixture(tenantA, origFlight, "P17-20B", "CONFIRMED");
        createReservationFixture(tenantA, origFlight, "P17-20C", "CONFIRMED");

        // Alternate flights with capacity
        createFlightFixture(tenantA, "AI-222", "DEL", "BOM", "SCHEDULED", 2);
        createFlightFixture(tenantA, "AI-223", "DEL", "BOM", "SCHEDULED", 5);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(origFlight, DisruptionType.CANCELLATION, DisruptionReason.MECHANICAL, DisruptionSeverity.CRITICAL, 0, "Hydraulic failure", null);

        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        ReaccommodationEngine.BatchReaccommodationResult result = engine.autoReaccommodateAll(d.id(), "Batch automated re-accommodation test");

        assertNotNull(result);
        assertEquals(3, result.totalCandidates());
        assertEquals(3, result.rebookedCount(), "All 3 passengers should be successfully rebooked across alternate flights");
        assertEquals(0, result.failedCount());
        assertEquals(3, result.items().size());
    }

    // =========================================================================
    // 21-27: SERVICE RECOVERY VOUCHERS TESTS
    // =========================================================================

    @Test @Order(21)
    void test21_issueMealVoucher() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-301", "DEL", "BOM", "DELAYED", 50);
        long resId = createReservationFixture(tenantA, flight, "P17-21", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.DELAY, DisruptionReason.ATC, DisruptionSeverity.MEDIUM, 180, "Delayed 3 hours", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = vService.issueVoucher(
            d.id(),
            passengerId,
            resId,
            VoucherType.MEAL,
            new BigDecimal("25.00"),
            "USD",
            "Lunch refreshment voucher"
        );

        assertNotNull(voucher);
        assertTrue(voucher.voucherReference().startsWith("VCH-"));
        assertEquals(VoucherType.MEAL, voucher.type());
        assertEquals(new BigDecimal("25.00"), voucher.amount());
        assertEquals("USD", voucher.currency());
        assertEquals(VoucherStatus.ISSUED, voucher.status());
        assertTrue(voucher.isActive());
    }

    @Test @Order(22)
    void test22_issueHotelVoucher() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-302", "DEL", "BOM", "CANCELLED", 0);
        long resId = createReservationFixture(tenantA, flight, "P17-22", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.CANCELLATION, DisruptionReason.AIRPORT, DisruptionSeverity.CRITICAL, 0, "Overnight stay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = vService.issueVoucher(
            d.id(),
            passengerId,
            resId,
            VoucherType.HOTEL,
            new BigDecimal("150.00"),
            "USD",
            "Overnight transit hotel room"
        );

        assertNotNull(voucher);
        assertEquals(VoucherType.HOTEL, voucher.type());
        assertEquals(new BigDecimal("150.00"), voucher.amount());
    }

    @Test @Order(23)
    void test23_issueTransportAndCreditVouchers() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-303", "DEL", "BOM", "DELAYED", 50);
        long resId = createReservationFixture(tenantA, flight, "P17-23", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.DELAY, DisruptionReason.MECHANICAL, DisruptionSeverity.HIGH, 240, "Heavy delay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher trans = vService.issueVoucher(d.id(), passengerId, resId, VoucherType.TRANSPORT, new BigDecimal("40.00"), "USD", "Airport taxi transfer");
        DisruptionVoucher credit = vService.issueVoucher(d.id(), passengerId, resId, VoucherType.OTHER, new BigDecimal("100.00"), "USD", "Service apology flight credit");

        assertEquals(VoucherType.TRANSPORT, trans.type());
        assertEquals(VoucherType.OTHER, credit.type());
    }

    @Test @Order(24)
    void test24_redeemVoucher() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-304", "DEL", "BOM", "DELAYED", 50);
        long resId = createReservationFixture(tenantA, flight, "P17-24", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.MEDIUM, 120, "Delay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = vService.issueVoucher(d.id(), passengerId, resId, VoucherType.MEAL, new BigDecimal("30.00"), "USD", "Dinner");

        DisruptionVoucher redeemed = vService.redeemVoucher(voucher.id(), "Redeemed at Terminal 3 Food Court");
        assertEquals(VoucherStatus.REDEEMED, redeemed.status());
        assertNotNull(redeemed.redeemedAt());
        assertTrue(redeemed.isTerminal());
    }

    @Test @Order(25)
    void test25_cancelVoucher() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-305", "DEL", "BOM", "DELAYED", 50);
        long resId = createReservationFixture(tenantA, flight, "P17-25", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.MEDIUM, 120, "Delay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = vService.issueVoucher(d.id(), passengerId, resId, VoucherType.MEAL, new BigDecimal("20.00"), "USD", "Issued in error");

        DisruptionVoucher cancelled = vService.cancelVoucher(voucher.id(), "Passenger took immediate refund");
        assertEquals(VoucherStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.isTerminal());
    }

    @Test @Order(26)
    void test26_preventRedemptionOfTerminalVoucher() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-306", "DEL", "BOM", "DELAYED", 50);
        long resId = createReservationFixture(tenantA, flight, "P17-26", "CONFIRMED");
        long passengerId = getReservationPassengerId(resId);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption d = dService.declareDisruption(flight, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.MEDIUM, 120, "Delay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = vService.issueVoucher(d.id(), passengerId, resId, VoucherType.MEAL, new BigDecimal("20.00"), "USD", "Meal");
        vService.redeemVoucher(voucher.id(), "First redemption");

        // Second redemption attempt must fail
        assertThrows(IllegalStateException.class, () ->
            vService.redeemVoucher(voucher.id(), "Duplicate redemption attempt")
        );
    }

    @Test @Order(27)
    void test27_searchVouchers() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        List<DisruptionVoucher> results = vService.searchVouchers("VCH", null, null);
        assertNotNull(results);
        assertTrue(results.size() >= 3, "Should find multiple vouchers matching query");

        List<DisruptionVoucher> meals = vService.searchVouchers(null, null, VoucherType.MEAL);
        assertNotNull(meals);
        assertTrue(meals.stream().allMatch(v -> v.type() == VoucherType.MEAL));
    }

    // =========================================================================
    // 28-34: RBAC AUTHORIZATION & MULTI-TENANT ISOLATION TESTS
    // =========================================================================

    @Test @Order(28)
    void test28_unauthorizedRoleCannotDeclareDisruption() throws Exception {
        UserSession.login(1705, "irops_unauth", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long flight = createFlightFixture(tenantA, "AI-401", "DEL", "BOM", "SCHEDULED", 50);
        DisruptionService service = DisruptionService.getInstance();

        assertThrows(AccessDeniedException.class, () ->
            service.declareDisruption(flight, DisruptionType.CANCELLATION, DisruptionReason.SECURITY, DisruptionSeverity.HIGH, 0, "Unauthorized attempt", null)
        );
    }

    @Test @Order(29)
    void test29_unauthorizedRoleCannotReaccommodate() throws Exception {
        UserSession.login(1705, "irops_unauth", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();
        assertThrows(AccessDeniedException.class, () ->
            engine.reaccommodatePassenger(1L, 1L, 2L, "1A", ReaccommodationType.MANUAL, "Unauthorized")
        );
    }

    @Test @Order(30)
    void test30_unauthorizedRoleCannotIssueVouchers() throws Exception {
        UserSession.login(1705, "irops_unauth", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        DisruptionVoucherService service = DisruptionVoucherService.getInstance();
        assertThrows(AccessDeniedException.class, () ->
            service.issueVoucher(1L, 1L, 1L, VoucherType.MEAL, new BigDecimal("25.00"), "USD", "Unauthorized")
        );
    }

    @Test @Order(31)
    void test31_auditorHasReadOnlyAccess() throws Exception {
        UserSession.login(1704, "irops_auditor", Role.AUDITOR.name());
        TenantContext.setCurrentTenant(tenantA);

        DisruptionService dService = DisruptionService.getInstance();
        // Auditor CAN read disruptions
        List<FlightDisruption> list = dService.searchDisruptions(null, null);
        assertNotNull(list);

        // Auditor CANNOT declare disruption
        assertThrows(AccessDeniedException.class, () ->
            dService.declareDisruption(1L, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.LOW, 10, "Auditor mutation attempt", null)
        );

        // Auditor CANNOT reaccommodate
        ReaccommodationEngine rEngine = ReaccommodationEngine.getInstance();
        assertThrows(AccessDeniedException.class, () ->
            rEngine.reaccommodatePassenger(1L, 1L, 2L, "1A", ReaccommodationType.MANUAL, "Auditor reaccommodation")
        );

        // Auditor CANNOT issue voucher
        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        assertThrows(AccessDeniedException.class, () ->
            vService.issueVoucher(1L, 1L, 1L, VoucherType.MEAL, new BigDecimal("10.00"), "USD", "Auditor voucher")
        );
    }

    @Test @Order(32)
    void test32_crossTenantDisruptionIsolation() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long fltA = createFlightFixture(tenantA, "TA-101", "DEL", "BOM", "SCHEDULED", 50);
        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption dA = dService.declareDisruption(fltA, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Tenant A Disruption", null);

        // Switch to Tenant B
        TenantContext.setCurrentTenant(tenantB);

        // Tenant B cannot find Tenant A's disruption by ID
        Optional<FlightDisruption> lookup = dService.findById(dA.id());
        assertTrue(lookup.isEmpty(), "Cross-tenant disruption lookup must return empty");

        // Tenant B cannot resolve Tenant A's disruption
        assertThrows(Exception.class, () ->
            dService.resolveDisruption(dA.id(), "Cross-tenant resolution attempt")
        );
    }

    @Test @Order(33)
    void test33_crossTenantReaccommodationIsolation() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());

        // Create flight and reservation in Tenant A
        TenantContext.setCurrentTenant(tenantA);
        long fltA = createFlightFixture(tenantA, "TA-201", "DEL", "BOM", "CANCELLED", 0);
        long resA = createReservationFixture(tenantA, fltA, "TA-PNR", "CONFIRMED");
        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption dA = dService.declareDisruption(fltA, DisruptionType.CANCELLATION, DisruptionReason.MECHANICAL, DisruptionSeverity.HIGH, 0, "Disrupted", null);

        // Create alternate flight in Tenant B
        TenantContext.setCurrentTenant(tenantB);
        long fltB = createFlightFixture(tenantB, "TB-202", "DEL", "BOM", "SCHEDULED", 30);

        // Attempting to rebook across tenant boundaries must be rejected
        TenantContext.setCurrentTenant(tenantA);
        ReaccommodationEngine engine = ReaccommodationEngine.getInstance();

        assertThrows(AccessDeniedException.class, () ->
            engine.reaccommodatePassenger(dA.id(), resA, fltB, "1A", ReaccommodationType.MANUAL, "Cross-tenant rebooking attempt")
        );
    }

    @Test @Order(34)
    void test34_crossTenantVoucherIsolation() throws Exception {
        UserSession.login(1702, "irops_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long fltA = createFlightFixture(tenantA, "TA-301", "DEL", "BOM", "DELAYED", 50);
        long resA = createReservationFixture(tenantA, fltA, "TA-VCH-PNR", "CONFIRMED");
        long passA = getReservationPassengerId(resA);

        DisruptionService dService = DisruptionService.getInstance();
        FlightDisruption dA = dService.declareDisruption(fltA, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.MEDIUM, 90, "Delay", null);

        DisruptionVoucherService vService = DisruptionVoucherService.getInstance();
        DisruptionVoucher vA = vService.issueVoucher(dA.id(), passA, resA, VoucherType.MEAL, new BigDecimal("25.00"), "USD", "Tenant A Voucher");

        // Switch to Tenant B
        TenantContext.setCurrentTenant(tenantB);

        Optional<DisruptionVoucher> lookup = vService.findById(vA.id());
        assertTrue(lookup.isEmpty(), "Cross-tenant voucher lookup must return empty");

        assertThrows(Exception.class, () ->
            vService.redeemVoucher(vA.id(), "Cross-tenant redeem attempt")
        );
    }

    // =========================================================================
    // 35-37: AUDIT & REPORTING INTEGRATION TESTS
    // =========================================================================

    @Test @Order(35)
    void test35_auditTrailVerification() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // Verify that audit log recorded events for actions performed in earlier tests
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT DISTINCT action FROM audit_logs WHERE organization_id = ? AND action IN (?, ?, ?, ?)")) {
            stmt.setLong(1, tenantA.organizationId());
            stmt.setString(2, AuditAction.DISRUPTION_DECLARED.name());
            stmt.setString(3, AuditAction.REACCOMMODATION_COMPLETED.name());
            stmt.setString(4, AuditAction.VOUCHER_ISSUED.name());
            stmt.setString(5, AuditAction.VOUCHER_REDEEMED.name());
            try (ResultSet rs = stmt.executeQuery()) {
                int auditMatches = 0;
                while (rs.next()) {
                    auditMatches++;
                }
                assertTrue(auditMatches >= 3, "Audit logs must record DISRUPTION_DECLARED, REACCOMMODATION_COMPLETED, VOUCHER_ISSUED/REDEEMED");
            }
        }
    }

    @Test @Order(36)
    void test36_operationalReportIROPSCategory() throws Exception {
        UserSession.login(1701, "irops_admin", Role.SUPERADMIN.name());
        TenantContext.setCurrentTenant(tenantA);

        OperationalReportService.ReportFilter filter = new OperationalReportService.ReportFilter(
            OperationalReportService.Category.IROPS_DISRUPTIONS,
            null,
            null,
            null,
            null
        );

        OperationalReportService.ReportData data = OperationalReportService.generateReport(filter);
        assertNotNull(data);
        assertEquals(OperationalReportService.Category.IROPS_DISRUPTIONS, data.category());

        // Validate KPIs
        assertNotNull(data.summaryMetrics());
        assertTrue(data.summaryMetrics().containsKey("Total Disruptions"));
        assertTrue(data.summaryMetrics().containsKey("Active Disruptions"));
        assertTrue(data.summaryMetrics().containsKey("Impacted Passengers"));
        assertTrue(data.summaryMetrics().containsKey("Total Reaccommodations"));
        assertTrue(data.summaryMetrics().containsKey("Vouchers Issued"));

        // Validate tabular records
        assertNotNull(data.tableModel());
        assertTrue(data.tableModel().getRowCount() > 0, "IROPS report must contain disruption records");
    }

    @Test @Order(37)
    void test37_dashboardAlertsDisruptionIntegration() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // Verify dashboard queries compute active disruptions count without syntax or table errors
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*), COALESCE(SUM(passenger_impact_count), 0) FROM flight_disruptions WHERE organization_id = ? AND status NOT IN ('RESOLVED', 'CANCELLED')")) {
            stmt.setLong(1, tenantA.organizationId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                int activeCount = rs.getInt(1);
                assertTrue(activeCount >= 0, "Active disruptions count must be non-negative");
            }
        }
    }

    // =========================================================================
    // FIXTURE HELPERS
    // =========================================================================

    private static long createFlightFixture(Tenant t, String fltNum, String src, String dest, String status, int seats) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO flights(organization_id, flight_number, airline, source, destination, departure_time, arrival_time, total_seats, available_seats, fare, status) " +
                 "VALUES(?, ?, 'AeroDesk Test', ?, ?, ?, ?, ?, ?, 250.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, t.organizationId());
            p.setString(2, fltNum);
            p.setString(3, src);
            p.setString(4, dest);
            p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now().plusHours(4)));
            p.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now().plusHours(6)));
            p.setInt(7, Math.max(seats, 50));
            p.setInt(8, seats);
            p.setString(9, status);
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                createdFlightIds.add(id);
                return id;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger seatCounter = new java.util.concurrent.atomic.AtomicInteger(1);

    private static long createReservationFixture(Tenant t, long flightId, String pnr, String status) throws Exception {
        long passId = createPassengerFixture(t);
        String seat = ((seatCounter.incrementAndGet() % 120) + 1) + "B";
        try (Connection c = DBConnection.getConnection()) {
            long resId;
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO reservations(organization_id, pnr, passenger_id, flight_id, seat_number, fare, status) " +
                    "VALUES(?, ?, ?, ?, ?, 250.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, t.organizationId());
                p.setString(2, pnr);
                p.setLong(3, passId);
                p.setLong(4, flightId);
                p.setString(5, seat);
                p.setString(6, status);
                p.executeUpdate();
                try (ResultSet rs = p.getGeneratedKeys()) {
                    rs.next();
                    resId = rs.getLong(1);
                }
            }

            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO payments(organization_id, reservation_id, amount, payment_method, payment_status, transaction_reference) " +
                    "VALUES(?, ?, 250.00, 'CREDIT_CARD', 'SUCCESS', ?)")) {
                p.setLong(1, t.organizationId());
                p.setLong(2, resId);
                p.setString(3, "TX-" + pnr);
                p.executeUpdate();
            }

            return resId;
        }
    }

    private static long createPassengerFixture(Tenant t) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO passengers(organization_id, name, phone, email) " +
                 "VALUES(?, 'Traveler Test', '9876543210', ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, t.organizationId());
            p.setString(2, "pax_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                createdPassengerIds.add(id);
                return id;
            }
        }
    }

    private static long createOrganization(String code) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO organizations(org_code, org_name, contact_email) VALUES(?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, code);
            p.setString(2, "Test Tenant " + code);
            p.setString(3, code.toLowerCase() + "@example.com");
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                createdOrgIds.add(id);
                return id;
            }
        }
    }

    private static void createUser(long id, String name, String role) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO app_users(id,username,password_hash,role,active) VALUES(?,?, 'hash',?,TRUE) " +
                 "ON DUPLICATE KEY UPDATE role = VALUES(role), active = TRUE")) {
            p.setLong(1, id);
            p.setString(2, name);
            p.setString(3, role);
            p.executeUpdate();
        }
    }

    private static String getFlightStatus(long flightId) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT status FROM flights WHERE id = ?")) {
            p.setLong(1, flightId);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString("status");
                return null;
            }
        }
    }

    private static int getAvailableSeats(long flightId) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT available_seats FROM flights WHERE id = ?")) {
            p.setLong(1, flightId);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getInt("available_seats");
                return -1;
            }
        }
    }

    private static String getReservationStatus(long resId) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT status FROM reservations WHERE id = ?")) {
            p.setLong(1, resId);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getString("status");
                return null;
            }
        }
    }

    private static long getReservationPassengerId(long resId) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT passenger_id FROM reservations WHERE id = ?")) {
            p.setLong(1, resId);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return rs.getLong("passenger_id");
                return -1;
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

            String fltSub = "SELECT id FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR airline = 'AeroDesk Test'";
            String paxSub = "SELECT id FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE 'pax_%@example.com'";
            String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ") OR pnr LIKE 'P17-%'";
            String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")";
            String passSub = "SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")";
            String bagSub = "SELECT id FROM baggage WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")";
            String pirSub = "SELECT id FROM pir_reports WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ")";
            String claimSub = "SELECT id FROM claim_cases WHERE baggage_id IN (" + bagSub + ") OR pir_id IN (" + pirSub + ") OR organization_id IN (" + orgIds + ")";

            // 1. IROPS vouchers and reaccommodations
            executeScopedDelete(s, "disruption_vouchers", "DELETE FROM disruption_vouchers WHERE passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ") OR voucher_reference LIKE 'VCH%'", failures);
            executeScopedDelete(s, "passenger_reaccommodations", "DELETE FROM passenger_reaccommodations WHERE original_flight_id IN (" + fltSub + ") OR new_flight_id IN (" + fltSub + ") OR original_reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "flight_disruptions", "DELETE FROM flight_disruptions WHERE flight_id IN (" + fltSub + ") OR organization_id IN (" + orgIds + ")", failures);

            // 2. Claims & PIR
            executeScopedDelete(s, "claim_events", "DELETE FROM claim_events WHERE (entity_type = 'CLAIM' AND entity_id IN (" + claimSub + ")) OR (entity_type = 'PIR' AND entity_id IN (" + pirSub + ")) OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "claim_settlements", "DELETE FROM claim_settlements WHERE claim_id IN (" + claimSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "claim_cases", "DELETE FROM claim_cases WHERE baggage_id IN (" + bagSub + ") OR pir_id IN (" + pirSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "pir_reports", "DELETE FROM pir_reports WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ")", failures);

            // 3. Baggage
            executeScopedDelete(s, "baggage_events", "DELETE FROM baggage_events WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "baggage", "DELETE FROM baggage WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ")", failures);

            // 4. Boarding passes & Checkins
            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE boarding_pass_id IN (" + passSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);

            // 5. Cancellations & Payments
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ") OR transaction_reference LIKE 'TX-P17-%'", failures);

            // 6. Reservations
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ") OR pnr LIKE 'P17-%'", failures);

            // 7. Passengers
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE 'pax_%@example.com'", failures);

            // 8. Flights
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR airline = 'AeroDesk Test'", failures);

            // 9. Users & Audit logs
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id BETWEEN 1701 AND 1705", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE (user_id BETWEEN 1701 AND 1705) OR username LIKE 'irops_%' OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE (id BETWEEN 1701 AND 1705) OR username LIKE 'irops_%'", failures);

            // 10. Partner organization
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE id IN (" + orgIds + ") OR org_code = 'P17B'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
            createdOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase17IROPSOperationsTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    private static void runMigrationScript(String file) throws Exception {
        String sql = Files.readString(Path.of("..", "database", file))
                .replace("project4", TEST_DB)
                .replaceAll("(?m)^--.*(?:\\R|$)", "");
        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    s.execute(stmt);
                }
            }
        }
    }

    private static boolean hasActiveSeatColumn() throws Exception {
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
