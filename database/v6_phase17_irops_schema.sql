-- ============================================================================
-- AeroDesk Operations & Airport Staff SaaS Platform
-- Phase 17: Flight Disruption & Irregular Operations (IROPS) Schema Migration
--
-- Required by application startup in both the explicitly selected project4 and project4test catalogs.
-- Safety Guarantee: Non-destructive. Preserves all existing tables and data.
--                   Idempotent and strictly multi-tenant aware.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. flight_disruptions: Core operational disruption declarations
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

-- ----------------------------------------------------------------------------
-- 2. passenger_reaccommodations: Individual passenger re-accommodation records
-- ----------------------------------------------------------------------------
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

-- ----------------------------------------------------------------------------
-- 3. disruption_vouchers: Passenger service recovery vouchers
-- ----------------------------------------------------------------------------
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
