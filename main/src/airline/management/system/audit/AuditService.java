package airline.management.system.audit;

import airline.management.system.DBConnection;
import airline.management.system.UserSession;
import airline.management.system.tenant.TenantContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized audit logging service for operational traceability and compliance.
 * Persists audit logs to MySQL database with a durable append-only disk spool fallback.
 */
public interface AuditService {
    void log(AuditEvent event);
    void log(AuditAction action, String targetType, String targetId, String details);
    List<AuditEvent> getRecentEvents(int limit);
    List<AuditEvent> getAuditHistory(int limit);
    int count();
    void clear();

    default AuditStatus getStatus() {
        return new AuditStatus(0, 0, 0, 0, 0, 0);
    }

    default int replaySpooledEvents() {
        return 0;
    }

    static AuditService getInstance() {
        return PersistentAuditService.INSTANCE;
    }

    /**
     * High-level static helper to record audit event using current UserSession.
     */
    static void record(AuditAction action, String targetType, String targetId, String details) {
        getInstance().log(action, targetType, targetId, details);
    }

    /** Drains accepted audit writes before the application closes its database pool. */
    static void shutdown() {
        PersistentAuditService.INSTANCE.shutdown();
    }
}

final class PersistentAuditService implements AuditService {
    static final PersistentAuditService INSTANCE = new PersistentAuditService();
    private static final int MAX_CAPACITY = 500;
    private static final int WRITE_QUEUE_CAPACITY = 1024;
    private static final int MAX_DB_ATTEMPTS = 2;
    private static final long INITIAL_BACKOFF_MS = 50L;
    private static final Logger LOGGER = Logger.getLogger(PersistentAuditService.class.getName());

    private final ConcurrentLinkedDeque<AuditEvent> buffer = new ConcurrentLinkedDeque<>();
    private final AuditSpool spool;
    private volatile AuditEventDispatcher<PendingAuditEvent> dispatcher;
    private final ConnectionSupplier connectionSupplier;

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private volatile CountDownLatch shutdownLatch = new CountDownLatch(1);
    private static volatile boolean shutdownHookRegistered = false;

    private final AtomicLong totalLogged = new AtomicLong(0);
    private final AtomicLong persistedCount = new AtomicLong(0);
    private final AtomicLong spooledCount = new AtomicLong(0);
    private final AtomicLong replayedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    @FunctionalInterface
    interface ConnectionSupplier {
        Connection getConnection() throws SQLException;
    }

    private PersistentAuditService() {
        this(new AuditSpool(), DBConnection::getConnection, WRITE_QUEUE_CAPACITY);
    }

    PersistentAuditService(AuditSpool spool, ConnectionSupplier connectionSupplier, int queueCapacity) {
        this.spool = Objects.requireNonNull(spool, "spool cannot be null");
        this.connectionSupplier = Objects.requireNonNull(connectionSupplier, "connectionSupplier cannot be null");
        this.dispatcher = new AuditEventDispatcher<>(queueCapacity, this::processPendingEvent,
            (pending, failure) -> {
                LOGGER.log(Level.SEVERE, "Unexpected dispatcher failure for: " + pending.event().action(), failure);
                spoolEvent(pending, "Dispatcher failure");
            });
        registerShutdownHookOnce();
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered) {
            synchronized (PersistentAuditService.class) {
                if (!shutdownHookRegistered) {
                    try {
                        Runtime.getRuntime().addShutdownHook(new Thread(AuditService::shutdown, "AeroDesk-AuditShutdownHook"));
                        shutdownHookRegistered = true;
                    } catch (IllegalStateException ignored) {
                        // JVM already shutting down
                    }
                }
            }
        }
    }

    @Override
    public void log(AuditEvent event) {
        if (event == null) return;
        totalLogged.incrementAndGet();

        // In-memory bounded cache for UI display and status querying
        buffer.addFirst(event);
        while (buffer.size() > MAX_CAPACITY) {
            buffer.pollLast();
        }

        Long organizationId = TenantContext.getOrganizationId();
        PendingAuditEvent pending = new PendingAuditEvent(event, organizationId == null ? 1L : organizationId);

        if (isShutdown.get()) {
            LOGGER.warning("AuditService is shut down; spooling incoming event directly to disk: " + event.action());
            spoolEvent(pending, "AuditService shut down");
            return;
        }

        try {
            dispatcher.submit(pending);
        } catch (RejectedExecutionException full) {
            LOGGER.log(Level.WARNING,
                "Audit write queue saturated; spooling directly to disk: " + event.action(), full);
            spoolEvent(pending, "Queue saturated");
        }
    }

    private void processPendingEvent(PendingAuditEvent pending) {
        // 1. If spool has records, drain spool first to preserve FIFO order
        if (spool.hasRecords()) {
            drainSpool();
        }

        // 2. If spool STILL has records, DB is currently unavailable; spool this event too
        if (spool.hasRecords()) {
            spoolEvent(pending, "Database unavailable; backlog active");
            return;
        }

        // 3. Spool is empty; try persisting directly to DB with bounded retry
        boolean success = false;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_DB_ATTEMPTS; attempt++) {
            try {
                persistToDb(pending);
                success = true;
                persistedCount.incrementAndGet();
                break;
            } catch (Exception ex) {
                lastException = ex;
                if (attempt < MAX_DB_ATTEMPTS) {
                    try {
                        long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!success) {
            LOGGER.log(Level.WARNING, "Direct database persist failed after " + MAX_DB_ATTEMPTS +
                " attempts; spooling audit event to durable fallback: " + pending.event().action(), lastException);
            spoolEvent(pending, "Direct DB write failed");
        }
    }

    synchronized int drainSpool() {
        if (!spool.hasRecords()) return 0;

        List<SpooledAuditRecord> records = spool.readAllValidRecords();
        if (records.isEmpty()) return 0;

        Set<String> persistedSpoolIds = new HashSet<>();
        int replayedInThisRun = 0;
        for (SpooledAuditRecord record : records) {
            try {
                persistToDb(record.pendingEvent());
                persistedSpoolIds.add(record.spoolId());
                persistedCount.incrementAndGet();
                replayedCount.incrementAndGet();
                replayedInThisRun++;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Spool replay paused at event " + record.spoolId() +
                    "; database unavailable: " + ex.getMessage());
                break; // Stop replay to preserve FIFO order and avoid hammering DB
            }
        }

        if (!persistedSpoolIds.isEmpty()) {
            try {
                spool.removeRecords(persistedSpoolIds);
                LOGGER.info("Successfully replayed and removed " + persistedSpoolIds.size() +
                    " spooled audit event(s) to database.");
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to update audit spool after database replay", ex);
            }
        }
        return replayedInThisRun;
    }

    private void spoolEvent(PendingAuditEvent pending, String reason) {
        try {
            spool.write(pending);
            spooledCount.incrementAndGet();
            LOGGER.info("Audit event spooled to disk: action=" + pending.event().action() +
                ", targetType=" + pending.event().targetType() + ", reason=" + reason);
        } catch (Exception ex) {
            failedCount.incrementAndGet();
            LOGGER.log(Level.SEVERE, "CRITICAL: Unable to spool audit event to disk. Durability compromised! Action: " +
                pending.event().action() + ", reason: " + reason, ex);
        }
    }

    private void persistToDb(PendingAuditEvent pending) throws SQLException {
        AuditEvent event = pending.event();
        try (Connection conn = connectionSupplier.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO audit_logs (organization_id, user_id, username, role, action, target_type, target_id, details, client_ip, created_at) " +
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setLong(1, pending.organizationId());
            Long validUserId = resolveValidUserId(conn, event.userId());
            if (validUserId != null) {
                stmt.setLong(2, validUserId);
            } else {
                stmt.setNull(2, java.sql.Types.BIGINT);
            }
            stmt.setString(3, event.username() != null ? event.username() : "SYSTEM");
            stmt.setString(4, event.role() != null ? event.role() : "SYSTEM");
            stmt.setString(5, event.action() != null ? event.action().name() : "SYSTEM");
            stmt.setString(6, event.targetType() != null ? event.targetType() : "SYSTEM");
            stmt.setString(7, event.targetId());
            stmt.setString(8, event.details());
            stmt.setString(9, event.clientIp() != null ? event.clientIp() : "127.0.0.1");
            stmt.setTimestamp(10, Timestamp.from(event.timestamp() != null ? event.timestamp() : Instant.now()));
            stmt.executeUpdate();
        }
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            try {
                LOGGER.info("Initiating graceful AuditService shutdown...");
                List<PendingAuditEvent> undrained = dispatcher.shutdownAndDrain(10, TimeUnit.SECONDS);
                if (!undrained.isEmpty()) {
                    LOGGER.warning("Audit dispatcher timeout reached; spooling " + undrained.size() +
                        " remaining queued event(s) to disk.");
                    for (PendingAuditEvent pending : undrained) {
                        spoolEvent(pending, "Shutdown timeout undrained");
                    }
                }

                try {
                    drainSpool();
                } catch (Exception ignored) {
                }

                if (spool.hasRecords()) {
                    LOGGER.info("AuditService shutdown complete. " + spool.recordCount() +
                        " spooled event(s) retained on disk for replay on next start.");
                } else {
                    LOGGER.info("AuditService shutdown complete. All events persisted.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.SEVERE, "Audit shutdown interrupted; draining remaining events to spool...", interrupted);
                for (PendingAuditEvent pending : dispatcher.drainRemaining()) {
                    spoolEvent(pending, "Shutdown interrupted");
                }
            } finally {
                shutdownLatch.countDown();
            }
        } else {
            try {
                if (!shutdownLatch.await(12, TimeUnit.SECONDS)) {
                    LOGGER.warning("Timed out waiting for concurrent AuditService shutdown to complete.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public AuditStatus getStatus() {
        return new AuditStatus(
            totalLogged.get(),
            dispatcher.pendingCount(),
            persistedCount.get(),
            spooledCount.get(),
            replayedCount.get(),
            failedCount.get()
        );
    }

    @Override
    public int replaySpooledEvents() {
        return drainSpool();
    }

    private static Long resolveValidUserId(Connection conn, Long userId) {
        if (userId == null) return null;
        try (PreparedStatement s = conn.prepareStatement("SELECT 1 FROM app_users WHERE id = ? LIMIT 1")) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                if (rs.next()) return userId;
            }
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public void log(AuditAction action, String targetType, String targetId, String details) {
        Long userId = UserSession.userId();
        String username = UserSession.username() != null ? UserSession.username() : "SYSTEM";
        String role = UserSession.role() != null ? UserSession.role() : "SYSTEM";
        AuditEvent event = new AuditEvent(
            Instant.now(),
            userId,
            username,
            role,
            action,
            targetType,
            targetId,
            details,
            "127.0.0.1"
        );
        log(event);
    }

    @Override
    public List<AuditEvent> getRecentEvents(int limit) {
        List<AuditEvent> result = new ArrayList<>();
        int count = 0;
        for (AuditEvent event : buffer) {
            if (count++ >= limit) break;
            result.add(event);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AuditEvent> getAuditHistory(int limit) {
        String sql = "SELECT organization_id, user_id, username, role, action, target_type, target_id, details, client_ip, created_at " +
                     "FROM audit_logs WHERE organization_id=? ORDER BY id DESC LIMIT ?";
        List<AuditEvent> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, TenantContext.getOrganizationId() != null ? TenantContext.getOrganizationId() : 1L);
            stmt.setInt(2, limit > 0 ? limit : 50);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String actionStr = rs.getString("action");
                    AuditAction action = null;
                    try {
                        action = AuditAction.valueOf(actionStr);
                    } catch (Exception e) {
                        action = AuditAction.LOGIN;
                    }

                    long uid = rs.getLong("user_id");
                    Long userId = rs.wasNull() ? null : uid;
                    Timestamp ts = rs.getTimestamp("created_at");
                    Instant instant = ts != null ? ts.toInstant() : Instant.now();

                    list.add(new AuditEvent(
                        instant,
                        userId,
                        rs.getString("username"),
                        rs.getString("role"),
                        action,
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("details"),
                        rs.getString("client_ip")
                    ));
                }
            }
        } catch (SQLException e) {
            // Fallback to in-memory events if DB read fails
            return getRecentEvents(limit);
        }
        return list.isEmpty() ? getRecentEvents(limit) : Collections.unmodifiableList(list);
    }

    @Override
    public int count() {
        return buffer.size();
    }

    @Override
    public synchronized void clear() {
        buffer.clear();
        if (isShutdown.get()) {
            isShutdown.set(false);
            shutdownLatch = new CountDownLatch(1);
            dispatcher = new AuditEventDispatcher<>(WRITE_QUEUE_CAPACITY, this::processPendingEvent,
                (pending, failure) -> {
                    LOGGER.log(Level.SEVERE, "Unexpected dispatcher failure for: " + pending.event().action(), failure);
                    spoolEvent(pending, "Dispatcher failure");
                });
        }
    }
}
