package com.axalotl.fabrolia.common.config;

import com.axalotl.fabrolia.common.platform.PlatformUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FabroliaConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger(FabroliaConfig.class);

    // volatile: read on region workers every tick, written on Main by commands.
    public static volatile boolean disabled = false;
    public static int maxThreads = -1;
    public static int threadPriority = Thread.NORM_PRIORITY - 1;
    public static boolean enableFabroliaSpawn = true;
    public static boolean enableFabroliaRandomTicks = true;
    public static boolean enableFabroliaVmpTracking = true;
    public static boolean enableFabroliaDespawn = true;
    public static volatile boolean enableRegionThreading = true;
    /**
     * Persisted operator preference for the exact-ordinal player-tick actor.
     *
     * <p>This is deliberately separate from the runtime safety gate in
     * {@code RegionalPlayerEntityTicksV2}: the preference survives scheduler stops and
     * server restarts, while the runtime gate may still fail closed when region
     * threading or another prerequisite is unavailable. With async islands enabled
     * the same preference selects the autonomous per-island player owner; otherwise it
     * selects the awaited exact-ordinal compatibility actor. On by default since
     * 3.1.11 (the production setting); a saved {@code false} is kept.
     */
    public static volatile boolean enablePlayerTickActor = true;
    /** R4 phase 0: count/log block writes landing outside the writing worker's
     *  claimed region — the certification data for the collector unlock.
     *  Observation only; off = one volatile read per setBlock. */
    public static boolean fabroliaDebugRegionAccess = false;
    /** Cross-region effect containment. The color gate proves cross-border effects
     *  safe up to ONE region wide (neighbours never tick concurrently); a single-tick
     *  cascade reaching a SAME-COLOR region two cells away (which DOES tick
     *  concurrently) — a long dust line or a contraption straddling boundaries
     *  triggered from the entity/random-tick phase — would otherwise race it. With
     *  this on, a region worker that would write a block state or dispatch a neighbour
     *  update OUTSIDE its claimed region defers that op to the main thread, run after
     *  the phase barrier when every region is quiescent. In-region redstone (the
     *  99.95% case) is untouched; only boundary-crossing effects defer, one sub-phase
     *  later in the same tick. Off = vanilla color-gate-only behaviour. */
    public static boolean fabroliaRedstoneCrossRegionGuard = true;
    /** Reclaim idle regions (no full chunks, no entities, idle for a grace period) at
     *  level-tick head. Without it the lazily-created region map grows unbounded as
     *  players explore — every O(regions) scan climbs and mspt rises monotonically.
     *  Two color-DAG hazards are handled: evicting a blocker re-arms its dependents
     *  (ColorGate.markDone before unmapping), and a region RE-CREATED after this epoch's
     *  dispatch sweep is stamped doneEpoch=epoch on creation (ThreadedRegionizer) so it is
     *  never a phantom blocker. The first cut without the second deadlocked the server
     *  (stale region-phase future never completing, all workers idle — live 2026-06-27);
     *  the phantom-blocker stamp is the fix. Toggle: config / {@code /fabrolia regions eviction
     *  on|off} — flip off to isolate a regression. */
    public static boolean fabroliaRegionEviction = true;
    // R4 stage B "optimistic flip" (ColorGate-off + post-barrier serial border pass) was
    // REMOVED 2026-06-30: its serial main border pass (~44% of scattered entities) was a net
    // 2× regression vs ColorGate's all-parallel 4-wave (user-confirmed). Its border
    // metric was removed 2026-10-28.
    // F1 (fabroliaIslandScheduledTicks, island-partitioned scheduled ticks in
    // synchronous mode) was removed 2026-10-11: its entry point returned early
    // unless async islands were active, where slotPartitionedAsync always takes
    // the lane first, so the partitioned body could never run.
    /** F2 — synchronous region threading only (async islands tick their own
     *  block entities): island-partitioned block-entity ticks. Each island's
     *  loaded chunks dispatch their {@code LevelChunk.tickersInLevel} entries
     *  to one worker; islands are spatially separated by ≥16 unloaded chunks
     *  so cross-island side effects (hopper pulls, piston pushes, comparator
     *  reads) are impossible by construction. Heaviest island stays on main.
     *  &lt;2 islands → vanilla serial path. */
    public static boolean fabroliaIslandBlockEntityTicks = true;
    // The LOCAL per-player mob cap can be driven by a registered LocalCapBridge provider
    // (the external Rustmi mod's off-heap arena). Ownership + toggle live in that mod;
    // Fabrolia only exposes the seam. No provider → Java LocalMobCapCalculator (correct
    // via the Long2ObjectConcurrentHashMap computeIfAbsent overload fix). Global cap stays Java.

    public static int regionWorkerCount = -1;
    // R5 event-driven entity scheduler: regions are the tasks; ColorGate.markDone pushes
    // each dependent region the moment its last lower-color blocker completes, and the
    // barrier is a counter — no group containers, no continuation polling (the legacy
    // group path burned ~a core in 20µs park slices at low load and added a poll-slice
    // of latency per DAG level; 2026-07-01 profile). Identical semantics: same color DAG,
    // same per-region [spawn][random][entities] FIFO and serial entity order. false = legacy
    // group dispatch (rollback lever). Tunable live: /fabrolia regions eventsched.
    public static boolean regionEventScheduler = true;
    /** System property that starts the server in synchronous region threading (no
     *  independent island clocks). A diagnostic control for "is this bug
     *  async-specific?", not a configuration key. */
    public static final String SYNC_MODE_PROPERTY = "fabrolia.syncMode";
    // A2 async islands: independent per-island deadline ticking. Always on unless
    // -Dfabrolia.syncMode=true; never read from the config file. With
    // -Dfabrolia.debugCommands=true, /fabrolia regions debug async on|off flips it
    // live (not persisted). Requires the event scheduler + color gate, see
    // docs/ASYNC-ISLANDS.md.
    public static volatile boolean asyncIslands = !Boolean.getBoolean(SYNC_MODE_PROPERTY);
    /** Entity phase of an async island unit. false (default): one serial pass
     *  over the island in Vanilla EntityTickList insertion order (3.0.0 TNT
     *  fix). true: the member regions tick their entities in parallel under the
     *  ColorGate, as up to 0.2.38; entity order across region borders then
     *  follows the colour waves instead of insertion order, and block writes
     *  outside the ticking region are replayed after the phase. A unit whose
     *  members hold primed TNT or any minecart keeps the serial pass, so TNT
     *  mechanics and cart pushes stay exact in both modes. Read once per unit; runtime:
     *  /fabrolia regions islandentities vanilla|parallel. */
    public static volatile boolean islandEntityParallel = false;
    /** Task #43 — cross-dimension deadline-anchor cadence coupling: linked
     *  Overworld↔Nether islands (populated-cell projections intersect, per
     *  CrossDimProjection) share one deadline anchor and one lower-TPS-wins
     *  rate, removing the phase drift that breaks portal farms straddling the
     *  boundary. Observation + scheduling only — no clock writes, no new
     *  claim topology, transfer lane untouched. Runtime:
     *  /fabrolia regions crossdim on|off. */
    public static volatile boolean fabroliaCrossDimCoupling = true;

    // (task #40 history) fabroliaSpawnProximitySnapshot / fabroliaSpawnProximityInlineWave
    // were removed 2026-08-02 together with the completed-wave proximity snapshot:
    // the wave drop (the load-bearing fix) is now unconditional in
    // DistanceManagerMixin, and the three spawn-proximity readers read the live
    // concurrent tracker map — no copy, no staleness, nothing left to toggle.

    /**
     * Atomic pack RESERVATION at chunk-pass approval for the LOCAL per-player
     * mob cap (task #32 endgame — replaces the per-candidate recheck, which
     * bounded the parallel overshoot but starved farms sitting AT the cap).
     * Approval reserves the pack's worth of slots in the same per-player
     * counts it consulted, so concurrent chunk passes immediately see them
     * taken; inside the pack spawning is exactly vanilla (no candidate gate),
     * and the unconsumed reservation is returned at pack end. Overshoot bound:
     * ≈ cap + one pack per player — vanilla's own sequential class.
     *
     * <p>Kill switch, because this sits directly under the mob cap: OFF
     * restores vanilla's plain once-per-pass approval (pack completion intact,
     * parallel overshoot window back — measured 2-7x the local cap, the cod
     * runaway) and lets a reservation-blind native local-cap arena attach
     * again. /fabrolia regions localcapreserve on|off ; measure with
     * /fabrolia regions debug spawnrate (checks vs accepts per category).
     */
    public static volatile boolean fabroliaSpawnLocalCapReservation = true;

    // Session-only forensic latch. A real violation sets it before fail-stop;
    // debugTrip uses it to exercise the old mode-collapse drill.
    public static volatile boolean asyncFailClosedTripped = false;

    public static Set<String> synchronizedEntities = getDefaultSynchronizedEntities();

    // A publication is a complete policy generation. In-flight readers may
    // finish against the old policy, but cannot repopulate the new one's cache.
    private static volatile SynchronizationPolicy synchronizationPolicy =
            new SynchronizationPolicy(Set.of(), Set.of());

    private static final class SynchronizationPolicy {
        final Set<String> exactEntities;
        final Set<String> namespaceWildcards;
        final Map<Identifier, Boolean> cache = new ConcurrentHashMap<>();

        SynchronizationPolicy(Set<String> exactEntities, Set<String> namespaceWildcards) {
            this.exactEntities = Set.copyOf(exactEntities);
            this.namespaceWildcards = Set.copyOf(namespaceWildcards);
        }

        boolean matches(Identifier id) {
            Boolean cached = cache.get(id);
            if (cached != null) return cached;
            boolean result = exactEntities.contains(id.toString())
                    || namespaceWildcards.contains(id.getNamespace());
            cache.put(id, result);
            return result;
        }
    }

    public static Set<String> getDefaultSynchronizedEntities() {
        return new HashSet<>(Set.of(
                "minecraft:tnt",
                "minecraft:item",
                "minecraft:experience_orb"
        ));
    }

    public static int getParallelism() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (maxThreads <= 0) return cores;
        return Math.min(cores, maxThreads);
    }

    public static int getThreadPriority() {
        return Math.clamp(threadPriority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
    }

    public static int getRegionWorkerCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        // Explicit override is NOT clamped to core count (cf. fabrolia-mod-defaults:
        // workers idle-park when unneeded; modest oversubscription keeps the pool
        // chewing while some workers block on chunk waits inside the window).
        if (regionWorkerCount > 0) return Math.min(64, regionWorkerCount);
        // Default: cores-2 (headroom for server thread + network). Measured 2026-06-09
        // (50 bots, ~10.8k entities, 32 cores): 8 workers multiplexing ~50-100 hot
        // regions made the entity barrier ceil(regions/workers)*avg ≈ sum/8 — Server
        // thread parked 9.73% at the barrier vs 1.09% on the old FJ path. At 30
        // workers the barrier collapsed to 0.70% and batch wall beat the FJ path
        // (10.3ms vs 11.6ms). Workers idle-park when unneeded (~90% park observed),
        // so over-provisioning costs nothing; capping below hot-region count is what
        // hurts. Do NOT reintroduce a hard cap (cf. fabrolia-mod-defaults feedback).
        return Math.max(1, cores - 2);
    }

    public static boolean useRegionColorGate() {
        return enableRegionThreading;
    }

    public static boolean isNamespaceWildcard(String input) {
        if (input == null) return false;
        int colon = input.indexOf(':');
        if (colon <= 0) return false;
        return input.substring(colon + 1).equals("*");
    }

    public static boolean existsNamespace(String namespace) {
        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) return true;
        }
        return false;
    }

    public static boolean matchesExistingNamespaceWildcard(String input) {
        if (!isNamespaceWildcard(input)) return false;
        String ns = input.substring(0, input.indexOf(':'));
        return existsNamespace(ns);
    }

    public static void syncEntity(String entity) {
        if (synchronizedEntities.add(entity)) {
            rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Added sync entity: {}", entity);
        } else {
            LOGGER.warn("Entity already synchronized: {}", entity);
        }
    }

    public static void removeEntity(String entity) {
        if (synchronizedEntities.remove(entity)) {
            rebuildCaches();
            PlatformUtils.saveConfig();
            LOGGER.info("Removed sync entity: {}", entity);
        } else {
            LOGGER.warn("Entity not found: {}", entity);
        }
    }

    private static void rebuildCaches() {
        Set<String> exactEntities = new HashSet<>();
        Set<String> namespaceWildcards = new HashSet<>();

        for (String entry : synchronizedEntities) {
            if (isNamespaceWildcard(entry)) {
                String ns = entry.substring(0, entry.indexOf(':'));
                namespaceWildcards.add(ns);
            } else {
                exactEntities.add(entry);
            }
        }
        synchronizationPolicy = new SynchronizationPolicy(exactEntities, namespaceWildcards);
    }

    public static boolean isEntitySynchronized(Identifier entityId) {
        return synchronizationPolicy.matches(entityId);
    }

    public static void onConfigLoaded() {
        rebuildCaches();
        LOGGER.info("Configuration loaded.");
    }

    public static void clearCaches() {
        synchronizationPolicy.cache.clear();
    }
}
