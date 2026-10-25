package com.axalotl.fabrolia.neoforge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.axalotl.fabrolia.common.config.FabroliaConfig.*;

/**
 * NeoForge ModConfigSpec persistence for {@link com.axalotl.fabrolia.common.config.FabroliaConfig}.
 *
 * <p>Every key here mirrors the Fabric counterpart. When adding a new
 * runtime-toggleable flag, register it in BOTH platforms or the toggle will
 * not survive a restart on one of them.
 */
public class FabroliaConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ── Core ────────────────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue disabled;
    private static final ModConfigSpec.IntValue maxThreads;
    private static final ModConfigSpec.IntValue threadPriority;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> synchronizedEntities;

    // ── Fabrolia subsystems ────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue enableFabroliaSpawn;
    private static final ModConfigSpec.BooleanValue fabroliaSpawnLocalCapReservation;
    private static final ModConfigSpec.BooleanValue enableFabroliaRandomTicks;
    private static final ModConfigSpec.BooleanValue enableFabroliaChunkSend;
    private static final ModConfigSpec.BooleanValue enableFabroliaDespawn;
    private static final ModConfigSpec.BooleanValue enableFabroliaVmpTracking;

    // ── Region threading ────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue enableRegionThreading;
    private static final ModConfigSpec.BooleanValue enablePlayerTickActor;
    private static final ModConfigSpec.IntValue regionWorkerCount;
    private static final ModConfigSpec.BooleanValue enableSpawnBarrierOverlap;
    private static final ModConfigSpec.BooleanValue regionEventScheduler;

    // ── Island parallelism ──────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue fabroliaRegionColorGate;
    private static final ModConfigSpec.BooleanValue fabroliaRegionEviction;
    private static final ModConfigSpec.BooleanValue fabroliaRedstoneCrossRegionGuard;
    private static final ModConfigSpec.BooleanValue fabroliaIslandBlockEntityTicks;
    private static final ModConfigSpec.BooleanValue asyncIslands;
    private static final ModConfigSpec.BooleanValue islandEntityParallel;
    private static final ModConfigSpec.BooleanValue asyncFailClosed;
    private static final ModConfigSpec.BooleanValue fabroliaCrossDimCoupling;
    private static final ModConfigSpec.IntValue crossDimUnlinkGraceTicks;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> crossDimLinkedPairs;

    static {
        // ───── Core ─────
        BUILDER.comment("Global kill switch + thread-pool sizing.").push("core");

        disabled = BUILDER.comment("Master kill switch. true = the mod still loads but every fabrolia path falls back to vanilla serial.")
                .define("disabled", com.axalotl.fabrolia.common.config.FabroliaConfig.disabled);
        maxThreads = BUILDER.comment("Maximum worker threads in the fabrolia pool. -1 = auto (cores). Hard-clamped to core count when positive.")
                .defineInRange("maxThreads", com.axalotl.fabrolia.common.config.FabroliaConfig.maxThreads, -1, Integer.MAX_VALUE);
        threadPriority = BUILDER.comment("Worker OS priority (1..10). Default 4 sits below the server/render thread (5) so they stay responsive on a saturated CPU.")
                .defineInRange("threadPriority", com.axalotl.fabrolia.common.config.FabroliaConfig.threadPriority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
        synchronizedEntities = BUILDER.comment("""
                        Entity types that must tick serially (order-sensitive: TNT chains, item merging, XP orbs).
                          'minecraft:zombie' — a single entity type
                          'minecraft:*'      — every entity in a namespace""")
                .defineListAllowEmpty(
                        "synchronizedEntities",
                        () -> new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities),
                        () -> "",
                        obj -> obj instanceof String
                );

        BUILDER.pop();

        // ───── Fabrolia subsystems ─────
        BUILDER.comment("Per-subsystem toggles. Each is also runtime-switchable via /fabrolia.").push("subsystems");

        enableFabroliaSpawn = BUILDER.comment("Fabrolia mob spawning (parallel candidate generation + serial cap claim). Profile-validated 2026-06-08.")
                .define("enableFabroliaSpawn", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaSpawn);
        fabroliaSpawnLocalCapReservation = BUILDER.comment("Atomic pack reservation at spawn chunk-pass approval (local per-player mob cap): parallel chunk passes immediately see approved packs' slots taken, so overshoot stays ~ cap + one pack while packs still complete like vanilla. false = vanilla plain approval (packs complete, parallel overshoot window returns). Runtime: /fabrolia regions localcapreserve on|off.")
                .define("fabroliaSpawnLocalCapReservation", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation);
        enableFabroliaRandomTicks = BUILDER.comment("Fabrolia random block ticks (parallel per-chunk). Default ON.")
                .define("enableFabroliaRandomTicks", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaRandomTicks);
        enableFabroliaChunkSend = BUILDER.comment("Legacy key with no effect: chunk delivery always uses Fabrolia's batched sender. Kept so existing configs and /fabrolia config toggles stay valid.")
                .define("enableFabroliaChunkSend", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaChunkSend);
        enableFabroliaDespawn = BUILDER.comment("Pre-pass despawn checks on workers (TickGuard entity reentry path). Halves the per-entity main-thread despawn cost on high-mob servers.")
                .define("enableFabroliaDespawn", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaDespawn);
        enableFabroliaVmpTracking = BUILDER.comment("Offload VMP's NearbyEntityTracking work (tryTickTracker + tryUpdateTracker, grouped per-tracker to avoid seenBy races) to the fabrolia pool. No effect without VMP installed.")
                .define("enableFabroliaVmpTracking", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking);

        BUILDER.pop();

        // ───── Region threading ─────
        BUILDER.comment("Folia-style region-owned entity ticks. Core architecture toggle.").push("regions");

        enableRegionThreading = BUILDER.comment("Master switch for region threading. ON = chunks partition into a fixed grid; entity ticks group by owning region and run on a dedicated worker pool (cross-region parallel). Runtime: /fabrolia regions on|off.")
                .define("enableRegionThreading", com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading);
        enablePlayerTickActor = BUILDER.comment("Player-tick ownership mode. With async islands, supported real players and audited Carpet fakes tick on their autonomous island; otherwise the awaited exact-ordinal compatibility actor is used. The saved preference is restored after scheduler startup. Runtime: /fabrolia regions playerticks on|off.")
                .define("enablePlayerTickActor", com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor);
        regionWorkerCount = BUILDER.comment("Region worker thread count. -1 = auto (cores-2). No effect unless enableRegionThreading=true.")
                .defineInRange("regionWorkerCount", com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount, -1, Integer.MAX_VALUE);
        enableSpawnBarrierOverlap = BUILDER.comment("Let the parallel-spawn future ride to the entity-phase barrier instead of awaiting it at the end of tickChunks. Main parks once for max(spawn, entities). Random/fluid ticks are still awaited before chunk phase ends (vanilla order preserved).")
                .define("enableSpawnBarrierOverlap", com.axalotl.fabrolia.common.config.FabroliaConfig.enableSpawnBarrierOverlap);
        regionEventScheduler = BUILDER.comment("R5 event-driven entity dispatch: regions are the tasks; markDone pushes each dependent when its last color-DAG blocker completes. Both dispatchers preserve the same DAG, chunk-task FIFO and serial entity order. false = legacy group dispatch. Runtime: /fabrolia regions debug eventsched true|false.")
                .define("regionEventScheduler", com.axalotl.fabrolia.common.config.FabroliaConfig.regionEventScheduler);

        BUILDER.pop();

        // ───── Island parallelism ─────
        BUILDER.comment("Cross-region safety, synchronous block-entity partitioning and async islands.").push("islands");

        fabroliaRegionColorGate = BUILDER.comment("R4 stage A: color-DAG scheduling — neighbours never tick concurrently. Required whenever region threading is enabled; false is clamped back to true.")
                .define("fabroliaRegionColorGate", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate);
        fabroliaRegionEviction = BUILDER.comment("Reclaim idle regions (no full chunks, no entities, idle ~5s) at tick head - bounds the region map on long exploration so O(regions) scans and mspt stop climbing. Default ON. Runtime: /fabrolia regions debug eviction true|false.")
                .define("fabroliaRegionEviction", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionEviction);
        fabroliaRedstoneCrossRegionGuard = BUILDER.comment("Defer a region worker's out-of-region block writes / neighbour updates to the main thread (post-barrier) so a cascade crossing into a concurrently-ticking region cannot race it. In-region redstone is untouched. Runtime: /fabrolia regions debug crossredstone true|false.")
                .define("fabroliaRedstoneCrossRegionGuard", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRedstoneCrossRegionGuard);
        fabroliaIslandBlockEntityTicks = BUILDER.comment("Synchronous region threading only (asyncIslands=false): block-entity ticks are split by loaded-space islands (≥16 unloaded chunks apart), one worker per island; the heaviest island stays on main, <2 islands → vanilla. Async islands always tick their own block entities. Runtime: /fabrolia regions debug setIslandBlockEntityTicks true|false.")
                .define("fabroliaIslandBlockEntityTicks", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks);
        asyncFailClosed = BUILDER.comment("Legacy debug-drill containment toggle. A real detected cross-island live write always fail-stops the current server generation; it is never recovered by continuing in sync mode.")
                .define("asyncFailClosed", com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosed);
        asyncIslands = BUILDER.comment("A2: independent per-island ticking on the island's own scheduling timeline (entity phase increment; further phases land per docs/ASYNC-ISLANDS.md). Requires regionEventScheduler + color gate. Default on. Runtime: /fabrolia regions async on|off (saved immediately).")
                .define("asyncIslands", com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslands);
        islandEntityParallel = BUILDER.comment("Async islands: false (default) ticks an island's entities in one serial pass in Vanilla insertion order. true ticks its regions in parallel under the color gate, as up to 0.2.38 (entity order across region borders follows the color waves, out-of-region block writes are replayed after the phase). Islands holding primed TNT or any minecart always use the serial pass to preserve explosion and cart-pushing order. Runtime: /fabrolia regions islandentities vanilla|parallel.")
                .define("islandEntityParallel", com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel);
        fabroliaCrossDimCoupling = BUILDER.comment("Cross-dimension cadence coupling: Overworld islands whose down-projected footprint (vanilla portal scale + pairing reach) touches a Nether island's cells are LINKED, and linked members share one deadline anchor + one lower-TPS-wins rate — removes the phase drift that breaks portal farms straddling the boundary. Clocks stay per-island (phase-lock, never value-merge); the End can never be linked. Runtime: /fabrolia regions crossdim on|off.")
                .define("fabroliaCrossDimCoupling", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling);
        crossDimUnlinkGraceTicks = BUILDER.comment("Unlink hysteresis for cross-dimension links: the intersection must be absent for this many wall ticks before a link drops (reappearance resets the timer). Links themselves form immediately.")
                .defineInRange("crossDimUnlinkGraceTicks", com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimUnlinkGraceTicks, 0, Integer.MAX_VALUE);
        crossDimLinkedPairs = BUILDER.comment("Cross-dimension link table. Default and ONLY supported value: 'minecraft:overworld=minecraft:the_nether'. Every other entry — the End in particular — is refused at parse time.")
                .defineListAllowEmpty(
                        "crossDimLinkedPairs",
                        () -> new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimLinkedPairs),
                        () -> "",
                        obj -> obj instanceof String
                );

        BUILDER.pop();

        SPEC = BUILDER.build();
        LOGGER.info("Configuration initialized.");
    }

    /** Immediate operator-preference persistence (async toggle): push the
     *  live value into the spec and save — the TOML lands before the command
     *  returns, so crash/kill cannot lose the choice. */
    public static void registerPersister() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslandsPersister = v -> {
            asyncIslands.set(v);
            SPEC.save();
        };
    }

    public static void loadConfig() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.disabled = disabled.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.maxThreads = maxThreads.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.threadPriority = threadPriority.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaSpawn = enableFabroliaSpawn.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation = fabroliaSpawnLocalCapReservation.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaRandomTicks = enableFabroliaRandomTicks.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaChunkSend = enableFabroliaChunkSend.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaDespawn = enableFabroliaDespawn.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking = enableFabroliaVmpTracking.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading = enableRegionThreading.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor = enablePlayerTickActor.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount = regionWorkerCount.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableSpawnBarrierOverlap = enableSpawnBarrierOverlap.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.regionEventScheduler = regionEventScheduler.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate = fabroliaRegionColorGate.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionEviction = fabroliaRegionEviction.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRedstoneCrossRegionGuard = fabroliaRedstoneCrossRegionGuard.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks = fabroliaIslandBlockEntityTicks.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslands = asyncIslands.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel = islandEntityParallel.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosed = asyncFailClosed.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling = fabroliaCrossDimCoupling.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimUnlinkGraceTicks = crossDimUnlinkGraceTicks.get();
        List<? extends String> pairEntries = crossDimLinkedPairs.get();
        // An empty list in the file is the operator's choice, as on Fabric (audit
        // D-17); the spec's defaults apply only when the key is absent.
        com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimLinkedPairs = new ArrayList<>(pairEntries);

        List<? extends String> entries = synchronizedEntities.get();
        Set<String> entities = new HashSet<>();
        if (!entries.isEmpty()) entities.addAll(entries);
        com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities = entities;
        com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
    }

    public static void saveConfig() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.enforceSafetyInvariants();
        disabled.set(com.axalotl.fabrolia.common.config.FabroliaConfig.disabled);
        maxThreads.set(com.axalotl.fabrolia.common.config.FabroliaConfig.maxThreads);
        threadPriority.set(com.axalotl.fabrolia.common.config.FabroliaConfig.threadPriority);

        enableFabroliaSpawn.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaSpawn);
        fabroliaSpawnLocalCapReservation.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation);
        enableFabroliaRandomTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaRandomTicks);
        enableFabroliaChunkSend.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaChunkSend);
        enableFabroliaDespawn.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaDespawn);
        enableFabroliaVmpTracking.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking);

        enableRegionThreading.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading);
        enablePlayerTickActor.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor);
        regionWorkerCount.set(com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount);
        enableSpawnBarrierOverlap.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableSpawnBarrierOverlap);
        regionEventScheduler.set(com.axalotl.fabrolia.common.config.FabroliaConfig.regionEventScheduler);

        fabroliaRegionColorGate.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate);
        fabroliaRegionEviction.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionEviction);
        fabroliaRedstoneCrossRegionGuard.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRedstoneCrossRegionGuard);
        fabroliaIslandBlockEntityTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks);
        islandEntityParallel.set(com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel);
        fabroliaCrossDimCoupling.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling);
        crossDimUnlinkGraceTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimUnlinkGraceTicks);
        crossDimLinkedPairs.set(new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimLinkedPairs));

        synchronizedEntities.set(new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities));

        SPEC.save();
        com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
    }
}
