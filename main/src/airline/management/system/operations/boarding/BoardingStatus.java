package airline.management.system.operations.boarding;

/**
 * Gate boarding lifecycle states for scheduled flights.
 */
public enum BoardingStatus {
    SCHEDULED("Scheduled", "Gate not yet initiated for boarding"),
    BOARDING_OPEN("Boarding Open", "Passengers actively validating and boarding"),
    FINAL_CALL("Final Call", "Final passenger boarding call; gate closing shortly"),
    BOARDING_CLOSED("Boarding Closed", "Flight boarding complete; gate closed for departure");

    private final String label;
    private final String description;

    BoardingStatus(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
