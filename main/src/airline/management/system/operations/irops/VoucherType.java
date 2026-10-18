package airline.management.system.operations.irops;

/**
 * Passenger service recovery and amenity voucher classifications.
 */
public enum VoucherType {
    MEAL("Meal Voucher", "Airport terminal food and beverage voucher"),
    HOTEL("Hotel Accommodation", "Overnight hotel lodging accommodation voucher"),
    TRANSPORT("Ground Transport", "Airport transfer, taxi, or shuttle bus transport voucher"),
    OTHER("Service Recovery", "Miscellaneous service recovery or amenity voucher");

    private final String displayName;
    private final String description;

    VoucherType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static VoucherType fromString(String text) {
        if (text == null || text.isBlank()) {
            return MEAL;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (VoucherType type : values()) {
            if (type.name().equals(clean)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Voucher Type: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
