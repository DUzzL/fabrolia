package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * EXPERIMENTAL regional player simulation (task #30, 2026-07-20). Default OFF.
 *
 * <p>With {@code /fabrolia regions playerticks on} (async islands active), a real
 * player's SIMULATION tick ({@code ServerPlayer.doTick}: movement physics, aiStep,
 * travel, collision, environmental damage, effect progression, local game events —
 * the sculk-visible surface) runs on the island worker that already ticks the
 * player's entity, immediately after {@code ServerPlayer.tick()} — exactly the
 * shape carpet fake players have always used (their {@code tick()} override calls
 * {@code doTick()} directly, region-proven under production bot load). Main's
 * {@code tickPlayer} skips {@code doTick} for region-owned players; simulation
 * therefore runs at the ISLAND's cadence — a lagging main no longer delays a
 * healthy region's player, and a lagging region no longer gets a player advancing
 * faster than the mobs fighting them.
 *
 * <p>Simulation-relevant serverbound packets (move, interact, use, action) are
 * routed into the owner island's FIFO task lane with an ownership-epoch token:
 * packet application and physics run in ONE timeline (the A5 torn-player-state
 * class this feature had to solve to be allowed back on), and sculk sensors in
 * the island observe player-generated events in deterministic regional order.
 *
 * <p>Session/global processing is UNTOUCHED: connection lifecycle, keepalive,
 * chat/commands (fenced), player list, scoreboards, final advancement grants
 * (batch-end belts), saves (fenced), respawn and dimension-change orchestration,
 * chunk delivery. Exactly-once simulation per player logical tick is enforced by
 * a per-player gameTime stamp consulted by BOTH venues; eligibility flips
 * (spectator, mounting, toggle-off) fall back to main cleanly — the stamp kills
 * the one-tick double at the seam and disabling never corrupts a player.
 *
 * <p>V1 scope cuts (documented, main-owned fallback — correct, just not yet
 * regionalized): passengers (vehicle/passenger double-sim risk), spectators
 * (unstable owner), container CLICKS (menu mutation stays main; the open
 * interaction itself routes). These are the first expansion targets.
 */
public final class RegionalPlayerTicks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-PlayerTicks");

    private RegionalPlayerTicks() {}

    public static final class Sim {
        /** Simulation identity = the OWNING ISLAND's unit sequence, never the
         *  global gameTime: an island may run many units per global tick
         *  (regional sprint, custom rate above 20, main stalled) and each is a
         *  legitimate player simulation tick. Global gameTime remains what the
         *  WORLD clock says (vanilla semantics, main-owned); it may repeat
         *  across island units and must never suppress local simulation. */
        volatile long lastSimIslandId = -1;
        volatile long lastSimIslandGen = -1;
        volatile long lastSimUnitL = Long.MIN_VALUE;
        /** Seam guard only (eligibility flip within one GLOBAL tick): the last
         *  global gameTime at which the WORKER venue simulated this player
         *  (consulted by main). */
        volatile long lastSimGlobalGt = Long.MIN_VALUE;
        /** audit13 — the symmetric half of the seam guard: the last global
         *  gameTime at which MAIN ran the ineligible-fallback doTick
         *  (consulted by the worker). The class doc has always promised the
         *  stamp is "consulted by BOTH venues", but only main consulted one:
         *  a flip the OTHER way — main reads ineligible (say, still a
         *  passenger) and runs doTick, then a lane-applied dismount makes the
         *  island worker read eligible later the SAME global tick — simulated
         *  the player twice in one tick (double movement/hunger, duplicated
         *  sculk-visible game events). */
        volatile long lastMainSimGt = Long.MIN_VALUE;
        volatile long ownerIslandId = -1;
        final LongAdder simTicks = new LongAdder();
        final LongAdder mainSkips = new LongAdder();
        final LongAdder doubleGuards = new LongAdder();
        final LongAdder routedPackets = new LongAdder();
        final LongAdder replayedToMain = new LongAdder();
        final LongAdder routeFallbacks = new LongAdder();
        /** Per-player packet lane: single-consumer FIFO drained at the current
         *  owner venue. Order is total across venue switches (draining flag). */
        final java.util.concurrent.ConcurrentLinkedQueue<LaneItem> lane =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean draining =
                new java.util.concurrent.atomic.AtomicBoolean();
    }

    /** venue: REGION = simulation apply (worker preferred, main legal on
     *  fallback); MAIN = must run on main (v1: container mutations) but is
     *  SEQUENCED through the lane so it can never overtake an earlier routed
     *  simulation packet. */
    record LaneItem(String kind, boolean mainOnly, ServerPlayer p, Runnable apply) {}

    private static final ConcurrentHashMap<UUID, Sim> SIMS = new ConcurrentHashMap<>();

    /** Bound while a ROUTED packet application executes on an island worker —
     *  the only context in which vanilla's same-thread packet reschedule may
     *  accept a region worker (PacketUtilsWorkerMixin). Never set otherwise,
     *  keeping every other worker-context packet path (carpet action packs,
     *  mod-invoked handlers) on its vanilla main-reschedule behavior. */
    private static final ThreadLocal<Boolean> ROUTED_APPLY = new ThreadLocal<>();

    public static boolean inRoutedApply() {
        return ROUTED_APPLY.get() != null;
    }

    public static Sim sim(ServerPlayer p) {
        return SIMS.computeIfAbsent(p.getUUID(), u -> new Sim());
    }

    public static void forget(ServerPlayer p) {
        SIMS.remove(p.getUUID());
    }

    public static boolean enabled() {
        return FabroliaConfig.regionalPlayerTicksExperimental && RegionScheduler.anyAsyncActive();
    }

    /** Region-simulation eligibility. Fake players are excluded — they already
     *  simulate regionally through their own tick() override. */
    public static boolean regionEligible(ServerPlayer p) {
        if (!enabled()) return false;
        if (p.getClass().getName().contains("Fake")) return false;
        if (p.isRemoved() || !p.isAlive()) return false;
        if (p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) return false;
        if (p.isPassenger()) return false;
        // Vanilla /tick freeze EXEMPTS players (they keep ticking while the
        // world is frozen). Frozen islands run no units, so region-owned
        // players would freeze too — fall back to main to preserve the
        // vanilla exemption exactly.
        if (p.level().tickRateManager().isFrozen()) return false;
        return !ParallelProcessor.requiresMainThread(p);
    }

    /**
     * Worker venue (ServerPlayer.tick wrap tail, region lane): run doTick now?
     * Exactly-once per ISLAND UNIT via (islandId, gen, unitL) — one entity tick
     * per unit already guarantees this structurally; the stamp guards handoff
     * seams (merge/split reassigns id/gen and legitimately re-sims; a repeated
     * global gameTime NEVER suppresses). Also drains the packet lane first so
     * this unit's inputs precede this unit's physics, in arrival order.
     */
    public static boolean shouldWorkerDoTick(ServerPlayer p) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) return false;
        if (!regionEligible(p)) return false;
        Sim s = sim(p);
        // audit13 symmetric seam guard (main→worker flip direction): if MAIN
        // already ran the ineligible-fallback doTick THIS global tick, the
        // worker must not add a second simulation right after an eligibility
        // flip. Steady-state region ownership never sets lastMainSimGt, so
        // island cadence (multiple units per global tick under rate/sprint)
        // is untouched; at worst ONE unit sim is suppressed on the flip tick
        // itself — the safe direction (a vanilla-lag-shaped miss, not a
        // double).
        if (p.level() instanceof ServerLevel sl
                && s.lastMainSimGt == IslandClock.wallTime(sl)) {
            s.doubleGuards.increment();
            return false;
        }
        IslandRegistry.PIsland isl = islandFor(p);
        long islandId = isl != null ? isl.id : -1;
        long islandGen = isl != null ? isl.gen : -1;
        long unitL = isl != null ? isl.logicalTick + 1 : Long.MIN_VALUE; // unit runs AT L+1
        if (isl != null && s.lastSimIslandId == islandId
                && s.lastSimIslandGen == islandGen && s.lastSimUnitL == unitL) {
            s.doubleGuards.increment();
            return false;
        }
        s.lastSimIslandId = islandId;
        s.lastSimIslandGen = islandGen;
        s.lastSimUnitL = unitL;
        s.lastSimGlobalGt = IslandClock.wallTime((ServerLevel) p.level());
        s.simTicks.increment();
        PlayerOwnership.Owner o = PlayerOwnership.owner(p);
        o.domain = PlayerOwnership.Domain.ISLAND;
        s.ownerIslandId = o.islandId = islandId;
        drainLane(p, s);
        return true;
    }

    /** Validation probe (debug `playerticks fakes on`): runs the SAME identity
     *  stamping for carpet fakes (which already doTick regionally through
     *  their own override) purely to measure suppression behavior under rate /
     *  sprint / frozen-main scenarios. No behavior change — accounting only. */
    public static volatile boolean debugStampFakes = false;

    public static void stampFakeForValidation(ServerPlayer p) {
        if (!debugStampFakes) return;
        if (!(Thread.currentThread() instanceof RegionWorkerThread)) return;
        if (!p.getClass().getName().contains("Fake")) return;
        Sim s = sim(p);
        IslandRegistry.PIsland isl = islandFor(p);
        if (isl == null) return;
        long unitL = isl.logicalTick + 1;
        if (s.lastSimIslandId == isl.id && s.lastSimIslandGen == isl.gen && s.lastSimUnitL == unitL) {
            s.doubleGuards.increment();
            return;
        }
        s.lastSimIslandId = isl.id;
        s.lastSimIslandGen = isl.gen;
        s.lastSimUnitL = unitL;
        s.lastSimGlobalGt = IslandClock.wallTime((ServerLevel) p.level());
        s.simTicks.increment();
        s.ownerIslandId = isl.id;
    }

    /**
     * Main venue (tickPlayer doTick wrap): skip when the region owns simulation.
     * The stamp also guards the eligibility-flip seam: if the worker already
     * simulated this gameTime, main never double-runs it in the same tick.
     */
    public static boolean mainShouldSkipDoTick(ServerPlayer p) {
        Sim s = SIMS.get(p.getUUID());
        // Seam guard: if a worker already simulated during THIS global tick,
        // main never adds a second sim in the same tick right after an
        // eligibility flip. (Island cadence itself is invisible to main —
        // only the flip seam needs this.)
        if (s != null && p.level() instanceof ServerLevel sl
                && s.lastSimGlobalGt == IslandClock.wallTime(sl)) {
            s.doubleGuards.increment();
            return true;
        }
        if (!regionEligible(p)) {
            if (s != null && s.ownerIslandId != -1) {
                // fell back to main (mounted/spectator/toggle-off): reclaim
                s.ownerIslandId = -1;
                PlayerOwnership.Owner o = PlayerOwnership.owner(p);
                o.domain = PlayerOwnership.Domain.MAIN;
                o.islandId = -1;
                PlayerOwnership.bumpEpoch(p);
            }
            // audit13: main is about to run doTick — record it so a worker
            // whose eligibility read flips to TRUE later this SAME global
            // tick skips (the main→worker half of the seam; the worker→main
            // half is the lastSimGlobalGt check above). Only meaningful while
            // the feature is on — off, no worker path exists to guard.
            if (enabled() && p.level() instanceof ServerLevel sl2) {
                if (s == null) s = sim(p);
                s.lastMainSimGt = IslandClock.wallTime(sl2);
            }
            return false;
        }
        if (s == null) s = sim(p);
        s.mainSkips.increment();
        return true;
    }

    /**
     * Route a simulation packet's application into the player's owner island
     * lane (FIFO, executed under the island claim at its next unit — one
     * timeline with physics and sculk sensors). Epoch-stamped: a handoff
     * (death, dimension change, logout, fallback-to-main) invalidates queued
     * applications, which are dropped loudly at DEBUG.
     *
     * @return true if routed (caller cancels the main-thread handling)
     */
    public static boolean routePacket(ServerPlayer p, String kind, Runnable apply) {
        return routePacket(p, kind, false, apply);
    }

    /**
     * Enqueue a packet application into the player's FIFO lane and schedule a
     * drain at the CURRENT owner venue. Semantics:
     * <ul>
     *   <li>NOTHING is ever dropped for epoch/ownership reasons — non-idempotent
     *       actions (attacks, item use, block actions) survive region transfer:
     *       an undrained lane simply gets re-drained at the player's next owner
     *       (island task epoch-checks re-SCHEDULE, never discard; disconnect
     *       remains the one terminal drop, matching vanilla).</li>
     *   <li>Total order across venues: the single-consumer {@code draining}
     *       flag means items apply in arrival order even when the venue flips
     *       mid-stream, and MAIN-only items (containers) are sequenced through
     *       the same lane so they cannot overtake earlier simulation packets.</li>
     *   <li>Connection-state safety: every apply runs under the per-listener
     *       monitor (see ServerGamePacketRoutingMixin) so anti-cheat /
     *       teleport-ack bookkeeping never tears against main's listener tick.</li>
     * </ul>
     */
    public static boolean routePacket(ServerPlayer p, String kind, boolean mainOnly, Runnable apply) {
        if (Thread.currentThread() instanceof RegionWorkerThread) return false; // already regional
        if (!regionEligible(p)) return false;
        Sim s = sim(p);
        s.lane.add(new LaneItem(kind, mainOnly, p, apply));
        s.routedPackets.increment();
        scheduleDrain(p, s);
        return true;
    }

    /** The island whose domain owns the player's current chunk — valid from
     *  any phase (regionizer lookup; worker currentDomain is phase-scoped). */
    static IslandRegistry.PIsland islandFor(ServerPlayer p) {
        RegionizedServer rs = p.level() instanceof ServerLevel sl ? RegionScheduler.forLevel(sl) : null;
        if (rs == null) return null;
        Region region = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(p.chunkPosition().pack()));
        return region != null && region.domain != null ? region.domain.owner : null;
    }

    private static void scheduleDrain(ServerPlayer p, Sim s) {
        if (s.lane.isEmpty()) return;
        RegionizedServer rs = p.level() instanceof ServerLevel sl ? RegionScheduler.forLevel(sl) : null;
        Region region = rs == null ? null
                : rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(p.chunkPosition().pack()));
        IslandRegistry.PIsland owner = region != null && region.domain != null ? region.domain.owner : null;
        if (owner != null && !owner.retired && regionEligible(p)) {
            // coalesced per player: at most one pending drain task per island
            CommandShards.enqueueIslandTask(owner, "pktlane:" + p.getUUID(), () -> drainLane(p, s));
        } else {
            s.replayedToMain.increment();
            net.minecraft.server.MinecraftServer srv =
                    com.axalotl.fabrolia.common.ParallelProcessor.getServer();
            if (srv != null) srv.execute(() -> drainLane(p, s));
        }
    }

    /** Single-consumer lane drain at the calling venue. A MAIN-only item met
     *  on a worker stops the drain and reschedules the remainder on main
     *  (order preserved); REGION items reached on main just run there —
     *  main is always a legal (vanilla) venue for simulation applies. */
    static void drainLane(ServerPlayer p, Sim s) {
        if (!s.draining.compareAndSet(false, true)) return;
        boolean onWorker = Thread.currentThread() instanceof RegionWorkerThread;
        try {
            LaneItem it;
            while ((it = s.lane.peek()) != null) {
                if (p.isRemoved() && !p.isAlive()) {
                    // disconnect/death teardown drains handle the rest
                    break;
                }
                if (it.mainOnly() && onWorker) {
                    // reschedule remainder on main, order intact
                    s.replayedToMain.increment();
                    net.minecraft.server.MinecraftServer srv =
                            com.axalotl.fabrolia.common.ParallelProcessor.getServer();
                    if (srv != null) srv.execute(() -> drainLane(p, s));
                    return;
                }
                s.lane.poll();
                ROUTED_APPLY.set(Boolean.TRUE);
                try {
                    synchronized (p.connection) {
                        it.apply().run();
                    }
                } catch (Throwable t) {
                    LOGGER.error("[player-ticks] routed {} apply failed for {}",
                            it.kind(), p.getName().getString(), t);
                } finally {
                    ROUTED_APPLY.remove();
                }
            }
        } finally {
            s.draining.set(false);
        }
        if (!s.lane.isEmpty()) scheduleDrain(p, s); // missed-wakeup guard
    }

    public static String status(ServerPlayer one) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("regional player ticks: ").append(FabroliaConfig.regionalPlayerTicksExperimental
                ? "ON (experimental)" : "off").append(" asyncActive=").append(RegionScheduler.anyAsyncActive());
        for (var e : SIMS.entrySet()) {
            ServerPlayer p = one;
            if (p != null && !p.getUUID().equals(e.getKey())) continue;
            Sim s = e.getValue();
            PlayerOwnership.Owner o = p != null ? PlayerOwnership.owner(p) : null;
            sb.append("\n ").append(e.getKey()).append(p != null ? " (" + p.getName().getString() + ")" : "")
              .append(" owner=").append(s.ownerIslandId == -1 ? "MAIN" : "island#" + s.ownerIslandId)
              .append(o != null ? " epoch=" + o.epoch.get() : "")
              .append(" lastSimUnit=").append(s.lastSimUnitL == Long.MIN_VALUE ? "-"
                      : ("isl" + s.lastSimIslandId + "g" + s.lastSimIslandGen + "L" + s.lastSimUnitL))
              .append(" lastSimGt=").append(s.lastSimGlobalGt == Long.MIN_VALUE ? "-" : s.lastSimGlobalGt)
              .append(" simTicks=").append(s.simTicks.sum())
              .append(" mainSkips=").append(s.mainSkips.sum())
              .append(" doubleGuards=").append(s.doubleGuards.sum())
              .append(" routedPkts=").append(s.routedPackets.sum())
              .append(" laneDepth=").append(s.lane.size())
              .append(" replayedToMain=").append(s.replayedToMain.sum())
              .append(" routeFallbacks=").append(s.routeFallbacks.sum());
        }
        return sb.toString();
    }
}
