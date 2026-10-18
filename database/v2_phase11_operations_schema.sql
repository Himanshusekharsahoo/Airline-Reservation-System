-- ============================================================================
-- AeroDesk Operations & Airport Staff SaaS Platform
-- Phase 11 / Phase 12 Forward-Compatible Schema Migration Strategy
--
-- Apply to the explicitly selected project4 or project4test catalog.
-- Safety Guarantee: Non-destructive. Uses CREATE TABLE IF NOT EXISTS.
--                   Preserves existing tables (app_users, flights, passengers,
--                   reservations, payments, cancellations).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. MULTI-TENANCY: Organizations / Airlines
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 2. ROLE-BASED ACCESS CONTROL (RBAC): Roles, Permissions, User Roles
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 3. FLEET & SEAT CONFIGURATION: Aircraft, Aircraft Seats
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 4. FLIGHT STATUS DISPATCH HISTORY
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 5. AIRPORT GATES & FLIGHT GATE ASSIGNMENTS
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 6. PASSENGER CHECK-IN & BOARDING PASSES
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 7. BAGGAGE & HANDLING EVENTS
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 8. OPERATIONAL AUDIT LOG
-- ----------------------------------------------------------------------------
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
