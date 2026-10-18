-- ============================================================================
-- AeroDesk Operations & Airport Staff Platform
-- Consolidated Database Initialization Script (v2.0.0 Enterprise Baseline)
--
-- File: database/init_complete_schema.sql
-- Purpose: Complete, consolidated, idempotent schema initialization for fresh
--          installations of the AeroDesk platform.
--
-- Target Catalog: Apply only to an explicitly selected target database.
--                 Create the target schema catalog separately prior to running this script.
--
-- Safety Guarantees:
--   1. Non-destructive: Contains no database deletion, table deletion, or table reset statements.
--   2. Explicit catalog isolation: Contains NO hardcoded 'USE <database>' statements.
--   3. Idempotent: Uses 'CREATE TABLE IF NOT EXISTS' and conditional procedures.
--   4. Zero demo data: Contains NO demo credentials (admin/admin), demo flights,
--      or mock passengers.
--   5. Master reference data: Seeds required tenant defaults, RBAC roles,
--      permissions, and initial airport gate infrastructure.
--
-- Execution Method:
--   This script contains MySQL stored procedures utilizing 'DELIMITER $$'.
--   It MUST be executed using the MySQL CLI client or a compatible tool:
--
--   PowerShell:
--     Get-Content -Raw database/init_complete_schema.sql | mysql -u <user> -p --database=<target_db>
--
--   Bash:
--     mysql -u <user> -p --database=<target_db> < database/init_complete_schema.sql
--
-- Source Migration Lineage:
--   - Stage 1: Base Normalized Core Tables       (database/schema.sql)
--   - Stage 2: Reservation Active-Seat Guard    (database/migration_3b.sql)
--   - Stage 3: Operations, RBAC & Fleet Tables   (database/v2_phase11_operations_schema.sql)
--   - Stage 4: Multi-Tenant Architecture         (database/v3_phase14_multitenant_schema.sql)
--   - Stage 5: Baggage & Journey Upgrades       (database/v4_phase15_baggage_schema.sql)
--   - Stage 6: PIR & Claims Management          (database/v5_phase16_pir_claims_schema.sql)
--   - Stage 7: IROPS & Disruption Management     (database/v6_phase17_irops_schema.sql)
--
-- Note on Omissions:
--   - database/migration.sql: Omitted (Legacy ETL from obsolete 2025 tables; not applicable to fresh install).
--   - database/demo/seed_demo_data.sql: Omitted (Optional demo fixture; strictly isolated).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- STAGE 1: Base Normalized Core Tables (from database/schema.sql)
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS flights (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    legacy_flight_code VARCHAR(30) UNIQUE,
    flight_number VARCHAR(30) NOT NULL UNIQUE,
    airline VARCHAR(100) NOT NULL,
    source VARCHAR(100) NOT NULL,
    destination VARCHAR(100) NOT NULL,
    departure_time DATETIME NOT NULL,
    arrival_time DATETIME NOT NULL,
    total_seats INT UNSIGNED NOT NULL,
    available_seats INT UNSIGNED NOT NULL,
    fare DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_flight_route CHECK (source <> destination),
    CONSTRAINT chk_flight_times CHECK (arrival_time > departure_time),
    CONSTRAINT chk_flight_seats CHECK (available_seats <= total_seats),
    CONSTRAINT chk_flight_fare CHECK (fare >= 0),
    INDEX idx_flights_route_departure (source, destination, departure_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS passengers (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    legacy_pnr VARCHAR(30) UNIQUE,
    name VARCHAR(100) NOT NULL,
    age TINYINT UNSIGNED NULL,
    gender VARCHAR(20) NULL,
    phone VARCHAR(30) NOT NULL,
    email VARCHAR(254) NULL UNIQUE,
    address TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_passenger_age CHECK (age IS NULL OR age BETWEEN 0 AND 120)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reservations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    pnr VARCHAR(30) NOT NULL UNIQUE,
    passenger_id BIGINT UNSIGNED NOT NULL,
    flight_id BIGINT UNSIGNED NOT NULL,
    seat_number VARCHAR(10) NULL,
    booking_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fare DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    CONSTRAINT fk_reservation_passenger FOREIGN KEY (passenger_id) REFERENCES passengers(id),
    CONSTRAINT fk_reservation_flight FOREIGN KEY (flight_id) REFERENCES flights(id),
    CONSTRAINT chk_reservation_fare CHECK (fare >= 0),
    INDEX idx_reservation_flight (flight_id),
    INDEX idx_reservation_passenger (passenger_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    reservation_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    transaction_reference VARCHAR(80) NOT NULL UNIQUE,
    payment_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT chk_payment_amount CHECK (amount >= 0),
    INDEX idx_payment_reservation (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cancellations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    reservation_id BIGINT UNSIGNED NOT NULL UNIQUE,
    cancellation_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(255) NULL,
    refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    CONSTRAINT fk_cancellation_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT chk_refund_amount CHECK (refund_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- STAGE 2: Reservation Active-Seat Safeguards (from database/migration_3b.sql)
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS aerodesk_init_add_active_seat_if_missing;
DELIMITER $$
CREATE PROCEDURE aerodesk_init_add_active_seat_if_missing()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reservations' AND COLUMN_NAME = 'active_seat_number') THEN
        ALTER TABLE reservations
          ADD COLUMN active_seat_number VARCHAR(10) GENERATED ALWAYS AS
            (CASE WHEN status IN ('PENDING_PAYMENT','CONFIRMED') THEN seat_number ELSE NULL END) STORED,
          ADD UNIQUE KEY uq_active_flight_seat (flight_id, active_seat_number);
    END IF;
END$$
DELIMITER ;
CALL aerodesk_init_add_active_seat_if_missing();
DROP PROCEDURE IF EXISTS aerodesk_init_add_active_seat_if_missing;

-- ----------------------------------------------------------------------------
-- STAGE 3: Operations, RBAC, Fleet, Gates & Infrastructure (from database/v2_phase11_operations_schema.sql)
-- ----------------------------------------------------------------------------

-- 1. Multi-Tenancy: Organizations / Airlines
CREATE TABLE IF NOT EXISTS organizations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    org_code VARCHAR(10) NOT NULL UNIQUE,
    org_name VARCHAR(120) NOT NULL,
    iata_code VARCHAR(3) NULL,
    icao_code VARCHAR(4) NULL,
    contact_email VARCHAR(254) NOT NULL,
    contact_phone VARCHAR(30) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_org_code (org_code),
    INDEX idx_org_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed primary default tenant
INSERT IGNORE INTO organizations (id, org_code, org_name, iata_code, icao_code, contact_email)
VALUES (1, 'AD', 'AeroDesk Operations', 'AD', 'ADK', 'admin@aerodesk.internal');

-- 2. Role-Based Access Control (RBAC)
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO roles (name, display_name, description) VALUES
('SUPERADMIN', 'Super Administrator', 'Platform and tenant administration'),
('AIRLINEADMIN', 'Airline Administrator', 'Airline-level staff, fleet, and operations management'),
('RESERVATIONAGENT', 'Reservation Agent', 'Passenger booking, ticketing desk, and refunds'),
('CHECKINAGENT', 'Check-in Agent', 'Passenger verification, seat assignment, and boarding pass issuance'),
('GATEAGENT', 'Gate Agent', 'Gate boarding operations, boarding pass validation, and no-shows'),
('OPERATIONSMANAGER', 'Operations Manager', 'Flight dispatch, delays, gate assignment, and operations tracking'),
('FINANCEOFFICER', 'Finance Officer', 'Payments, refunds, settlement reconciliation, and financial reports'),
('AUDITOR', 'Auditor', 'Read-only compliance verification and audit log inspection');

CREATE TABLE IF NOT EXISTS permissions (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    category VARCHAR(40) NOT NULL DEFAULT 'OPERATIONS',
    description VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO permissions (name, display_name, category, description) VALUES
('VIEW_DASHBOARD', 'View Dashboard', 'OPERATIONS', 'Access live operations overview and KPIs'),
('MANAGE_FLIGHTS', 'Manage Flights', 'OPERATIONS', 'Create, update, and manage scheduled flights'),
('MANAGE_PASSENGERS', 'Manage Passengers', 'PASSENGERS', 'Register and edit passenger profiles'),
('MANAGE_RESERVATIONS', 'Manage Reservations', 'RESERVATIONS', 'Create bookings and allocate passenger seats'),
('MANAGE_PAYMENTS', 'Manage Payments', 'FINANCE', 'Inspect and process financial transactions'),
('MANAGE_CANCELLATIONS', 'Manage Cancellations', 'RESERVATIONS', 'Cancel bookings, release seats, and refund payments'),
('CHECKIN_PASSENGER', 'Check-in Passenger', 'CHECKIN', 'Verify passenger identity and confirm seat assignment'),
('ISSUE_BOARDING_PASS', 'Issue Boarding Pass', 'CHECKIN', 'Generate and issue passenger boarding passes'),
('MANAGE_BOARDING', 'Manage Boarding', 'GATE', 'Validate boarding passes and clear passengers at gate'),
('MANAGE_GATES', 'Manage Gates', 'GATE', 'Assign airport gates and control gate boarding status'),
('MANAGE_FLIGHT_OPERATIONS', 'Manage Flight Operations', 'OPERATIONS', 'Manage flight dispatch, delay logging, and status history'),
('VIEW_REPORTS', 'View Reports', 'REPORTING', 'Access operational, passenger volume, and revenue reports'),
('MANAGE_USERS', 'Manage Users', 'ADMIN', 'Administer staff accounts, roles, and access credentials'),
('VIEW_AUDIT_LOGS', 'View Audit Logs', 'COMPLIANCE', 'Inspect compliance logs and operational audit trail');

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id, organization_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Fleet & Seat Configuration
CREATE TABLE IF NOT EXISTS aircraft (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    registration_tail VARCHAR(20) NOT NULL UNIQUE,
    model VARCHAR(60) NOT NULL,
    manufacturer VARCHAR(60) NOT NULL,
    economy_seats INT UNSIGNED NOT NULL DEFAULT 150,
    business_seats INT UNSIGNED NOT NULL DEFAULT 20,
    first_class_seats INT UNSIGNED NOT NULL DEFAULT 0,
    total_capacity INT UNSIGNED GENERATED ALWAYS AS (economy_seats + business_seats + first_class_seats) STORED,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_aircraft_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    INDEX idx_aircraft_status (status),
    INDEX idx_aircraft_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS aircraft_seats (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    aircraft_id BIGINT UNSIGNED NOT NULL,
    seat_code VARCHAR(6) NOT NULL,
    cabin_class VARCHAR(20) NOT NULL DEFAULT 'ECONOMY',
    seat_row INT UNSIGNED NOT NULL,
    column_letter CHAR(1) NOT NULL,
    is_exit_row BOOLEAN NOT NULL DEFAULT FALSE,
    is_accessible BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_aircraft_seat (aircraft_id, seat_code),
    CONSTRAINT fk_seat_aircraft FOREIGN KEY (aircraft_id) REFERENCES aircraft(id) ON DELETE CASCADE,
    INDEX idx_seat_row (aircraft_id, seat_row)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Flight Status History
CREATE TABLE IF NOT EXISTS flight_status_history (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    flight_id BIGINT UNSIGNED NOT NULL,
    previous_status VARCHAR(20) NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_by_user_id BIGINT UNSIGNED NULL,
    reason VARCHAR(255) NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_fsh_flight FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_fsh_user FOREIGN KEY (changed_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_fsh_flight_time (flight_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Airport Gates & Flight Gate Assignments
CREATE TABLE IF NOT EXISTS gates (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    airport_code VARCHAR(5) NOT NULL,
    terminal VARCHAR(20) NOT NULL,
    gate_number VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    UNIQUE KEY uq_airport_terminal_gate (airport_code, terminal, gate_number),
    CONSTRAINT fk_gates_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    INDEX idx_gate_status (airport_code, status),
    INDEX idx_gate_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed initial airport gates
INSERT IGNORE INTO gates (id, organization_id, airport_code, terminal, gate_number, status) VALUES
(1, 1, 'MNL', 'Terminal 1', 'G01', 'AVAILABLE'),
(2, 1, 'MNL', 'Terminal 1', 'G02', 'AVAILABLE'),
(3, 1, 'MNL', 'Terminal 1', 'G03', 'AVAILABLE'),
(4, 1, 'MNL', 'Terminal 2', 'G04', 'AVAILABLE'),
(5, 1, 'MNL', 'Terminal 2', 'G05', 'AVAILABLE'),
(6, 1, 'MNL', 'Terminal 3', 'G06', 'AVAILABLE');

CREATE TABLE IF NOT EXISTS flight_gates (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    flight_id BIGINT UNSIGNED NOT NULL,
    gate_id BIGINT UNSIGNED NOT NULL,
    assigned_by_user_id BIGINT UNSIGNED NULL,
    scheduled_start DATETIME NOT NULL,
    scheduled_end DATETIME NOT NULL,
    actual_boarding_start DATETIME NULL,
    actual_boarding_closed DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_fg_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_fg_flight FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_fg_gate FOREIGN KEY (gate_id) REFERENCES gates(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fg_user FOREIGN KEY (assigned_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_fg_flight (flight_id),
    INDEX idx_fg_gate (gate_id),
    INDEX idx_fg_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Passenger Check-in & Boarding Passes
CREATE TABLE IF NOT EXISTS checkins (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    reservation_id BIGINT UNSIGNED NOT NULL UNIQUE,
    passenger_id BIGINT UNSIGNED NOT NULL,
    flight_id BIGINT UNSIGNED NOT NULL,
    seat_assigned VARCHAR(10) NOT NULL,
    checked_in_by_user_id BIGINT UNSIGNED NULL,
    checkin_channel VARCHAR(20) NOT NULL DEFAULT 'DESK',
    baggage_count INT UNSIGNED NOT NULL DEFAULT 0,
    checkin_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'CHECKED_IN',
    CONSTRAINT fk_checkin_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_checkin_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_pass FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_flight FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_user FOREIGN KEY (checked_in_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_checkin_flight (flight_id, status),
    INDEX idx_checkin_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS boarding_passes (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    checkin_id BIGINT UNSIGNED NOT NULL UNIQUE,
    boarding_pass_number VARCHAR(40) NOT NULL UNIQUE,
    barcode_payload VARCHAR(255) NOT NULL,
    security_hash VARCHAR(64) NOT NULL,
    boarding_group VARCHAR(10) NOT NULL DEFAULT 'GROUP_3',
    gate VARCHAR(10) NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bp_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_bp_checkin FOREIGN KEY (checkin_id) REFERENCES checkins(id) ON DELETE CASCADE,
    INDEX idx_bp_number (boarding_pass_number),
    INDEX idx_bp_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS boarding_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    boarding_pass_id BIGINT UNSIGNED NOT NULL,
    flight_id BIGINT UNSIGNED NOT NULL,
    gate_agent_user_id BIGINT UNSIGNED NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    outcome VARCHAR(20) NOT NULL DEFAULT 'BOARDED',
    remarks VARCHAR(255) NULL,
    CONSTRAINT fk_be_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_be_pass FOREIGN KEY (boarding_pass_id) REFERENCES boarding_passes(id) ON DELETE CASCADE,
    CONSTRAINT fk_be_flight FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_be_agent FOREIGN KEY (gate_agent_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_be_flight_time (flight_id, scanned_at),
    INDEX idx_be_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. Initial Baggage & Handling Events
CREATE TABLE IF NOT EXISTS baggage (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    checkin_id BIGINT UNSIGNED NOT NULL,
    tag_number VARCHAR(30) NOT NULL UNIQUE,
    weight_kg DECIMAL(5,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CHECKED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bag_checkin FOREIGN KEY (checkin_id) REFERENCES checkins(id) ON DELETE CASCADE,
    INDEX idx_bag_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS baggage_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    baggage_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    location_code VARCHAR(30) NOT NULL,
    scanned_by_user_id BIGINT UNSIGNED NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bdev_bag FOREIGN KEY (baggage_id) REFERENCES baggage(id) ON DELETE CASCADE,
    CONSTRAINT fk_bdev_user FOREIGN KEY (scanned_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_bdev_bag (baggage_id, scanned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Operational Audit Log
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    user_id BIGINT UNSIGNED NULL,
    username VARCHAR(50) NOT NULL,
    role VARCHAR(40) NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(80) NULL,
    details TEXT NULL,
    client_ip VARCHAR(45) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_audit_time (created_at),
    INDEX idx_audit_action (action),
    INDEX idx_audit_target (target_type, target_id),
    INDEX idx_audit_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- STAGE 4: Multi-Tenant Architecture & Data Ownership (from database/v3_phase14_multitenant_schema.sql)
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS aerodesk_init_v3_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v3_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE aerodesk_init_v3_add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN col_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @aerodesk_v3_sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_definition);
        PREPARE aerodesk_v3_stmt FROM @aerodesk_v3_sql;
        EXECUTE aerodesk_v3_stmt;
        DEALLOCATE PREPARE aerodesk_v3_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_init_v3_add_index_if_missing(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN idx_columns VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
        SET @aerodesk_v3_sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', idx_columns, ')');
        PREPARE aerodesk_v3_stmt FROM @aerodesk_v3_sql;
        EXECUTE aerodesk_v3_stmt;
        DEALLOCATE PREPARE aerodesk_v3_stmt;
    END IF;
END$$
DELIMITER ;

CALL aerodesk_init_v3_add_column_if_missing('flights', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('flights', 'idx_flights_org', 'organization_id');
CALL aerodesk_init_v3_add_index_if_missing('flights', 'idx_flights_org_status', 'organization_id, status');
CALL aerodesk_init_v3_add_index_if_missing('flights', 'idx_flights_org_dept', 'organization_id, departure_time');
CALL aerodesk_init_v3_add_column_if_missing('passengers', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('passengers', 'idx_passengers_org', 'organization_id');
CALL aerodesk_init_v3_add_column_if_missing('reservations', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('reservations', 'idx_reservations_org', 'organization_id');
CALL aerodesk_init_v3_add_index_if_missing('reservations', 'idx_reservations_org_status', 'organization_id, status');
CALL aerodesk_init_v3_add_column_if_missing('payments', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('payments', 'idx_payments_org', 'organization_id');
CALL aerodesk_init_v3_add_index_if_missing('payments', 'idx_payments_org_status', 'organization_id, payment_status');
CALL aerodesk_init_v3_add_column_if_missing('cancellations', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('cancellations', 'idx_cancellations_org', 'organization_id');
CALL aerodesk_init_v3_add_index_if_missing('cancellations', 'idx_cancellations_org_date', 'organization_id, cancellation_date');
CALL aerodesk_init_v3_add_column_if_missing('flight_status_history', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v3_add_index_if_missing('flight_status_history', 'idx_fsh_org', 'organization_id');
CALL aerodesk_init_v3_add_index_if_missing('flight_status_history', 'idx_fsh_org_flight', 'organization_id, flight_id');

DROP PROCEDURE IF EXISTS aerodesk_init_v3_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v3_add_index_if_missing;

-- Map any existing unassigned records to default tenant 1
UPDATE flights SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE passengers SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE reservations SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE payments SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE cancellations SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE flight_status_history SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;

-- Idempotently add foreign key constraints referencing organizations(id)
DROP PROCEDURE IF EXISTS aerodesk_init_add_fk_if_not_exists;
DELIMITER $$
CREATE PROCEDURE aerodesk_init_add_fk_if_not_exists(
    IN tbl_name VARCHAR(64),
    IN constraint_name VARCHAR(64),
    IN fk_sql VARCHAR(255)
)
BEGIN
    DECLARE fk_exists INT DEFAULT 0;
    SELECT COUNT(*) INTO fk_exists
    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = tbl_name
      AND CONSTRAINT_NAME = constraint_name;
    IF fk_exists = 0 THEN
        SET @sql = CONCAT('ALTER TABLE ', tbl_name, ' ADD CONSTRAINT ', constraint_name, ' ', fk_sql);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL aerodesk_init_add_fk_if_not_exists('flights', 'fk_flights_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_add_fk_if_not_exists('passengers', 'fk_passengers_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_add_fk_if_not_exists('reservations', 'fk_reservations_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_add_fk_if_not_exists('payments', 'fk_payments_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_add_fk_if_not_exists('cancellations', 'fk_cancellations_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_add_fk_if_not_exists('flight_status_history', 'fk_fsh_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');

DROP PROCEDURE IF EXISTS aerodesk_init_add_fk_if_not_exists;

-- Record schema migration audit entry
INSERT INTO audit_logs (organization_id, user_id, username, role, action, target_type, target_id, details)
VALUES (1, NULL, 'SYSTEM', 'SYSTEM', 'TENANT_DATA_MIGRATED', 'SCHEMA', 'PHASE_14',
        'Phase 14 multi-tenant migration applied: core business tables ownership mapped to organization_id');

-- ----------------------------------------------------------------------------
-- STAGE 5: Baggage & Passenger Journey Upgrades (from database/v4_phase15_baggage_schema.sql)
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_index_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_fk_if_missing;
DELIMITER $$
CREATE PROCEDURE aerodesk_init_v4_add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN col_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @aerodesk_v4_sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_definition);
        PREPARE aerodesk_v4_stmt FROM @aerodesk_v4_sql;
        EXECUTE aerodesk_v4_stmt;
        DEALLOCATE PREPARE aerodesk_v4_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_init_v4_add_index_if_missing(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN idx_columns VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
        SET @aerodesk_v4_sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', idx_columns, ')');
        PREPARE aerodesk_v4_stmt FROM @aerodesk_v4_sql;
        EXECUTE aerodesk_v4_stmt;
        DEALLOCATE PREPARE aerodesk_v4_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_init_v4_add_fk_if_missing(IN tbl VARCHAR(64), IN fk VARCHAR(64), IN fk_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = tbl
                     AND CONSTRAINT_NAME = fk AND CONSTRAINT_TYPE = 'FOREIGN KEY') THEN
        SET @aerodesk_v4_sql = CONCAT('ALTER TABLE `', tbl, '` ADD CONSTRAINT `', fk, '` ', fk_definition);
        PREPARE aerodesk_v4_stmt FROM @aerodesk_v4_sql;
        EXECUTE aerodesk_v4_stmt;
        DEALLOCATE PREPARE aerodesk_v4_stmt;
    END IF;
END$$
DELIMITER ;

CALL aerodesk_init_v4_add_column_if_missing('baggage', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v4_add_column_if_missing('baggage', 'passenger_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_init_v4_add_column_if_missing('baggage', 'reservation_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_init_v4_add_column_if_missing('baggage', 'flight_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_init_v4_add_column_if_missing('baggage', 'baggage_type', 'VARCHAR(20) NOT NULL DEFAULT ''CHECKED''');
CALL aerodesk_init_v4_add_column_if_missing('baggage', 'updated_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
CALL aerodesk_init_v4_add_column_if_missing('baggage_events', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_init_v4_add_column_if_missing('baggage_events', 'remarks', 'VARCHAR(255) NULL');

-- Existing check-in rows provide the authoritative relationship values
UPDATE baggage b
JOIN checkins c ON c.id = b.checkin_id
SET b.organization_id = c.organization_id,
    b.passenger_id = c.passenger_id,
    b.reservation_id = c.reservation_id,
    b.flight_id = c.flight_id
WHERE b.passenger_id IS NULL OR b.reservation_id IS NULL OR b.flight_id IS NULL;

UPDATE baggage_events be
JOIN baggage b ON b.id = be.baggage_id
SET be.organization_id = b.organization_id
WHERE be.organization_id IS NULL OR be.organization_id = 0;

CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_org', 'organization_id');
CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_flight', 'organization_id, flight_id');
CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_res', 'organization_id, reservation_id');
CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_pass', 'organization_id, passenger_id');
CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_status', 'organization_id, status');
CALL aerodesk_init_v4_add_index_if_missing('baggage', 'idx_bag_tag', 'tag_number');
CALL aerodesk_init_v4_add_index_if_missing('baggage_events', 'idx_bdev_org', 'organization_id');
CALL aerodesk_init_v4_add_index_if_missing('baggage_events', 'idx_bdev_bag', 'baggage_id, scanned_at');

CALL aerodesk_init_v4_add_fk_if_missing('baggage', 'fk_bag_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_init_v4_add_fk_if_missing('baggage', 'fk_bag_pass', 'FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE');
CALL aerodesk_init_v4_add_fk_if_missing('baggage', 'fk_bag_res', 'FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE');
CALL aerodesk_init_v4_add_fk_if_missing('baggage', 'fk_bag_flight', 'FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE');
CALL aerodesk_init_v4_add_fk_if_missing('baggage_events', 'fk_bdev_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');

DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_index_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_init_v4_add_fk_if_missing;

-- ----------------------------------------------------------------------------
-- STAGE 6: Property Irregularity Reports & Claims (from database/v5_phase16_pir_claims_schema.sql)
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS pir_reports (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    pir_reference VARCHAR(30) NOT NULL UNIQUE,
    baggage_id BIGINT UNSIGNED NOT NULL,
    passenger_id BIGINT UNSIGNED NOT NULL,
    reservation_id BIGINT UNSIGNED NOT NULL,
    flight_id BIGINT UNSIGNED NOT NULL,
    pir_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    incident_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    station_code VARCHAR(30) NOT NULL DEFAULT 'STATION',
    description TEXT NOT NULL,
    investigation_notes TEXT NULL,
    created_by_user_id BIGINT UNSIGNED NULL,
    assigned_to_user_id BIGINT UNSIGNED NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_pir_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_pir_bag FOREIGN KEY (baggage_id) REFERENCES baggage(id) ON DELETE CASCADE,
    CONSTRAINT fk_pir_pass FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE,
    CONSTRAINT fk_pir_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_pir_flt FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_pir_user FOREIGN KEY (created_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_pir_org (organization_id),
    INDEX idx_pir_ref (pir_reference),
    INDEX idx_pir_bag (organization_id, baggage_id),
    INDEX idx_pir_res (organization_id, reservation_id),
    INDEX idx_pir_pass (organization_id, passenger_id),
    INDEX idx_pir_flt (organization_id, flight_id),
    INDEX idx_pir_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claim_cases (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    claim_reference VARCHAR(30) NOT NULL UNIQUE,
    pir_id BIGINT UNSIGNED NOT NULL,
    baggage_id BIGINT UNSIGNED NOT NULL,
    passenger_id BIGINT UNSIGNED NOT NULL,
    reservation_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    requested_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    approved_amount DECIMAL(10,2) NULL,
    claim_reason TEXT NOT NULL,
    review_notes TEXT NULL,
    created_by_user_id BIGINT UNSIGNED NULL,
    reviewed_by_user_id BIGINT UNSIGNED NULL,
    settled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_claim_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_claim_pir FOREIGN KEY (pir_id) REFERENCES pir_reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_bag FOREIGN KEY (baggage_id) REFERENCES baggage(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_pass FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_claim_cuser FOREIGN KEY (created_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_claim_ruser FOREIGN KEY (reviewed_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_claim_org (organization_id),
    INDEX idx_claim_ref (claim_reference),
    INDEX idx_claim_pir (organization_id, pir_id),
    INDEX idx_claim_bag (organization_id, baggage_id),
    INDEX idx_claim_res (organization_id, reservation_id),
    INDEX idx_claim_pass (organization_id, passenger_id),
    INDEX idx_claim_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claim_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    entity_type VARCHAR(20) NOT NULL,
    entity_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    performed_by_user_id BIGINT UNSIGNED NULL,
    remarks TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_clev_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_clev_user FOREIGN KEY (performed_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_clev_org (organization_id),
    INDEX idx_clev_entity (entity_type, entity_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claim_settlements (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    settlement_reference VARCHAR(30) NOT NULL UNIQUE,
    claim_id BIGINT UNSIGNED NOT NULL UNIQUE,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    payment_method VARCHAR(30) NOT NULL DEFAULT 'BANK_TRANSFER',
    settlement_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    settlement_reason TEXT NOT NULL,
    authorized_by_user_id BIGINT UNSIGNED NULL,
    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_settle_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_settle_claim FOREIGN KEY (claim_id) REFERENCES claim_cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_settle_user FOREIGN KEY (authorized_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_settle_org (organization_id),
    INDEX idx_settle_claim (claim_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------------------
-- STAGE 7: Irregular Operations (IROPS) & Service Recovery (from database/v6_phase17_irops_schema.sql)
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS flight_disruptions (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    flight_id BIGINT UNSIGNED NOT NULL,
    disruption_reference VARCHAR(30) NOT NULL UNIQUE,
    disruption_type VARCHAR(30) NOT NULL,
    reason_code VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    declared_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    declared_by_user_id BIGINT UNSIGNED NULL,
    scheduled_departure TIMESTAMP NULL,
    estimated_departure TIMESTAMP NULL,
    delay_minutes INT NOT NULL DEFAULT 0,
    impact_description TEXT NOT NULL,
    passenger_impact_count INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DECLARED',
    resolved_at TIMESTAMP NULL,
    resolved_by_user_id BIGINT UNSIGNED NULL,
    remarks TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_fd_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_fd_flight FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_fd_duser FOREIGN KEY (declared_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_fd_ruser FOREIGN KEY (resolved_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_fd_org (organization_id),
    INDEX idx_fd_ref (disruption_reference),
    INDEX idx_fd_flight (organization_id, flight_id),
    INDEX idx_fd_status (organization_id, status),
    INDEX idx_fd_declared (organization_id, declared_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS passenger_reaccommodations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    disruption_id BIGINT UNSIGNED NOT NULL,
    original_reservation_id BIGINT UNSIGNED NOT NULL,
    original_pnr VARCHAR(30) NOT NULL,
    passenger_id BIGINT UNSIGNED NOT NULL,
    original_flight_id BIGINT UNSIGNED NOT NULL,
    new_flight_id BIGINT UNSIGNED NULL,
    new_reservation_id BIGINT UNSIGNED NULL,
    new_pnr VARCHAR(30) NULL,
    new_seat_number VARCHAR(20) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PROPOSED',
    reaccommodation_type VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    notification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    initiated_by BIGINT UNSIGNED NULL,
    completed_at TIMESTAMP NULL,
    remarks TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_pr_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_pr_disrupt FOREIGN KEY (disruption_id) REFERENCES flight_disruptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_orig_res FOREIGN KEY (original_reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_pass FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_orig_flt FOREIGN KEY (original_flight_id) REFERENCES flights(id) ON DELETE CASCADE,
    CONSTRAINT fk_pr_new_flt FOREIGN KEY (new_flight_id) REFERENCES flights(id) ON DELETE SET NULL,
    CONSTRAINT fk_pr_new_res FOREIGN KEY (new_reservation_id) REFERENCES reservations(id) ON DELETE SET NULL,
    CONSTRAINT fk_pr_user FOREIGN KEY (initiated_by) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_pr_org (organization_id),
    INDEX idx_pr_disrupt (organization_id, disruption_id),
    INDEX idx_pr_orig_res (organization_id, original_reservation_id),
    INDEX idx_pr_pass (organization_id, passenger_id),
    INDEX idx_pr_status (organization_id, status),
    INDEX idx_pr_orig_pnr (organization_id, original_pnr),
    INDEX idx_pr_new_pnr (organization_id, new_pnr)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS disruption_vouchers (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    disruption_id BIGINT UNSIGNED NOT NULL,
    passenger_id BIGINT UNSIGNED NOT NULL,
    reservation_id BIGINT UNSIGNED NOT NULL,
    voucher_reference VARCHAR(30) NOT NULL UNIQUE,
    voucher_type VARCHAR(20) NOT NULL,
    amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
    issued_by BIGINT UNSIGNED NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    redeemed_at TIMESTAMP NULL,
    remarks TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_dv_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_dv_disrupt FOREIGN KEY (disruption_id) REFERENCES flight_disruptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_dv_pass FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE,
    CONSTRAINT fk_dv_res FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_dv_user FOREIGN KEY (issued_by) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_dv_org (organization_id),
    INDEX idx_dv_ref (voucher_reference),
    INDEX idx_dv_disrupt (organization_id, disruption_id),
    INDEX idx_dv_pass (organization_id, passenger_id),
    INDEX idx_dv_res (organization_id, reservation_id),
    INDEX idx_dv_status (organization_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- End of AeroDesk Consolidated Initialization Script
-- ============================================================================
