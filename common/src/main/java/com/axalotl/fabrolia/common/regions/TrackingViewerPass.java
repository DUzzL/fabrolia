package com.axalotl.fabrolia.common.regions;

import java.util.Iterator;
import java.util.Set;

/**
 * One viewer's step of the VMP tracker tick: diff the trackers whose area covers
 * the viewer's chunk against the trackers the viewer is currently tracking.
 *
 * <p>Same outcomes as VMP's {@code NearbyEntityTracking.tick0} per-player loop,
 * minus calls that are provably no-ops: a re-evaluation whose exact vanilla
 * result equals the current pairing, a removal for a viewer that is neither
 * paired nor staged, and a removal from a retired tracker (its
 * {@code broadcastRemoved} already removed every viewer). Every remaining call
 * reaches the real (staged) vanilla body.</p>
 *
 * <p>Thread contract: {@code tracked} belongs to this viewer and is touched by
 * one thread; {@code inRange} is read-only for the duration of the pass.</p>
 */
public final class TrackingViewerPass {

    private TrackingViewerPass() {}

    /** Per-tracker effects; one instance serves one viewer pass. */
    public interface Trackers<T> {
        /** Broadcast removal already happened; the tracker can never pair again. */
        boolean retired(T tracker);

        /** Position changed since the previous tracker tick (snapshot of this tick). */
        boolean moved(T tracker);

        /** True exactly once per tracker per tracker tick, across all viewers. */
        boolean claimTick(T tracker);

        /** The once-per-tick state publication (sendChanges when due). */
        void tick(T tracker);

        /** {@code updatePlayer(viewer)} would neither add nor remove the pairing. */
        boolean pairingCurrent(T tracker);

        /** {@code removePlayer(viewer)} would change nothing: not paired, nothing staged. */
        boolean absentAndIdle(T tracker);

        void updatePlayer(T tracker);

        void removePlayer(T tracker);
    }

    /**
     * @param viewerMoved the viewer's position changed since its previous pass
     * @param inRange     trackers whose area covers the viewer's chunk
     * @param tracked     trackers this viewer tracked after its previous pass;
     *                    updated in place to {@code inRange}
     */
    public static <T> void run(boolean viewerMoved, Set<T> inRange, Set<T> tracked,
                               Trackers<T> trackers) {
        Iterator<T> iterator = tracked.iterator();
        while (iterator.hasNext()) {
            T tracker = iterator.next();
            if (inRange.contains(tracker)) {
                handle(viewerMoved, tracker, trackers);
                continue;
            }
            if (!trackers.retired(tracker) && !trackers.absentAndIdle(tracker)) {
                trackers.removePlayer(tracker);
            }
            iterator.remove();
        }
        for (T tracker : inRange) {
            if (tracked.contains(tracker)) continue;
            handle(viewerMoved, tracker, trackers);
            tracked.add(tracker);
        }
    }

    private static <T> void handle(boolean viewerMoved, T tracker, Trackers<T> trackers) {
        if (trackers.claimTick(tracker)) trackers.tick(tracker);
        if ((viewerMoved || trackers.moved(tracker)) && !trackers.pairingCurrent(tracker)) {
            trackers.updatePlayer(tracker);
        }
    }

    /**
     * The distance half of vanilla {@code TrackedEntity.updatePlayer}
     * ({@code dx = viewer.x - entity.x}, same for z; installed VMP makes the
     * chunk-tracked half always true), with vanilla's exact arithmetic.
     */
    public static boolean inVanillaRange(double dx, double dz, int effectiveRange,
                                         int viewDistance) {
        double visibleRange = Math.min(effectiveRange, viewDistance * 16);
        return dx * dx + dz * dz <= visibleRange * visibleRange;
    }
}
