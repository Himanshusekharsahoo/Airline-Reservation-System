package airline.management.system;

import airline.management.system.operations.flight.FlightOperationsService;
import airline.management.system.operations.gate.GateRecord;
import airline.management.system.operations.gate.GateService;
import airline.management.system.security.Role;
import airline.management.system.staff.StaffRecord;
import airline.management.system.staff.StaffService;
import airline.management.system.tenant.Tenant;
import airline.management.system.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Focused Phase 13 service tests; all mutations are confined to project4test. */
class Phase13OperationsTest {
    private static final String TEST_DB = "project4test";
    private static final Set<Long> createdFlightIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Long> createdOrgIds = Collections.synchronizedSet(new LinkedHashSet<>());

    @BeforeAll static void setup() throws Exception {
        System.setProperty("DB_NAME", TEST_DB);
        TestDatabaseGuard.ensureTestDatabase();
        try (Connection c = DBConnection.getConnection()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
        }
        script("schema.sql");
        if (!activeSeatColumnExists()) script("migration_3b.sql");
        script("v2_phase11_operations_schema.sql");
        cleanupTestFixtures();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "INSERT INTO app_users(id,username,password_hash,role,active) VALUES(900,'phase13_admin','hash','SUPERADMIN',TRUE) " +
                 "ON DUPLICATE KEY UPDATE role='SUPERADMIN', active=TRUE")) {
            p.executeUpdate();
        }
        UserSession.login(900,"phase13_admin",Role.SUPERADMIN.name());
    }

    @AfterAll static void cleanup() throws Exception {
        cleanupTestFixtures();
        UserSession.logout();
    }

    @Test void staffCreationDuplicateRejectionRoleChangeAndLastAdminProtection() throws Exception {
        String name="staff_"+UUID.randomUUID().toString().substring(0,8);
        StaffRecord staff=StaffService.create(name,"temporary-password".toCharArray(),Role.CHECKINAGENT.name());
        assertEquals(Role.CHECKINAGENT,staff.role()); assertThrows(Exception.class,()->StaffService.create(name,"temporary-password".toCharArray(),Role.CHECKINAGENT.name()));
        StaffService.changeRole(staff.id(),Role.GATEAGENT.name()); assertEquals(Role.GATEAGENT,StaffService.find(name,"ALL",null).get(0).role());
        StaffService.setActive(staff.id(),false); assertFalse(StaffService.find(name,"ALL",null).get(0).active());
        List<Long> otherActiveAdminIds = new ArrayList<>();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement("SELECT id FROM app_users WHERE id != 900 AND active=TRUE AND role IN ('SUPERADMIN','AIRLINEADMIN','ADMIN')");
             ResultSet r = p.executeQuery()) {
            while (r.next()) otherActiveAdminIds.add(r.getLong(1));
        }
        try {
            if (!otherActiveAdminIds.isEmpty()) {
                try (Connection c = DBConnection.getConnection();
                     PreparedStatement p = c.prepareStatement("UPDATE app_users SET active=FALSE WHERE id != 900 AND role IN ('SUPERADMIN','AIRLINEADMIN','ADMIN')")) {
                    p.executeUpdate();
                }
            }
            assertThrows(IllegalStateException.class, () -> StaffService.changeRole(900, Role.RESERVATIONAGENT.name()));
        } finally {
            if (!otherActiveAdminIds.isEmpty()) {
                try (Connection c = DBConnection.getConnection();
                     PreparedStatement p = c.prepareStatement("UPDATE app_users SET active=TRUE WHERE id = ?")) {
                    for (Long aid : otherActiveAdminIds) {
                        p.setLong(1, aid);
                        p.executeUpdate();
                    }
                }
            }
        }
    }

    @Test void validFlightLifecycleWritesHistoryAndRejectsInvalidTransition() throws Exception {
        long flight=flight("OPS");
        FlightOperationsService.transition(flight,FlightOperationsService.Status.DELAYED,"Weather");
        FlightOperationsService.transition(flight,FlightOperationsService.Status.BOARDING,"Cleared");
        assertThrows(IllegalStateException.class,()->FlightOperationsService.transition(flight,FlightOperationsService.Status.ARRIVED,"Too early"));
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT status FROM flights WHERE id=?")){p.setLong(1,flight);try(ResultSet r=p.executeQuery()){assertTrue(r.next());assertEquals("BOARDING",r.getString(1));}}
        assertEquals(2,count("SELECT COUNT(*) FROM flight_status_history WHERE flight_id="+flight));
    }

    @Test void gateReleaseRequiresFinishedFlightAndPersistsAvailableState() throws Exception {
        long flight=flight("GATE"); GateService gates=GateService.getInstance(); GateRecord gate=gates.getAllGates().get(0);
        gates.assignFlightGate(flight,gate.id(),LocalDateTime.of(2032,1,1,9,0),LocalDateTime.of(2032,1,1,10,0));
        assertThrows(IllegalStateException.class,()->gates.releaseFlightGate(flight));
        FlightOperationsService.transition(flight,FlightOperationsService.Status.BOARDING,"Boarding"); FlightOperationsService.transition(flight,FlightOperationsService.Status.DEPARTED,"Departed"); gates.releaseFlightGate(flight);
        assertEquals("AVAILABLE",string("SELECT status FROM gates WHERE id="+gate.id()));
    }

    @Test void gateReadIsTenantScoped() throws Exception {
        String code="T"+UUID.randomUUID().toString().replace("-","").substring(0,6).toUpperCase(); long organization;
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO organizations(org_code,org_name,contact_email) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){p.setString(1,code);p.setString(2,"JUnit Tenant");p.setString(3,code.toLowerCase()+"@test.invalid");p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();organization=r.getLong(1);createdOrgIds.add(organization);}}
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO gates(organization_id,airport_code,terminal,gate_number,status) VALUES(?, 'TST', 'T1', 'X1', 'AVAILABLE')")){p.setLong(1,organization);p.executeUpdate();}
        TenantContext.setCurrentTenant(new Tenant(organization,code,"JUnit Tenant",true));
        try { assertEquals(1,GateService.getInstance().getAllGates().size()); }
        finally { TenantContext.clear(); }
    }

    private static long flight(String prefix) throws Exception {
        try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO flights(flight_number,airline,source,destination,departure_time,arrival_time,total_seats,available_seats,fare,status) VALUES(?,'JUnit Ops','A','B','2032-01-01 09:00:00','2032-01-01 10:00:00',10,10,99.00,'SCHEDULED')",Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,prefix+UUID.randomUUID().toString().substring(0,6));
            p.executeUpdate();
            try(ResultSet r=p.getGeneratedKeys()){r.next();long id=r.getLong(1);createdFlightIds.add(id);return id;}
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

    private static void cleanupTestFixtures() throws Exception {
        TestDatabaseGuard.ensureTestDatabase();
        List<Throwable> failures = new ArrayList<>();
        try (Connection c = DBConnection.getConnection(); Statement s = c.createStatement()) {
            TestDatabaseGuard.validateConnectionCatalog(c);
            String fltIds = idListOrZero(createdFlightIds);
            String orgIds = idListOrZero(createdOrgIds);
            String fltSub = "SELECT id FROM flights WHERE id IN (" + fltIds + ") OR airline = 'JUnit Ops'";

            // Dependent records first
            executeScopedDelete(s, "flight_gates", "DELETE FROM flight_gates WHERE flight_id IN (" + fltSub + ")", failures);
            executeScopedDelete(s, "flight_status_history", "DELETE FROM flight_status_history WHERE flight_id IN (" + fltSub + ")", failures);

            // Gates and tenant organizations
            executeScopedDelete(s, "gates", "DELETE FROM gates WHERE organization_id IN (" + orgIds + ") OR (airport_code = 'TST' AND gate_number = 'X1')", failures);
            executeScopedDelete(s, "organizations", "DELETE FROM organizations WHERE id IN (" + orgIds + ") OR (org_name = 'JUnit Tenant' AND org_code LIKE 'T%')", failures);

            // Flights
            executeScopedDelete(s, "flights", "DELETE FROM flights WHERE id IN (" + fltIds + ") OR airline = 'JUnit Ops'", failures);

            // Test users
            executeScopedDelete(s, "user_roles", "DELETE FROM user_roles WHERE user_id = 900 OR user_id IN (SELECT id FROM app_users WHERE username LIKE 'staff_%')", failures);
            executeScopedDelete(s, "audit_logs", "DELETE FROM audit_logs WHERE user_id = 900 OR username = 'phase13_admin' OR username LIKE 'staff_%'", failures);
            executeScopedDelete(s, "app_users", "DELETE FROM app_users WHERE id = 900 OR username = 'phase13_admin' OR username LIKE 'staff_%'", failures);
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            createdFlightIds.clear();
            createdOrgIds.clear();
        }

        if (!failures.isEmpty()) {
            SQLException composite = new SQLException("Phase13OperationsTest cleanup failed with " + failures.size() + " error(s)");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }

    private static void script(String file)throws Exception{String sql=java.nio.file.Files.readString(java.nio.file.Path.of("..","database",file)).replace("project4",TEST_DB).replaceAll("(?m)^--.*(?:\\R|$)","");try(Connection c=DBConnection.getConnection();Statement s=c.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);}}
    private static int count(String sql)throws Exception{try(Connection c=DBConnection.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}} private static String string(String sql)throws Exception{try(Connection c=DBConnection.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    private static boolean activeSeatColumnExists()throws Exception{try(Connection c=DBConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_schema=? AND table_name='reservations' AND column_name='active_seat_number'")){p.setString(1,TEST_DB);try(ResultSet r=p.executeQuery()){return r.next();}}}
}
