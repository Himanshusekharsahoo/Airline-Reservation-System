package airline.management.system.audit;

/**
 * Diagnostic metrics snapshot capturing audit subsystem durability states.
 */
public record AuditStatus(
    long totalLogged,
    int queued,
    long persisted,
    long spooled,
    long replayed,
    long failed
) {}
