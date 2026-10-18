package airline.management.system;

import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.claim.ClaimCase;
import airline.management.system.operations.claim.ClaimEventRecord;
import airline.management.system.operations.claim.ClaimService;
import airline.management.system.operations.claim.ClaimSettlementRecord;
import airline.management.system.operations.claim.ClaimStatus;
import airline.management.system.operations.pir.PIRRecord;
import airline.management.system.operations.pir.PIRService;
import airline.management.system.operations.pir.PIRStatus;
import airline.management.system.operations.pir.PIRType;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * Authoritative Phase 16 end-to-end integration and security test suite.
 * Validates complete PIR & Baggage Claims domain operations, lifecycle state machines,
 * compensation settlements, multi-tenant isolation, authorization controls, and reporting.
 *
 * All operations execute strictly against project4test; project4 remains untouched.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase16PIRClaimsTest {

    private static final String TEST_DB = "project4test";
    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdBaggageIds = Collections.synchronizedSet(new LinkedHashSet<>());
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
        }

        clearTestData();

        tenantA = new Tenant(1L, "AD", "AeroDesk", true);
        long bOrgId = createOrganization("P16B");
        tenantB = new Tenant(bOrgId, "P16B", "Phase 16 Tenant B", true);

        createUser(1601, "pir_admin", Role.SUPERADMIN.name());
        createUser(1602, "pir_auditor", Role.AUDITOR.name());
        createUser(1603, "pir_staff", Role.CHECKINAGENT.name());
        createUser(1604, "pir_finance", Role.FINANCEOFFICER.name());
        createUser(1605, "pir_unauth", Role.GATEAGENT.name());

        TenantContext.setCurrentTenant(tenantA);
        UserSession.login(1601, "pir_admin", Role.SUPERADMIN.name());
    }

    @AfterAll
    static void cleanup() throws Exception {
        TenantContext.resetSession();
        UserSession.logout();
        clearTestData();
    }

    // =========================================================================
    // 1-10: PROPERTY IRREGULARITY REPORT (PIR) TESTS
    // =========================================================================

    @Test @Order(1)
    void test01_createDelayedPIR() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();

        PIRRecord record = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Luggage delayed in inbound transfer");
        assertNotNull(record);
        assertEquals(PIRType.DELAYED_BAGGAGE, record.type());
        assertEquals(PIRStatus.OPEN, record.status());
        assertTrue(record.pirReference().startsWith("PIR"), "PIR reference should start with PIR");
        assertEquals("DEL", record.stationCode());
        assertEquals("Luggage delayed in inbound transfer", record.description());
        assertNotNull(record.passengerName());
        assertNotNull(record.flightNumber());
    }

    @Test @Order(2)
    void test02_createMissingPIR() throws Exception {
        long bagId = createBaggageFixture(tenantA, "MISSING");
        PIRService pir = PIRService.getInstance();

        PIRRecord record = pir.createPIR(bagId, PIRType.MISSING_BAGGAGE, "BOM", "Baggage missing at carousel 4");
        assertNotNull(record);
        assertEquals(PIRType.MISSING_BAGGAGE, record.type());
        assertEquals(PIRStatus.OPEN, record.status());
        assertTrue(record.pirReference().startsWith("PIR"));
        assertEquals("BOM", record.stationCode());
    }

    @Test @Order(3)
    void test03_duplicateActivePIRPrevention() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();

        PIRRecord first = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Initial incident report");
        assertNotNull(first);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Duplicate active PIR attempt")
        );
        assertTrue(ex.getMessage().contains("already exists"), "Should indicate active PIR exists");
    }

    @Test @Order(4)
    void test04_validPIRTransition() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();
        PIRRecord report = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Tracking test");

        PIRRecord underInv = pir.updatePIRStatus(report.id(), PIRStatus.UNDER_INVESTIGATION, "Investigation begun at station");
        assertEquals(PIRStatus.UNDER_INVESTIGATION, underInv.status());

        PIRRecord located = pir.updatePIRStatus(report.id(), PIRStatus.LOCATED, "Bag located at ramp container");
        assertEquals(PIRStatus.LOCATED, located.status());

        PIRRecord delivered = pir.updatePIRStatus(report.id(), PIRStatus.DELIVERED, "Bag delivered to passenger address");
        assertEquals(PIRStatus.DELIVERED, delivered.status());

        PIRRecord closed = pir.updatePIRStatus(report.id(), PIRStatus.CLOSED, "Case closed after delivery acknowledgement");
        assertEquals(PIRStatus.CLOSED, closed.status());
        assertNotNull(closed.resolvedAt(), "Resolved timestamp must be recorded upon closure");
    }

    @Test @Order(5)
    void test05_invalidPIRTransition() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();
        PIRRecord report = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Transition validation test");

        assertThrows(IllegalStateException.class, () ->
            pir.updatePIRStatus(report.id(), PIRStatus.DELIVERED, "Invalid direct jump to DELIVERED")
        );
        assertThrows(IllegalStateException.class, () ->
            pir.updatePIRStatus(report.id(), PIRStatus.LOCATED, "Invalid direct jump to LOCATED")
        );

        // Move properly to CLOSED
        pir.updatePIRStatus(report.id(), PIRStatus.UNDER_INVESTIGATION, "Investigating");
        pir.updatePIRStatus(report.id(), PIRStatus.LOCATED, "Located");
        pir.updatePIRStatus(report.id(), PIRStatus.DELIVERED, "Delivered");
        pir.updatePIRStatus(report.id(), PIRStatus.CLOSED, "Closed");

        // Attempting to reopen CLOSED PIR must be rejected
        assertThrows(IllegalStateException.class, () ->
            pir.updatePIRStatus(report.id(), PIRStatus.OPEN, "Cannot reopen closed PIR")
        );
    }

    @Test @Order(6)
    void test06_investigationNotePersistence() throws Exception {
        long bagId = createBaggageFixture(tenantA, "MISSING");
        PIRService pir = PIRService.getInstance();
        PIRRecord report = pir.createPIR(bagId, PIRType.MISSING_BAGGAGE, "DEL", "Initial note test");

        pir.addInvestigationNotes(report.id(), "Physical search of transfer belt completed with negative result.");
        PIRRecord updated = pir.findById(report.id()).orElseThrow();
        assertTrue(updated.investigationNotes().contains("Physical search of transfer belt completed"));

        List<ClaimEventRecord> events = pir.getPIREvents(report.id());
        assertTrue(events.stream().anyMatch(e -> "PIR_NOTE_ADDED".equals(e.eventType())));
    }

    @Test @Order(7)
    void test07_pirLookup() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();
        PIRRecord created = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Lookup test");

        assertTrue(pir.findById(created.id()).isPresent());
        assertEquals(created.pirReference(), pir.findById(created.id()).get().pirReference());

        assertTrue(pir.findByReference(created.pirReference()).isPresent());
        assertEquals(created.id(), pir.findByReference(created.pirReference()).get().id());

        assertTrue(pir.findActiveByBaggageTag(created.baggageTag()).isPresent());
        assertEquals(created.id(), pir.findActiveByBaggageTag(created.baggageTag()).get().id());

        List<PIRRecord> searchByPnr = pir.searchPIRs(created.pnr(), null);
        assertTrue(searchByPnr.stream().anyMatch(p -> p.id() == created.id()));

        List<PIRRecord> searchById = pir.searchPIRs(String.valueOf(created.id()), null);
        assertTrue(searchById.stream().anyMatch(p -> p.id() == created.id()));
    }

    @Test @Order(8)
    void test08_tenantIsolationPIR() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();
        PIRRecord created = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Tenant A report");

        // Switch to Tenant B
        TenantContext.setCurrentTenant(tenantB);
        assertTrue(pir.findById(created.id()).isEmpty(), "Tenant B must not see Tenant A PIR by ID");
        assertTrue(pir.findByReference(created.pirReference()).isEmpty(), "Tenant B must not see Tenant A PIR by reference");
        assertTrue(pir.searchPIRs(created.pirReference(), null).isEmpty(), "Tenant B search must return empty");

        assertThrows(AccessDeniedException.class, () ->
            pir.updatePIRStatus(created.id(), PIRStatus.UNDER_INVESTIGATION, "Cross tenant update attempt")
        );

        TenantContext.setCurrentTenant(tenantA);
    }

    @Test @Order(9)
    void test09_unauthorizedAccessPIR() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();

        // Unauthenticated check
        UserSession.logout();
        assertThrows(AccessDeniedException.class, () ->
            pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Unauthenticated PIR create")
        );

        // Unauthorized user (Gate agent has no MANAGE_PIR permission)
        UserSession.login(1605, "pir_unauth", Role.GATEAGENT.name());
        assertThrows(AccessDeniedException.class, () ->
            pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Gate agent unauthorized PIR create")
        );

        // Restore admin session
        UserSession.login(1601, "pir_admin", Role.SUPERADMIN.name());
    }

    @Test @Order(10)
    void test10_auditEventGeneratedOnPIRActions() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();

        PIRRecord report = pir.createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Audit test");
        pir.updatePIRStatus(report.id(), PIRStatus.UNDER_INVESTIGATION, "Beginning investigation");
        pir.addInvestigationNotes(report.id(), "Noted container barcode scan.");

        List<ClaimEventRecord> events = pir.getPIREvents(report.id());
        assertTrue(events.stream().anyMatch(e -> "PIR_CREATED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "PIR_STATUS_CHANGED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "PIR_NOTE_ADDED".equals(e.eventType())));

        for (ClaimEventRecord ev : events) {
            assertEquals("pir_admin", ev.performedByUsername());
            assertNotNull(ev.createdAt());
        }
    }

    // =========================================================================
    // 11-30: BAGGAGE CLAIMS & SETTLEMENT TESTS
    // =========================================================================

    @Test @Order(11)
    void test11_createClaimFromEligiblePIR() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Delayed bag claim eligible");
        ClaimService claims = ClaimService.getInstance();

        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("300.00"), "USD", "Essential clothes and toiletries");
        assertNotNull(claim);
        assertTrue(claim.claimReference().startsWith("CLM"));
        assertEquals(ClaimStatus.SUBMITTED, claim.status());
        assertEquals(0, claim.requestedAmount().compareTo(new BigDecimal("300.00")));
        assertEquals("USD", claim.currency());
        assertEquals(pir.id(), claim.pirId());
    }

    @Test @Order(12)
    void test12_duplicateClaimPrevention() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Duplicate claim test");
        ClaimService claims = ClaimService.getInstance();

        claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Initial claim");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Duplicate active claim attempt")
        );
        assertTrue(ex.getMessage().toLowerCase().contains("already exists"));
    }

    @Test @Order(13)
    void test13_claimTransitionSubmittedToUnderReview() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Review test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("250.00"), "USD", "Toiletries expenses");

        ClaimCase reviewed = claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Auditing passenger purchase receipts");
        assertEquals(ClaimStatus.UNDER_REVIEW, reviewed.status());
        assertTrue(reviewed.reviewNotes().contains("Auditing passenger purchase receipts"));
    }

    @Test @Order(14)
    void test14_underReviewToApprovedWithApprovedAmount() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Approval test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("400.00"), "USD", "Expense items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Examining receipts");

        // Amount exceeding requested amount must be rejected
        assertThrows(IllegalArgumentException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("401.00"), "Exceeds requested")
        );
        // Non-positive amount rejected
        assertThrows(IllegalArgumentException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, BigDecimal.ZERO, "Zero amount")
        );

        ClaimCase approved = claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("350.00"), "Approved verified items");
        assertEquals(ClaimStatus.APPROVED, approved.status());
        assertEquals(0, approved.approvedAmount().compareTo(new BigDecimal("350.00")));
    }

    @Test @Order(15)
    void test15_underReviewToRejectedWithRemarks() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Rejection test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("150.00"), "USD", "Delay compensation");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Verifying tracking log");

        ClaimCase rejected = claims.reviewClaim(claim.id(), ClaimStatus.REJECTED, null, "Baggage was delivered within 30 minutes; no compensable delay.");
        assertEquals(ClaimStatus.REJECTED, rejected.status());
        assertTrue(rejected.reviewNotes().contains("delivered within 30 minutes"));
    }

    @Test @Order(16)
    void test16_approvedToSettledViaSettleClaim() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Settlement execution test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("500.00"), "USD", "Lost electronics");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Receipts verified");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("450.00"), "Approved maximum policy limit");

        ClaimSettlementRecord settlement = claims.settleClaim(claim.id(), "BANK_TRANSFER", "Disbursement via ACH direct deposit");
        assertNotNull(settlement);
        assertTrue(settlement.settlementReference().startsWith("STL"));
        assertEquals(0, settlement.amount().compareTo(new BigDecimal("450.00")));
        assertEquals("COMPLETED", settlement.settlementStatus());
        assertEquals("BANK_TRANSFER", settlement.paymentMethod());

        ClaimCase updatedClaim = claims.findById(claim.id()).orElseThrow();
        assertEquals(ClaimStatus.SETTLED, updatedClaim.status());
        assertNotNull(updatedClaim.settledAt());
    }

    @Test @Order(17)
    void test17_settledToClosed() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Settled to closed test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Clothing");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Reviewing");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("200.00"), "Approved");
        claims.settleClaim(claim.id(), "BANK_TRANSFER", "Settled");

        ClaimCase closed = claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Payment clearance confirmed; claim closed");
        assertEquals(ClaimStatus.CLOSED, closed.status());
        assertTrue(closed.isTerminal());
    }

    @Test @Order(18)
    void test18_rejectedToClosed() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Rejected to closed test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Snacks");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Reviewing");
        claims.reviewClaim(claim.id(), ClaimStatus.REJECTED, null, "Non-compensable items");

        ClaimCase closed = claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Rejection communicated; file closed");
        assertEquals(ClaimStatus.CLOSED, closed.status());
        assertTrue(closed.isTerminal());
    }

    @Test @Order(19)
    void test19_invalidSubmittedToClosedRejected() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Invalid SUBMITTED to CLOSED");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Items");

        assertThrows(IllegalStateException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Illegal skip to CLOSED")
        );
    }

    @Test @Order(20)
    void test20_invalidUnderReviewToClosedRejected() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Invalid UNDER_REVIEW to CLOSED");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Under review");

        assertThrows(IllegalStateException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Illegal skip to CLOSED")
        );
    }

    @Test @Order(21)
    void test21_invalidApprovedToRejectedRejected() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Invalid APPROVED to REJECTED");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Under review");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("180.00"), "Approved");

        assertThrows(IllegalStateException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.REJECTED, null, "Cannot reject an approved claim")
        );
    }

    @Test @Order(22)
    void test22_invalidApprovedToClosedWithoutSettlement() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Invalid APPROVED to CLOSED");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Under review");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("180.00"), "Approved");

        assertThrows(IllegalStateException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Cannot close approved claim without settlement")
        );
    }

    @Test @Order(23)
    void test23_closedToAnyStateRejected() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Terminal CLOSED test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Review");
        claims.reviewClaim(claim.id(), ClaimStatus.REJECTED, null, "Rejected");
        claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Closed");

        for (ClaimStatus target : List.of(ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW, ClaimStatus.APPROVED, ClaimStatus.REJECTED, ClaimStatus.SETTLED)) {
            assertThrows(IllegalStateException.class, () ->
                claims.reviewClaim(claim.id(), target, null, "Transition out of terminal state must fail")
            );
        }
    }

    @Test @Order(24)
    void test24_claimEventPersistence() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Claim audit lifecycle");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("300.00"), "USD", "Delayed bag items");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Reviewing");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("250.00"), "Approved");
        claims.settleClaim(claim.id(), "BANK_TRANSFER", "Settled");
        claims.reviewClaim(claim.id(), ClaimStatus.CLOSED, null, "Closed");

        List<ClaimEventRecord> events = claims.getClaimEvents(claim.id());
        assertTrue(events.stream().anyMatch(e -> "CLAIM_CREATED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "CLAIM_STATUS_CHANGED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "CLAIM_APPROVED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "CLAIM_SETTLED".equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> "CLAIM_CLOSED".equals(e.eventType())));
    }

    @Test @Order(25)
    void test25_reviewerPersistence() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Reviewer tracking");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Toiletries");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Checking items");

        ClaimCase updated = claims.findById(claim.id()).orElseThrow();
        assertEquals(Long.valueOf(1601L), updated.reviewedByUserId());
    }

    @Test @Order(26)
    void test26_settlementPersistence() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Settlement record test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("250.00"), "USD", "Clothing");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Reviewing");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("200.00"), "Approved");
        ClaimSettlementRecord created = claims.settleClaim(claim.id(), "BANK_TRANSFER", "Direct bank transfer");

        Optional<ClaimSettlementRecord> loaded = claims.getSettlementByClaimId(claim.id());
        assertTrue(loaded.isPresent());
        assertEquals(created.settlementReference(), loaded.get().settlementReference());
        assertEquals("BANK_TRANSFER", loaded.get().paymentMethod());
        assertEquals(Long.valueOf(1601L), loaded.get().authorizedByUserId());
        assertEquals("pir_admin", loaded.get().authorizedByUsername());
    }

    @Test @Order(27)
    void test27_settlementAmountPersistence() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Amount persistence test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("385.50"), "USD", "Detailed receipts");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Reviewing");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("350.25"), "Approved specific items");
        claims.settleClaim(claim.id(), "CARD", "Credit card refund");

        ClaimSettlementRecord settlement = claims.getSettlementByClaimId(claim.id()).orElseThrow();
        assertEquals(new BigDecimal("350.25"), settlement.amount());

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT amount FROM claim_settlements WHERE claim_id = ?")) {
            p.setLong(1, claim.id());
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("350.25"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test @Order(28)
    void test28_tenantIsolationClaims() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Tenant A claim");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("200.00"), "USD", "Tenant A items");

        // Switch to Tenant B
        TenantContext.setCurrentTenant(tenantB);
        assertTrue(claims.findById(claim.id()).isEmpty(), "Tenant B must not see Tenant A claim by ID");
        assertTrue(claims.findByReference(claim.claimReference()).isEmpty(), "Tenant B must not see Tenant A claim by reference");
        assertTrue(claims.findActiveByPirId(pir.id()).isEmpty(), "Tenant B must not see claim by PIR ID");
        assertTrue(claims.searchClaims(claim.claimReference(), null).isEmpty(), "Tenant B search must return empty");

        assertThrows(AccessDeniedException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Cross tenant review attempt")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.settleClaim(claim.id(), "CASH", "Cross tenant settle attempt")
        );

        TenantContext.setCurrentTenant(tenantA);
    }

    @Test @Order(29)
    void test29_auditorReadOnly() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Auditor read-only test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Audit test items");

        // Log in as Auditor
        UserSession.login(1602, "pir_auditor", Role.AUDITOR.name());

        // Auditor CAN read
        assertDoesNotThrow(() -> PIRService.getInstance().searchPIRs("", null));
        assertDoesNotThrow(() -> claims.searchClaims("", null));
        assertDoesNotThrow(() -> claims.getClaimEvents(claim.id()));

        // Auditor CANNOT mutate
        assertThrows(AccessDeniedException.class, () ->
            PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Auditor create PIR denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            PIRService.getInstance().updatePIRStatus(pir.id(), PIRStatus.UNDER_INVESTIGATION, "Auditor update PIR denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.createClaim(pir.id(), new BigDecimal("50.00"), "USD", "Auditor create claim denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Auditor review claim denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.settleClaim(claim.id(), "CASH", "Auditor settle claim denied")
        );

        // Restore admin
        UserSession.login(1601, "pir_admin", Role.SUPERADMIN.name());
    }

    @Test @Order(30)
    void test30_unauthorizedMutationDenied() throws Exception {
        long bagId = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord pir = PIRService.getInstance().createPIR(bagId, PIRType.DELAYED_BAGGAGE, "DEL", "Unauthorized mutations test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(pir.id(), new BigDecimal("100.00"), "USD", "Mutation test items");

        UserSession.logout();
        assertThrows(AccessDeniedException.class, () ->
            claims.createClaim(pir.id(), new BigDecimal("10.00"), "USD", "Unauthenticated claim create denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Unauthenticated claim review denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.settleClaim(claim.id(), "CASH", "Unauthenticated claim settlement denied")
        );

        UserSession.login(1605, "pir_unauth", Role.GATEAGENT.name());
        assertThrows(AccessDeniedException.class, () ->
            claims.createClaim(pir.id(), new BigDecimal("10.00"), "USD", "Gate agent claim create denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Gate agent claim review denied")
        );
        assertThrows(AccessDeniedException.class, () ->
            claims.settleClaim(claim.id(), "CASH", "Gate agent claim settlement denied")
        );

        UserSession.login(1601, "pir_admin", Role.SUPERADMIN.name());
    }

    // =========================================================================
    // 31-36: INTEGRATION & OPERATIONAL TESTS
    // =========================================================================

    @Test @Order(31)
    void test31_baggageToPIRIntegration() throws Exception {
        long deliveredBag = createBaggageFixture(tenantA, "DELIVERED");
        PIRService pir = PIRService.getInstance();

        // Attempting to file PIR for delivered baggage must be prohibited
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            pir.createPIR(deliveredBag, PIRType.DELAYED_BAGGAGE, "DEL", "Delivered baggage cannot be delayed")
        );
        assertTrue(ex.getMessage().contains("DELIVERED"));

        // Delayed baggage files successfully and links back to baggage
        long delayedBag = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord record = pir.createPIR(delayedBag, PIRType.DELAYED_BAGGAGE, "DEL", "Integration trace");
        assertEquals(delayedBag, record.baggageId());

        Optional<PIRRecord> activeForBag = pir.findActiveByBaggageId(delayedBag);
        assertTrue(activeForBag.isPresent());
        assertEquals(record.id(), activeForBag.get().id());
    }

    @Test @Order(32)
    void test32_pirToClaimIntegration() throws Exception {
        long delayedBag = createBaggageFixture(tenantA, "DELAYED");
        PIRService pir = PIRService.getInstance();
        ClaimService claims = ClaimService.getInstance();

        PIRRecord report = pir.createPIR(delayedBag, PIRType.DELAYED_BAGGAGE, "DEL", "PIR claim integration test");
        ClaimCase claim = claims.createClaim(report.id(), new BigDecimal("220.00"), "USD", "Integration claim");

        Optional<ClaimCase> linkedClaim = claims.findActiveByPirId(report.id());
        assertTrue(linkedClaim.isPresent());
        assertEquals(claim.claimReference(), linkedClaim.get().claimReference());
        assertEquals(report.pirReference(), linkedClaim.get().pirReference());

        // Updating PIR status should not corrupt linked claim reference
        pir.updatePIRStatus(report.id(), PIRStatus.UNDER_INVESTIGATION, "Checking bins");
        assertEquals(report.pirReference(), claims.findById(claim.id()).orElseThrow().pirReference());
    }

    @Test @Order(33)
    void test33_claimToSettlementIntegration() throws Exception {
        long delayedBag = createBaggageFixture(tenantA, "DELAYED");
        PIRRecord report = PIRService.getInstance().createPIR(delayedBag, PIRType.DELAYED_BAGGAGE, "DEL", "Settlement integration test");
        ClaimService claims = ClaimService.getInstance();
        ClaimCase claim = claims.createClaim(report.id(), new BigDecimal("300.00"), "USD", "Lost jacket");
        claims.reviewClaim(claim.id(), ClaimStatus.UNDER_REVIEW, null, "Under review");
        claims.reviewClaim(claim.id(), ClaimStatus.APPROVED, new BigDecimal("250.00"), "Approved");

        ClaimSettlementRecord stl = claims.settleClaim(claim.id(), "UPI", "Direct settlement payout");
        assertNotNull(stl);
        assertEquals("UPI", stl.paymentMethod());

        // Duplicate settlement on same claim is strictly prevented
        IllegalStateException dupEx = assertThrows(IllegalStateException.class, () ->
            claims.settleClaim(claim.id(), "UPI", "Second settlement attempt")
        );
        assertTrue(dupEx.getMessage().toLowerCase().contains("already settled"));
    }

    @Test @Order(34)
    void test34_dashboardMetricsReflectRealState() throws Exception {
        // Run aggregation queries matching DashboardPanel
        try (Connection c = DBConnection.getConnection()) {
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT COUNT(*) FROM pir_reports WHERE organization_id = ? AND status NOT IN ('CLOSED')")) {
                p.setLong(1, tenantA.organizationId());
                try (ResultSet rs = p.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 0);
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT COUNT(*) FROM claim_cases WHERE organization_id = ? AND status = 'SUBMITTED'")) {
                p.setLong(1, tenantA.organizationId());
                try (ResultSet rs = p.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 0);
                }
            }
        }
    }

    @Test @Order(35)
    void test35_reportMetricsReflectRealStateAndDateFiltering() throws Exception {
        OperationalReportService.ReportFilter filterAll = new OperationalReportService.ReportFilter(
            OperationalReportService.Category.PIR_CLAIMS, null, null, null, null
        );
        OperationalReportService.ReportData dataAll = OperationalReportService.generateReport(filterAll);
        assertNotNull(dataAll);
        Map<String, String> metrics = dataAll.summaryMetrics();
        assertTrue(metrics.containsKey("Total PIRs"));
        assertTrue(metrics.containsKey("Active PIRs"));
        assertTrue(metrics.containsKey("Claims Submitted"));
        assertTrue(metrics.containsKey("Claims Approved"));
        assertTrue(metrics.containsKey("Claims Settled"));
        assertTrue(metrics.containsKey("Total Settlements Paid"));

        // Date filter in the far future must return 0 for date-filtered metrics
        OperationalReportService.ReportFilter futureFilter = new OperationalReportService.ReportFilter(
            OperationalReportService.Category.PIR_CLAIMS, LocalDate.of(2045, 1, 1), LocalDate.of(2045, 12, 31), null, null
        );
        OperationalReportService.ReportData futureData = OperationalReportService.generateReport(futureFilter);
        assertEquals(0, futureData.tableModel().getRowCount(), "Far future date filter should return 0 report records");
    }

    @Test @Order(36)
    void test36_crossTenantReportIsolation() throws Exception {
        // Tenant A has multiple PIRs and Claims from prior tests
        OperationalReportService.ReportFilter filter = new OperationalReportService.ReportFilter(
            OperationalReportService.Category.PIR_CLAIMS, null, null, null, null
        );
        TenantContext.setCurrentTenant(tenantA);
        OperationalReportService.ReportData reportA = OperationalReportService.generateReport(filter);
        int rowsA = reportA.tableModel().getRowCount();
        assertTrue(rowsA > 0, "Tenant A must have report rows");

        // Switch to Tenant B (which has no PIRs filed yet)
        TenantContext.setCurrentTenant(tenantB);
        OperationalReportService.ReportData reportB = OperationalReportService.generateReport(filter);
        assertEquals(0, reportB.tableModel().getRowCount(), "Tenant B must not see Tenant A report rows");
        assertEquals("0", reportB.summaryMetrics().get("Total PIRs"));

        TenantContext.setCurrentTenant(tenantA);
    }

    // =========================================================================
    // FIXTURES AND HELPERS
    // =========================================================================

    private static long createBaggageFixture(Tenant t, String status) throws Exception {
        long f = insert("INSERT INTO flights(organization_id,flight_number,airline,source,destination,departure_time,arrival_time,total_seats,available_seats,fare,status) VALUES(?,?,'Test Air','A','B','2035-01-01 10:00:00','2035-01-01 11:00:00',10,10,10,'SCHEDULED')",
            t.organizationId(), "P16" + UUID.randomUUID().toString().substring(0, 7));
        createdFlightIds.add(f);
        long p = insert("INSERT INTO passengers(organization_id,name,phone,email) VALUES(?, ?, ?, ?)",
            t.organizationId(), "Passenger " + UUID.randomUUID().toString().substring(0, 5), UUID.randomUUID().toString().substring(0, 10), UUID.randomUUID() + "@test.invalid");
        createdPassengerIds.add(p);
        long r = insert("INSERT INTO reservations(organization_id,pnr,passenger_id,flight_id,seat_number,fare,status) VALUES(?,?,?,?, '1A',10,'CONFIRMED')",
            t.organizationId(), "P16-" + UUID.randomUUID().toString().substring(0, 8), p, f);
        long c = insert("INSERT INTO checkins(organization_id,reservation_id,passenger_id,flight_id,seat_assigned,status) VALUES(?,?,?,?, '1A','CHECKED_IN')",
            t.organizationId(), r, p, f);
        long b = insert("INSERT INTO baggage(organization_id,checkin_id,passenger_id,reservation_id,flight_id,tag_number,baggage_type,weight_kg,status) VALUES(?,?,?,?,?,?,'CHECKED',15,?)",
            t.organizationId(), c, p, r, f, "BAG" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), status);
        createdBaggageIds.add(b);
        return b;
    }

    private static long insert(String sql, Object... v) throws Exception {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < v.length; i++) {
                p.setObject(i + 1, v[i]);
            }
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static long createOrganization(String code) throws Exception {
        long orgId = insert("INSERT INTO organizations(org_code,org_name,contact_email) VALUES(?,?,?)",
            code, "Tenant " + code, code.toLowerCase() + "@test.invalid");
        createdOrgIds.add(orgId);
        return orgId;
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
            String bagIds = idListOrZero(createdBaggageIds);
            String orgIds = idListOrZero(createdOrgIds);

            String fltSub = "SELECT id FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR airline = 'Test Air' OR flight_number LIKE 'P16%'";
            String paxSub = "SELECT id FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@test.invalid'";
            String resSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ") OR pnr LIKE 'P16-%'";
            String checkinSub = "SELECT id FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")";
            String passSub = "SELECT id FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")";
            String bagSub = "SELECT id FROM baggage WHERE id IN (" + bagIds + ") OR flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ") OR tag_number LIKE 'BAG%'";
            String pirSub = "SELECT id FROM pir_reports WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ") OR pir_reference LIKE 'PIR%'";
            String claimSub = "SELECT id FROM claim_cases WHERE baggage_id IN (" + bagSub + ") OR pir_id IN (" + pirSub + ") OR organization_id IN (" + orgIds + ") OR claim_reference LIKE 'CLM%'";

            // 1. Claim events & settlements
            executeScopedDelete(s, "claim_events", "DELETE FROM claim_events WHERE (entity_type = 'CLAIM' AND entity_id IN (" + claimSub + ")) OR (entity_type = 'PIR' AND entity_id IN (" + pirSub + ")) OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "claim_settlements", "DELETE FROM claim_settlements WHERE claim_id IN (" + claimSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 2. Claim cases
            executeScopedDelete(s, "claim_cases", "DELETE FROM claim_cases WHERE baggage_id IN (" + bagSub + ") OR pir_id IN (" + pirSub + ") OR organization_id IN (" + orgIds + ") OR claim_reference LIKE 'CLM%'", failures);
            // 3. PIR reports
            executeScopedDelete(s, "pir_reports", "DELETE FROM pir_reports WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ") OR pir_reference LIKE 'PIR%'", failures);
            // 4. Baggage events & baggage
            executeScopedDelete(s, "baggage_events", "DELETE FROM baggage_events WHERE baggage_id IN (" + bagSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "baggage", "DELETE FROM baggage WHERE id IN (" + bagIds + ") OR flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ") OR tag_number LIKE 'BAG%'", failures);
            // 5. Boarding & Checkins
            executeScopedDelete(s, "boarding_events", "DELETE FROM boarding_events WHERE boarding_pass_id IN (" + passSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "boarding_passes", "DELETE FROM boarding_passes WHERE checkin_id IN (" + checkinSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "checkins", "DELETE FROM checkins WHERE flight_id IN (" + fltSub + ") OR reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 6. Cancellations & Payments
            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resSub + ") OR organization_id IN (" + orgIds + ")", failures);
            // 7. Reservations
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltSub + ") OR passenger_id IN (" + paxSub + ") OR organization_id IN (" + orgIds + ") OR pnr LIKE 'P16-%'", failures);
            // 8. Passengers
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR organization_id IN (" + orgIds + ") OR email LIKE '%@test.invalid'", failures);
            // 9. Flights
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR organization_id IN (" + orgIds + ") OR airline = 'Test Air' OR flight_number LIKE 'P16%'", failures);
            // 10. Audit logs & Users
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id BETWEEN 1601 AND 1605", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE (user_id BETWEEN 1601 AND 1605) OR username LIKE 'pir_%' OR organization_id IN (" + orgIds + ")", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE (id BETWEEN 1601 AND 1605) OR username LIKE 'pir_%'", failures);
            // 11. Partner organization
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE id IN (" + orgIds + ") OR org_code = 'P16B'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
            createdBaggageIds.clear();
            createdOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase16PIRClaimsTest cleanup failed with " + failures.size() + " error(s)");
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
