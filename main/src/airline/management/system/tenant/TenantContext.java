package airline.management.system.tenant;

/**
 * Thread-safe context holder for the active airline tenant organization.
 * Provides tenant boundary isolation in preparation for multi-tenant SaaS deployment.
 * <p>
 * Architecture Design & Migration Strategy:
 * 1. Current Phase (Phase 11): Operates on single-airline deployment with transparent fallback to {@link Tenant#DEFAULT}.
 * 2. Future Phase (Phase 12+): Database queries will append 'WHERE organization_id = ?' automatically
 *    or route through schema-per-tenant connection pools based on TenantContext.
 * 3. Scope: Applies to flights, reservations, gates, aircraft, passenger manifests, and payments.
 */
public final class TenantContext {
    private static volatile Tenant sessionTenant = Tenant.DEFAULT;
    private static final ThreadLocal<Tenant> CURRENT_TENANT = new InheritableThreadLocal<>() {
        @Override
        protected Tenant initialValue() {
            return sessionTenant != null ? sessionTenant : Tenant.DEFAULT;
        }
    };

    private TenantContext() {}

    /**
     * Retrieves the currently active tenant organization in the calling thread context.
     */
    public static Tenant getCurrentTenant() {
        Tenant tenant = CURRENT_TENANT.get();
        return (tenant != null) ? tenant : (sessionTenant != null ? sessionTenant : Tenant.DEFAULT);
    }

    /**
     * Sets the active airline tenant for the current thread execution context.
     */
    public static void setCurrentTenant(Tenant tenant) {
        if (tenant == null) {
            CURRENT_TENANT.set(sessionTenant != null ? sessionTenant : Tenant.DEFAULT);
        } else {
            CURRENT_TENANT.set(tenant);
        }
    }

    /**
     * Binds the active airline tenant to the global user session.
     */
    public static void setSessionTenant(Tenant tenant) {
        sessionTenant = (tenant != null) ? tenant : Tenant.DEFAULT;
        CURRENT_TENANT.set(sessionTenant);
    }

    /**
     * Returns the active organization ID for query filtering.
     */
    public static Long getOrganizationId() {
        return getCurrentTenant().organizationId();
    }

    /**
     * Returns the active airline code (e.g., 'AD', 'AA', 'DL').
     */
    public static String getAirlineCode() {
        return getCurrentTenant().airlineCode();
    }

    /**
     * Clears the current tenant context for this thread and restores the session default.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /**
     * Resets the session tenant back to default (called on logout).
     */
    public static void resetSession() {
        sessionTenant = Tenant.DEFAULT;
        CURRENT_TENANT.remove();
    }
}
