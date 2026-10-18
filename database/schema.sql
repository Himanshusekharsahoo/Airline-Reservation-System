-- Apply only after explicitly selecting the intended catalog (project4 or project4test).
-- Create the catalog separately; this script never creates or switches databases.

CREATE TABLE IF NOT EXISTS app_users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'ADMIN', active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS flights (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, legacy_flight_code VARCHAR(30) UNIQUE,
  flight_number VARCHAR(30) NOT NULL UNIQUE, airline VARCHAR(100) NOT NULL, source VARCHAR(100) NOT NULL,
  destination VARCHAR(100) NOT NULL, departure_time DATETIME NOT NULL, arrival_time DATETIME NOT NULL,
  total_seats INT UNSIGNED NOT NULL, available_seats INT UNSIGNED NOT NULL, fare DECIMAL(10,2) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_flight_route CHECK (source <> destination), CONSTRAINT chk_flight_times CHECK (arrival_time > departure_time),
  CONSTRAINT chk_flight_seats CHECK (available_seats <= total_seats), CONSTRAINT chk_flight_fare CHECK (fare >= 0),
  INDEX idx_flights_route_departure (source, destination, departure_time)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS passengers (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, legacy_pnr VARCHAR(30) UNIQUE, name VARCHAR(100) NOT NULL,
  age TINYINT UNSIGNED NULL, gender VARCHAR(20) NULL, phone VARCHAR(30) NOT NULL, email VARCHAR(254) NULL UNIQUE,
  address TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_passenger_age CHECK (age IS NULL OR age BETWEEN 0 AND 120)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS reservations (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, pnr VARCHAR(30) NOT NULL UNIQUE, passenger_id BIGINT UNSIGNED NOT NULL,
  flight_id BIGINT UNSIGNED NOT NULL, seat_number VARCHAR(10) NULL, booking_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  fare DECIMAL(10,2) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
  CONSTRAINT fk_reservation_passenger FOREIGN KEY (passenger_id) REFERENCES passengers(id),
  CONSTRAINT fk_reservation_flight FOREIGN KEY (flight_id) REFERENCES flights(id),
  CONSTRAINT chk_reservation_fare CHECK (fare >= 0), INDEX idx_reservation_flight (flight_id), INDEX idx_reservation_passenger (passenger_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS payments (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, reservation_id BIGINT UNSIGNED NOT NULL, amount DECIMAL(10,2) NOT NULL,
  payment_method VARCHAR(20) NOT NULL, payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', transaction_reference VARCHAR(80) NOT NULL UNIQUE,
  payment_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
  CONSTRAINT chk_payment_amount CHECK (amount >= 0), INDEX idx_payment_reservation (reservation_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS cancellations (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, reservation_id BIGINT UNSIGNED NOT NULL UNIQUE, cancellation_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reason VARCHAR(255) NULL, refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  CONSTRAINT fk_cancellation_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id), CONSTRAINT chk_refund_amount CHECK (refund_amount >= 0)
) ENGINE=InnoDB;
