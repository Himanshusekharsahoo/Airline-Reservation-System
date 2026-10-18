package airline.management.system.audit;

/** Representation of an audit event persisted in the local append-only spool file. */
record SpooledAuditRecord(String spoolId, PendingAuditEvent pendingEvent) {}
