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
 * <p>A waiter never queues behind unrelated files: {@link #await(Ticket)} and
 * {@link #awaitFile(Path)} run the pending jobs of their own file inline when no
 * thread is currently writing it, so a logout or a re-read costs at most what
 * vanilla's synchronous write costs.
 *
 * <p>Bounded per file: a submit for a file that still has an unstarted job first
 * writes (or waits for) that job, so at most one write per file is queued and
 * memory stays proportional to the number of files. A stuck disk therefore
 * blocks the saving thread exactly like vanilla's synchronous write, and is
 * logged loudly while it does.
 *
 * <p>Jobs reproduce vanilla's own exception handling for the IO failures vanilla
 * tolerates (log and continue). Anything that escapes a job is a bug that vanilla
 * would have thrown on the saving thread: it is always published as fatal and is
 * rethrown by awaits whose own (or last) ticket it is.
 */
public final class AsyncSaveWriter {

    /** Body of one write; runs on a writer thread or inline on a waiter. */
    @FunctionalInterface
    public interface Job {
        void run() throws Exception;
    }

    /** Completion of one submitted job. */
    public static final class Ticket {
        private final FileQueue file;
        private boolean done;
        private Throwable failure;

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
        Thread writer;
        boolean scheduled;
        Ticket last;

        FileQueue(Path path) {
            this.path = path;
        }

        boolean idle() {
            return pending.isEmpty() && writer == null && !scheduled;
        }
    }

    private static final long STUCK_LOG_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Fabrolia-Save");

    public static final AsyncSaveWriter INSTANCE = new AsyncSaveWriter(
            defaultThreads(), AsyncSaveWriter::publishFatal, null);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition progress = lock.newCondition();
    private final Map<Path, FileQueue> files = new HashMap<>();
    private final int threads;
    private final Consumer<Throwable> fatalSink;
    private Executor executor;
    private int pending;

    AsyncSaveWriter(int threads, Consumer<Throwable> fatalSink, Executor executor) {
        this.threads = threads;
        this.fatalSink = fatalSink;
        this.executor = executor;
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }

    private static void publishFatal(Throwable failure) {
        Throwable winner = com.axalotl.fabrolia.common.regions.RegionScheduler
                .publishFatalAndSelectWinner(failure);
        LOGGER.error("Async save job failed; vanilla would have thrown on the saving thread", winner);
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

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** Queues {@code job} after every job already submitted for {@code path}. */
    public Ticket submit(Path path, Job job) {
        Path key = key(path);
        lock.lock();
        try {
            FileQueue file = files.computeIfAbsent(key, FileQueue::new);
            while (!file.pending.isEmpty()) {
                // Backpressure: the previous write of this file has not started yet.
                awaitLocked(file.pending.peekLast().ticket);
                file = files.computeIfAbsent(key, FileQueue::new);
            }
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
            forgetIfIdleLocked(file);
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
            entry.job.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        lock.lock();
        file.writer = null;
        Ticket ticket = entry.ticket;
        ticket.done = true;
        ticket.failure = failure;
        pending--;
        progress.signalAll();
        if (failure != null) {
            // Waiters already see the failure; publish without holding our lock.
            lock.unlock();
            try {
                fatalSink.accept(failure);
            } catch (Throwable sinkFailure) {
                if (sinkFailure != failure) failure.addSuppressed(sinkFailure);
            } finally {
                lock.lock();
            }
        }
    }

    private void forgetIfIdleLocked(FileQueue file) {
        if (file.idle()) files.remove(file.path, file);
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

    /**
     * Read barrier: returns once every write submitted so far for {@code path} is
     * on disk, so a reader never sees an older or half-written file.
     */
    public void awaitFile(Path path) {
        Ticket last;
        lock.lock();
        try {
            FileQueue file = files.get(key(path));
            last = file == null ? null : file.last;
            if (last != null) awaitLocked(last);
        } finally {
            lock.unlock();
        }
        if (last != null) throwIfFailed(last.failure);
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
        long since = System.nanoTime();
        boolean reported = false;
        try {
            while (!ticket.done) {
                if (file.writer == null && !file.pending.isEmpty()) {
                    runOneLocked(file);
                    continue;
                }
                try {
                    progress.awaitNanos(STUCK_LOG_NANOS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (!reported && !ticket.done && System.nanoTime() - since >= STUCK_LOG_NANOS) {
                    reported = true;
                    LOGGER.error("Save write of {} has not completed for {} s ({} save writes pending,"
                                    + " writer {}); the disk is stuck or far too slow. {} keeps waiting.",
                            file.path, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since), pending,
                            file.writer == null ? "none" : file.writer.getName(),
                            Thread.currentThread().getName());
                }
            }
            // Anything submitted after our ticket keeps its background drain.
            scheduleLocked(file);
            forgetIfIdleLocked(file);
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
