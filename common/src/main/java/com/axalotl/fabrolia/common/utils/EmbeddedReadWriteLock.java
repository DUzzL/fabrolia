package com.axalotl.fabrolia.common.utils;

/**
 * Read-write lock whose whole state is two fields of the guarded object and
 * whose wait queue is that object's own monitor: no lock objects per
 * instance. Built for {@code PalettedContainer} (≈2 per chunk section, a
 * million on a large server), where a {@code ReentrantReadWriteLock} cost
 * ~120 bytes each and contention is rare.
 *
 * <p>Semantics, as the callers rely on them:
 * <ul>
 *   <li>readers share, a writer is exclusive;</li>
 *   <li>reads are reentrant, also while a writer waits: a reader never waits
 *       for a WAITING writer, only for a HOLDING one, so a thread that
 *       already reads can always read again (chunk-packet serialization
 *       read-locks every section, then each section's {@code write} and
 *       {@code getSerializedSize} read-lock again);</li>
 *   <li>the write lock is reentrant, and its holder may read-lock — both just
 *       deepen its exclusive hold, which ends when the last of them is
 *       released;</li>
 *   <li>read→write upgrade deadlocks, as with {@code ReentrantReadWriteLock};</li>
 *   <li>acquisition is uninterruptible; an interrupt that arrives while
 *       waiting is restored on return.</li>
 * </ul>
 * Readers are preferred: a writer waits until no reader holds the object, so
 * back-to-back overlapping reads of one container (packet builds, saves) can
 * delay a write; read holds are short. Without per-thread read accounting a
 * read unlock cannot tell whose hold it releases; it fails only when no hold
 * is left. The monitor is held only for the state transition, never while the
 * caller runs its locked body.
 */
public final class EmbeddedReadWriteLock {

    /** The lock state, stored on the guarded object. Accessed only under its monitor. */
    public interface State {
        /** {@code > 0}: that many read holds; {@code < 0}: write hold of depth {@code -state}; 0: free. */
        int fabrolia$lockState();

        void fabrolia$lockState(int state);

        Thread fabrolia$lockWriter();

        void fabrolia$lockWriter(Thread writer);
    }

    private EmbeddedReadWriteLock() {}

    public static void readLock(State s) {
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        synchronized (s) {
            int state = s.fabrolia$lockState();
            if (state < 0 && s.fabrolia$lockWriter() == current) {
                s.fabrolia$lockState(state - 1);
                return;
            }
            while (state < 0) {
                try {
                    s.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                state = s.fabrolia$lockState();
            }
            s.fabrolia$lockState(state + 1);
        }
        if (interrupted) current.interrupt();
    }

    public static void readUnlock(State s) {
        synchronized (s) {
            int state = s.fabrolia$lockState();
            if (state < 0) {
                releaseWriteHold(s, state);
                return;
            }
            if (state == 0) {
                throw new IllegalMonitorStateException("read unlock without a read hold");
            }
            s.fabrolia$lockState(--state);
            if (state == 0) s.notifyAll();
        }
    }

    public static void writeLock(State s) {
        Thread current = Thread.currentThread();
        boolean interrupted = false;
        synchronized (s) {
            int state = s.fabrolia$lockState();
            if (state < 0 && s.fabrolia$lockWriter() == current) {
                s.fabrolia$lockState(state - 1);
                return;
            }
            while (state != 0) {
                try {
                    s.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                state = s.fabrolia$lockState();
            }
            s.fabrolia$lockWriter(current);
            s.fabrolia$lockState(-1);
        }
        if (interrupted) current.interrupt();
    }

    public static void writeUnlock(State s) {
        synchronized (s) {
            int state = s.fabrolia$lockState();
            if (state >= 0) {
                throw new IllegalMonitorStateException("write unlock without a write hold");
            }
            releaseWriteHold(s, state);
        }
    }

    private static void releaseWriteHold(State s, int state) {
        if (s.fabrolia$lockWriter() != Thread.currentThread()) {
            throw new IllegalMonitorStateException("unlock by a thread that does not hold the write lock");
        }
        s.fabrolia$lockState(++state);
        if (state == 0) {
            s.fabrolia$lockWriter(null);
            s.notifyAll();
        }
    }
}
