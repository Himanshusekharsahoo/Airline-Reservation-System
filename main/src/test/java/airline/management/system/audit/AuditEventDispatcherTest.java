package airline.management.system.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuditEventDispatcherTest {
    @Test
    void dispatchesAcceptedEventsInOrderAndRejectsWhenBoundedQueueIsFull() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        List<Integer> written = new ArrayList<>();
        AuditEventDispatcher<Integer> dispatcher = new AuditEventDispatcher<>(1, value -> {
            if (value == 1) {
                writerStarted.countDown();
                try {
                    if (!releaseWriter.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test writer timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            written.add(value);
        }, (value, failure) -> { throw new AssertionError(failure); });

        dispatcher.submit(1);
        assertTrue(writerStarted.await(1, TimeUnit.SECONDS));
        dispatcher.submit(2);
        assertThrows(RejectedExecutionException.class, () -> dispatcher.submit(3));
        assertEquals(2, dispatcher.pendingCount());

        releaseWriter.countDown();
        assertTrue(dispatcher.shutdown(2, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), written);
    }

    @Test
    void reportsWriterFailuresAndDrainsOnGracefulShutdown() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        List<Integer> failedEvents = new ArrayList<>();
        AuditEventDispatcher<Integer> dispatcher = new AuditEventDispatcher<>(2,
            value -> { throw new IllegalStateException("write failed"); },
            (value, failure) -> {
                failedEvents.add(value);
                reported.countDown();
            });

        dispatcher.submit(7);
        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertTrue(dispatcher.shutdown(1, TimeUnit.SECONDS));
        assertEquals(List.of(7), failedEvents);
        assertFalse(dispatcher.pendingCount() > 0);
    }

    @Test
    void drainsUnexecutedTasksOnShutdownTimeout() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch blockWriter = new CountDownLatch(1);
        AuditEventDispatcher<Integer> dispatcher = new AuditEventDispatcher<>(5, value -> {
            writerStarted.countDown();
            try {
                blockWriter.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, (val, err) -> {});

        dispatcher.submit(100);
        assertTrue(writerStarted.await(1, TimeUnit.SECONDS));
        dispatcher.submit(101);
        dispatcher.submit(102);

        // Shutdown with very short timeout to trigger drain of remaining tasks
        List<Integer> undrained = dispatcher.shutdownAndDrain(50, TimeUnit.MILLISECONDS);
        blockWriter.countDown();
        assertEquals(List.of(101, 102), undrained);
    }
}
