package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Navigation membership publication and explosion notification batching.
 * Both locks are leaf locks: callers do all entity/path inspection and dispatch outside them.
 */
public final class NavigationBatchState<N> {
    private final Object membershipLock = new Object();
    private final Set<N> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile List<N> snapshot = List.of();

    private final Object pendingLock = new Object();
    private volatile int batchDepth;
    private Set<N> pending;

    /** Immutable, array-backed read cut; acquiring it never allocates or locks. */
    public List<N> activeSnapshot() {
        return snapshot;
    }

    public void activate(N navigation) {
        Objects.requireNonNull(navigation);
        synchronized (membershipLock) {
            if (active.add(navigation)) snapshot = List.copyOf(active);
        }
    }

    public void deactivate(N navigation) {
        synchronized (membershipLock) {
            if (active.remove(navigation)) snapshot = List.copyOf(active);
        }
    }

    /** The supplied immutable list contains identities inspected outside the leaf lock. */
    public void deactivateAll(List<N> navigations) {
        synchronized (membershipLock) {
            boolean changed = false;
            for (int i = 0; i < navigations.size(); i++) changed |= active.remove(navigations.get(i));
            if (changed) snapshot = List.copyOf(active);
        }
    }

    public void beginBatch() {
        synchronized (pendingLock) {
            batchDepth++;
        }
    }

    /** Resource cleanup only. Detaching/recomputing requires a separate success admission. */
    public void endBatch() {
        synchronized (pendingLock) {
            if (batchDepth == 0) throw new IllegalStateException("Navigation batch not entered");
            batchDepth--;
        }
    }

    /** False transfers this notification to the caller's normal owner-routed recompute path. */
    public boolean defer(N navigation) {
        if (batchDepth == 0) return false;
        synchronized (pendingLock) {
            if (batchDepth == 0) return false;
            if (pending == null) pending = Collections.newSetFromMap(new IdentityHashMap<>());
            pending.add(navigation);
            return true;
        }
    }

    /** Transfers sole ownership of the detached set; its consumer must run outside leaf locks. */
    public Set<N> detachReady() {
        synchronized (pendingLock) {
            if (batchDepth != 0 || pending == null) return Collections.emptySet();
            Set<N> detached = pending;
            pending = null;
            return detached;
        }
    }

    public int pendingCount() {
        synchronized (pendingLock) {
            return pending == null ? 0 : pending.size();
        }
    }
}
