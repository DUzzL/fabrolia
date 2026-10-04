package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Structural changes of VMP's tracker index that never wait for the tracker tick.
 *
 * <p>A non-player tracker's add/remove is published lock-free and applied, in
 * publication order, by the next holder of the index lock. Its immediate pairing
 * with every viewer is not part of this queue: it happens at add time through the
 * owner-tail bulk refresh, so a viewer may already see a tracker which no viewer
 * pass has recorded in that viewer's tracked set. Such trackers stay "fresh"
 * until their staged membership has settled; then they join the tracked set of
 * every current viewer, and the ordinary passes take over (including removing a
 * viewer that left the tracker's area before its first pass). A lifecycle viewer
 * removal visits the fresh trackers as well as the viewer's tracked set.</p>
 *
 * <p>Thread contract: {@link #queueAdd}/{@link #queueRemove}/{@link #queued} from
 * any thread; every other method only while holding the index lock. The holder
 * may be the tracker tick, a lifecycle viewer removal, or the off-tick applier
 * that drains published changes between ticks — applying a change earlier than
 * the next tick head is indistinguishable for every reader, since all readers
 * of the index hold the same lock.</p>
 */
public final class TrackerIndexChanges<T, P> {

    /**
     * The index operations, all invoked while the caller holds the index lock.
     * Installed on VMP's NearbyEntityTracking, hence the prefixed names.
     */
    public interface Index<T, P> {
        boolean fabrolia$indexed(T tracker);

        /** Insert into the area index; refuses a removed or retired generation. */
        void fabrolia$insert(T tracker);

        /** The index's own removal body. */
        void fabrolia$remove(T tracker);

        /** Retired by broadcastRemoved, or its index removal already entered. */
        boolean fabrolia$gone(T tracker);

        /** Feeds every current viewer and returns true iff no membership is staged. */
        boolean fabrolia$viewersSettled(T tracker, Consumer<P> viewerSink);

        /** The viewer's tracked set, or null if the viewer is not indexed. */
        Set<T> fabrolia$trackedBy(P viewer);

        /** removePlayer(viewer) would change nothing. */
        boolean fabrolia$absentAndIdle(T tracker, P viewer);

        /** The tracker's real (staged) removePlayer(viewer). */
        void fabrolia$removeViewer(T tracker, P viewer);
    }

    private record Change<T>(T tracker, boolean add) {}

    private final ConcurrentLinkedQueue<Change<T>> changes = new ConcurrentLinkedQueue<>();
    private final ReferenceLinkedOpenHashSet<T> fresh = new ReferenceLinkedOpenHashSet<>();
    /** Published, not yet applied changes (exact once a polling apply returns). */
    private final java.util.concurrent.atomic.AtomicInteger queued =
            new java.util.concurrent.atomic.AtomicInteger();

    public void queueAdd(T tracker) {
        changes.add(new Change<>(tracker, true));
        queued.incrementAndGet();
    }

    public void queueRemove(T tracker) {
        changes.add(new Change<>(tracker, false));
        queued.incrementAndGet();
    }

    /** Number of published changes not yet applied (any thread). */
    public int queued() {
        return queued.get();
    }

    /** Applies every published change in order. */
    public void apply(Index<T, P> index) {
        Change<T> change;
        while ((change = changes.poll()) != null) {
            queued.decrementAndGet();
            T tracker = change.tracker();
            if (!change.add()) {
                index.fabrolia$remove(tracker);
                fresh.remove(tracker);
                continue;
            }
            if (index.fabrolia$indexed(tracker)) continue;
            index.fabrolia$insert(tracker);
            if (index.fabrolia$indexed(tracker)) fresh.add(tracker);
        }
    }

    /** A removal applied outside the queue (locked path) also ends freshness. */
    public void forget(T tracker) {
        fresh.remove(tracker);
    }

    /** After the viewer passes: settled fresh trackers join their viewers' tracked sets. */
    public void graduate(Index<T, P> index) {
        if (fresh.isEmpty()) return;
        Iterator<T> iterator = fresh.iterator();
        while (iterator.hasNext()) {
            T tracker = iterator.next();
            if (index.fabrolia$gone(tracker)) {
                iterator.remove();
                continue;
            }
            if (index.fabrolia$viewersSettled(tracker, viewer -> {
                Set<T> tracked = index.fabrolia$trackedBy(viewer);
                if (tracked != null) tracked.add(tracker);
            })) {
                iterator.remove();
            }
        }
    }

    /**
     * Lifecycle removal of {@code viewer} (disconnect, respawn, dimension change)
     * for trackers the viewer's tracked set may not contain yet. Call after
     * {@link #apply} so queued adds are covered too.
     */
    public void removeViewer(P viewer, Index<T, P> index) {
        for (T tracker : fresh) {
            if (index.fabrolia$gone(tracker)
                    || index.fabrolia$absentAndIdle(tracker, viewer)) continue;
            index.fabrolia$removeViewer(tracker, viewer);
        }
    }
}
