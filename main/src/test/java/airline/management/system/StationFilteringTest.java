package airline.management.system;

import airline.management.system.operations.baggage.BaggageRecord;
import airline.management.system.operations.baggage.BaggageService.BaggageSummary;
import airline.management.system.operations.baggage.BaggageServiceImpl;
import airline.management.system.operations.baggage.BaggageStatus;
import airline.management.system.operations.boarding.BoardingServiceImpl;
import airline.management.system.operations.boarding.FlightBoardingSummary;
import airline.management.system.operations.gate.GateRecord;
import airline.management.system.operations.gate.GateServiceImpl;
import airline.management.system.tenant.Tenant;
import airline.management.system.tenant.TenantContext;
import airline.management.system.ui.layout.OperationalStation;
import airline.management.system.ui.layout.StationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StationFilteringTest {

    private OperationalStation initialStation;
    private Tenant initialTenant;

    @BeforeEach
    void setUp() {
        initialStation = StationContext.getCurrentStation();
        initialTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(new Tenant(1L, "AD", "AeroDesk Operations", true));
        StationContext.setStation(OperationalStation.DEL);
    }

    @AfterEach
    void tearDown() {
        StationContext.setStation(initialStation != null ? initialStation : OperationalStation.DEL);
        TenantContext.setCurrentTenant(initialTenant != null ? initialTenant : Tenant.DEFAULT);
    }

    // ------------------------------------------------------------------------
    // 1. StationContext Lifecycle, Listeners, and Fail-Closed Resolution
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("StationContext provides safe resolution, aliases, and duplicate listener prevention")
    void testStationContextListenersAndLookup() throws Exception {
        // findByCode resolution
        assertEquals(OperationalStation.DEL, StationContext.findByCode("DEL"));
        assertEquals(OperationalStation.BOM, StationContext.findByCode("bom"));
        assertEquals(OperationalStation.DXB, StationContext.findByCode(" dxb "));
        // Unknown or empty falls back to DEL for findByCode
        assertEquals(OperationalStation.DEL, StationContext.findByCode("UNKNOWN_STATION"));
        assertEquals(OperationalStation.DEL, StationContext.findByCode(null));
        assertEquals(OperationalStation.DEL, StationContext.findByCode("  "));

        // findStation returns Optional
        assertTrue(StationContext.findStation("DEL").isPresent());
        assertEquals(OperationalStation.DEL, StationContext.findStation("DEL").get());
        assertTrue(StationContext.findStation("bom").isPresent());
        assertEquals(OperationalStation.BOM, StationContext.findStation("bom").get());
        assertTrue(StationContext.findStation("XYZ").isEmpty());
        assertTrue(StationContext.findStation(null).isEmpty());
        assertTrue(StationContext.findStation("  ").isEmpty());

        // Listener registration and notification dispatch
        List<OperationalStation> received = Collections.synchronizedList(new ArrayList<>());
        Consumer<OperationalStation> listener = received::add;

        StationContext.addStationChangeListener(listener);
        // Duplicate registration should be prevented
        StationContext.addStationChangeListener(listener);

        // Change station to BOM
        StationContext.setStation(OperationalStation.BOM);
        flushSwingEvents();

        assertEquals(OperationalStation.BOM, StationContext.getCurrentStation());
        assertEquals(1, received.size(), "Listener should receive exactly one event despite duplicate add calls");
        assertEquals(OperationalStation.BOM, received.get(0));

        // Setting the same station again must NOT re-notify
        StationContext.setStation(OperationalStation.BOM);
        flushSwingEvents();
        assertEquals(1, received.size(), "Re-setting same station should not trigger listener");

        // Remove listener via alias
        StationContext.removeStationChangeListener(listener);
        StationContext.setStation(OperationalStation.DXB);
        flushSwingEvents();
        assertEquals(1, received.size(), "Unregistered listener must not receive further events");
        assertEquals(OperationalStation.DXB, StationContext.getCurrentStation());
    }

    // ------------------------------------------------------------------------
    // 2. BoardingService Station Scoping & Fail-Closed Behavior
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("BoardingService scopes scheduled flights to origin station and fails closed on unknown station")
    void testBoardingServiceFlightScoping() throws Exception {
        AtomicReference<String> capturedSql = new AtomicReference<>("");
        List<Object> capturedParams = new ArrayList<>();

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                capturedSql.set((String) args[0]);
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("setObject".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("executeQuery".equals(psMethod.getName())) {
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return false; // empty result
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        // 1. Valid station BOM -> origin filter source = BOM or Mumbai
        capturedParams.clear();
        List<FlightBoardingSummary> resultBom = BoardingServiceImpl.INSTANCE.getScheduledFlights(mockConn, "BOM");
        assertNotNull(resultBom);
        assertTrue(capturedSql.get().contains("f.source = ? OR f.source = ?"));
        assertTrue(capturedParams.contains("BOM"));
        assertTrue(capturedParams.contains("Mumbai"));
        assertTrue(capturedParams.contains(1L), "Tenant org ID must be bound");

        // 2. Unknown station XYZ -> fail closed: matches exact code (yielding 0 rows, never leaking all stations)
        capturedParams.clear();
        capturedSql.set("");
        List<FlightBoardingSummary> resultXyz = BoardingServiceImpl.INSTANCE.getScheduledFlights(mockConn, "XYZ");
        assertNotNull(resultXyz);
        assertTrue(capturedSql.get().contains("f.source = ?"));
        assertFalse(capturedSql.get().contains("f.source = ? OR f.source = ?"), "Unknown station must not match city");
        assertTrue(capturedParams.contains("XYZ"));

        // 3. Null stationCode -> defaults to StationContext.getCurrentStation()
        StationContext.setStation(OperationalStation.DEL);
        capturedParams.clear();
        capturedSql.set("");
        List<FlightBoardingSummary> resultDefault = BoardingServiceImpl.INSTANCE.getScheduledFlights(mockConn, null);
        assertNotNull(resultDefault);
        assertTrue(capturedSql.get().contains("f.source = ? OR f.source = ?"));
        assertTrue(capturedParams.contains("DEL"));
        assertTrue(capturedParams.contains("Delhi"));
    }

    // ------------------------------------------------------------------------
    // 3. GateService Airport Scoping
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("GateService scopes gate allocation to active airport code with case-insensitivity")
    void testGateServiceAirportScoping() throws Exception {
        AtomicReference<String> capturedSql = new AtomicReference<>("");
        List<Object> capturedParams = new ArrayList<>();

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                capturedSql.set((String) args[0]);
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("setLong".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("setString".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("executeQuery".equals(psMethod.getName())) {
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return false;
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        // Test with DEL
        List<GateRecord> gatesDel = GateServiceImpl.INSTANCE.getGatesByAirport(mockConn, "DEL");
        assertNotNull(gatesDel);
        assertTrue(capturedSql.get().contains("gates WHERE organization_id = ? AND UPPER(airport_code) = UPPER(?)"));
        assertEquals(2, capturedParams.size());
        assertEquals(1L, capturedParams.get(0), "Tenant org ID");
        assertEquals("DEL", capturedParams.get(1), "Airport code parameter");

        // Test with case-insensitive lowercase 'bom'
        capturedParams.clear();
        capturedSql.set("");
        List<GateRecord> gatesBom = GateServiceImpl.INSTANCE.getGatesByAirport(mockConn, "bom");
        assertNotNull(gatesBom);
        assertTrue(capturedSql.get().contains("UPPER(airport_code) = UPPER(?)"));
        assertEquals(2, capturedParams.size());
        assertEquals(1L, capturedParams.get(0));
        assertEquals("bom", capturedParams.get(1));

        // Test null fallback to active station
        StationContext.setStation(OperationalStation.DXB);
        capturedParams.clear();
        capturedSql.set("");
        List<GateRecord> gatesFallback = GateServiceImpl.INSTANCE.getGatesByAirport(mockConn, null);
        assertNotNull(gatesFallback);
        assertEquals(2, capturedParams.size());
        assertEquals("DXB", capturedParams.get(1));
    }

    // ------------------------------------------------------------------------
    // 4. BaggageService Station Scoping & Metric Calculation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("BaggageService filters baggage records and summary by station origin, destination, and event scan")
    void testBaggageServiceStationScoping() throws Exception {
        AtomicReference<String> capturedSql = new AtomicReference<>("");
        List<Object> capturedParams = new ArrayList<>();

        AtomicBoolean hasRow = new AtomicBoolean(true);

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                capturedSql.set((String) args[0]);
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("setObject".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("executeQuery".equals(psMethod.getName())) {
                        hasRow.set(true);
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            String mName = rsMethod.getName();
                            if ("next".equals(mName)) {
                                return hasRow.compareAndSet(true, false);
                            }
                            if ("getInt".equals(mName)) return 5;
                            if ("getBigDecimal".equals(mName)) return new BigDecimal("100.50");
                            if ("getLong".equals(mName)) return 1L;
                            if ("getString".equals(mName)) {
                                String col = rsArgs != null && rsArgs.length > 0 && rsArgs[0] instanceof String ? (String) rsArgs[0] : "";
                                if ("status".equalsIgnoreCase(col)) return "ACCEPTED";
                                if ("baggage_type".equalsIgnoreCase(col)) return "STANDARD";
                                if ("source".equalsIgnoreCase(col)) return "DEL";
                                if ("destination".equalsIgnoreCase(col)) return "BOM";
                                if ("tag_number".equalsIgnoreCase(col)) return "BAG0001";
                                return "TEST";
                            }
                            if ("getTimestamp".equals(mName)) return new Timestamp(System.currentTimeMillis());
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        // 1. searchBaggage with valid station DEL
        capturedParams.clear();
        capturedSql.set("");
        List<BaggageRecord> records = BaggageServiceImpl.INSTANCE.searchBaggage(mockConn, "TAG123", BaggageStatus.ACCEPTED, 10L, "DEL");
        assertNotNull(records);
        String sql = capturedSql.get();
        assertTrue(sql.contains("b.organization_id = ?"));
        assertTrue(sql.contains("b.tag_number LIKE ?"));
        assertTrue(sql.contains("b.status = ?"));
        assertTrue(sql.contains("b.flight_id = ?"));
        // Station scoping should match flight source/destination or scan event
        assertTrue(sql.contains("f.source = ? OR f.source = ? OR f.destination = ? OR f.destination = ?"));
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM baggage_events be WHERE be.baggage_id = b.id AND be.organization_id = b.organization_id AND (be.location_code = ? OR be.location_code = ?))"));
        assertTrue(capturedParams.contains("DEL"));
        assertTrue(capturedParams.contains("Delhi"));

        // 2. searchBaggage with unknown station XYZ -> fail-closed parameter binding
        capturedParams.clear();
        capturedSql.set("");
        BaggageServiceImpl.INSTANCE.searchBaggage(mockConn, null, null, null, "XYZ");
        String sqlXyz = capturedSql.get();
        assertTrue(sqlXyz.contains("f.source = ? OR f.destination = ?"));
        assertFalse(sqlXyz.contains("OR f.source = ? OR f.destination = ? OR f.destination = ?"));
        assertTrue(capturedParams.contains("XYZ"));

        // 3. getBaggageSummary with station DEL
        capturedParams.clear();
        capturedSql.set("");
        BaggageSummary summary = BaggageServiceImpl.INSTANCE.getBaggageSummary(mockConn, null, "DEL");
        assertNotNull(summary);
        String summarySql = capturedSql.get();
        assertTrue(summarySql.contains("SELECT COUNT(*) AS `total`"));
        assertTrue(summarySql.contains("FROM baggage b"));
        assertTrue(summarySql.contains("LEFT JOIN flights f ON f.id = b.flight_id"));
        assertTrue(summarySql.contains("f.source = ? OR f.source = ? OR f.destination = ? OR f.destination = ?"));
        assertEquals(5, summary.totalRegistered());
        assertEquals(5, summary.accepted());
        assertEquals(new BigDecimal("100.50"), summary.totalWeightKg());
    }

    // ------------------------------------------------------------------------
    // 5. Tenant Isolation Preserved Across Station Queries
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Tenant isolation is strictly preserved across station-scoped queries")
    void testTenantIsolationPreserved() throws Exception {
        TenantContext.setCurrentTenant(new Tenant(99L, "XX", "Test Airline", true));
        List<Object> capturedParams = new ArrayList<>();

        Connection mockConn = createProxyConnection((method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return createProxyPreparedStatement((psMethod, psArgs) -> {
                    if ("setObject".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("setLong".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("setString".equals(psMethod.getName())) {
                        capturedParams.add(psArgs[1]);
                    }
                    if ("executeQuery".equals(psMethod.getName())) {
                        return createProxyResultSet((rsMethod, rsArgs) -> {
                            if ("next".equals(rsMethod.getName())) return false;
                            return null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });

        // Boarding service
        capturedParams.clear();
        BoardingServiceImpl.INSTANCE.getScheduledFlights(mockConn, "DEL");
        assertTrue(capturedParams.contains(99L), "BoardingService must scope to tenant org ID 99");

        // Gate service
        capturedParams.clear();
        GateServiceImpl.INSTANCE.getGatesByAirport(mockConn, "DEL");
        assertTrue(capturedParams.contains(99L), "GateService must scope to tenant org ID 99");

        // Baggage service search
        capturedParams.clear();
        BaggageServiceImpl.INSTANCE.searchBaggage(mockConn, null, null, null, "DEL");
        assertTrue(capturedParams.contains(99L), "BaggageService search must scope to tenant org ID 99");

        // Baggage service summary
        capturedParams.clear();
        BaggageServiceImpl.INSTANCE.getBaggageSummary(mockConn, null, "DEL");
        assertTrue(capturedParams.contains(99L), "BaggageService summary must scope to tenant org ID 99");
    }

    // ------------------------------------------------------------------------
    // Helper Methods & Reflection Proxies
    // ------------------------------------------------------------------------

    private static void flushSwingEvents() throws InterruptedException, InvocationTargetException {
        if (SwingUtilities.isEventDispatchThread()) return;
        SwingUtilities.invokeAndWait(() -> {});
    }

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
