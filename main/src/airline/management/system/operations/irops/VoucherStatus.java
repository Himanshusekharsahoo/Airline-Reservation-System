package airline.management.system.operations.irops;

/**
 * Authoritative lifecycle states for passenger disruption vouchers.
 */
public enum VoucherStatus {
    ISSUED("Issued", "Voucher generated and delivered to passenger"),
    REDEEMED("Redeemed", "Voucher claimed at participating partner merchant or hotel"),
    CANCELLED("Cancelled", "Voucher invalidated or voided by airline operations"),
    EXPIRED("Expired", "Voucher validity period elapsed without redemption");

    private final String displayName;
    private final String description;

    VoucherStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this != ISSUED;
    }

    public boolean isActive() {
        return this == ISSUED;
    }

    public static VoucherStatus fromString(String text) {
        if (text == null || text.isBlank()) {
            return ISSUED;
        }
        String clean = text.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        for (VoucherStatus status : values()) {
            if (status.name().equals(clean)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown Voucher Status: " + text);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
