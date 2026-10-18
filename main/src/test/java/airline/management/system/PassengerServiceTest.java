package airline.management.system;

import airline.management.system.audit.AuditAction;
import airline.management.system.audit.AuditEvent;
import airline.management.system.audit.AuditService;
import airline.management.system.security.AccessDeniedException;
import airline.management.system.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PassengerServiceTest {

    @BeforeEach
    void setUp() {
        AuditService.getInstance().clear();
    }

    // ------------------------------------------------------------------------
    // 1. Input Validation Tests
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Validation rejects null, blank, or excessively long passenger names")
    void testValidatePassengerName() {
        // Null name
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData(null, "+1234567890", "test@aero.com", 30));
        assertEquals("Passenger name is required.", ex1.getMessage());

        // Empty / whitespace name
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("   ", "+1234567890", "test@aero.com", 30));
        assertEquals("Passenger name is required.", ex2.getMessage());

        // Over 100 characters
        String longName = "A".repeat(101);
        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData(longName, "+1234567890", "test@aero.com", 30));
        assertEquals("Passenger name cannot exceed 100 characters.", ex3.getMessage());
    }

    @Test
    @DisplayName("Validation rejects missing, malformed, or excessively long phone numbers")
    void testValidatePassengerPhone() {
        // Null phone
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", null, "test@aero.com", 30));
        assertEquals("Please enter a valid phone number.", ex1.getMessage());

        // Empty phone
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "", "test@aero.com", 30));
        assertEquals("Please enter a valid phone number.", ex2.getMessage());

        // Malformed phone with alphabetic characters
        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "phone12345", "test@aero.com", 30));
        assertEquals("Please enter a valid phone number.", ex3.getMessage());

        // Too short (< 7 characters)
        IllegalArgumentException ex4 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "12345", "test@aero.com", 30));
        assertEquals("Please enter a valid phone number.", ex4.getMessage());

        // Over 30 characters
        String longPhone = "1".repeat(31);
        IllegalArgumentException ex5 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", longPhone, "test@aero.com", 30));
        assertEquals("Phone number cannot exceed 30 characters.", ex5.getMessage());
    }

    @Test
    @DisplayName("Validation rejects malformed or overlength emails, but accepts valid or null/empty emails")
    void testValidatePassengerEmail() {
        // Malformed email
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "+1234567890", "not-an-email", 30));
        assertEquals("Please enter a valid email address.", ex1.getMessage());

        // Missing domain dot
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "+1234567890", "john@domain", 30));
        assertEquals("Please enter a valid email address.", ex2.getMessage());

        // Over 254 characters
        String longEmail = "a".repeat(245) + "@domain.com";
        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "+1234567890", longEmail, 30));
        assertEquals("Email address cannot exceed 254 characters.", ex3.getMessage());

        // Valid email passes
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("John Doe", "+1234567890", "john.doe@aero.com", 30));

        // Null and empty emails pass (optional field)
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("John Doe", "+1234567890", null, 30));
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("John Doe", "+1234567890", "   ", 30));
    }

    @Test
    @DisplayName("Validation rejects negative or unrealistic ages, but accepts null or valid ages (0-120)")
    void testValidatePassengerAge() {
        // Negative age
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "+1234567890", "john@aero.com", -1));
        assertEquals("Age must be between 0 and 120.", ex1.getMessage());

        // Over 120
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
            PassengerService.validatePassengerData("John Doe", "+1234567890", "john@aero.com", 121));
        assertEquals("Age must be between 0 and 120.", ex2.getMessage());

        // Boundary ages 0 and 120 pass
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("Infant Doe", "+1234567890", null, 0));
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("Senior Doe", "+1234567890", null, 120));

        // Null age passes (optional)
        assertDoesNotThrow(() -> PassengerService.validatePassengerData("John Doe", "+1234567890", null, null));
    }

    @Test
    @DisplayName("Service operations reject invalid passenger IDs (<= 0)")
    void testInvalidIdRejection() {
        assertThrows(IllegalArgumentException.class, () ->
            PassengerService.updatePassenger(0, "Name", "+1234567890", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            PassengerService.deletePassenger(-5));
        assertThrows(IllegalArgumentException.class, () ->
            PassengerService.getReservationHistory(0));
        assertThrows(IllegalArgumentException.class, () ->
            PassengerService.getPassengerById(-1));
    }

    // ------------------------------------------------------------------------
    // 2. Mock / Proxy JDBC Service Execution Tests
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Successful passenger creation generates ID and records PASSENGER_CREATE audit event")
    void testCreatePassengerSuccessWithAudit() throws Exception {
        AtomicInteger generatedId = new AtomicInteger(42);

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeUpdate".equals(psMethod.getName())) {
                        return 1;
                    }
                    if ("getGeneratedKeys".equals(psMethod.getName())) {
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return true;
                            if ("getLong".equals(rsMethod.getName())) return (long) generatedId.get();
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        long newId = PassengerService.createPassenger(mockConn, "Alice Smith", "+1-555-0199", "alice@example.com", 28, "Female", "123 Main St");
        assertEquals(42L, newId);

        // Verify audit event
        List<AuditEvent> events = AuditService.getInstance().getRecentEvents(5);
        assertEquals(1, events.size());
        AuditEvent event = events.get(0);
        assertEquals(AuditAction.PASSENGER_CREATE, event.action());
        assertEquals("PASSENGER", event.targetType());
        assertEquals("42", event.targetId());
        assertTrue(event.details().contains("Alice Smith"));
    }

    @Test
    @DisplayName("Duplicate email constraint violation throws SQLIntegrityConstraintViolationException")
    void testCreatePassengerDuplicateEmailThrowsConstraintException() {
        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeUpdate".equals(psMethod.getName())) {
                        throw new SQLIntegrityConstraintViolationException("Duplicate entry 'alice@example.com' for key 'passengers.email'", "23000", 1062);
                    }
                    return null;
                });
            }
            return null;
        });

        SQLIntegrityConstraintViolationException ex = assertThrows(SQLIntegrityConstraintViolationException.class, () ->
            PassengerService.createPassenger(mockConn, "Alice Smith", "+1-555-0199", "alice@example.com", 28, "Female", "123 Main St"));
        assertEquals("A passenger with this email address already exists.", ex.getMessage());
    }

    @Test
    @DisplayName("Successful passenger update records PASSENGER_UPDATE audit event")
    void testUpdatePassengerSuccessWithAudit() throws Exception {
        AtomicBoolean checkExecuted = new AtomicBoolean(false);
        AtomicBoolean updateExecuted = new AtomicBoolean(false);

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                String sql = (String) args[0];
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        checkExecuted.set(true);
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return true;
                            if ("getLong".equals(rsMethod.getName())) return 1L; // matches tenant 1
                            return null;
                        });
                    }
                    if ("executeUpdate".equals(psMethod.getName())) {
                        updateExecuted.set(true);
                        return 1;
                    }
                    return null;
                });
            }
            return null;
        });

        assertDoesNotThrow(() ->
            PassengerService.updatePassenger(mockConn, 10L, "Bob Jones", "+1-555-9876", "bob@example.com", 45, "Male", "456 Market St"));

        assertTrue(checkExecuted.get(), "Tenant existence check must be executed");
        assertTrue(updateExecuted.get(), "Update statement must be executed");

        List<AuditEvent> events = AuditService.getInstance().getRecentEvents(5);
        assertEquals(1, events.size());
        AuditEvent event = events.get(0);
        assertEquals(AuditAction.PASSENGER_UPDATE, event.action());
        assertEquals("PASSENGER", event.targetType());
        assertEquals("10", event.targetId());
        assertTrue(event.details().contains("Bob Jones"));
    }

    @Test
    @DisplayName("Update passenger throws AccessDeniedException when passenger belongs to a different tenant")
    void testUpdatePassengerCrossTenantViolation() {
        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return true;
                            if ("getLong".equals(rsMethod.getName())) return 999L; // Different from tenant 1
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        assertThrows(AccessDeniedException.class, () ->
            PassengerService.updatePassenger(mockConn, 10L, "Bob Jones", "+1-555-9876", "bob@example.com", 45, "Male", "456 Market St"));
    }

    @Test
    @DisplayName("Delete passenger is blocked when reservation history exists")
    void testDeletePassengerBlockedByReservationHistory() {
        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                String sql = (String) args[0];
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        if (sql.contains("reservations")) {
                            // Reservation exists!
                            return createProxyResultSet((rsMethod, rsArgs) -> {
                                if ("next".equals(rsMethod.getName())) return true;
                                return null;
                            });
                        } else {
                            // Passenger check
                            return createProxyResultSet((rsMethod, rsArgs) -> {
                                if ("next".equals(rsMethod.getName())) return true;
                                if ("getLong".equals(rsMethod.getName())) return 1L;
                                return null;
                            });
                        }
                    }
                    return null;
                });
            }
            return null;
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            PassengerService.deletePassenger(mockConn, 15L));
        assertEquals("This passenger cannot be deleted because reservation history exists.", ex.getMessage());
    }

    @Test
    @DisplayName("Delete passenger succeeds when no reservation history exists")
    void testDeletePassengerSuccess() throws Exception {
        AtomicBoolean deleteExecuted = new AtomicBoolean(false);

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                String sql = (String) args[0];
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        if (sql.contains("reservations")) {
                            // No reservations!
                            return createProxyResultSet((rsMethod, rsArgs) -> {
                                if ("next".equals(rsMethod.getName())) return false;
                                return null;
                            });
                        } else {
                            // Passenger check
                            return createProxyResultSet((rsMethod, rsArgs) -> {
                                if ("next".equals(rsMethod.getName())) return true;
                                if ("getLong".equals(rsMethod.getName())) return 1L;
                                return null;
                            });
                        }
                    }
                    if ("executeUpdate".equals(psMethod.getName())) {
                        deleteExecuted.set(true);
                        return 1;
                    }
                    return null;
                });
            }
            return null;
        });

        assertDoesNotThrow(() -> PassengerService.deletePassenger(mockConn, 20L));
        assertTrue(deleteExecuted.get(), "DELETE statement must be executed");
    }

    @Test
    @DisplayName("Search passengers maps columns correctly into PassengerRecord")
    void testSearchPassengersMapping() throws Exception {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        AtomicInteger rowCount = new AtomicInteger(0);
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) {
                                return rowCount.getAndIncrement() < 1;
                            }
                            if ("getLong".equals(rsMethod.getName())) return 101L;
                            if ("getString".equals(rsMethod.getName())) {
                                String col = (String) rsArgs[0];
                                return switch (col) {
                                    case "legacy_pnr" -> "LEG-101";
                                    case "display_pnr" -> "LEG-101";
                                    case "name" -> "Carol Danvers";
                                    case "gender" -> "Female";
                                    case "phone" -> "+1-555-7788";
                                    case "email" -> "carol@marvel.com";
                                    case "address" -> "Hangar 18";
                                    default -> "—";
                                };
                            }
                            if ("getInt".equals(rsMethod.getName())) {
                                String col = (String) rsArgs[0];
                                if ("age".equals(col)) return 32;
                                if ("reservation_count".equals(col)) return 3;
                                return 0;
                            }
                            if ("wasNull".equals(rsMethod.getName())) return false;
                            if ("getTimestamp".equals(rsMethod.getName())) return now;
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        List<PassengerService.PassengerRecord> results = PassengerService.searchPassengers(mockConn, "Carol", "All Passengers");
        assertEquals(1, results.size());
        PassengerService.PassengerRecord p = results.get(0);
        assertEquals(101L, p.id());
        assertEquals("Carol Danvers", p.name());
        assertEquals("LEG-101", p.displayPnr());
        assertEquals(32, p.age());
        assertEquals("Female", p.gender());
        assertEquals("+1-555-7788", p.phone());
        assertEquals("carol@marvel.com", p.email());
        assertEquals("Hangar 18", p.address());
        assertEquals(3, p.reservationCount());
        assertEquals(now, p.createdAt());
    }

    @Test
    @DisplayName("Reservation history maps flight and reservation details accurately")
    void testGetReservationHistoryMapping() throws Exception {
        Timestamp bookingDate = new Timestamp(System.currentTimeMillis());
        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("executeQuery".equals(psMethod.getName())) {
                        AtomicInteger rowCount = new AtomicInteger(0);
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) {
                                return rowCount.getAndIncrement() < 1;
                            }
                            if ("getString".equals(rsMethod.getName())) {
                                String col = (String) rsArgs[0];
                                return switch (col) {
                                    case "flight_number" -> "AD-101";
                                    case "pnr" -> "PNR999";
                                    case "source" -> "MNL";
                                    case "destination" -> "CEB";
                                    case "seat_number" -> "12A";
                                    case "status" -> "CONFIRMED";
                                    default -> "—";
                                };
                            }
                            if ("getDouble".equals(rsMethod.getName())) return 4500.50;
                            if ("getTimestamp".equals(rsMethod.getName())) return bookingDate;
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        List<PassengerService.PassengerReservationRecord> history = PassengerService.getReservationHistory(mockConn, 101L);
        assertEquals(1, history.size());
        PassengerService.PassengerReservationRecord r = history.get(0);
        assertEquals("AD-101", r.flightNumber());
        assertEquals("PNR999", r.pnr());
        assertEquals("MNL → CEB", r.route());
        assertEquals("12A", r.seatNumber());
        assertEquals(4500.50, r.fare(), 0.001);
        assertEquals("CONFIRMED", r.status());
        assertEquals(bookingDate, r.bookingDate());
    }

    // ------------------------------------------------------------------------
    // Dynamic Reflection Proxy Factory Helpers
    // ------------------------------------------------------------------------

    private interface FunctionalHandler {
        Object handle(Method method, Object[] args) throws Throwable;
    }

    private static Connection createProxyConnection(FunctionalHandler handler) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) return null;
                if ("isClosed".equals(method.getName())) return false;
                Object res = handler.handle(method, args);
                if (res != null) return res;
                return defaultReturn(method.getReturnType());
            }
        );
    }

    private static PreparedStatement createProxyPreparedStatement(FunctionalHandler handler) {
        return (PreparedStatement) Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) return null;
                Object res = handler.handle(method, args);
                if (res != null) return res;
                return defaultReturn(method.getReturnType());
            }
        );
    }

    private static ResultSet createProxyResultSet(FunctionalHandler handler) {
        return (ResultSet) Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) return null;
                Object res = handler.handle(method, args);
                if (res != null) return res;
                return defaultReturn(method.getReturnType());
            }
        );
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }
}
