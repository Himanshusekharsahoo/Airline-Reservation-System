package airline.management.system;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReservationServiceIntegrationTest {
    private static final String TEST_DB = "project4test";
    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdPassengerIds = Collections.synchronizedSet(new LinkedHashSet<>());

    @BeforeAll static void setupTestDatabase() throws Exception {
        System.setProperty("DB_NAME", TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();
        try (Connection c = DBConnection.getConnection()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
        }
        executeScript("schema.sql");
        if (!activeSeatColumnExists()) executeScript("migration_3b.sql");
        cleanupTestFixtures();
    }

    @AfterAll static void cleanup() throws Exception {
        cleanupTestFixtures();
    }

    @Test @Order(1) void successfulBookingCreatesConfirmedReservationAndPayment() throws Exception {
        long flight = flight(2), passenger = passenger(); int before = available(flight);
        String pnr = ReservationService.book(flight, passenger, "1A", "UPI");
        try (Connection c=DBConnection.getConnection(); PreparedStatement p=c.prepareStatement("SELECT r.status,r.seat_number,p.payment_status,p.amount FROM reservations r JOIN payments p ON p.reservation_id=r.id WHERE r.pnr=?")) {p.setString(1,pnr);try(ResultSet r=p.executeQuery()){assertTrue(r.next());assertEquals("CONFIRMED",r.getString(1));assertEquals("1A",r.getString(2));assertEquals("SUCCESS",r.getString(3));assertEquals(0,new BigDecimal("100.00").compareTo(r.getBigDecimal(4)));}}
        assertEquals(before-1,available(flight));
    }
    @Test @Order(2) void duplicateSeatFailsWithoutSecondInventoryChange() throws Exception {
        long flight=flight(2), passenger=passenger(); ReservationService.book(flight,passenger,"1A","Cash"); int afterFirst=available(flight);
        assertThrows(IllegalArgumentException.class,()->ReservationService.book(flight,passenger,"1A","Cash"));
        assertEquals(afterFirst,available(flight)); assertEquals(1, activeSeatCount(flight,"1A"));
    }
    @Test @Order(3) void invalidSeatLeavesNoPartialBooking() throws Exception {
        long flight=flight(1), passenger=passenger(); int before=available(flight);
        assertThrows(IllegalArgumentException.class,()->ReservationService.book(flight,passenger,"0Z","UPI"));
        assertEquals(before,available(flight)); assertEquals(0,reservationCount(flight)); assertEquals(0,paymentCount(flight));
    }
    @Test @Order(4) void cancellationStoresReasonRefundsAndReleasesSeat() throws Exception {
        long flight=flight(1), passenger=passenger(); String pnr=ReservationService.book(flight,passenger,"1A","Card");
        assertEquals(0,new BigDecimal("100.00").compareTo(ReservationService.cancel(pnr,"JUnit cancellation test")));
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT r.status,c.reason,c.refund_amount,p.payment_status FROM reservations r JOIN cancellations c ON c.reservation_id=r.id JOIN payments p ON p.reservation_id=r.id WHERE r.pnr=?")){p.setString(1,pnr);try(ResultSet r=p.executeQuery()){assertTrue(r.next());assertEquals("CANCELLED",r.getString(1));assertEquals("JUnit cancellation test",r.getString(2));assertEquals(0,new BigDecimal("100.00").compareTo(r.getBigDecimal(3)));assertEquals("REFUNDED",r.getString(4));}}
        assertEquals(1,available(flight)); assertDoesNotThrow(()->ReservationService.book(flight,passenger,"1A","Card"));
    }
    @Test @Order(5) void generatedPnrsAreUniqueAndIntegrityHolds() throws Exception {
        long flight=flight(3), passenger=passenger(); Set<String> pnrs=new HashSet<>(); for(String seat:List.of("1A","1B","1C"))pnrs.add(ReservationService.book(flight,passenger,seat,"UPI")); assertEquals(3,pnrs.size());
        try(Connection c=DBConnection.getConnection();Statement s=c.createStatement()){try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM (SELECT flight_id,active_seat_number FROM reservations WHERE active_seat_number IS NOT NULL GROUP BY flight_id,active_seat_number HAVING COUNT(*)>1) x")){r.next();assertEquals(0,r.getInt(1));}try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM flights WHERE available_seats<0")){r.next();assertEquals(0,r.getInt(1));}try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM payments p LEFT JOIN reservations r ON r.id=p.reservation_id WHERE r.id IS NULL")){r.next();assertEquals(0,r.getInt(1));}}
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

    private static void cleanupTestFixtures() throws Exception {
        TestDatabaseGuard.ensureTestDatabase();
        List<Throwable> failures = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            String fltIds = idListOrZero(createdFlightIds);
            String paxIds = idListOrZero(createdPassengerIds);

            String flightRefSub = "SELECT id FROM flights WHERE airline = 'JUnit Air' AND flight_number LIKE 'JUNIT%'";
            String paxRefSub = "SELECT id FROM passengers WHERE name = 'JUnit Passenger' AND email LIKE 'junit%@test.invalid'";
            String resRefSub = "SELECT id FROM reservations WHERE flight_id IN (" + fltIds + ") OR passenger_id IN (" + paxIds + ") " +
                    "OR flight_id IN (" + flightRefSub + ") OR passenger_id IN (" + paxRefSub + ")";

            executeScopedDelete(s, "cancellations", "DELETE FROM cancellations WHERE reservation_id IN (" + resRefSub + ")", failures);
            executeScopedDelete(s, "payments", "DELETE FROM payments WHERE reservation_id IN (" + resRefSub + ")", failures);
            executeScopedDelete(s, "reservations", "DELETE FROM reservations WHERE flight_id IN (" + fltIds + ") OR passenger_id IN (" + paxIds + ") " +
                    "OR flight_id IN (" + flightRefSub + ") OR passenger_id IN (" + paxRefSub + ")", failures);
            executeScopedDelete(s, "passengers", "DELETE FROM passengers WHERE id IN (" + paxIds + ") OR (name = 'JUnit Passenger' AND email LIKE 'junit%@test.invalid')", failures);
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR (airline = 'JUnit Air' AND flight_number LIKE 'JUNIT%')", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdPassengerIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("ReservationServiceIntegrationTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    private static long flight(int seats) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO flights(flight_number,airline,source,destination,departure_time,arrival_time,total_seats,available_seats,fare,status) " +
                 "VALUES(?, 'JUnit Air','A','B','2031-01-01 09:00:00','2031-01-01 10:00:00',?,?,100.00,'SCHEDULED')",
                 Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, "JUNIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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

    private static long passenger() throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO passengers(name,phone,email) VALUES('JUnit Passenger',?,?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            p.setString(1, "+100" + id.replaceAll("[a-f]", "1"));
            p.setString(2, "junit" + id + "@test.invalid");
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                long idNum = r.getLong(1);
                createdPassengerIds.add(idNum);
                return idNum;
            }
        }
    }

    private static int available(long id) throws SQLException { return count("SELECT available_seats FROM flights WHERE id=" + id); }
    private static int reservationCount(long id) throws SQLException { return count("SELECT COUNT(*) FROM reservations WHERE flight_id=" + id); }
    private static int paymentCount(long id) throws SQLException { return count("SELECT COUNT(*) FROM payments p JOIN reservations r ON r.id=p.reservation_id WHERE r.flight_id=" + id); }
    private static int activeSeatCount(long id, String seat) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM reservations WHERE flight_id=? AND active_seat_number=?")) {
            p.setLong(1, id);
            p.setString(2, seat);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }
    private static int count(String sql) throws SQLException {
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }
    private static void executeScript(String file) throws Exception {
        String sql = Files.readString(Path.of("..", "database", file)).replace("project4", TEST_DB).replaceAll("(?m)^--.*(?:\\R|$)", "");
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            for (String part : sql.split(";")) if (!part.isBlank()) s.execute(part);
        }
    }
    private static boolean activeSeatColumnExists() throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND table_name='reservations' AND column_name='active_seat_number'")) {
            p.setString(1, TEST_DB);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }
}
