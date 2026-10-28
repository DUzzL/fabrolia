package com.axalotl.fabrolia.common.regions;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One linearization point for an entity's tick: the owner is the exact executing
 * {@link Thread} or {@code null}.
 */
public final class EntityTickLease extends AtomicReference<Object> {

    private static final long serialVersionUID = 1L;
    private static final VarHandle ARRIVAL_PENDING;
    /**
     * Upper bound of leases whose flag is set: incremented before a flag turns
     * true and decremented only after it turned false, so a zero read proves
     * every flag false at that instant. Hot publication paths read this shared,
     * almost never written counter instead of each entity's lease.
     */
    private static final AtomicInteger PENDING_ARRIVALS = new AtomicInteger();

    static {
        try {
            ARRIVAL_PENDING = MethodHandles.lookup().findVarHandle(
                    EntityTickLease.class, "arrivalPending", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Readiness is not a tick owner: an arrival must not look like a running
    // tick, nor like a removed source entity.
    private volatile boolean arrivalPending;

    public boolean isArrivalPending() {
        return arrivalPending;
    }

    /** False proves no lease has a pending arrival; true says nothing about a given lease. */
    public static boolean anyArrivalPending() {
        return PENDING_ARRIVALS.get() != 0;
    }

    /** Tail-mode arrivals only: published before destination insertion; cleared after source retirement. */
    void setArrivalPending(boolean pending) {
        if (pending) {
            PENDING_ARRIVALS.incrementAndGet();
            if (!ARRIVAL_PENDING.compareAndSet(this, false, true)) {
                PENDING_ARRIVALS.decrementAndGet();
            }
        } else if (ARRIVAL_PENDING.compareAndSet(this, true, false)) {
            PENDING_ARRIVALS.decrementAndGet();
        }
    }


    /** Entity-consumer admission: the executing thread becomes the owner. */
    public boolean tryBegin() {
        return compareAndSet(null, Thread.currentThread());
    }

    /** Strict release: a wrong-thread/double release is a correctness failure, never a set(false). */
    public void end() {
        Thread thread = Thread.currentThread();
        if (!compareAndSet(thread, null)) {
            throw new IllegalStateException("entity tick lease released by a non-owner thread");
        }
    }
}
