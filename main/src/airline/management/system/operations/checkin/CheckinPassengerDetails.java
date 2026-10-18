package airline.management.system.operations.checkin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Authoritative reservation and passenger verification model for check-in operations.
 * Loaded directly from database relations across reservations, passengers, flights, checkins, and payments.
 */
public record CheckinPassengerDetails(
    Long reservationId,
    String pnr,
    Long passengerId,
    String passengerName,
    String phone,
    String email,
    Long flightId,
    String flightNumber,
    String airline,
    String source,
    String destination,
    LocalDateTime departureTime,
    LocalDateTime arrivalTime,
    String seatNumber,
    String reservationStatus,
    BigDecimal fare,
    String paymentStatus,
    String paymentMethod,
    String transactionReference,
    CheckinStatus checkinStatus,
    Long checkinId,
    String boardingPassNumber,
    String assignedGate,
    LocalDateTime checkinTime
) {
    /**
     * Backwards-compatible constructor for 20-field callers.
     */
    public CheckinPassengerDetails(
        Long reservationId, String pnr, Long passengerId, String passengerName, String phone, String email,
        Long flightId, String flightNumber, String airline, String source, String destination,
        LocalDateTime departureTime, LocalDateTime arrivalTime, String seatNumber, String reservationStatus,
        BigDecimal fare, String paymentStatus, String paymentMethod, String transactionReference,
        CheckinStatus checkinStatus
    ) {
        this(reservationId, pnr, passengerId, passengerName, phone, email, flightId, flightNumber, airline,
             source, destination, departureTime, arrivalTime, seatNumber, reservationStatus, fare,
             paymentStatus, paymentMethod, transactionReference, checkinStatus, null, null, null, null);
    }

    public boolean isConfirmed() {
        return "CONFIRMED".equalsIgnoreCase(reservationStatus);
    }

    public boolean isPaymentCompleted() {
        return "SUCCESS".equalsIgnoreCase(paymentStatus) || "COMPLETED".equalsIgnoreCase(paymentStatus);
    }

    public boolean isEligibleForCheckin() {
        return isConfirmed() && isPaymentCompleted() && checkinStatus != CheckinStatus.CHECKED_IN;
    }
}
