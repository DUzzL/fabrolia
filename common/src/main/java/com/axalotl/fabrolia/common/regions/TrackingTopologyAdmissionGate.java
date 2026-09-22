package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exact reader-drain gate for tracker-topology transitions.
 *
 * <p>The old fair {@code ReentrantReadWriteLock} put every tracker/player
 * routing decision through one shared read-count word.  With one island worker
 * per core, the read-side increment/decrement cache line became a server-wide
 * serialization point even when no topology writer existed.  This gate keeps
 * the same writer cut without a shared reader RMW:</p>
 *
 * <ol>
 *     <li>A writer changes the phase from even/open to odd/closed.</li>
 *     <li>Readers increment a cache-line-spaced stripe and then validate that
 *     the phase is still the same even value.  A reader which raced the close
 *     backs out before its protected body can begin.</li>
 *     <li>The writer waits, without a timeout, until every stripe is zero.
 *     Once that drain completes, every reader admitted under the old phase has
 *     returned and no new reader can enter until write unlock.</li>
 * </ol>
 *
 * <p>Read admission is reentrant per thread.  That is required for an already
 * admitted routing decision to make a nested routing call after writer intent
 * is published: counting the nested call again would make that reader wait for
 * the writer while the writer waits for its outer read hold.</p>
 */
final class TrackingTopologyAdmissionGate {

    /** Enough unique stripes for all normal region/VMP workers in one level. */
    private static final int STRIPE_COUNT = 256;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    /** Sixteen ints are one 64-byte cache line on the supported server VMs. */
    private static final int STRIPE_STRIDE = 16;

    /** Even values admit readers; odd values are writer intent/ownership. */
    private final AtomicLong phase = new AtomicLong();
    private final AtomicIntegerArray readers =
            new AtomicIntegerArray(STRIPE_COUNT * STRIPE_STRIDE);
    private final AtomicInteger nextStripe = new AtomicInteger();
    private final ThreadLocal<ReadHold> readHold = ThreadLocal.withInitial(
            () -> new ReadHold(nextStripe.getAndIncrement() & STRIPE_MASK));

    /** Writers are rare and physical-Main-confined in production. */
    private final ReentrantLock writerSerial = new ReentrantLock(true);
    /** Readers only join this queue while an odd phase prevents admission. */
    private final ConcurrentLinkedQueue<Thread> parkedReaders =
            new ConcurrentLinkedQueue<>();

    /** Non-null from intent publication through the complete reader drain. */
    private volatile Thread drainingWriter;
    private volatile Thread writerOwner;
    private int writerDepth;

    void readLock() {
        ReadHold hold = readHold.get();
        if (hold.depth != 0) {
            if (hold.depth == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "tracking-topology read hold count overflow");
            }
            hold.depth++;
            return;
        }
        if (writerOwner == Thread.currentThread()) {
            throw new IllegalStateException(
                    "tracking-topology writer attempted a nested read admission");
        }

        int stripeOffset = hold.stripe * STRIPE_STRIDE;
        for (;;) {
            long observedPhase = phase.get();
            if ((observedPhase & 1L) != 0L) {
                awaitReaderPhase();
                continue;
            }

            int active = readers.incrementAndGet(stripeOffset);
            if (active <= 0) {
                readers.decrementAndGet(stripeOffset);
                throw new IllegalStateException(
                        "tracking-topology reader stripe overflow");
            }

            // This is the admission linearization check.  A complete writer
            // close/open cycle changes the value by two, so a reader delayed
            // across even a very short writer cannot validate the old phase.
            if (phase.get() == observedPhase) {
                hold.depth = 1;
                return;
            }

            releaseStripe(stripeOffset);
        }
    }

    void readUnlock() {
        ReadHold hold = readHold.get();
        if (hold.depth == 0) {
            throw new IllegalStateException(
                    "tracking-topology read unlock without ownership");
        }
        if (hold.depth > 1) {
            hold.depth--;
            return;
        }

        hold.depth = 0;
        releaseStripe(hold.stripe * STRIPE_STRIDE);
    }

    int getReadHoldCount() {
        return readHold.get().depth;
    }

    void writeLock() {
        Thread current = Thread.currentThread();
        if (writerOwner == current) {
            if (writerDepth == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "tracking-topology write hold count overflow");
            }
            writerDepth++;
            return;
        }
        if (getReadHoldCount() != 0) {
            throw new IllegalStateException(
                    "tracking-topology read-to-write upgrade attempted");
        }

        writerSerial.lock();
        long openPhase = phase.get();
        if ((openPhase & 1L) != 0L || writerOwner != null) {
            writerSerial.unlock();
            throw new IllegalStateException(
                    "tracking-topology writer serialization lost its open phase");
        }

        // Close admission before looking at the first stripe.  A reader which
        // already incremented is part of this drain; one which increments from
        // a stale even observation must see the odd phase and back out.
        phase.set(openPhase + 1L);
        drainingWriter = current;
        boolean interrupted = false;
        while (!allReaderStripesEmpty()) {
            if (Thread.interrupted()) interrupted = true;
            LockSupport.park(this);
        }
        if (interrupted) current.interrupt();
        writerOwner = current;
        writerDepth = 1;
    }

    void writeUnlock() {
        Thread current = Thread.currentThread();
        if (writerOwner != current || writerDepth == 0) {
            throw new IllegalStateException(
                    "tracking-topology write unlock without ownership");
        }
        if (writerDepth > 1) {
            writerDepth--;
            return;
        }

        long closedPhase = phase.get();
        if ((closedPhase & 1L) == 0L) {
            throw new IllegalStateException(
                    "tracking-topology writer lost its closed phase");
        }
        writerDepth = 0;
        writerOwner = null;
        drainingWriter = null;
        phase.set(closedPhase + 1L);

        // Do not remove registrations here.  If another writer closes the
        // phase before an awakened reader gets CPU time, that reader parks
        // again under the same still-live registration and the next writer can
        // wake it.  The reader removes its own entry only after observing an
        // even phase.
        for (Thread reader : parkedReaders) {
            LockSupport.unpark(reader);
        }
        writerSerial.unlock();
    }

    /** Package-visible read used by focused concurrency tests and diagnostics. */
    boolean writerIntentPublished() {
        return (phase.get() & 1L) != 0L;
    }

    private void awaitReaderPhase() {
        Thread current = Thread.currentThread();
        parkedReaders.add(current);
        boolean interrupted = false;
        while ((phase.get() & 1L) != 0L) {
            // Non-interruptible, no timeout and no ownership miss: this is the
            // same exact wait contract as Lock.lock(), with explicit wake-up.
            if (Thread.interrupted()) interrupted = true;
            LockSupport.park(this);
        }
        parkedReaders.remove(current);
        if (interrupted) current.interrupt();
    }

    private void releaseStripe(int stripeOffset) {
        int remaining = readers.decrementAndGet(stripeOffset);
        if (remaining < 0) {
            readers.incrementAndGet(stripeOffset);
            throw new IllegalStateException(
                    "tracking-topology reader stripe underflow");
        }
        Thread writer = drainingWriter;
        if (writer != null) {
            // Multiple exits may collapse into one park permit.  That is fine:
            // the awakened writer rescans every stripe and parks again only if
            // at least one admitted reader remains.
            LockSupport.unpark(writer);
        }
    }

    private boolean allReaderStripesEmpty() {
        for (int stripe = 0; stripe < STRIPE_COUNT; stripe++) {
            if (readers.get(stripe * STRIPE_STRIDE) != 0) return false;
        }
        return true;
    }

    private static final class ReadHold {
        private final int stripe;
        private int depth;

        private ReadHold(int stripe) {
            this.stripe = stripe;
        }
    }
}
