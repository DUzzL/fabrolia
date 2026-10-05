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
    /** Null after a membership change until the next read rebuilds it under the leaf lock. */
    private volatile List<N> snapshot = List.of();

    private final Object pendingLock = new Object();
    private volatile int batchDepth;
    private Set<N> pending;

    /**
     * Immutable, array-backed read cut of every completed membership change.
     * Writers only invalidate; the first read after a change copies the set once
     * under the leaf lock, so a burst of path starts/stops between two block
     * updates costs one copy instead of one per change.
     */
    public List<N> activeSnapshot() {
        List<N> current = snapshot;
        if (current != null) return current;
        synchronized (membershipLock) {
            current = snapshot;
            if (current == null) {
                current = List.copyOf(active);
                snapshot = current;
            }
            return current;
        }
    }

    public void activate(N navigation) {
        Objects.requireNonNull(navigation);
        synchronized (membershipLock) {
            if (active.add(navigation)) snapshot = null;
        }
    }

    public void deactivate(N navigation) {
        synchronized (membershipLock) {
            if (active.remove(navigation)) snapshot = null;
        }
    }

    /** The supplied immutable list contains identities inspected outside the leaf lock. */
    public void deactivateAll(List<N> navigations) {
        synchronized (membershipLock) {
            boolean changed = false;
            for (int i = 0; i < navigations.size(); i++) changed |= active.remove(navigations.get(i));
            if (changed) snapshot = null;
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
