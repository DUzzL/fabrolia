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

    // ── Island parallelism ──────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue fabroliaRegionColorGate;
    private static final ModConfigSpec.BooleanValue fabroliaIslandScheduledTicks;
    private static final ModConfigSpec.BooleanValue fabroliaIslandBlockEntityTicks;
    private static final ModConfigSpec.BooleanValue asyncIslands;
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
        enableFabroliaChunkSend = BUILDER.comment("Pre-build ClientboundLevelChunkWithLightPacket on the worker pool during PlayerChunkSender.sendNextChunks. Removes main-thread cost of chunk packet construction on join bursts.")
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

        BUILDER.pop();

        // ───── Island parallelism ─────
        BUILDER.comment("Cross-region safety + parallel scheduled/BE ticks per island.").push("islands");

        fabroliaRegionColorGate = BUILDER.comment("R4 stage A: color-DAG scheduling — neighbours never tick concurrently. Required whenever region threading is enabled; false is clamped back to true.")
                .define("fabroliaRegionColorGate", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate);
        fabroliaIslandScheduledTicks = BUILDER.comment("F1: island-partitioned scheduled block/fluid ticks. Due chunks split by loaded-space islands (≥16-chunk unloaded gap between any two); each slice runs vanilla's exact collect/run; heaviest slice stays on main. <2 islands → vanilla. Runtime: /fabrolia regions islandticks on|off.")
                .define("fabroliaIslandScheduledTicks", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandScheduledTicks);
        fabroliaIslandBlockEntityTicks = BUILDER.comment("F2 (Stage C): island-partitioned block-entity ticks. Each island's loaded chunks dispatch their LevelChunk.tickersInLevel entries to one worker; islands are spatially separated by ≥16 unloaded chunks so cross-island side effects are impossible by construction. Heaviest island stays on main. <2 islands → vanilla. Runtime: /fabrolia regions islandbeticks on|off.")
                .define("fabroliaIslandBlockEntityTicks", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks);
        asyncFailClosed = BUILDER.comment("Legacy debug-drill containment toggle. A real detected cross-island live write always fail-stops the current server generation; it is never recovered by continuing in sync mode.")
                .define("asyncFailClosed", com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosed);
        asyncIslands = BUILDER.comment("A2: independent per-island ticking on the island's own scheduling timeline (entity phase increment; further phases land per docs/ASYNC-ISLANDS.md). Requires regionEventScheduler + color gate. DEFAULT OFF while the phase rewire completes. Runtime: /fabrolia regions async.")
                .define("asyncIslands", com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslands);
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

        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate = fabroliaRegionColorGate.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandScheduledTicks = fabroliaIslandScheduledTicks.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks = fabroliaIslandBlockEntityTicks.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslands = asyncIslands.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncFailClosed = asyncFailClosed.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling = fabroliaCrossDimCoupling.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimUnlinkGraceTicks = crossDimUnlinkGraceTicks.get();
        List<? extends String> pairEntries = crossDimLinkedPairs.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimLinkedPairs = pairEntries.isEmpty()
                ? com.axalotl.fabrolia.common.config.FabroliaConfig.defaultCrossDimLinkedPairs()
                : new ArrayList<>(pairEntries);

        List<? extends String> entries = synchronizedEntities.get();
        Set<String> entities = new HashSet<>();
        if (!entries.isEmpty()) entities.addAll(entries);
        com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities = entities.isEmpty()
                ? getDefaultSynchronizedEntities()
                : entities;
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

        fabroliaRegionColorGate.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaRegionColorGate);
        fabroliaIslandScheduledTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandScheduledTicks);
        fabroliaIslandBlockEntityTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaIslandBlockEntityTicks);
        fabroliaCrossDimCoupling.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling);
        crossDimUnlinkGraceTicks.set(com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimUnlinkGraceTicks);
        crossDimLinkedPairs.set(new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.crossDimLinkedPairs));

        synchronizedEntities.set(new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities));

        SPEC.save();
        com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
    }
}
