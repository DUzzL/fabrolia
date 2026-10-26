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
 * not survive a restart on one of them. Keep the table names (core, subsystems,
 * regions, islands): renaming one resets its saved values.
 */
public class FabroliaConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ── Core ────────────────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue disabled;
    private static final ModConfigSpec.IntValue maxThreads;
    private static final ModConfigSpec.IntValue threadPriority;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> synchronizedEntities;

    // ── Subsystems ──────────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue fabroliaSpawnLocalCapReservation;
    private static final ModConfigSpec.BooleanValue enableFabroliaVmpTracking;

    // ── Regions ─────────────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue enableRegionThreading;
    private static final ModConfigSpec.BooleanValue enablePlayerTickActor;
    private static final ModConfigSpec.IntValue regionWorkerCount;

    // ── Islands ─────────────────────────────────────────────────────────
    private static final ModConfigSpec.BooleanValue islandEntityParallel;
    private static final ModConfigSpec.BooleanValue fabroliaCrossDimCoupling;

    static {
        // ───── Core ─────
        BUILDER.push("core");

        disabled = BUILDER.comment("true = Fabrolia stays loaded but everything runs as vanilla. Runtime: /fabrolia config toggle.")
                .define("disabled", com.axalotl.fabrolia.common.config.FabroliaConfig.disabled);
        maxThreads = BUILDER.comment("Threads of the helper pool. -1 = number of cores.")
                .defineInRange("maxThreads", com.axalotl.fabrolia.common.config.FabroliaConfig.maxThreads, -1, Integer.MAX_VALUE);
        threadPriority = BUILDER.comment("OS priority of the worker threads (1..10).")
                .defineInRange("threadPriority", com.axalotl.fabrolia.common.config.FabroliaConfig.threadPriority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
        synchronizedEntities = BUILDER.comment("Entity types ticked serially, 'minecraft:zombie' or 'minecraft:*'. Only used with enableRegionThreading=false.")
                .defineListAllowEmpty(
                        "synchronizedEntities",
                        () -> new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities),
                        () -> "",
                        obj -> obj instanceof String
                );

        BUILDER.pop();

        // ───── Subsystems ─────
        BUILDER.push("subsystems");

        fabroliaSpawnLocalCapReservation = BUILDER.comment("Parallel spawning reserves a pack's slots in the per-player mob cap. false = vanilla approval. Runtime: /fabrolia regions localcapreserve on|off.")
                .define("fabroliaSpawnLocalCapReservation", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation);
        enableFabroliaVmpTracking = BUILDER.comment("Run VMP's entity tracking on the Fabrolia pool. No effect without VMP. Runtime: /fabrolia config setFabroliaVmpTracking.")
                .define("enableFabroliaVmpTracking", com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking);

        BUILDER.pop();

        // ───── Regions ─────
        BUILDER.push("regions");

        enableRegionThreading = BUILDER.comment("Tick islands on worker threads. Runtime: /fabrolia regions on|off.")
                .define("enableRegionThreading", com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading);
        enablePlayerTickActor = BUILDER.comment("Players tick on their own island. Runtime: /fabrolia regions playerticks on|off.")
                .define("enablePlayerTickActor", com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor);
        regionWorkerCount = BUILDER.comment("Region worker threads. -1 = cores minus 2.")
                .defineInRange("regionWorkerCount", com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount, -1, Integer.MAX_VALUE);

        BUILDER.pop();

        // ───── Islands ─────
        BUILDER.push("islands");

        islandEntityParallel = BUILDER.comment("Tick the entities of one island in parallel per region. false = one serial pass in vanilla order. Runtime: /fabrolia regions islandentities vanilla|parallel.")
                .define("islandEntityParallel", com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel);
        fabroliaCrossDimCoupling = BUILDER.comment("Overworld and Nether islands that overlap share one clock anchor and the lower TPS. Runtime: /fabrolia regions crossdim on|off.")
                .define("fabroliaCrossDimCoupling", com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling);

        BUILDER.pop();

        SPEC = BUILDER.build();
        LOGGER.info("Configuration initialized.");
    }

    public static void loadConfig() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.disabled = disabled.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.maxThreads = maxThreads.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.threadPriority = threadPriority.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation = fabroliaSpawnLocalCapReservation.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking = enableFabroliaVmpTracking.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading = enableRegionThreading.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor = enablePlayerTickActor.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount = regionWorkerCount.get();

        com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel = islandEntityParallel.get();
        com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling = fabroliaCrossDimCoupling.get();

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

        fabroliaSpawnLocalCapReservation.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaSpawnLocalCapReservation);
        enableFabroliaVmpTracking.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableFabroliaVmpTracking);

        enableRegionThreading.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enableRegionThreading);
        enablePlayerTickActor.set(com.axalotl.fabrolia.common.config.FabroliaConfig.enablePlayerTickActor);
        regionWorkerCount.set(com.axalotl.fabrolia.common.config.FabroliaConfig.regionWorkerCount);

        islandEntityParallel.set(com.axalotl.fabrolia.common.config.FabroliaConfig.islandEntityParallel);
        fabroliaCrossDimCoupling.set(com.axalotl.fabrolia.common.config.FabroliaConfig.fabroliaCrossDimCoupling);

        synchronizedEntities.set(new ArrayList<>(com.axalotl.fabrolia.common.config.FabroliaConfig.synchronizedEntities));

        SPEC.save();
        com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
    }
}
