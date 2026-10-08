package com.axalotl.fabrolia.common.regions;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection event loops that never run a queued task by themselves.
 *
 * <p>Carpet's fake players (and any other connection built on an {@link EmbeddedChannel})
 * have an {@code EmbeddedEventLoop}: {@code execute} only appends to a queue, and the
 * queue runs when the channel is next operated on (a write or flush from any thread). If
 * nothing is sent to the bot, that is vanilla's {@code resumeFlushing} on physical Main at
 * the end of {@code tickChildren}. Fabrolia's ordered transport publications
 * ({@code Connection.fabrolia$enqueueEventLoopTask}: full-chunk batches, chunk forgets)
 * are pool obligations ({@link RegionScheduler#executeTrackedExternal}). One made
 * between two ticks, e.g. by a console/RCON {@code /tp} of a bot or by a bot logout,
 * would hold the next pool fixed point (exact player turn, Main preparation turn, stop,
 * fail-stop cancellation) open while the only thread that can run it waits there.
 *
 * <p>The publisher notes the channel after its task became visible on the loop; every
 * physical-Main fixed point runs the noted queues, exactly as the next vanilla flush
 * would, in the same order. After a fatal the queued pool tasks only cancel their permits.
 */
public final class PassiveConnectionLoops {
    private static final Set<EmbeddedChannel> NOTED = ConcurrentHashMap.newKeySet();

    private PassiveConnectionLoops() {
    }

    /**
     * Called by the publishing thread after {@code channel}'s loop accepted a task. A
     * drain that removed the entry earlier can no longer miss that task: it is either
     * run by that drain or noted again here.
     */
    public static void noteQueued(Channel channel) {
        if (channel instanceof EmbeddedChannel embedded) NOTED.add(embedded);
    }

    /**
     * Physical Main fixed-point progress only: runs every noted loop's queue. Fixed points
     * call it only while no pool step is live or queued, so no worker publishes to these
     * loops at the same time (EmbeddedEventLoop's task queue is not thread-safe).
     */
    static void runQueued() {
        if (NOTED.isEmpty()) return;
        Iterator<EmbeddedChannel> noted = NOTED.iterator();
        while (noted.hasNext()) {
            EmbeddedChannel channel = noted.next();
            noted.remove();
            try {
                // A failing pool task published the generation fatal before ConnectionOutbox
                // (like any Netty loop) logs it and runs the connection's next operation.
                channel.runPendingTasks();
            } catch (Throwable failure) {
                // EmbeddedChannel rethrows Errors with pool tasks possibly still queued: the
                // fail-stop sweep must still find this channel.
                NOTED.add(channel);
                throw failure;
            }
        }
    }

    static boolean isEmpty() {
        return NOTED.isEmpty();
    }
}
