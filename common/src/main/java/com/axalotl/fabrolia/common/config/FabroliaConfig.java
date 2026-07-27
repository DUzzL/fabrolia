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

    public static boolean disabled = false;
    public static int maxThreads = -1;
    public static int threadPriority = Thread.NORM_PRIORITY - 1;
    public static boolean enableFabroliaSpawn = true;
    /** task #21: O(chunks x categories) incremental spawn census replacing the
     *  per-tick full-entity scan. */
    public static boolean fabroliaIncrementalSpawnCensus = true;
    public static boolean enableFabroliaRandomTicks = true;
    public static boolean enableFabroliaVmpTracking = true;
    public static boolean enableFabroliaDespawn = true;
    public static boolean enableFabroliaChunkSend = true;
    public static boolean enableFabroliaAdvancements = true;
    public static boolean enableRegionThreading = true;
    /** R4 phase 0: count/log block writes landing outside the writing worker's
     *  claimed region — the certification data for the collector unlock.
     *  Observation only; off = one volatile read per setBlock. */
    public static boolean fabroliaDebugRegionAccess = false;
    /** R4 stage A: color-DAG scheduling — neighbors never tick concurrently
     *  (checkerboard classes as claim dependencies). This is a correctness
     *  invariant for region threading, not an optional speed toggle: without it
     *  cross-border block/entity effects can race adjacent claimed regions. */
    public static boolean fabroliaRegionColorGate = true;
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
    /** R4 stage B groundwork — interior/border classifier METRIC only (no behaviour change).
     *  When on, every ticked entity is classified interior (footprint provably within its
     *  region, cannot touch a neighbour region) vs border (within {@link #fabroliaOptimisticBorderMargin}
     *  blocks of a region edge → may interact cross-region), and the per-window border fraction
     *  is logged. This number is the make-or-break input for the "optimistic single-wave" design
     *  (drop the 4-wave ColorGate, run interiors fully parallel, coordinate only the border): a
     *  low border% means the flip pays off massively; a high one means the border pass would
     *  bottleneck. Pure measurement — does NOT change scheduling. Toggle: {@code /fabrolia regions
     *  bordermetric on|off}. */
    public static volatile boolean fabroliaOptimisticBorderMetric = false;
    /** Conservative cross-region interaction reach (blocks) used by the border classifier above.
     *  Covers an entity tick's WRITE reach to neighbour blocks/entities (collision push, melee,
     *  block place/break ≈ a few blocks); cross-region READS (long-range AI targeting) are
     *  1-tick-stale-tolerant and intentionally NOT counted as border. 16 = one chunk, generous. */
    public static volatile double fabroliaOptimisticBorderMargin = 16.0;
    // R4 stage B "optimistic flip" (ColorGate-off + post-barrier serial border pass) was
    // REMOVED 2026-06-30: its serial main border pass (~44% of scattered entities) was a net
    // 2× regression vs ColorGate's all-parallel 4-wave (user-confirmed). The border metric
    // below stays for diagnostics.
    /** F1 — island-partitioned scheduled block/fluid ticks: due chunks split
     *  by loaded-space islands (≥16-chunk unloaded gap between any two), each
     *  slice runs vanilla's exact collect/run; heaviest slice stays on main
     *  (Oxidizium redstone engine is main-confined). <2 islands → vanilla. */
    public static boolean fabroliaIslandScheduledTicks = true;
    /** F2 — island-partitioned block-entity ticks (Stage C). Each island's
     *  loaded chunks dispatch their {@code LevelChunk.tickersInLevel} entries
     *  to one worker; islands are spatially separated by ≥16 unloaded chunks
     *  so cross-island side effects (hopper pulls, piston pushes, comparator
     *  reads) are impossible by construction. Heaviest island stays on main.
     *  &lt;2 islands → vanilla serial path. */
    public static boolean fabroliaIslandBlockEntityTicks = true;
    // The LOCAL per-player mob cap can be driven by a registered LocalCapBridge provider
    // (Oxidizium's off-heap Rust arena). Ownership + toggle live in Oxidizium (/oxidizium
    // spawncap); Fabrolia only exposes the seam. No provider → Java LocalMobCapCalculator (correct
    // via the Long2ObjectConcurrentHashMap computeIfAbsent overload fix). Global cap stays Java.

    // Route ServerPlayer ticks through region workers when region threading is active.
    // Gated at use-site on enableRegionThreading && RegionScheduler.isActive(), so this
    // is inert unless regions are on. Default true: parallel player ticks are the single
    // largest serial cost regions remove (~14% of server thread at 50 players).
    public static boolean enableRegionPlayerTicks = true;
    /** EXPERIMENTAL (task #30): real players' doTick simulation runs on their
     *  island worker (fake-player shape) with packet routing + epoch handoffs.
     *  Runtime toggle: /fabrolia regions playerticks on|off. Default OFF. */
    public static volatile boolean regionalPlayerTicksExperimental = false;
    // Let the parallel-spawn future ride to the entity-phase barrier instead of being
    // awaited at the end of tickChunks — one park covers max(spawn, entities). Random/
    // fluid ticks are still awaited before the chunk phase ends (vanilla order). A mob
    // spawned mid-entity-phase takes its first tick next tick — same guarantee vanilla
    // gives entities added during entityTickList iteration.
    public static boolean enableSpawnBarrierOverlap = true;
    // Dense-region intra-region fan-out: a region whose entity count exceeds this
    // threshold is split into sub-batches executed by OTHER region workers via the
    // region's help-queue, under the region's own lock (currentRegion set by the help
    // protocol) with a region-local countdown fan-in — sync goes to the region, not main.
    // Region-serial would degenerate to one ~25ms chain for 2k villagers in one grid cell
    // (measured 2026-06-10); the old flat pool handled 10k. Types listed in
    // synchronizedEntities (TNT, items…) keep exact mutual order in a serial lane on the
    // coordinator. 0 or negative disables fan-out (always region-serial).
    // Tunable live: /fabrolia regions densefanout <n>. Lowered 256→128 so medium-density regions
    // also spread their entity work across idle workers (the fan-out deadlock is fixed).
    public static int regionDenseFanoutThreshold = 128;
    public static int regionWorkerCount = -1;
    // R5 event-driven entity scheduler: regions are the tasks; ColorGate.markDone pushes
    // each dependent region the moment its last lower-color blocker completes, and the
    // barrier is a counter — no group containers, no continuation polling (the legacy
    // group path burned ~a core in 20µs park slices at low load and added a poll-slice
    // of latency per DAG level; 2026-07-01 profile). Identical semantics: same color DAG,
    // same per-region [spawn][random][entities] FIFO, same ordered lane. false = legacy
    // group dispatch (rollback lever). Tunable live: /fabrolia regions eventsched.
    public static boolean regionEventScheduler = true;
    // R6 TNT lane: ordered lane with explosion-segmented parallelism — consecutive runs of
    // vanilla PrimedTnt that will NOT detonate this tick (deterministic fuse) are mutually
    // non-interacting (read level/blocks, write only themselves) and tick in parallel;
    // items/orbs/exploding TNT/modded TNT stay exact-order serial barriers, so every
    // explosion still sees earlier moves done and mutates later entities before they move —
    // bit-identical to the serial lane. 18k TNT in one region = 15s serial wall without it.
    // Tunable live: /fabrolia regions tntlane.
    public static boolean fabroliaTntSegmentedLane = true;
    // A2 async islands: independent per-island deadline ticking. DEFAULT OFF and
    // the /fabrolia regions async toggle REFUSES to enable until the island tick
    // unit's phase rewire (IslandTickUnit increments 1-4, see
    // docs/ASYNC-ISLANDS.md + the a2 checkpoint) is complete — enabling a
    // partial unit would violate the ownership model this exists to enforce.
    public static volatile boolean asyncIslands = false;
    /** OPERATOR-PREFERENCE PERSISTENCE (2026-07-18 v2, corrected semantics):
     *  /fabrolia regions async [on|off] sets {@link #asyncIslands} AND
     *  persists it immediately through the loader's config system (atomic
     *  write) — the last operator choice IS the boot default and survives
     *  clean restart, crash, and hard kill. The runtime SAFETY fallback
     *  (asyncFailClosedTripped) is session-only and never touches this
     *  preference; crash sentinels that gate later boots remain forbidden.
     *  Registered by each loader at init. */
    public static volatile java.util.function.Consumer<Boolean> asyncIslandsPersister = null;

    public static void persistAsyncIslands(boolean v) {
        asyncIslands = v;
        java.util.function.Consumer<Boolean> pers = asyncIslandsPersister;
        if (pers != null) {
            try {
                pers.accept(v);
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("fabrolia")
                        .error("failed to persist asyncIslands={} — runtime state applied, "
                                + "boot default unchanged", v, t);
            }
        } else {
            org.slf4j.LoggerFactory.getLogger("fabrolia")
                    .warn("no config persister registered — asyncIslands={} applies to this "
                            + "session only", v);
        }
    }
    // Fail-closed: a detected cross-ownership write auto-disables asyncIslands
    // (collapses to byte-identical sync mode) instead of risking world state.
    public static volatile boolean asyncFailClosed = true;
    // Set when fail-closed trips (marker file persists it across the
    // designed watchdog restart): the mode latch refuses async until an
    // operator clears config/fabrolia-failclosed.marker.
    public static volatile boolean asyncFailClosedTripped = false;

    public static Set<String> synchronizedEntities = getDefaultSynchronizedEntities();

    // Caches
    private static final Map<Identifier, Boolean> syncCache = new ConcurrentHashMap<>();
    private static final Set<String> exactEntities = new HashSet<>();
    private static final Set<String> namespaceWildcards = new HashSet<>();

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

    public static void enforceSafetyInvariants() {
        if (enableRegionThreading && !fabroliaRegionColorGate) {
            fabroliaRegionColorGate = true;
            LOGGER.warn("fabroliaRegionColorGate=false is unsupported with region threading; forcing it ON");
        }
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
        syncCache.clear();
        exactEntities.clear();
        namespaceWildcards.clear();

        for (String entry : synchronizedEntities) {
            if (isNamespaceWildcard(entry)) {
                String ns = entry.substring(0, entry.indexOf(':'));
                namespaceWildcards.add(ns);
            } else {
                exactEntities.add(entry);
            }
        }
        com.axalotl.fabrolia.common.ParallelProcessor.clearCaches();
    }

    public static boolean isEntitySynchronized(Identifier entityId) {
        Boolean cached = syncCache.get(entityId);
        if (cached != null) return cached;

        String idString = entityId.toString();
        if (exactEntities.contains(idString)) {
            syncCache.put(entityId, true);
            return true;
        }

        if (namespaceWildcards.contains(entityId.getNamespace())) {
            syncCache.put(entityId, true);
            return true;
        }

        syncCache.put(entityId, false);
        return false;
    }

    public static void onConfigLoaded() {
        enforceSafetyInvariants();
        rebuildCaches();
        LOGGER.info("Configuration loaded.");
    }

    public static void clearCaches() {
        syncCache.clear();
    }
}
