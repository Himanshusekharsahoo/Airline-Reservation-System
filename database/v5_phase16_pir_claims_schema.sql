-- ============================================================================
-- AeroDesk Operations & Airport Staff SaaS Platform
-- Phase 16: Property Irregularity Report (PIR) & Claims Management Schema Migration
--
-- Target Database: project4 / project4test
-- Safety Guarantee: Non-destructive. Preserves all existing tables and data.
--                   Idempotent and strictly multi-tenant aware.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. pir_reports: Authoritative Property Irregularity Reports for baggage incidents
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

-- ----------------------------------------------------------------------------
-- 2. claim_cases: Passenger baggage claims linked to PIRs
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 3. claim_events: Persistent audit trail for PIR and claim transitions
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 4. claim_settlements: Financial compensation records for approved claims
-- ----------------------------------------------------------------------------
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
