package com.axalotl.fabrolia.common.regions;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One linearization point for an entity's normal tick and a reserved v2 player tick.
 *
 * <p>The owner is either the exact executing {@link Thread}, an opaque reservation token,
 * or {@code null}.  A reservation therefore competes with the same CAS as vanilla's guarded
 * entity consumer; it is not a second flag that can race before or after TickGuard.
 */
public final class EntityTickLease extends AtomicReference<Object> {

    private static final long serialVersionUID = 1L;
    // Readiness is not a tick owner: an arrival must not look like a foreign
    // player-actor reservation, nor like a removed source entity.
    private volatile boolean arrivalPending;

    public boolean isArrivalPending() {
        return arrivalPending;
    }

    /** Tail-mode arrivals only: published before destination insertion; cleared after source retirement. */
    void setArrivalPending(boolean pending) {
        arrivalPending = pending;
    }


    public enum ReservationResult { ACQUIRED, BUSY }
    public enum CancelResult { RELEASED, FREE, FOREIGN }

    /** Normal (non-reserved) entity-consumer admission. */
    public boolean tryBegin() {
        return compareAndSet(null, Thread.currentThread());
    }

    /**
     * Reserve the exact entity before publishing the phase ledger entry.
     * The token must be an identity object and is never compared with equals().
     */
    public ReservationResult tryReserve(Object token) {
        requireReservationToken(token);
        return compareAndSet(null, token)
                ? ReservationResult.ACQUIRED : ReservationResult.BUSY;
    }

    /** Atomic witness used by v2 to distinguish a running normal body from another token. */
    public Object tryReserveWitness(Object token) {
        requireReservationToken(token);
        return compareAndExchange(null, token);
    }

    /** Authenticate the exact token immediately before its body; identity remains installed. */
    public boolean beginReserved(Object token) {
        requireReservationToken(token);
        return get() == token;
    }

    /** Cancel an admitted reservation whose body has definitely not started. */
    public boolean cancelReserved(Object token) {
        return cancelReservedExact(token) == CancelResult.RELEASED;
    }

    /**
     * Identity-exact cancellation result.  FREE is an idempotent cleanup; FOREIGN is
     * an ownership violation and must never be mistaken for an already-released claim.
     */
    public CancelResult cancelReservedExact(Object token) {
        requireReservationToken(token);
        for (;;) {
            Object observed = get();
            if (observed == null) return CancelResult.FREE;
            if (observed != token) return CancelResult.FOREIGN;
            if (compareAndSet(token, null)) {
                signalReleased();
                return CancelResult.RELEASED;
            }
        }
    }

    public boolean isOwnedByCurrentThread() {
        return get() == Thread.currentThread();
    }

    /** True during the CAS-before-ledger-publication window as well as afterward. */
    public boolean hasReservation() {
        Object observed = get();
        return observed != null && !(observed instanceof Thread);
    }

    public boolean isReservedBy(Object token) {
        return get() == token;
    }

    public boolean hasThreadOwner() {
        return get() instanceof Thread;
    }

    /** Strict release: a wrong-thread/double release is a correctness failure, never a set(false). */
    public void end() {
        Thread thread = Thread.currentThread();
        if (!compareAndSet(thread, null)) {
            throw new IllegalStateException("entity tick lease released by a non-owner thread");
        }
        signalReleased();
    }

    /**
     * Fail-stop barrier helper for a route that collided with a body already in progress.
     * No timeout may be interpreted as permission to enter the next vanilla phase.
     */
    public boolean awaitFreeNanos(long nanos) throws InterruptedException {
        if (get() == null) return true;
        synchronized (this) {
            if (get() == null) return true;
            long millis = nanos / 1_000_000L;
            int remainder = (int) (nanos % 1_000_000L);
            wait(millis, remainder);
        }
        return get() == null;
    }

    private void signalReleased() {
        synchronized (this) {
            notifyAll();
        }
    }

    private static void requireReservationToken(Object token) {
        Objects.requireNonNull(token, "reservation token");
        if (token instanceof Thread) {
            throw new IllegalArgumentException("a Thread cannot be used as an entity reservation token");
        }
    }
}
