package airline.management.system;

import airline.management.system.operations.boarding.BoardingStatus;
import airline.management.system.operations.boarding.FlightBoardingSummary;
import airline.management.system.operations.checkin.CheckinPassengerDetails;
import airline.management.system.operations.checkin.CheckinStatus;
import airline.management.system.tenant.Tenant;
import airline.management.system.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CheckinAndBoardingArchitectureTest {

    @Test
    void checkinEligibilityRequiresConfirmedReservationAndSuccessfulPayment() {
        CheckinPassengerDetails eligible = new CheckinPassengerDetails(
            1L, "PNR-OK1", 10L, "John Doe", "+1234567890", "john@example.com",
            100L, "AD101", "AeroDesk Air", "Manila", "Cebu",
            LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(4),
            "12A", "CONFIRMED", new BigDecimal("150.00"), "SUCCESS", "Card", "TXN-1",
            CheckinStatus.ELIGIBLE
        );
        assertTrue(eligible.isConfirmed());
        assertTrue(eligible.isPaymentCompleted());
        assertTrue(eligible.isEligibleForCheckin());

        CheckinPassengerDetails unconfirmed = new CheckinPassengerDetails(
            2L, "PNR-CANC", 11L, "Jane Doe", "+1234567891", "jane@example.com",
            100L, "AD101", "AeroDesk Air", "Manila", "Cebu",
            LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(4),
            "12B", "CANCELLED", new BigDecimal("150.00"), "REFUNDED", "Card", "TXN-2",
            CheckinStatus.INELIGIBLE_STATUS
        );
        assertFalse(unconfirmed.isConfirmed());
        assertFalse(unconfirmed.isEligibleForCheckin());

        CheckinPassengerDetails unpaid = new CheckinPassengerDetails(
            3L, "PNR-UNPD", 12L, "Alice Smith", "+1234567892", "alice@example.com",
            100L, "AD101", "AeroDesk Air", "Manila", "Cebu",
            LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(4),
            "14C", "CONFIRMED", new BigDecimal("150.00"), "PENDING", "UPI", "TXN-3",
            CheckinStatus.INELIGIBLE_PAYMENT
        );
        assertTrue(unpaid.isConfirmed());
        assertFalse(unpaid.isPaymentCompleted());
        assertFalse(unpaid.isEligibleForCheckin());
    }

    @Test
    void boardingSummaryCalculatesRouteAndStatusCorrectly() {
        FlightBoardingSummary summary = new FlightBoardingSummary(
            10L, "AD500", "AeroDesk Air", "Manila", "Tokyo",
            LocalDateTime.now().plusHours(3), 180, 50, 130,
            BoardingStatus.BOARDING_OPEN
        );

        assertEquals("Manila → Tokyo", summary.getRoute());
        assertEquals("AD500 (Manila → Tokyo)", summary.toString());
        assertEquals(BoardingStatus.BOARDING_OPEN, summary.status());
        assertEquals(130, summary.bookedPassengers());
    }

    @Test
    void tenantContextDefaultsToStandardAirlineAndAllowsThreadIsolation() {
        assertEquals("AD", TenantContext.getAirlineCode());
        assertEquals("AeroDesk Operations", TenantContext.getCurrentTenant().airlineName());

        Tenant customTenant = new Tenant(2L, "PH", "Philippine Airlines", true);
        TenantContext.setCurrentTenant(customTenant);

        assertEquals("PH", TenantContext.getAirlineCode());
        assertEquals(2L, TenantContext.getOrganizationId());

        TenantContext.clear();
        assertEquals("AD", TenantContext.getAirlineCode());
    }
}
