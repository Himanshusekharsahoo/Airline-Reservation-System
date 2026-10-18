package airline.management.system.operations.irops;

/**
 * Authoritative lifecycle status for passenger disruption notifications.
 */
public enum NotificationStatus {
    PENDING,
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
    ACKNOWLEDGED
}
