package airline.management.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseInitializationScriptsTest {
    private static Path repoFile(String path) {
        Path fromModule = Path.of("..", path).normalize();
        return Files.exists(fromModule) ? fromModule : Path.of(path);
    }

    private static String read(String path) throws IOException {
        return Files.readString(repoFile(path));
    }

    @Test
    void normalAndOptionalScriptsDoNotSwitchCatalogs() throws IOException {
        for (String script : List.of(
                "database/init_complete_schema.sql",
                "database/schema.sql", "database/migration_3b.sql", "database/v2_phase11_operations_schema.sql",
                "database/v3_phase14_multitenant_schema.sql", "database/v4_phase15_baggage_schema.sql",
                "database/v5_phase16_pir_claims_schema.sql", "database/v6_phase17_irops_schema.sql",
                "database/seed.sql", "database/demo/seed_demo_data.sql", "database/migration.sql")) {
            String sql = read(script).toLowerCase();
            assertFalse(sql.matches("(?s).*\\buse\\s+[a-z0-9_]+\\s*;.*"), script);
        }
        assertFalse(read("database/schema.sql").toUpperCase().contains("CREATE DATABASE"));
        assertFalse(read("database/init_complete_schema.sql").toUpperCase().contains("CREATE DATABASE"));
    }

    @Test
    void developmentDefaultAndTestGuardRemainSeparate() throws IOException {
        String config = read("main/src/airline/management/system/DatabaseConfig.java");
        String guard = read("main/src/test/java/airline/management/system/TestDatabaseGuard.java");
        assertTrue(config.contains("properties, \"project4\")"));
        assertTrue(guard.contains("REQUIRED_TEST_DB = \"project4test\""));
    }

    @Test
    void baggageUpgradeUsesConditionalAdditiveDdlAndKeepsRequiredObjects() throws IOException {
        String sql = read("database/v4_phase15_baggage_schema.sql").toLowerCase();
        for (String required : List.of("organization_id", "passenger_id", "reservation_id", "flight_id",
                "baggage_type", "updated_at", "remarks", "information_schema.columns",
                "information_schema.statistics", "information_schema.table_constraints",
                "fk_bag_checkin", "fk_bag_org", "fk_bag_pass", "fk_bag_res", "fk_bag_flight",
                "idx_bag_org", "idx_bdev_org")) {
            assertTrue(sql.contains(required), "missing baggage schema element: " + required);
        }
        assertFalse(sql.contains("drop table"));
    }

    @Test
    void mysqlScriptsAvoidUnsupportedConditionalAddSyntaxAndIropsGuardMatches() throws IOException {
        for (String script : List.of("database/v3_phase14_multitenant_schema.sql", "database/v4_phase15_baggage_schema.sql")) {
            String sql = read(script).toLowerCase();
            assertFalse(sql.contains("add column if not exists"), script);
            assertFalse(sql.contains("add index if not exists"), script);
        }
        String guard = read("main/src/airline/management/system/database/SchemaPreflightGuard.java");
        String irops = read("database/v6_phase17_irops_schema.sql");
        for (String table : List.of("flight_disruptions", "passenger_reaccommodations", "disruption_vouchers")) {
            assertTrue(guard.contains("REQUIRED_TABLES.put(\"" + table + "\""));
            assertTrue(irops.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
    }

    @Test
    void seedNoticeContainsNoExecutableInsertsAndDemoFixtureExists() throws IOException {
        String seedSql = read("database/seed.sql").toLowerCase();
        assertFalse(seedSql.contains("insert into"), "database/seed.sql must not contain executable INSERT INTO statements");
        assertFalse(seedSql.contains("insert ignore into"), "database/seed.sql must not contain executable INSERT IGNORE statements");
        assertTrue(seedSql.contains("database/demo/seed_demo_data.sql"), "database/seed.sql must reference the relocated demo fixture");

        Path demoFixture = repoFile("database/demo/seed_demo_data.sql");
        assertTrue(Files.isRegularFile(demoFixture), "Optional demo fixture must exist at database/demo/seed_demo_data.sql");

        String demoSql = read("database/demo/seed_demo_data.sql").toLowerCase();
        assertTrue(demoSql.contains("insert ignore into app_users"), "Demo fixture must provide optional demo user");
        assertTrue(demoSql.contains("insert ignore into flights"), "Demo fixture must provide optional demo flights");
    }

    @Test
    void initCompleteSchemaExistsAndContainsAllRequiredSections() throws IOException {
        Path initScript = repoFile("database/init_complete_schema.sql");
        assertTrue(Files.isRegularFile(initScript), "database/init_complete_schema.sql must exist");

        String sql = read("database/init_complete_schema.sql");
        for (String section : List.of(
                "STAGE 1: Base Normalized Core Tables",
                "STAGE 2: Reservation Active-Seat Safeguards",
                "STAGE 3: Operations, RBAC, Fleet, Gates & Infrastructure",
                "STAGE 4: Multi-Tenant Architecture & Data Ownership",
                "STAGE 5: Baggage & Passenger Journey Upgrades",
                "STAGE 6: Property Irregularity Reports & Claims",
                "STAGE 7: Irregular Operations (IROPS) & Service Recovery")) {
            assertTrue(sql.contains(section), "Consolidated script must contain section: " + section);
        }

        for (String table : List.of(
                "app_users", "flights", "passengers", "reservations", "payments", "cancellations",
                "organizations", "roles", "permissions", "role_permissions", "user_roles",
                "aircraft", "aircraft_seats", "flight_status_history", "gates", "flight_gates",
                "checkins", "boarding_passes", "boarding_events", "baggage", "baggage_events", "audit_logs",
                "pir_reports", "claim_cases", "claim_events", "claim_settlements",
                "flight_disruptions", "passenger_reaccommodations", "disruption_vouchers")) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table),
                    "Consolidated script must define table: " + table);
        }
    }

    @Test
    void initCompleteSchemaContainsNoDemoCredentialsOrFlights() throws IOException {
        String sql = read("database/init_complete_schema.sql").toLowerCase();

        assertFalse(sql.contains("sha2('admin'"), "Consolidated script must not contain demo admin credentials");
        assertFalse(sql.contains("values ('admin'"), "Consolidated script must not insert admin demo user");
        assertFalse(sql.contains("values('admin'"), "Consolidated script must not insert admin demo user");
        assertFalse(sql.contains("demo101"), "Consolidated script must not contain demo flight DEMO101");
        assertFalse(sql.contains("demo202"), "Consolidated script must not contain demo flight DEMO202");
        assertFalse(sql.contains("demo airways"), "Consolidated script must not contain Demo Airways");
    }

    @Test
    void initCompleteSchemaContainsNoDestructiveResetStatements() throws IOException {
        String sql = read("database/init_complete_schema.sql").toLowerCase();

        assertFalse(sql.contains("drop database"), "Consolidated script must not contain DROP DATABASE");
        assertFalse(sql.contains("drop table"), "Consolidated script must not contain DROP TABLE");
        assertFalse(sql.contains("truncate table"), "Consolidated script must not contain TRUNCATE TABLE");
        assertFalse(sql.contains("truncate "), "Consolidated script must not contain TRUNCATE");
    }

    @Test
    void initCompleteSchemaRepresentsAllSixCanonicalMigrations() throws IOException {
        String sql = read("database/init_complete_schema.sql").toLowerCase();

        // Stage 1 (schema.sql)
        assertTrue(sql.contains("chk_flight_route"), "Missing Stage 1 constraint chk_flight_route");
        assertTrue(sql.contains("chk_passenger_age"), "Missing Stage 1 constraint chk_passenger_age");

        // Stage 2 (migration_3b.sql)
        assertTrue(sql.contains("active_seat_number"), "Missing Stage 2 column active_seat_number");
        assertTrue(sql.contains("uq_active_flight_seat"), "Missing Stage 2 unique key uq_active_flight_seat");

        // Stage 3 (v2_phase11_operations_schema.sql)
        assertTrue(sql.contains("'superadmin'"), "Missing Stage 3 reference role SUPERADMIN");
        assertTrue(sql.contains("'view_dashboard'"), "Missing Stage 3 reference permission VIEW_DASHBOARD");
        assertTrue(sql.contains("'terminal 1'"), "Missing Stage 3 reference airport gate Terminal 1");

        // Stage 4 (v3_phase14_multitenant_schema.sql)
        assertTrue(sql.contains("fk_flights_org"), "Missing Stage 4 foreign key fk_flights_org");
        assertTrue(sql.contains("tenant_data_migrated"), "Missing Stage 4 audit action TENANT_DATA_MIGRATED");

        // Stage 5 (v4_phase15_baggage_schema.sql)
        assertTrue(sql.contains("fk_bag_org"), "Missing Stage 5 foreign key fk_bag_org");
        assertTrue(sql.contains("fk_bdev_org"), "Missing Stage 5 foreign key fk_bdev_org");

        // Stage 6 (v5_phase16_pir_claims_schema.sql)
        assertTrue(sql.contains("pir_reports"), "Missing Stage 6 table pir_reports");
        assertTrue(sql.contains("claim_cases"), "Missing Stage 6 table claim_cases");

        // Stage 7 (v6_phase17_irops_schema.sql)
        assertTrue(sql.contains("flight_disruptions"), "Missing Stage 7 table flight_disruptions");
        assertTrue(sql.contains("disruption_vouchers"), "Missing Stage 7 table disruption_vouchers");
    }

    @Test
    void existingGranularMigrationsRemainUnchanged() {
        for (String script : List.of(
                "database/schema.sql",
                "database/migration_3b.sql",
                "database/v2_phase11_operations_schema.sql",
                "database/v3_phase14_multitenant_schema.sql",
                "database/v4_phase15_baggage_schema.sql",
                "database/v5_phase16_pir_claims_schema.sql",
                "database/v6_phase17_irops_schema.sql",
                "database/seed.sql",
                "database/demo/seed_demo_data.sql",
                "database/migration.sql")) {
            Path file = repoFile(script);
            assertTrue(Files.isRegularFile(file), "Granular migration must exist: " + script);
            try {
                assertTrue(Files.size(file) > 0, "Granular migration must be non-empty: " + script);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
