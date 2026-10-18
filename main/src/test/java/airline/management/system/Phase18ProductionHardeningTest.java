package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditService;
import airline.management.system.operations.baggage.BaggageEventType;
import airline.management.system.operations.baggage.BaggageMigration;
import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.baggage.BaggageType;
import airline.management.system.operations.boarding.BoardingService;
import airline.management.system.operations.boarding.BoardingValidationResult;
import airline.management.system.operations.checkin.CheckinPassengerDetails;
import airline.management.system.operations.checkin.CheckinResult;
import airline.management.system.operations.checkin.CheckinService;
import airline.management.system.operations.checkin.CheckinStatus;
import airline.management.system.operations.claim.ClaimCase;
import airline.management.system.operations.claim.ClaimService;
import airline.management.system.operations.claim.ClaimSettlementRecord;
import airline.management.system.operations.claim.ClaimStatus;
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
import airline.management.system.operations.pir.PIRRecord;
import airline.management.system.operations.pir.PIRService;
import airline.management.system.operations.pir.PIRStatus;
import airline.management.system.operations.pir.PIRType;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 18 Production Hardening & System Stability Test Suite.
 * Validates:
 * 1. Concurrency Safety:
 *    - Concurrent seat reservation conflict resolution.
 *    - Concurrent passenger check-in duplicate prevention.
 *    - Concurrent Property Irregularity Report (PIR) duplicate prevention.
 *    - Concurrent claim settlement duplicate prevention.
 *    - Concurrent passenger re-accommodation race safety.
 *    - Concurrent alternate flight seat allocation atomicity.
 *    - Concurrent voucher redemption prevention.
 * 2. Multi-Tenant Data Isolation.
 * 3. Role-Based Access Control (RBAC) Security & Auditor Read-Only Enforcement.
 * 4. End-to-End Operational Journeys (A: Normal, B: Missing Baggage/Claim, C: IROPS/Recovery, D: Partition).
 * 5. Database Resource Safety and Connection Cleanliness.
 *
 * All tests execute exclusively on project4test; project4 remains 100% untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase18ProductionHardeningTest {

    private static final String TEST_DB = "project4test";

    private static Tenant tenantA;
    private static Tenant tenantB;

    private static final AtomicInteger seatCounter = new AtomicInteger(100);

    private static final Set<Long> testFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> testPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> testReservationIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> testBaggageIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> testUserIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> testUsernames = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> testOrgIds = Collections.synchronizedSet(new LinkedHashSet<>());

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

        cleanupTestFixtures();

        tenantA = new Tenant(1L, "AD", "AeroDesk", true);
        long bOrgId = createOrganization("P18B");
        tenantB = new Tenant(bOrgId, "P18B", "Phase 18 Tenant B", true);

        createUser(1801, "p18_admin", Role.SUPERADMIN.name());
        createUser(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        createUser(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
        createUser(1804, "p18_checkin", Role.CHECKINAGENT.name());
        createUser(1805, "p18_gate", Role.GATEAGENT.name());
        createUser(1806, "p18_finance", Role.FINANCEOFFICER.name());
        createUser(1807, "p18_auditor", Role.AUDITOR.name());

        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(1801, "p18_admin", Role.SUPERADMIN.name());
    }

    @AfterAll
    static void cleanup() throws Exception {
        TenantContext.resetSession();
        UserSession.logout();
        cleanupTestFixtures();
    }

    // =========================================================================
    // STEP 4: CONCURRENCY TESTING (7 SCENARIOS)
    // =========================================================================

    @Test @Order(1)
    void test01_concurrentSeatReservation() throws Exception {
        UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "CR-101", "DEL", "BOM", "SCHEDULED", 10);
        long pass1 = createPassengerFixture(tenantA);
        long pass2 = createPassengerFixture(tenantA);
        String contestSeat = "14A";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReservationService.book(flightId, pass1, contestSeat, "Card");
        };

        Callable<String> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReservationService.book(flightId, pass2, contestSeat, "UPI");
        };

        Future<String> f1 = pool.submit(task1);
        Future<String> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown(); // trigger both simultaneously

        int successes = 0;
        int failures = 0;

        try {
            String pnr1 = f1.get();
            if (pnr1 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            String pnr2 = f2.get();
            if (pnr2 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one concurrent booking must succeed for seat " + contestSeat);
        assertEquals(1, failures, "The competing concurrent booking must fail");

        // Verify available seats was decremented by exactly 1 (from 10 to 9)
        assertEquals(9, getAvailableSeats(flightId), "Available seats must decrement by exactly 1");

        // Verify reservations table has exactly 1 confirmed record for this seat
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM reservations WHERE flight_id = ? AND seat_number = ? AND status = 'CONFIRMED'")) {
            stmt.setLong(1, flightId);
            stmt.setString(2, contestSeat);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Only one active reservation can exist for seat " + contestSeat);
            }
        }
    }

    @Test @Order(2)
    void test02_concurrentPassengerCheckin() throws Exception {
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long flightId = createFlightFixture(tenantA, "CR-102", "DEL", "BOM", "SCHEDULED", 20);
        String pnr = "P18-CKIN";
        createReservationFixture(tenantA, flightId, pnr, "CONFIRMED", "15B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<CheckinResult> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
            return CheckinService.getInstance().processCheckin(pnr, "15B");
        };

        Callable<CheckinResult> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
            return CheckinService.getInstance().processCheckin(pnr, "15B");
        };

        Future<CheckinResult> f1 = pool.submit(task1);
        Future<CheckinResult> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        CheckinResult r1 = f1.get();
        CheckinResult r2 = f2.get();
        pool.shutdown();

        int successCount = (r1.success() ? 1 : 0) + (r2.success() ? 1 : 0);
        assertEquals(1, successCount, "Exactly one concurrent check-in must succeed");

        // Verify the other was rejected
        CheckinResult failed = r1.success() ? r2 : r1;
        assertFalse(failed.success(), "Competing checkin attempt must fail");
        assertTrue(failed.status() == CheckinStatus.CHECKED_IN || failed.status() == CheckinStatus.INELIGIBLE_STATUS);

        // Verify exactly one checkin record in database
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM checkins c JOIN reservations r ON r.id = c.reservation_id WHERE r.pnr = ?")) {
            stmt.setString(1, pnr);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Exactly 1 check-in record must exist for " + pnr);
            }
        }
    }

    @Test @Order(3)
    void test03_concurrentPIRCreation() throws Exception {
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long bagId = createBaggageFixture(tenantA, "DELAYED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<PIRRecord> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
            return PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Bag delayed at carousel");
        };

        Callable<PIRRecord> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
            return PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Duplicate counter report");
        };

        Future<PIRRecord> f1 = pool.submit(task1);
        Future<PIRRecord> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int failures = 0;

        try {
            PIRRecord p1 = f1.get();
            if (p1 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            PIRRecord p2 = f2.get();
            if (p2 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one concurrent PIR creation must succeed for the same baggage tag");
        assertEquals(1, failures, "Competing PIR creation must be rejected as duplicate active PIR");

        // Verify exactly one active PIR exists in DB
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM pir_reports WHERE baggage_id = ? AND status != 'CLOSED'")) {
            stmt.setLong(1, bagId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Only 1 active PIR record must exist for baggage #" + bagId);
            }
        }
    }

    @Test @Order(4)
    void test04_concurrentClaimSettlement() throws Exception {
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        TenantContext.setCurrentTenant(tenantA);

        long bagId = createBaggageFixture(tenantA, "MISSING");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.MISSING_BAGGAGE, "DEL", "Lost baggage report");

        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        ClaimCase claim = ClaimService.getInstance().createClaim(
            pir.id(),
            new BigDecimal("600.00"),
            "USD",
            "Contents declared lost"
        );

        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        ClaimService.getInstance().reviewClaim(
            claim.id(),
            ClaimStatus.UNDER_REVIEW,
            null,
            "Auditing passenger claim"
        );
        ClaimCase approved = ClaimService.getInstance().reviewClaim(
            claim.id(),
            ClaimStatus.APPROVED,
            new BigDecimal("500.00"),
            "Approved standard maximum compensation"
        );
        assertEquals(ClaimStatus.APPROVED, approved.status());

        UserSession.login(1806, "p18_finance", Role.FINANCEOFFICER.name());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<ClaimSettlementRecord> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1806, "p18_finance", Role.FINANCEOFFICER.name());
            return ClaimService.getInstance().settleClaim(approved.id(), "BANK_TRANSFER", "Disbursement A");
        };

        Callable<ClaimSettlementRecord> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1806, "p18_finance", Role.FINANCEOFFICER.name());
            return ClaimService.getInstance().settleClaim(approved.id(), "BANK_TRANSFER", "Disbursement B");
        };

        Future<ClaimSettlementRecord> f1 = pool.submit(task1);
        Future<ClaimSettlementRecord> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int failures = 0;

        try {
            ClaimSettlementRecord s1 = f1.get();
            if (s1 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            ClaimSettlementRecord s2 = f2.get();
            if (s2 != null) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one concurrent claim settlement must succeed");
        assertEquals(1, failures, "Competing settlement attempt must be rejected");

        // Verify only 1 settlement record exists in claim_settlements table
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM claim_settlements WHERE claim_id = ?")) {
            stmt.setLong(1, approved.id());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Exactly 1 settlement record must exist");
            }
        }
    }

    @Test @Order(5)
    void test05_concurrentPassengerReaccommodation() throws Exception {
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "CR-105A", "DEL", "BOM", "CANCELLED", 0);
        long origRes = createReservationFixture(tenantA, origFlight, "P18-R5", "CONFIRMED", "18A");
        long altFlight = createFlightFixture(tenantA, "CR-105B", "DEL", "BOM", "SCHEDULED", 20);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(
            origFlight, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Grounded", null
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<PassengerReaccommodation> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReaccommodationEngine.getInstance().reaccommodatePassenger(
                d.id(), origRes, altFlight, "4A", ReaccommodationType.MANUAL, "Rebooking thread 1"
            );
        };

        Callable<PassengerReaccommodation> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReaccommodationEngine.getInstance().reaccommodatePassenger(
                d.id(), origRes, altFlight, "4B", ReaccommodationType.MANUAL, "Rebooking thread 2"
            );
        };

        Future<PassengerReaccommodation> f1 = pool.submit(task1);
        Future<PassengerReaccommodation> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int failures = 0;

        try {
            PassengerReaccommodation r1 = f1.get();
            if (r1 != null && r1.isSuccessful()) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            PassengerReaccommodation r2 = f2.get();
            if (r2 != null && r2.isSuccessful()) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one concurrent reaccommodation must succeed for the passenger");
        assertEquals(1, failures, "Competing reaccommodation must fail because original reservation was cancelled");

        // Verify available seats on alternate flight decremented by exactly 1 (20 -> 19)
        assertEquals(19, getAvailableSeats(altFlight));
    }

    @Test @Order(6)
    void test06_concurrentAlternateSeatAllocation() throws Exception {
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "CR-106A", "DEL", "BOM", "CANCELLED", 0);
        long origRes1 = createReservationFixture(tenantA, origFlight, "P18-R6A", "CONFIRMED", "19A");
        long origRes2 = createReservationFixture(tenantA, origFlight, "P18-R6B", "CONFIRMED", "19B");
        long altFlight = createFlightFixture(tenantA, "CR-106B", "DEL", "BOM", "SCHEDULED", 20);
        String targetSeat = "2B";

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(
            origFlight, DisruptionType.CANCELLATION, DisruptionReason.MECHANICAL, DisruptionSeverity.HIGH, 0, "Engine fault", null
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<PassengerReaccommodation> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReaccommodationEngine.getInstance().reaccommodatePassenger(
                d.id(), origRes1, altFlight, targetSeat, ReaccommodationType.MANUAL, "Pax 1 to seat 2B"
            );
        };

        Callable<PassengerReaccommodation> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
            return ReaccommodationEngine.getInstance().reaccommodatePassenger(
                d.id(), origRes2, altFlight, targetSeat, ReaccommodationType.MANUAL, "Pax 2 to seat 2B"
            );
        };

        Future<PassengerReaccommodation> f1 = pool.submit(task1);
        Future<PassengerReaccommodation> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int failures = 0;

        try {
            PassengerReaccommodation r1 = f1.get();
            if (r1 != null && r1.isSuccessful()) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            PassengerReaccommodation r2 = f2.get();
            if (r2 != null && r2.isSuccessful()) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one passenger must be allocated seat " + targetSeat);
        assertEquals(1, failures, "The competing passenger must fail due to seat collision");

        // Verify only 1 reservation exists on altFlight with seat 2B
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM reservations WHERE flight_id = ? AND seat_number = ? AND status = 'CONFIRMED'")) {
            stmt.setLong(1, altFlight);
            stmt.setString(2, targetSeat);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Seat " + targetSeat + " must have exactly 1 confirmed reservation");
            }
        }
    }

    @Test @Order(7)
    void test07_concurrentVoucherRedemption() throws Exception {
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        TenantContext.setCurrentTenant(tenantA);

        long origFlight = createFlightFixture(tenantA, "CR-107", "DEL", "BOM", "DELAYED", 10);
        long origRes = createReservationFixture(tenantA, origFlight, "P18-R7", "CONFIRMED", "21A");
        long passId = getReservationPassengerId(origRes);

        DisruptionService service = DisruptionService.getInstance();
        FlightDisruption d = service.declareDisruption(
            origFlight, DisruptionType.DELAY, DisruptionReason.ATC, DisruptionSeverity.MEDIUM, 180, "ATC ground stop", null
        );

        DisruptionVoucherService voucherService = DisruptionVoucherService.getInstance();
        DisruptionVoucher voucher = voucherService.issueVoucher(
            d.id(), passId, origRes, VoucherType.MEAL, new BigDecimal("35.00"), "USD", "Dinner recovery"
        );
        assertEquals(VoucherStatus.ISSUED, voucher.status());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<DisruptionVoucher> task1 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
            return voucherService.redeemVoucher(voucher.id(), "Terminal Diner 1");
        };

        Callable<DisruptionVoucher> task2 = () -> {
            ready.countDown();
            start.await();
            TenantContext.setCurrentTenant(tenantA);
            UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
            return voucherService.redeemVoucher(voucher.id(), "Terminal Diner 2");
        };

        Future<DisruptionVoucher> f1 = pool.submit(task1);
        Future<DisruptionVoucher> f2 = pool.submit(task2);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        int successes = 0;
        int failures = 0;

        try {
            DisruptionVoucher v1 = f1.get();
            if (v1 != null && v1.status() == VoucherStatus.REDEEMED) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        try {
            DisruptionVoucher v2 = f2.get();
            if (v2 != null && v2.status() == VoucherStatus.REDEEMED) successes++;
        } catch (ExecutionException e) {
            failures++;
        }

        pool.shutdown();

        assertEquals(1, successes, "Exactly one concurrent voucher redemption must succeed");
        assertEquals(1, failures, "Competing redemption must be rejected");

        // Verify voucher status in database is REDEEMED
        DisruptionVoucher finalVoucher = voucherService.findById(voucher.id()).orElseThrow();
        assertEquals(VoucherStatus.REDEEMED, finalVoucher.status());
    }

    // =========================================================================
    // STEP 5: TENANT ISOLATION TESTS
    // =========================================================================

    @Test @Order(8)
    void test08_crossTenantDataAccessProhibited() throws Exception {
        UserSession.login(1801, "p18_admin", Role.SUPERADMIN.name());

        // Create resource in Tenant A
        TenantContext.setCurrentTenant(tenantA);
        long flightA = createFlightFixture(tenantA, "CR-108A", "DEL", "BOM", "SCHEDULED", 10);
        String pnrA = "P18-TNA";
        long resA = createReservationFixture(tenantA, flightA, pnrA, "CONFIRMED", "22A");
        long bagA = createBaggageFixture(tenantA, "CHECKED");

        // Switch to Tenant B
        TenantContext.setCurrentTenant(tenantB);

        // 1. Tenant B cannot read Tenant A reservation by PNR
        CheckinPassengerDetails details = CheckinService.getInstance().lookupReservation(pnrA);
        assertNull(details, "Tenant B must not see Tenant A reservation");

        // 2. Tenant B cannot check-in Tenant A reservation
        CheckinResult checkinRes = CheckinService.getInstance().processCheckin(pnrA, "22A");
        assertFalse(checkinRes.success(), "Tenant B cannot check in Tenant A passenger");

        // 3. Tenant B cannot read Tenant A baggage
        assertTrue(BaggageService.getInstance().searchBaggage(pnrA, null, null).isEmpty(), "Tenant B cannot find Tenant A baggage");

        // 4. Tenant B cannot book onto Tenant A flight
        long passB = createPassengerFixture(tenantB);
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.book(flightA, passB, "23A", "Card"),
            "Tenant B passenger cannot book Tenant A flight"
        );

        TenantContext.setCurrentTenant(tenantA);
    }

    // =========================================================================
    // STEP 6: RBAC SECURITY TESTS
    // =========================================================================

    @Test @Order(9)
    void test09_rbacAuditorStrictlyReadOnly() throws Exception {
        UserSession.login(1807, "p18_auditor", Role.AUDITOR.name());
        TenantContext.setCurrentTenant(tenantA);

        // Auditor must not be able to execute any operational mutations
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.book(1L, 1L, "1A", "Card"),
            "Auditor must not book reservations"
        );

        assertThrows(AccessDeniedException.class, () ->
            ReservationService.cancel("SOME-PNR"),
            "Auditor must not cancel reservations"
        );

        assertThrows(AccessDeniedException.class, () ->
            CheckinService.getInstance().processCheckin("SOME-PNR", "1A"),
            "Auditor must not perform check-in"
        );

        assertThrows(AccessDeniedException.class, () ->
            BaggageService.getInstance().registerBaggage("SOME-PNR", new BigDecimal("15.0"), BaggageType.CHECKED, "Audit attempt"),
            "Auditor must not check-in baggage"
        );

        assertThrows(AccessDeniedException.class, () ->
            PIRService.getInstance().createPIR(1L, PIRType.DELAYED_BAGGAGE, "DEL", "Audit attempt"),
            "Auditor must not create PIRs"
        );

        assertThrows(AccessDeniedException.class, () ->
            ClaimService.getInstance().settleClaim(1L, "BANK_TRANSFER", "Audit attempt"),
            "Auditor must not settle claims"
        );

        assertThrows(AccessDeniedException.class, () ->
            DisruptionService.getInstance().declareDisruption(1L, DisruptionType.DELAY, DisruptionReason.WEATHER, DisruptionSeverity.LOW, 10, "Audit", null),
            "Auditor must not declare disruptions"
        );

        assertThrows(AccessDeniedException.class, () ->
            ReaccommodationEngine.getInstance().reaccommodatePassenger(1L, 1L, 2L, "1A", ReaccommodationType.MANUAL, "Audit"),
            "Auditor must not reaccommodate passengers"
        );

        assertThrows(AccessDeniedException.class, () ->
            DisruptionVoucherService.getInstance().issueVoucher(1L, 1L, 1L, VoucherType.MEAL, new BigDecimal("20.00"), "USD", "Audit"),
            "Auditor must not issue vouchers"
        );
    }

    @Test @Order(10)
    void test10_rbacUnauthorizedMutationsDenied() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // CHECKINAGENT cannot declare disruptions
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        assertThrows(AccessDeniedException.class, () ->
            DisruptionService.getInstance().declareDisruption(1L, DisruptionType.CANCELLATION, DisruptionReason.WEATHER, DisruptionSeverity.HIGH, 0, "Unauthorized", null)
        );

        // CHECKINAGENT cannot settle claims
        assertThrows(AccessDeniedException.class, () ->
            ClaimService.getInstance().settleClaim(1L, "BANK_TRANSFER", "Unauthorized settlement")
        );

        // GATEAGENT cannot cancel reservations
        UserSession.login(1805, "p18_gate", Role.GATEAGENT.name());
        assertThrows(AccessDeniedException.class, () ->
            ReservationService.cancel("SOME-PNR")
        );

        // RESERVATIONAGENT cannot execute gate boarding
        UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
        assertThrows(AccessDeniedException.class, () ->
            BoardingService.getInstance().validateAndBoard("SOME-PNR", 1L)
        );
    }

    // =========================================================================
    // STEP 10: END-TO-END REGRESSION JOURNEYS
    // =========================================================================

    @Test @Order(11)
    void test11_journeyA_normalPassengerLifecycle() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // 1. Flight creation
        long flightId = createFlightFixture(tenantA, "JA-101", "DEL", "BOM", "SCHEDULED", 50);

        // 2. Reservation & Payment
        UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
        long passengerId = createPassengerFixture(tenantA);
        String pnr = ReservationService.book(flightId, passengerId, "16A", "Card");
        assertNotNull(pnr);

        // 3. Check-in
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        CheckinResult checkinResult = CheckinService.getInstance().processCheckin(pnr, "16A");
        assertTrue(checkinResult.success());
        assertNotNull(checkinResult.details().boardingPassNumber());

        // 4. Baggage Registration
        BaggageRecord bag = BaggageService.getInstance().registerBaggage(pnr, new BigDecimal("18.5"), BaggageType.CHECKED, "Normal luggage");
        assertNotNull(bag);
        assertEquals(BaggageStatus.ACCEPTED, bag.status());

        // 5. Gate Boarding
        UserSession.login(1805, "p18_gate", Role.GATEAGENT.name());
        BoardingValidationResult boardResult = BoardingService.getInstance().validateAndBoard(pnr, flightId);
        assertTrue(boardResult.valid(), "Passenger must be successfully boarded at gate");

        // Verify status in DB is BOARDED
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT c.status FROM checkins c JOIN reservations r ON r.id = c.reservation_id WHERE r.pnr = ?")) {
            stmt.setString(1, pnr);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("BOARDED", rs.getString("status"));
            }
        }
    }

    @Test @Order(12)
    void test12_journeyB_missingBaggageClaimSettlement() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // 1. Create baggage and transition to MISSING
        UserSession.login(1804, "p18_checkin", Role.CHECKINAGENT.name());
        long bagId = createBaggageFixture(tenantA, "MISSING");

        // 2. File PIR (OPEN)
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.MISSING_BAGGAGE, "BOM", "Passenger arrived, bag missing from hold");
        assertEquals(PIRStatus.OPEN, pir.status());

        // 3. Move PIR to UNDER_INVESTIGATION
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        PIRRecord underInv = PIRService.getInstance().updatePIRStatus(pir.id(), PIRStatus.UNDER_INVESTIGATION, "Baggage search query dispatched to origin");
        assertEquals(PIRStatus.UNDER_INVESTIGATION, underInv.status());

        // 4. File Claim
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        ClaimCase claim = ClaimService.getInstance().createClaim(
            pir.id(),
            new BigDecimal("750.00"),
            "INR",
            "Business attire and personal belongings"
        );
        assertEquals(ClaimStatus.SUBMITTED, claim.status());

        // 5. Review & Approve Claim
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        ClaimService.getInstance().reviewClaim(
            claim.id(),
            ClaimStatus.UNDER_REVIEW,
            null,
            "Auditing passenger claim receipts"
        );
        ClaimCase approved = ClaimService.getInstance().reviewClaim(
            claim.id(),
            ClaimStatus.APPROVED,
            new BigDecimal("600.00"),
            "Approved per airline domestic tariff liability limits"
        );
        assertEquals(ClaimStatus.APPROVED, approved.status());

        // 6. Settle Claim via Finance
        UserSession.login(1806, "p18_finance", Role.FINANCEOFFICER.name());
        ClaimSettlementRecord settlement = ClaimService.getInstance().settleClaim(
            approved.id(),
            "BANK_TRANSFER",
            "Authorized domestic lost luggage compensation"
        );
        assertNotNull(settlement);
        assertEquals(approved.id(), settlement.claimId());
        assertEquals(new BigDecimal("600.00"), settlement.amount());

        // 7. Verify full traceability Baggage -> PIR -> Claim -> Settlement
        ClaimCase finalClaim = ClaimService.getInstance().findById(claim.id()).orElseThrow();
        assertEquals(ClaimStatus.SETTLED, finalClaim.status());
        assertEquals(pir.id(), finalClaim.pirId());
        assertEquals(bagId, finalClaim.baggageId());
    }

    @Test @Order(13)
    void test13_journeyC_flightDisruptionReaccommodationRecovery() throws Exception {
        TenantContext.setCurrentTenant(tenantA);

        // 1. Create original flight with confirmed reservation
        long origFlight = createFlightFixture(tenantA, "JC-101", "DEL", "BOM", "SCHEDULED", 40);
        String pnr = "P18-DISR";
        long resId = createReservationFixture(tenantA, origFlight, pnr, "CONFIRMED", "10A");
        long passengerId = getReservationPassengerId(resId);

        // 2. Create alternative replacement flight
        long altFlight = createFlightFixture(tenantA, "JC-102", "DEL", "BOM", "SCHEDULED", 25);

        // 3. Declare Flight Cancellation Disruption
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        FlightDisruption disruption = DisruptionService.getInstance().declareDisruption(
            origFlight,
            DisruptionType.CANCELLATION,
            DisruptionReason.WEATHER,
            DisruptionSeverity.HIGH,
            0,
            "Dense winter fog grounded operations",
            "Emergency weather declaration"
        );
        assertEquals(DisruptionStatus.DECLARED, disruption.status());
        assertEquals("CANCELLED", getFlightStatus(origFlight));

        // 4. Reaccommodate passenger onto alternative flight
        UserSession.login(1803, "p18_resagent", Role.RESERVATIONAGENT.name());
        PassengerReaccommodation reacc = ReaccommodationEngine.getInstance().reaccommodatePassenger(
            disruption.id(),
            resId,
            altFlight,
            "5C",
            ReaccommodationType.MANUAL,
            "Rebooked onto afternoon flight"
        );
        assertNotNull(reacc);
        assertEquals(ReaccommodationStatus.REBOOKED, reacc.status());
        assertTrue(reacc.isSuccessful());
        assertNotNull(reacc.newPnr());

        // Verify zero-charge transfer payment
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT payment_method, payment_status FROM payments WHERE reservation_id = ?")) {
            stmt.setLong(1, reacc.newReservationId());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REBOOK_TRANSFER", rs.getString("payment_method"));
                assertEquals("SUCCESS", rs.getString("payment_status"));
            }
        }

        // 5. Update passenger notification status
        PassengerReaccommodation notifSent = ReaccommodationEngine.getInstance().updateNotificationStatus(
            reacc.id(), NotificationStatus.SENT
        );
        assertEquals(NotificationStatus.SENT, notifSent.notificationStatus());

        PassengerReaccommodation notifAck = ReaccommodationEngine.getInstance().updateNotificationStatus(
            reacc.id(), NotificationStatus.ACKNOWLEDGED
        );
        assertEquals(NotificationStatus.ACKNOWLEDGED, notifAck.notificationStatus());

        // 6. Issue and redeem recovery vouchers
        UserSession.login(1802, "p18_opsmgr", Role.OPERATIONSMANAGER.name());
        DisruptionVoucher mealVoucher = DisruptionVoucherService.getInstance().issueVoucher(
            disruption.id(), passengerId, resId, VoucherType.MEAL, new BigDecimal("25.00"), "USD", "Meal coupon"
        );
        assertEquals(VoucherStatus.ISSUED, mealVoucher.status());

        DisruptionVoucher hotelVoucher = DisruptionVoucherService.getInstance().issueVoucher(
            disruption.id(), passengerId, resId, VoucherType.HOTEL, new BigDecimal("120.00"), "USD", "Hotel stay"
        );
        assertEquals(VoucherStatus.ISSUED, hotelVoucher.status());

        DisruptionVoucher redeemed = DisruptionVoucherService.getInstance().redeemVoucher(mealVoucher.id(), "Airport Bistro");
        assertEquals(VoucherStatus.REDEEMED, redeemed.status());
    }

    @Test @Order(14)
    void test14_journeyD_crossTenantTotalDataPartition() throws Exception {
        UserSession.login(1801, "p18_admin", Role.SUPERADMIN.name());

        // Populate Tenant A
        TenantContext.setCurrentTenant(tenantA);
        long flightA = createFlightFixture(tenantA, "JD-101A", "DEL", "BOM", "SCHEDULED", 30);
        String pnrA = "P18-JDA";
        long resA = createReservationFixture(tenantA, flightA, pnrA, "CONFIRMED", "11B");
        FlightDisruption dA = DisruptionService.getInstance().declareDisruption(
            flightA, DisruptionType.DELAY, DisruptionReason.OPERATIONAL, DisruptionSeverity.LOW, 45, "Turnaround delay", null
        );

        // Populate Tenant B
        TenantContext.setCurrentTenant(tenantB);
        long flightB = createFlightFixture(tenantB, "JD-101B", "CCU", "BLR", "SCHEDULED", 30);
        String pnrB = "P18-JDB";
        long resB = createReservationFixture(tenantB, flightB, pnrB, "CONFIRMED", "11B");
        FlightDisruption dB = DisruptionService.getInstance().declareDisruption(
            flightB, DisruptionType.DELAY, DisruptionReason.CREW, DisruptionSeverity.LOW, 30, "Crew transfer delay", null
        );

        // In Tenant B context, active disruptions search must only return Tenant B disruptions
        List<FlightDisruption> tenantBDisruptions = DisruptionService.getInstance().searchDisruptions(null, null);
        for (FlightDisruption d : tenantBDisruptions) {
            assertEquals(tenantB.organizationId(), d.organizationId(), "Must strictly contain only Tenant B disruptions");
            assertNotEquals(dA.id(), d.id(), "Tenant A disruption must not appear in Tenant B list");
        }

        // Switch back to Tenant A
        TenantContext.setCurrentTenant(tenantA);
        List<FlightDisruption> tenantADisruptions = DisruptionService.getInstance().searchDisruptions(null, null);
        for (FlightDisruption d : tenantADisruptions) {
            assertEquals(tenantA.organizationId(), d.organizationId(), "Must strictly contain only Tenant A disruptions");
            assertNotEquals(dB.id(), d.id(), "Tenant B disruption must not appear in Tenant A list");
        }
    }

    // =========================================================================
    // STEP 7: DATABASE RESOURCE SAFETY & CONNECTION CLEANLINESS
    // =========================================================================

    @Test @Order(15)
    void test15_databaseResourceAndConnectionSafety() throws Exception {
        UserSession.login(1801, "p18_admin", Role.SUPERADMIN.name());
        TenantContext.setCurrentTenant(tenantA);

        // Rapid execution of valid and intentionally failing operations
        // to verify that connections, statements, and transactions never leak.
        for (int i = 0; i < 25; i++) {
            // Intentional failure 1: checkin non-existent PNR
            CheckinResult r = CheckinService.getInstance().processCheckin("NON_EXISTENT_" + i, "1A");
            assertFalse(r.success());

            // Intentional failure 2: invalid seat booking
            assertThrows(IllegalArgumentException.class, () ->
                ReservationService.book(999999L, 1L, "INVALID_SEAT", "Card")
            );

            // Valid read operation
            List<FlightDisruption> disruptions = DisruptionService.getInstance().searchDisruptions(null, null);
            assertNotNull(disruptions);
        }

        // Verify that database is completely responsive and healthy
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
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
                 "VALUES(?, ?, 'AeroDesk Hardening', ?, ?, ?, ?, ?, ?, 250.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
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
                testFlightIds.add(id);
                return id;
            }
        }
    }

    private static long createReservationFixture(Tenant t, long flightId, String pnr, String status, String seatNumber) throws Exception {
        long passId = createPassengerFixture(t);
        try (Connection c = DBConnection.getConnection()) {
            long resId;
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO reservations(organization_id, pnr, passenger_id, flight_id, seat_number, fare, status) " +
                    "VALUES(?, ?, ?, ?, ?, 250.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, t.organizationId());
                p.setString(2, pnr);
                p.setLong(3, passId);
                p.setLong(4, flightId);
                p.setString(5, seatNumber);
                p.setString(6, status);
                p.executeUpdate();
                try (ResultSet rs = p.getGeneratedKeys()) {
                    rs.next();
                    resId = rs.getLong(1);
                    testReservationIds.add(resId);
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
                 "VALUES(?, 'Hardening Pax', '9876543210', ?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, t.organizationId());
            p.setString(2, "pax_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                testPassengerIds.add(id);
                return id;
            }
        }
    }

    private static long createBaggageFixture(Tenant t, String status) throws Exception {
        long f = createFlightFixture(t, "BF-" + UUID.randomUUID().toString().substring(0, 6), "DEL", "BOM", "SCHEDULED", 30);
        String pnr = "P18-B" + UUID.randomUUID().toString().substring(0, 5);
        long r = createReservationFixture(t, f, pnr, "CONFIRMED", ((seatCounter.incrementAndGet() % 100) + 1) + "C");
        long p = getReservationPassengerId(r);

        long c;
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO checkins(organization_id, reservation_id, passenger_id, flight_id, seat_assigned, status) " +
                 "VALUES(?, ?, ?, ?, '16A', 'CHECKED_IN')", Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, t.organizationId());
            stmt.setLong(2, r);
            stmt.setLong(3, p);
            stmt.setLong(4, f);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                rs.next();
                c = rs.getLong(1);
            }
        }

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO baggage(organization_id, checkin_id, passenger_id, reservation_id, flight_id, tag_number, baggage_type, weight_kg, status) " +
                 "VALUES(?, ?, ?, ?, ?, ?, 'CHECKED', 15.00, ?)", Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, t.organizationId());
            stmt.setLong(2, c);
            stmt.setLong(3, p);
            stmt.setLong(4, r);
            stmt.setLong(5, f);
            stmt.setString(6, "BG" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            stmt.setString(7, status);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                rs.next();
                long id = rs.getLong(1);
                testBaggageIds.add(id);
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
                testOrgIds.add(id);
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
            testUserIds.add(id);
            testUsernames.add(name);
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
        if (ids == null || ids.isEmpty()) {
            return "0";
        }
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

    private static String quotedUserListSql() {
        Set<String> set = new LinkedHashSet<>(List.of(
            "p18_admin", "p18_opsmgr", "p18_resagent", "p18_checkin", "p18_gate", "p18_finance", "p18_auditor"
        ));
        synchronized (testUsernames) {
            set.addAll(testUsernames);
        }
        StringBuilder sb = new StringBuilder();
        for (String u : set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("'").append(u.replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private static void executeScopedDelete(Statement s, String targetTable, String sql, List<Throwable> failures) {
        try {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            failures.add(new SQLException("Cleanup failed for table [" + targetTable + "]: " + e.getMessage(), e));
        }
    }

    private static void cleanupTestFixtures() throws Exception {
        String flightIds = idListOrZero(testFlightIds);
        String passIds = idListOrZero(testPassengerIds);
        String resIds = idListOrZero(testReservationIds);
        String bagIds = idListOrZero(testBaggageIds);
        String testUserList = quotedUserListSql();
        String orgIds = idListOrZero(testOrgIds);

        String fltScope = "airline = 'AeroDesk Hardening' OR id IN (" + flightIds + ")";
        String paxScope = "name = 'Hardening Pax' OR id IN (" + passIds + ")";
        String fltSub = "SELECT id FROM flights WHERE " + fltScope;
        String paxSub = "SELECT id FROM passengers WHERE " + paxScope;
        String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR id IN (" + resIds + ")";
        String bagSub = "SELECT id FROM baggage WHERE id IN (" + bagIds + ") OR flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR reservation_id IN (" + resSub + ")";
        String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR reservation_id IN (" + resSub + ")";
        String bpSub = "SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ")";
        String disruptSub = "SELECT id FROM flight_disruptions WHERE flight_id IN (" + fltSub + ")";
        String claimSub = "SELECT id FROM claim_cases WHERE passenger_id IN (" + paxSub + ") OR reservation_id IN (" + resSub + ") OR baggage_id IN (" + bagSub + ")";
        String userSub = "SELECT id FROM app_users WHERE username IN (" + testUserList + ")";
        String orgSub = "SELECT id FROM organizations WHERE (id IN (" + orgIds + ") OR org_code = 'P18B') AND id > 1 AND org_code != 'AD'";

        TestDatabaseGuard.ensureTestDatabase();
        List<Throwable> failures = new ArrayList<>();
        try (Connection c = DBConnection.getConnection();
             Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);

            // 1. Disruption vouchers (references disruptions, reaccommodations, reservations, passengers, orgs)
            executeScopedDelete(s, "disruption_vouchers", "DELETE FROM disruption_vouchers WHERE disruption_id IN (" + disruptSub + ") " +
                    "OR passenger_id IN (" + paxSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 2. Passenger reaccommodations (references disruptions, reservations, passengers, flights, orgs)
            executeScopedDelete(s, "passenger_reaccommodations", "DELETE FROM passenger_reaccommodations WHERE disruption_id IN (" + disruptSub + ") " +
                    "OR original_flight_id IN (" + fltSub + ") OR new_flight_id IN (" + fltSub + ") " +
                    "OR passenger_id IN (" + paxSub + ") OR original_reservation_id IN (" + resSub + ") " +
                    "OR new_reservation_id IN (" + resSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 3. Flight disruptions (references flights, orgs)
            executeScopedDelete(s, "flight_disruptions", "DELETE FROM flight_disruptions WHERE flight_id IN (" + fltSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 4. Claim settlements (references claim_cases, orgs)
            executeScopedDelete(s, "claim_settlements", "DELETE FROM claim_settlements WHERE claim_id IN (" + claimSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 5. Claim events (references orgs, users)
            executeScopedDelete(s, "claim_events", "DELETE FROM claim_events WHERE performed_by_user_id IN (" + userSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 6. Claim cases (references pir_reports, baggage, passengers, reservations, orgs)
            executeScopedDelete(s, "claim_cases", "DELETE FROM claim_cases WHERE passenger_id IN (" + paxSub + ") " +
                    "OR reservation_id IN (" + resSub + ") OR baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 7. PIR reports (references baggage, passengers, reservations, flights, orgs)
            executeScopedDelete(s, "pir_reports", "DELETE FROM pir_reports WHERE passenger_id IN (" + paxSub + ") " +
                    "OR flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") " +
                    "OR baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 8. Baggage events (references baggage, orgs)
            executeScopedDelete(s, "baggage_events", "DELETE FROM baggage_events WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 9. Baggage (references checkins, passengers, reservations, flights, orgs)
            executeScopedDelete(s, "baggage", "DELETE FROM baggage WHERE id IN (" + bagIds + ") " +
                    "OR flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") " +
                    "OR reservation_id IN (" + resSub + ") OR checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 10. Boarding events (references boarding_passes, flights, orgs)
            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE flight_id IN (" + fltSub + ") " +
                    "OR boarding_pass_id IN (" + bpSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 11. Boarding passes (references checkins, orgs)
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 12. Checkins (references reservations, passengers, flights, orgs)
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") " +
                    "OR passenger_id IN (" + paxSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 13. Cancellations (references reservations)
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ")", failures);

            // 14. Payments (references reservations, orgs)
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 15. Reservations (references passengers, flights, orgs)
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE id IN (" + resIds + ") " +
                    "OR flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 16. Passengers (references orgs)
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + passIds + ") " +
                    "OR name = 'Hardening Pax' OR organization_id IN (" + orgSub + ")", failures);

            // 17. Flights (references orgs)
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + flightIds + ") " +
                    "OR airline = 'AeroDesk Hardening' OR organization_id IN (" + orgSub + ")", failures);

            // 18. Audit logs (references orgs, users)
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE username IN (" + testUserList + ") " +
                    "OR user_id IN (" + userSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 19. User roles (references users, orgs)
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id IN (" + userSub + ") OR organization_id IN (" + orgSub + ")", failures);

            // 20. App users
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE username IN (" + testUserList + ")", failures);

            // 21. Test organizations (never delete default org 1 / AD)
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE (id IN (" + orgIds + ") OR org_code = 'P18B') " +
                    "AND id > 1 AND org_code != 'AD'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            testFlightIds.clear();
            testPassengerIds.clear();
            testReservationIds.clear();
            testBaggageIds.clear();
            testUserIds.clear();
            testUsernames.clear();
            testOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase18ProductionHardeningTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    private static void clearTestData() throws Exception {
        cleanupTestFixtures();
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
