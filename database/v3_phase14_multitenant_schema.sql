-- ============================================================================
-- AeroDesk Operations & Airport Staff SaaS Platform
-- Phase 14: Multi-Tenant Core Architecture & Operational Intelligence Schema Migration
--
-- Apply to the explicitly selected project4 or project4test catalog.
-- Safety Guarantee: Non-destructive. Preserves all existing data and tables.
--                   Existing baseline records safely map to default organization (id=1, 'AD').
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Ensure organizations table and default tenant exist
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

INSERT IGNORE INTO organizations (id, org_code, org_name, iata_code, icao_code, contact_email)
VALUES (1, 'AD', 'AeroDesk Operations', 'AD', 'ADK', 'admin@aerodesk.internal');

-- ----------------------------------------------------------------------------
-- 2. Add organization_id column to core business tables if not present
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS aerodesk_v3_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v3_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE aerodesk_v3_add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN col_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @aerodesk_v3_sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_definition);
        PREPARE aerodesk_v3_stmt FROM @aerodesk_v3_sql;
        EXECUTE aerodesk_v3_stmt;
        DEALLOCATE PREPARE aerodesk_v3_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_v3_add_index_if_missing(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN idx_columns VARCHAR(255))
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

CALL aerodesk_v3_add_column_if_missing('flights', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('flights', 'idx_flights_org', 'organization_id');
CALL aerodesk_v3_add_index_if_missing('flights', 'idx_flights_org_status', 'organization_id, status');
CALL aerodesk_v3_add_index_if_missing('flights', 'idx_flights_org_dept', 'organization_id, departure_time');
CALL aerodesk_v3_add_column_if_missing('passengers', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('passengers', 'idx_passengers_org', 'organization_id');
CALL aerodesk_v3_add_column_if_missing('reservations', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('reservations', 'idx_reservations_org', 'organization_id');
CALL aerodesk_v3_add_index_if_missing('reservations', 'idx_reservations_org_status', 'organization_id, status');
CALL aerodesk_v3_add_column_if_missing('payments', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('payments', 'idx_payments_org', 'organization_id');
CALL aerodesk_v3_add_index_if_missing('payments', 'idx_payments_org_status', 'organization_id, payment_status');
CALL aerodesk_v3_add_column_if_missing('cancellations', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('cancellations', 'idx_cancellations_org', 'organization_id');
CALL aerodesk_v3_add_index_if_missing('cancellations', 'idx_cancellations_org_date', 'organization_id, cancellation_date');
CALL aerodesk_v3_add_column_if_missing('flight_status_history', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v3_add_index_if_missing('flight_status_history', 'idx_fsh_org', 'organization_id');
CALL aerodesk_v3_add_index_if_missing('flight_status_history', 'idx_fsh_org_flight', 'organization_id, flight_id');

DROP PROCEDURE IF EXISTS aerodesk_v3_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v3_add_index_if_missing;

-- ----------------------------------------------------------------------------
-- 3. Map any existing unassigned records to default tenant 1
-- ----------------------------------------------------------------------------
UPDATE flights SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE passengers SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE reservations SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE payments SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE cancellations SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;
UPDATE flight_status_history SET organization_id = 1 WHERE organization_id IS NULL OR organization_id = 0;

-- ----------------------------------------------------------------------------
-- 4. Idempotently add foreign key constraints referencing organizations(id)
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS aerodesk_add_fk_if_not_exists;
DELIMITER $$
CREATE PROCEDURE aerodesk_add_fk_if_not_exists(
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

CALL aerodesk_add_fk_if_not_exists('flights', 'fk_flights_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_add_fk_if_not_exists('passengers', 'fk_passengers_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_add_fk_if_not_exists('reservations', 'fk_reservations_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_add_fk_if_not_exists('payments', 'fk_payments_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_add_fk_if_not_exists('cancellations', 'fk_cancellations_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_add_fk_if_not_exists('flight_status_history', 'fk_fsh_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');

DROP PROCEDURE IF EXISTS aerodesk_add_fk_if_not_exists;

-- ----------------------------------------------------------------------------
-- 5. Record schema migration audit entry
-- ----------------------------------------------------------------------------
INSERT INTO audit_logs (organization_id, user_id, username, role, action, target_type, target_id, details)
VALUES (1, NULL, 'SYSTEM', 'SYSTEM', 'TENANT_DATA_MIGRATED', 'SCHEMA', 'PHASE_14',
        'Phase 14 multi-tenant migration applied: core business tables ownership mapped to organization_id');
