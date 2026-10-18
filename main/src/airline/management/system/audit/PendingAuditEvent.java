package airline.management.system.audit;

/** Internal representation of an audit event awaiting background database write or disk spooling. */
record PendingAuditEvent(AuditEvent event, long organizationId) {}
