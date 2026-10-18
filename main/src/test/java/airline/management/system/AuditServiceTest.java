package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditEvent;
import airline.management.system.audit.AuditService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    private AuditService auditService;

    @BeforeAll
    static void setUpAll() {
        System.setProperty("DB_NAME", TestDatabaseGuard.REQUIRED_TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "DELETE FROM audit_logs WHERE username IN ('ops_john', 'SYSTEM') " +
                 "AND (details = 'Operator authenticated' OR details = 'Gate changed to B4' " +
                 "OR details LIKE 'Payment processed with%' OR details LIKE 'Event %')")) {
            TestDatabaseGuard.validateConnectionCatalog(conn);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new SQLException("AuditServiceTest cleanup failed: " + e.getMessage(), e);
        }
    }

    @BeforeEach
    void setUp() {
        auditService = AuditService.getInstance();
        auditService.clear();
        UserSession.logout();
    }

    @Test
    void recordsAuditEventsSuccessfully() {
        UserSession.login(501L, "ops_john", "OPERATIONSMANAGER");

        AuditService.record(AuditAction.LOGIN, "USER", "501", "Operator authenticated");
        AuditService.record(AuditAction.FLIGHT_UPDATE, "FLIGHT", "101", "Gate changed to B4");

        assertEquals(2, auditService.count());
        List<AuditEvent> events = auditService.getRecentEvents(10);
        assertEquals(2, events.size());

        // Latest event first
        assertEquals(AuditAction.FLIGHT_UPDATE, events.get(0).action());
        assertEquals("FLIGHT", events.get(0).targetType());
        assertEquals("ops_john", events.get(0).username());
        assertEquals("OPERATIONSMANAGER", events.get(0).role());

        assertEquals(AuditAction.LOGIN, events.get(1).action());
    }

    @Test
    void automaticallyRedactsSensitiveDataInAuditDetails() {
        AuditService.record(
            AuditAction.PAYMENT_PROCESS,
            "PAYMENT",
            "REF-1234",
            "Payment processed with password=SecretPassword123 and card 4111222233334444 CVV: 999"
        );

        List<AuditEvent> events = auditService.getRecentEvents(1);
        assertEquals(1, events.size());
        String details = events.get(0).details();

        assertFalse(details.contains("SecretPassword123"), "Password must be redacted");
        assertFalse(details.contains("4111222233334444"), "Card number must be redacted");
        assertTrue(details.contains("[REDACTED]") || details.contains("[CARD_REDACTED]"));
    }

    @Test
    void circularBufferMaintainsCapacityLimit() {
        for (int i = 0; i < 600; i++) {
            auditService.log(AuditAction.LOGIN, "SYSTEM", String.valueOf(i), "Event " + i);
        }
        // Max capacity is 500
        assertEquals(500, auditService.count());
    }
}
