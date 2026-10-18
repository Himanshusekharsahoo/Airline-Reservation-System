package airline.management.system.tenant;

/**
 * Multi-tenant airline organization entity.
 * Represents an airline tenant subscribing to the AeroDesk Operations SaaS Platform.
 */
public record Tenant(
    Long organizationId,
    String airlineCode,
    String airlineName,
    boolean active
) {
    public static final Tenant DEFAULT = new Tenant(1L, "AD", "AeroDesk Operations", true);
}
