-- ============================================================================
-- AeroDesk Operations & Airport Staff SaaS Platform
-- Phase 15: Baggage & Passenger Journey Operations Schema Migration
--
-- Apply to the explicitly selected project4 or project4test catalog.
-- Safety Guarantee: Non-destructive. Preserves all existing tables and data.
--                   Idempotent and strictly multi-tenant aware.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Ensure baggage table exists with all operational and multi-tenant columns
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS baggage (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    checkin_id BIGINT UNSIGNED NOT NULL,
    passenger_id BIGINT UNSIGNED NULL,
    reservation_id BIGINT UNSIGNED NULL,
    flight_id BIGINT UNSIGNED NULL,
    tag_number VARCHAR(30) NOT NULL UNIQUE,
    baggage_type VARCHAR(20) NOT NULL DEFAULT 'CHECKED',
    weight_kg DECIMAL(5,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_bag_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_bag_checkin FOREIGN KEY (checkin_id) REFERENCES checkins(id) ON DELETE CASCADE,
    INDEX idx_bag_org (organization_id),
    INDEX idx_bag_flight (organization_id, flight_id),
    INDEX idx_bag_res (organization_id, reservation_id),
    INDEX idx_bag_pass (organization_id, passenger_id),
    INDEX idx_bag_status (organization_id, status),
    INDEX idx_bag_tag (tag_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Create new event tables before applying conditional upgrades to old v2 tables.
CREATE TABLE IF NOT EXISTS baggage_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    baggage_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    location_code VARCHAR(30) NOT NULL DEFAULT 'STATION',
    scanned_by_user_id BIGINT UNSIGNED NULL,
    remarks VARCHAR(255) NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bdev_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_bdev_bag FOREIGN KEY (baggage_id) REFERENCES baggage(id) ON DELETE CASCADE,
    CONSTRAINT fk_bdev_user FOREIGN KEY (scanned_by_user_id) REFERENCES app_users(id) ON DELETE SET NULL,
    INDEX idx_bdev_org (organization_id),
    INDEX idx_bdev_bag (baggage_id, scanned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- CREATE TABLE IF NOT EXISTS does not upgrade tables created by v2. Add the
-- application-required columns and objects conditionally, without replacing rows.
DROP PROCEDURE IF EXISTS aerodesk_v4_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v4_add_index_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v4_add_fk_if_missing;
DELIMITER $$
CREATE PROCEDURE aerodesk_v4_add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN col_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @aerodesk_v4_sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_definition);
        PREPARE aerodesk_v4_stmt FROM @aerodesk_v4_sql;
        EXECUTE aerodesk_v4_stmt;
        DEALLOCATE PREPARE aerodesk_v4_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_v4_add_index_if_missing(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN idx_columns VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
        SET @aerodesk_v4_sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', idx_columns, ')');
        PREPARE aerodesk_v4_stmt FROM @aerodesk_v4_sql;
        EXECUTE aerodesk_v4_stmt;
        DEALLOCATE PREPARE aerodesk_v4_stmt;
    END IF;
END$$
CREATE PROCEDURE aerodesk_v4_add_fk_if_missing(IN tbl VARCHAR(64), IN fk VARCHAR(64), IN fk_definition VARCHAR(255))
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

CALL aerodesk_v4_add_column_if_missing('baggage', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v4_add_column_if_missing('baggage', 'passenger_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_v4_add_column_if_missing('baggage', 'reservation_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_v4_add_column_if_missing('baggage', 'flight_id', 'BIGINT UNSIGNED NULL');
CALL aerodesk_v4_add_column_if_missing('baggage', 'baggage_type', 'VARCHAR(20) NOT NULL DEFAULT ''CHECKED''');
CALL aerodesk_v4_add_column_if_missing('baggage', 'updated_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
CALL aerodesk_v4_add_column_if_missing('baggage_events', 'organization_id', 'BIGINT UNSIGNED NOT NULL DEFAULT 1');
CALL aerodesk_v4_add_column_if_missing('baggage_events', 'remarks', 'VARCHAR(255) NULL');

-- Existing check-in rows provide the authoritative relationship values.
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

CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_org', 'organization_id');
CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_flight', 'organization_id, flight_id');
CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_res', 'organization_id, reservation_id');
CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_pass', 'organization_id, passenger_id');
CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_status', 'organization_id, status');
CALL aerodesk_v4_add_index_if_missing('baggage', 'idx_bag_tag', 'tag_number');
CALL aerodesk_v4_add_index_if_missing('baggage_events', 'idx_bdev_org', 'organization_id');
CALL aerodesk_v4_add_index_if_missing('baggage_events', 'idx_bdev_bag', 'baggage_id, scanned_at');

CALL aerodesk_v4_add_fk_if_missing('baggage', 'fk_bag_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');
CALL aerodesk_v4_add_fk_if_missing('baggage', 'fk_bag_pass', 'FOREIGN KEY (passenger_id) REFERENCES passengers(id) ON DELETE CASCADE');
CALL aerodesk_v4_add_fk_if_missing('baggage', 'fk_bag_res', 'FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE');
CALL aerodesk_v4_add_fk_if_missing('baggage', 'fk_bag_flight', 'FOREIGN KEY (flight_id) REFERENCES flights(id) ON DELETE CASCADE');
CALL aerodesk_v4_add_fk_if_missing('baggage_events', 'fk_bdev_org', 'FOREIGN KEY (organization_id) REFERENCES organizations(id)');

DROP PROCEDURE IF EXISTS aerodesk_v4_add_column_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v4_add_index_if_missing;
DROP PROCEDURE IF EXISTS aerodesk_v4_add_fk_if_missing;
