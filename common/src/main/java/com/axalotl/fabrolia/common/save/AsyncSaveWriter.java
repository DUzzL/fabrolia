package com.axalotl.fabrolia.common.save;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Background writer for save files whose consistent snapshot was already taken by
 * the owning thread. Jobs for one file run strictly in submission order and never
 * concurrently; different files are written in parallel.
 *
 * <p>A waiter never queues behind unrelated files: {@link #await(Ticket)} runs the
 * pending jobs of its own file inline when no thread is currently writing that
 * file, so a logout save costs at most what vanilla's synchronous write costs.
 *
 * <p>Jobs reproduce vanilla's own exception handling for the IO failures vanilla
 * tolerates (log and continue). Anything that escapes a job is a bug that vanilla
 * would have thrown on the saving thread: it is published as fatal and rethrown
 * from every await covering that job.
 */
public final class AsyncSaveWriter {

    /** Body of one write; runs on a writer thread or inline on a waiter. */
    @FunctionalInterface
    public interface Job {
        void run(FileState state) throws Exception;
    }

    /** Per-file memory owned by whichever thread currently drains that file. */
    public static final class FileState {
        /** Bytes of the last successful write by this writer, or null. */
        public byte[] lastBytes;
        /** File size and mtime observed right after that write. */
        public long lastSize = -1L;
        public long lastModifiedNanos = Long.MIN_VALUE;

        public void forget() {
            lastBytes = null;
            lastSize = -1L;
            lastModifiedNanos = Long.MIN_VALUE;
        }
    }

    /** Completion of one submitted job. */
    public static final class Ticket {
        private final FileQueue file;
        private boolean done;
        private Throwable failure;
        private boolean forgetAfter;

        private Ticket(FileQueue file) {
            this.file = file;
        }
    }

    private static final class Entry {
        final Job job;
        final Ticket ticket;

        Entry(Job job, Ticket ticket) {
            this.job = job;
            this.ticket = ticket;
        }
    }

    private static final class FileQueue {
        final Path path;
        final ArrayDeque<Entry> pending = new ArrayDeque<>();
        final FileState state = new FileState();
        Thread writer;
        boolean scheduled;
        Ticket last;

        FileQueue(Path path) {
            this.path = path;
        }

        /** Nothing queued or running and no remembered content: the entry can go. */
        boolean removable() {
            return pending.isEmpty() && writer == null && !scheduled && state.lastBytes == null;
        }
    }

    public static final int DEFAULT_MAX_PENDING = 4096;

    public static final AsyncSaveWriter INSTANCE = new AsyncSaveWriter(
            defaultThreads(), DEFAULT_MAX_PENDING, AsyncSaveWriter::publishFatal, null);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition progress = lock.newCondition();
    private final Map<Path, FileQueue> files = new HashMap<>();
    private final int threads;
    private final int maxPending;
    private final Consumer<Throwable> fatalSink;
    private Executor executor;
    private int pending;

    AsyncSaveWriter(int threads, int maxPending, Consumer<Throwable> fatalSink, Executor executor) {
        this.threads = threads;
        this.maxPending = maxPending;
        this.fatalSink = fatalSink;
        this.executor = executor;
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }

    private static void publishFatal(Throwable failure) {
        Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                .publishFatalAndSelectWinner(failure);
        org.slf4j.LoggerFactory.getLogger("Fabrolia-Save")
                .error("Async save job failed; vanilla would have thrown on the saving thread", winner);
    }

    private Executor executorLocked() {
        if (executor == null) {
            AtomicInteger counter = new AtomicInteger();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), runnable -> {
                        Thread thread = new Thread(runnable,
                                "Fabrolia-Save-IO-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            executor = pool;
        }
        return executor;
    }

    /**
     * Queues {@code job} after every job already submitted for {@code path}.
     *
     * @throws IllegalStateException when the bounded queue is full: the writers are
     *         stuck or the disk cannot keep up, and silently dropping or blocking a
     *         save would hide it.
     */
    public Ticket submit(Path path, Job job) {
        Path key = path.toAbsolutePath().normalize();
        lock.lock();
        try {
            if (pending >= maxPending) {
                throw new IllegalStateException("Fabrolia async save queue is full ("
                        + pending + " unwritten saves, limit " + maxPending
                        + "); the save writer threads are stuck or the disk cannot keep up");
            }
            FileQueue file = files.computeIfAbsent(key, FileQueue::new);
            Ticket ticket = new Ticket(file);
            file.pending.addLast(new Entry(job, ticket));
            file.last = ticket;
            pending++;
            scheduleLocked(file);
            return ticket;
        } finally {
            lock.unlock();
        }
    }

    private void scheduleLocked(FileQueue file) {
        if (file.pending.isEmpty() || file.writer != null || file.scheduled) return;
        file.scheduled = true;
        try {
            executorLocked().execute(() -> drainOnPool(file));
        } catch (Throwable rejected) {
            file.scheduled = false;
            throw rejected;
        }
    }

    private void drainOnPool(FileQueue file) {
        lock.lock();
        try {
            file.scheduled = false;
            while (file.writer == null && !file.pending.isEmpty()) {
                runOneLocked(file);
            }
            // A waiter that took the file over reschedules any remainder itself.
        } finally {
            lock.unlock();
        }
    }

    /** Runs the head job of {@code file} with the lock released around the body. */
    private void runOneLocked(FileQueue file) {
        Entry entry = file.pending.pollFirst();
        file.writer = Thread.currentThread();
        lock.unlock();
        Throwable failure = null;
        try {
            entry.job.run(file.state);
        } catch (Throwable thrown) {
            failure = thrown;
        }
        if (failure != null) {
            // Unknown state after a failed body: never skip the next write.
            file.state.forget();
            try {
                fatalSink.accept(failure);
            } catch (Throwable sinkFailure) {
                if (sinkFailure != failure) failure.addSuppressed(sinkFailure);
            }
        }
        lock.lock();
        file.writer = null;
        Ticket ticket = entry.ticket;
        ticket.done = true;
        ticket.failure = failure;
        pending--;
        if (ticket.forgetAfter && file.pending.isEmpty()) {
            file.state.forget();
        }
        if (file.removable()) {
            files.remove(file.path, file);
        }
        progress.signalAll();
    }

    /**
     * Drops the per-file memory used to skip unchanged rewrites once the jobs
     * submitted so far for this ticket's file are written (a player left).
     */
    public void forgetAfter(Ticket ticket) {
        lock.lock();
        try {
            if (ticket.done) {
                if (ticket.file.pending.isEmpty() && ticket.file.writer == null) {
                    ticket.file.state.forget();
                }
                if (ticket.file.removable()) files.remove(ticket.file.path, ticket.file);
            } else {
                ticket.forgetAfter = true;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Waits until {@code ticket} and every earlier job of its file are written. */
    public void await(Ticket ticket) {
        lock.lock();
        try {
            awaitLocked(ticket);
        } finally {
            lock.unlock();
        }
        throwIfFailed(ticket.failure);
    }

    /** Waits for every job submitted before this call, on every file. */
    public void awaitAll() {
        List<Ticket> tickets = new ArrayList<>();
        lock.lock();
        try {
            for (FileQueue file : files.values()) {
                if (file.last != null && !file.last.done) tickets.add(file.last);
            }
            for (Ticket ticket : tickets) {
                awaitLocked(ticket);
            }
        } finally {
            lock.unlock();
        }
        Throwable failure = null;
        for (Ticket ticket : tickets) {
            if (ticket.failure != null && failure == null) failure = ticket.failure;
        }
        throwIfFailed(failure);
    }

    private void awaitLocked(Ticket ticket) {
        boolean interrupted = false;
        FileQueue file = ticket.file;
        try {
            while (!ticket.done) {
                if (file.writer == null && !file.pending.isEmpty()) {
                    runOneLocked(file);
                } else {
                    try {
                        progress.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            // Anything submitted after our ticket keeps its background drain.
            scheduleLocked(file);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void throwIfFailed(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Async save job failed", failure);
    }

    /** Number of submitted jobs not yet written. */
    public int pendingCount() {
        lock.lock();
        try {
            return pending;
        } finally {
            lock.unlock();
        }
    }
}
