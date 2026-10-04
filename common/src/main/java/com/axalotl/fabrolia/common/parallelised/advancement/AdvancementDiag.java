package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.LongAdder;

/**
 * Advancement-pipeline counters, surfaced by {@code /fabrolia regions debug advstats}.
 * Every global quiesce an advancement scope takes is attributed to exactly one
 * {@link Site}; with nothing routed, every site stays at zero.
 */
public final class AdvancementDiag {

    private AdvancementDiag() {}

    /** Where an advancement scope took (or would take) the global fence. */
    public enum Site {
        /** Tick-head window: routed work needed the fence. */
        HEAD_ROUTED,
        /** Tick-head window: the {@code #minecraft:tick} functions asked for a shared fence. */
        HEAD_TICK_FUNCTIONS,
        /** Level entity-batch end (never acquires; only borrows). */
        BATCH_END,
        /** Before {@code PlayerList.remove} saves a disconnecting player. */
        REMOVE,
        /** Server stop. */
        SHUTDOWN;

        final LongAdder takes = new LongAdder();
        final LongAdder nanos = new LongAdder();
    }

    /** Why a piece of advancement work left its fire-time owner. */
    public enum Route {
        /** Trigger fired off the player's owner (matcher at fire time, player condition at drain). */
        OFF_OWNER_TRIGGER,
        /** Owner trigger behind earlier routed work of the same player (condition at drain). */
        BACKLOG_TRIGGER,
        /** A fire-time player condition needed the player's ender pearls (condition at drain). */
        FOREIGN_READ_TRIGGER,
        /** A fire-time matcher needed a player's ender pearls (whole call at drain). */
        FOREIGN_READ_MATCHER,
        /** Award whose completion runs a reward the owner may not run (non-owner-local function or loot). */
        AWARD_GLOBAL_REWARD,
        /** Award of a fire-time match list queued behind an earlier routed award of it. */
        AWARD_BACKLOG,
        /** Reward function not proven owner-local ({@code FunctionPlans.ownerLocal}). */
        REWARD_FUNCTION,
        /** Whole reward grant (loot table, or behind earlier routed work). */
        REWARD_GRANT,
        /** Chat announcement from a thread that does not own the player list. */
        ANNOUNCEMENT;

        final LongAdder count = new LongAdder();
    }

    /** Trigger calls evaluated on the player's owner at fire time (islands live). */
    public static final LongAdder TRIGGERS_OWNER = new LongAdder();
    /** Trigger calls evaluated in a quiescent context (held fence, async off, V2 actor). */
    public static final LongAdder TRIGGERS_QUIESCENT = new LongAdder();
    /** Trigger-map entries walked at fire time (inspected). */
    public static final LongAdder ENTRIES_INSPECTED = new LongAdder();
    /** Listener matches (award candidates). */
    public static final LongAdder MATCHES = new LongAdder();
    /** Awards applied inline on the owner at fire time. */
    public static final LongAdder AWARDS_INLINE = new LongAdder();
    /** Reward functions run inline on the owner (proven owner-local). */
    public static final LongAdder REWARD_FUNCTIONS_OWNER = new LongAdder();
    /** Routed items applied by Main drains. */
    public static final LongAdder ROUTED_APPLIED = new LongAdder();
    /** Tick-head windows opened, and those that found routed work. */
    public static final LongAdder HEAD_WINDOWS = new LongAdder();
    public static final LongAdder HEAD_WINDOWS_ROUTED = new LongAdder();
    /** Reward FUNCTIONs executed inside an advancement batch scope (ServerFunctionManagerMixin). */
    public static final LongAdder REWARD_FUNCTIONS = new LongAdder();
    /** Every global quiesce taken by an advancement scope, all sites (incl. sticky reward fences). */
    public static final LongAdder QUIESCE_TAKES = new LongAdder();
    /** Nanos spent inside advancement drains (barrier accounting). */
    public static final LongAdder BARRIER_NANOS = new LongAdder();

    static void quiesced(Site site, long waitNanos) {
        site.takes.increment();
        site.nanos.add(waitNanos);
        QUIESCE_TAKES.increment();
    }

    static void routed(Route route) {
        route.count.increment();
    }

    public static String report() {
        StringBuilder out = new StringBuilder("advstats");
        MinecraftServer server = ParallelProcessor.getServer();
        if (server != null) out.append(" tick=").append(server.getTickCount());
        out.append(" triggersOwner=").append(TRIGGERS_OWNER.sum())
                .append(" triggersQuiescent=").append(TRIGGERS_QUIESCENT.sum())
                .append(" inspected=").append(ENTRIES_INSPECTED.sum())
                .append(" matches=").append(MATCHES.sum())
                .append(" awardsInline=").append(AWARDS_INLINE.sum())
                .append(" rewardFnsOwner=").append(REWARD_FUNCTIONS_OWNER.sum())
                .append(" rewardFnsFenced=").append(REWARD_FUNCTIONS.sum())
                .append(" routedApplied=").append(ROUTED_APPLIED.sum())
                .append(" headWindows=").append(HEAD_WINDOWS.sum())
                .append(" headWindowsRouted=").append(HEAD_WINDOWS_ROUTED.sum())
                .append(" quiesceTakes=").append(QUIESCE_TAKES.sum())
                .append(" barrierMs=").append(BARRIER_NANOS.sum() / 1_000_000L)
                .append(" routed[");
        for (Route route : Route.values()) {
            out.append(' ').append(route.name()).append('=').append(route.count.sum());
        }
        out.append(" ] quiesce[");
        for (Site site : Site.values()) {
            out.append(' ').append(site.name()).append('=').append(site.takes.sum())
                    .append('/').append(site.nanos.sum() / 1_000_000L).append("ms");
        }
        return out.append(" ]").toString();
    }
}
