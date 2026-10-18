package airline.management.system.operations.baggage;

/**
 * Operational event types recorded in the baggage tracking log.
 */
public enum BaggageEventType {
    BAG_REGISTERED("Bag Registered"),
    BAG_ACCEPTED("Bag Accepted"),
    BAG_LOADED("Bag Loaded"),
    BAG_IN_TRANSIT("Bag In Transit"),
    BAG_DELIVERED("Bag Delivered"),
    BAG_DELAYED("Bag Delayed"),
    BAG_MISSING("Bag Reported Missing"),
    BAG_CANCELLED("Bag Cancelled");

    private final String displayName;

    BaggageEventType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static BaggageEventType fromString(String text) {
        if (text == null || text.isBlank()) {
            return BAG_REGISTERED;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_");
        for (BaggageEventType e : values()) {
            if (e.name().equals(clean)) {
                return e;
            }
        }
        return BAG_REGISTERED;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
