package airline.management.system.operations.irops;

/**
 * Enriched passenger manifest item combining flight reservation, payment, check-in,
 * boarding pass, baggage, PIR irregularity, and re-accommodation tracking.
 */
public record DisruptedPassengerManifestItem(
    long reservationId,
    String pnr,
    long passengerId,
    String passengerName,
    String phone,
    String email,
    long flightId,
    String flightNumber,
    String route,
    String seatNumber,
    String reservationStatus,
    String paymentStatus,
    String checkinStatus,
    String boardingPassNumber,
    int baggageCount,
    String baggageStatus,
    String pirReference,
    String pirStatus,
    Long reaccommodationId,
    ReaccommodationStatus reaccommodationStatus,
    String newFlightNumber,
    String newPnr,
    int voucherCount
) {}
