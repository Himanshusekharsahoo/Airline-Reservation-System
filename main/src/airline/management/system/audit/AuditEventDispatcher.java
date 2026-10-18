package airline.management.system.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Single-writer FIFO dispatcher with explicit bounded-queue and shutdown behavior. */
final class AuditEventDispatcher<T> {
    private final ThreadPoolExecutor executor;
    private final Consumer<T> writer;
    private final BiConsumer<T, Exception> failureReporter;

    AuditEventDispatcher(int capacity, Consumer<T> writer, BiConsumer<T, Exception> failureReporter) {
        this(capacity, true, writer, failureReporter);
    }

    AuditEventDispatcher(int capacity, boolean daemon, Consumer<T> writer, BiConsumer<T, Exception> failureReporter) {
        if (capacity < 1) throw new IllegalArgumentException("Audit queue capacity must be positive.");
        this.writer = Objects.requireNonNull(writer);
        this.failureReporter = Objects.requireNonNull(failureReporter);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), task -> {
                Thread thread = new Thread(task, "AeroDesk-AuditWriter");
                thread.setDaemon(daemon);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    void submit(T event) {
        executor.execute(new DispatchTask<>(event, writer, failureReporter));
    }

    int pendingCount() {
        return executor.getQueue().size() + (executor.getActiveCount() == 0 ? 0 : 1);
    }

    boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(timeout, unit);
    }

    List<T> shutdownAndDrain(long timeout, TimeUnit unit) throws InterruptedException {
        executor.shutdown();
        boolean terminated = executor.awaitTermination(timeout, unit);
        if (terminated) {
            return Collections.emptyList();
        }
        return drainRemaining();
    }

    List<T> drainRemaining() {
        List<Runnable> dropped = executor.shutdownNow();
        List<T> remaining = new ArrayList<>();
        for (Runnable r : dropped) {
            if (r instanceof DispatchTask<?> dt) {
                @SuppressWarnings("unchecked")
                T item = (T) dt.item();
                remaining.add(item);
            }
        }
        return remaining;
    }

    private record DispatchTask<T>(T item, Consumer<T> writer, BiConsumer<T, Exception> failureReporter) implements Runnable {
        @Override
        public void run() {
            try {
                writer.accept(item);
            } catch (Exception failure) {
                failureReporter.accept(item, failure);
            }
        }
    }
}
