package airline.management.system.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditDurabilityTest {

    @TempDir
    Path tempDir;

    @Test
    void successfulPersistence() throws Exception {
        List<String> executedActions = Collections.synchronizedList(new ArrayList<>());
        Connection mockConn = createMockConnection(executedActions::add, () -> false);
        Path spoolFile = tempDir.resolve("successful-persistence.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 10);
        AuditEvent event = new AuditEvent(Instant.now(), 1L, "admin", "ADMIN",
            AuditAction.LOGIN, "USER", "1", "User login successful", "127.0.0.1");

        service.log(event);
        service.shutdown();

        assertEquals(List.of("LOGIN"), executedActions);
        assertEquals(1, service.getStatus().persisted());
        assertFalse(spool.hasRecords());
    }

    @Test
    void temporaryPersistenceFailureFollowedByRecovery() throws Exception {
        List<String> executedActions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger attempts = new AtomicInteger(0);

        // First 2 attempts fail (attempt 1 and retry 1), triggering spool to disk
        Connection mockConn = createMockConnection(executedActions::add, () -> attempts.incrementAndGet() <= 2);
        Path spoolFile = tempDir.resolve("temporary-failure.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 10);
        AuditEvent event = new AuditEvent(Instant.now(), 2L, "staff", "STAFF",
            AuditAction.CHECKIN_COMPLETED, "PASSENGER", "101", "Checkin completed", "127.0.0.1");

        service.log(event);

        // Wait up to 1 second for initial attempts to fail and spool to disk
        long start = System.currentTimeMillis();
        while (!spool.hasRecords() && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(20);
        }

        assertTrue(spool.hasRecords(), "Event should be spooled after DB attempts fail");
        assertEquals(1, service.getStatus().spooled());

        // Now database connectivity is restored; replay the spooled events
        int replayed = service.replaySpooledEvents();
        assertEquals(1, replayed);
        assertFalse(spool.hasRecords(), "Spool should be empty after recovery replay");
        assertEquals(List.of("CHECKIN_COMPLETED"), executedActions);
        assertEquals(1, service.getStatus().replayed());

        service.shutdown();
    }

    @Test
    void spoolReplayAndOrdering() throws Exception {
        List<String> executedActions = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean dbOnline = new AtomicBoolean(false);

        Connection mockConn = createMockConnection(executedActions::add, () -> !dbOnline.get());
        Path spoolFile = tempDir.resolve("ordering.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 10);

        service.log(new AuditEvent(Instant.now(), 1L, "u1", "R1", AuditAction.LOGIN, "T", "1", "d1", "ip"));
        service.log(new AuditEvent(Instant.now(), 2L, "u2", "R2", AuditAction.PASSENGER_CREATE, "T", "2", "d2", "ip"));
        service.log(new AuditEvent(Instant.now(), 3L, "u3", "R3", AuditAction.BOARDING_EVENT, "T", "3", "d3", "ip"));

        // Wait until all 3 events are spooled
        long deadline = System.currentTimeMillis() + 2000;
        while (spool.recordCount() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(3, spool.recordCount());

        // Restore DB and log 4th event; worker will drain spool first before persisting event 4
        dbOnline.set(true);
        service.log(new AuditEvent(Instant.now(), 4L, "u4", "R4", AuditAction.LOGOUT, "T", "4", "d4", "ip"));

        service.shutdown();

        assertEquals(List.of("LOGIN", "PASSENGER_CREATE", "BOARDING_EVENT", "LOGOUT"), executedActions);
        assertFalse(spool.hasRecords(), "All records should be drained from spool");
    }

    @Test
    void duplicatePreventionDuringReplay() throws Exception {
        Path spoolFile = tempDir.resolve("dedup.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        // Pre-populate spool with 3 records
        PendingAuditEvent p1 = new PendingAuditEvent(new AuditEvent(Instant.now(), 1L, "u", "r", AuditAction.LOGIN, "T", "1", "1", "ip"), 1L);
        PendingAuditEvent p2 = new PendingAuditEvent(new AuditEvent(Instant.now(), 2L, "u", "r", AuditAction.PASSENGER_CREATE, "T", "2", "2", "ip"), 1L);
        PendingAuditEvent p3 = new PendingAuditEvent(new AuditEvent(Instant.now(), 3L, "u", "r", AuditAction.LOGOUT, "T", "3", "3", "ip"), 1L);

        spool.write(p1);
        spool.write(p2);
        spool.write(p3);
        assertEquals(3, spool.recordCount());

        List<String> executedActions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger persistCount = new AtomicInteger(0);

        // Fail on 3rd event during first replay run
        Connection mockConn = createMockConnection(executedActions::add, () -> persistCount.incrementAndGet() >= 3);
        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 10);

        int replayedRun1 = service.drainSpool();
        assertEquals(2, replayedRun1, "Only first 2 should succeed before failure halts replay");
        assertEquals(1, spool.recordCount(), "Only unpersisted 3rd event should remain in spool");

        List<SpooledAuditRecord> remaining = spool.readAllValidRecords();
        assertEquals(1, remaining.size());
        assertEquals(AuditAction.LOGOUT, remaining.get(0).pendingEvent().event().action());

        // Now run replay again with DB healthy; only remaining 3rd event should execute
        persistCount.set(0); // reset failure condition
        int replayedRun2 = service.drainSpool();
        assertEquals(1, replayedRun2);
        assertEquals(0, spool.recordCount());

        // Total executed actions should contain LOGIN, PASSENGER_CREATE, LOGOUT exactly once
        assertEquals(List.of("LOGIN", "PASSENGER_CREATE", "LOGOUT"), executedActions);

        service.shutdown();
    }

    @Test
    void queueSaturation() throws Exception {
        Path spoolFile = tempDir.resolve("saturation.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        Connection mockConn = createMockConnection(s -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> false);

        // Queue capacity of 1: item 1 is active in worker, item 2 is queued, item 3 saturates queue
        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 1);

        service.log(new AuditEvent(Instant.now(), 1L, "u", "r", AuditAction.LOGIN, "T", "1", "1", "ip"));
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));

        service.log(new AuditEvent(Instant.now(), 2L, "u", "r", AuditAction.PASSENGER_CREATE, "T", "2", "2", "ip"));
        // This 3rd event will exceed queue capacity (1 active + 1 queued = full)
        service.log(new AuditEvent(Instant.now(), 3L, "u", "r", AuditAction.LOGOUT, "T", "3", "3", "ip"));

        assertTrue(spool.hasRecords(), "Saturated event must be spooled immediately without data loss");
        List<SpooledAuditRecord> spooled = spool.readAllValidRecords();
        assertEquals(1, spooled.size());
        assertEquals(AuditAction.LOGOUT, spooled.get(0).pendingEvent().event().action());

        releaseWorker.countDown();
        service.shutdown();
    }

    @Test
    void corruptOrIncompleteSpoolRecords() throws IOException {
        Path spoolFile = tempDir.resolve("corrupt.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        PendingAuditEvent valid1 = new PendingAuditEvent(new AuditEvent(Instant.now(), 1L, "u1", "r", AuditAction.LOGIN, "T", "1", "valid 1", "ip"), 1L);
        PendingAuditEvent valid2 = new PendingAuditEvent(new AuditEvent(Instant.now(), 2L, "u2", "r", AuditAction.LOGOUT, "T", "2", "valid 2", "ip"), 1L);

        spool.write(valid1);

        // Manually append corrupted / malformed lines to spool file
        List<String> corruptLines = List.of(
            "AERODESK_AUDIT_V1\tincomplete-line-with-no-terminator",
            "GARBAGE_VERSION\ttoo\tfew\ttokens",
            "AERODESK_AUDIT_V1\tid\t1\t" + Instant.now() + "\t1\tu\tr\tLOGIN\tT\t1\tdetails\tip\tdeadbeef\tEND_OF_RECORD" // invalid CRC
        );
        Files.write(spoolFile, corruptLines, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        spool.write(valid2);

        List<SpooledAuditRecord> validRecords = spool.readAllValidRecords();
        assertEquals(2, validRecords.size(), "Only the 2 valid records must be returned; corrupt records skipped");
        assertEquals(AuditAction.LOGIN, validRecords.get(0).pendingEvent().event().action());
        assertEquals("valid 1", validRecords.get(0).pendingEvent().event().details());
        assertEquals(AuditAction.LOGOUT, validRecords.get(1).pendingEvent().event().action());
        assertEquals("valid 2", validRecords.get(1).pendingEvent().event().details());
    }

    @Test
    void spoolWriteFailure() throws IOException {
        // Create a directory where the spool file was expected, causing file append to fail
        Path collidesWithDir = tempDir.resolve("collision-dir");
        Files.createDirectories(collidesWithDir);

        AuditSpool spool = new AuditSpool(collidesWithDir);
        PendingAuditEvent event = new PendingAuditEvent(new AuditEvent(Instant.now(), 1L, "u", "r", AuditAction.LOGIN, "T", "1", "details", "ip"), 1L);

        assertThrows(AuditSpool.AuditSpoolException.class, () -> spool.write(event));
    }

    @Test
    void repeatedShutdownAndShutdownTimeout() throws Exception {
        Path spoolFile = tempDir.resolve("repeated-shutdown.log");
        AuditSpool spool = new AuditSpool(spoolFile);

        CountDownLatch blockWorker = new CountDownLatch(1);
        Connection mockConn = createMockConnection(s -> {
            try {
                blockWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> false);

        PersistentAuditService service = new PersistentAuditService(spool, () -> mockConn, 10);
        service.log(new AuditEvent(Instant.now(), 1L, "u", "r", AuditAction.LOGIN, "T", "1", "1", "ip"));

        // Calling shutdown multiple times must be idempotent and non-exceptional
        service.shutdown();
        service.shutdown();
        service.shutdown();

        blockWorker.countDown();
    }

    // Helper utilities for dynamic mock JDBC connections without hitting MySQL
    private static Connection createMockConnection(Consumer<String> sqlRecorder, Supplier<Boolean> failureCondition) {
        InvocationHandler connHandler = (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                String sql = (String) args[0];
                return createMockPreparedStatement(sql, sqlRecorder, failureCondition);
            }
            if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                return null;
            }
            return null;
        };
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            connHandler
        );
    }

    private static PreparedStatement createMockPreparedStatement(String sql, Consumer<String> sqlRecorder, Supplier<Boolean> failureCondition) {
        Map<Integer, Object> parameters = new HashMap<>();
        InvocationHandler stmtHandler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                parameters.put(idx, args[1]);
                return null;
            }
            if ("setNull".equals(name) && args != null && args.length >= 1 && args[0] instanceof Integer idx) {
                parameters.put(idx, null);
                return null;
            }
            if ("executeUpdate".equals(name)) {
                if (failureCondition.get()) {
                    throw new SQLException("Simulated database failure");
                }
                Object action = parameters.get(5);
                sqlRecorder.accept(action != null ? action.toString() : sql);
                return 1;
            }
            if ("executeQuery".equals(name)) {
                return createMockResultSet();
            }
            if ("close".equals(name) || "isClosed".equals(name)) {
                return null;
            }
            return null;
        };
        return (PreparedStatement) Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            stmtHandler
        );
    }

    private static ResultSet createMockResultSet() {
        InvocationHandler rsHandler = (proxy, method, args) -> {
            if ("next".equals(method.getName())) {
                return false;
            }
            if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                return null;
            }
            return null;
        };
        return (ResultSet) Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            rsHandler
        );
    }
}
