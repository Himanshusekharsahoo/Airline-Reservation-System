-- Phase 3B reservation safeguards. Run after schema.sql and before the operations migration.
-- Apply to the explicitly selected catalog; this script never switches databases.
-- Cancelled rows produce NULL and therefore do not reserve a seat; active rows must be unique.
ALTER TABLE reservations
  ADD COLUMN active_seat_number VARCHAR(10) GENERATED ALWAYS AS
    (CASE WHEN status IN ('PENDING_PAYMENT','CONFIRMED') THEN seat_number ELSE NULL END) STORED,
  ADD UNIQUE KEY uq_active_flight_seat (flight_id, active_seat_number);
