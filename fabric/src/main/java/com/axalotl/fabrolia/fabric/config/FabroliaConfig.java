package com.axalotl.fabrolia.fabric.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.axalotl.fabrolia.common.config.FabroliaConfig.*;

/**
 * Fabric/TOML persistence for {@link com.axalotl.fabrolia.common.config.FabroliaConfig}.
 *
 * <p>Every key persisted here is also runtime-toggleable via {@code /fabrolia ...}
 * commands — both layers MUST stay in sync. If you add a flag in the common
 * config and wire a command, add it here too so the runtime toggle survives
 * a restart.
 *
 * <p>The save/load shape is data-driven: {@link #ENTRIES} declares every key
 * once with its section, type, and human-readable description. {@code save},
 * {@code load}, and {@code restoreComments} all walk the same table so a new
 * flag means a single new {@code ENTRIES.add(...)} call.
 */
public class FabroliaConfig {

    private static final Supplier<CommentedFileConfig> configSupplier =
            () -> CommentedFileConfig.builder(
                            FabricLoader.getInstance().getConfigDir().resolve("fabrolia.toml"))
                    .preserveInsertionOrder()
                    .sync()
                    // Atomic replace: the async-preference persist happens at
                    // command time; a crash mid-write must never leave a
                    // truncated TOML (write temp + atomic move).
                    .writingMode(com.electronwill.nightconfig.core.io.WritingMode.REPLACE_ATOMIC)
                    .build();

    private static CommentedFileConfig CONFIG;

    // ────────────────────────────────────────────────────────────────────
    //  Section banner helper — generates the ASCII box that introduces a
    //  group of keys in the TOML file. The banner becomes the leading
    //  comment block of the first key in each section.
    // ────────────────────────────────────────────────────────────────────

    private static String banner(String title, String description) {
        String bar = "════════════════════════════════════════════════════════════════════";
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(bar);
        sb.append("\n  ").append(title.toUpperCase());
        if (description != null && !description.isEmpty()) {
            sb.append("\n  ").append(description);
        }
        sb.append('\n').append(bar).append("\n\n");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────
    //  Schema. Each Entry pairs a key with its getter/setter (reflection-free)
    //  and its description. Section markers are blank entries with a banner.
    // ────────────────────────────────────────────────────────────────────

    private interface Entry {
        String key();
        String comment();
        Object read();
        void write(Object value);
    }

    private static Entry bool(String key, String section, String desc,
                              java.util.function.BooleanSupplier get,
                              java.util.function.Consumer<Boolean> set) {
        String comment = section != null ? section + desc : desc;
        return new Entry() {
            public String key() { return key; }
            public String comment() { return comment; }
            public Object read() { return get.getAsBoolean(); }
            public void write(Object v) { if (v instanceof Boolean b) set.accept(b); }
        };
    }

    private static Entry intEntry(String key, String section, String desc,
                                  java.util.function.IntSupplier get,
                                  java.util.function.IntConsumer set) {
        String comment = section != null ? section + desc : desc;
        return new Entry() {
            public String key() { return key; }
            public String comment() { return comment; }
            public Object read() { return get.getAsInt(); }
            public void write(Object v) { if (v instanceof Number n) set.accept(n.intValue()); }
        };
    }

    private static Entry stringList(String key, String section, String desc,
                                    Supplier<List<String>> get,
                                    java.util.function.Consumer<List<String>> set) {
        String comment = section != null ? section + desc : desc;
        return new Entry() {
            public String key() { return key; }
            public String comment() { return comment; }
            public Object read() { return get.get(); }
            @SuppressWarnings("unchecked")
            public void write(Object v) { if (v instanceof List<?> l) set.accept((List<String>) l); }
        };
    }

    private static final List<Entry> ENTRIES = buildSchema();

    private static List<Entry> buildSchema() {
        List<Entry> e = new ArrayList<>();

        // ── Core ───────────────────────────────────────────────────────
        e.add(bool("disabled",
                banner("Core", "Global kill switch + thread-pool sizing."),
                "Master kill switch. true = the mod still loads but every fabrolia path falls back to vanilla serial.",
                () -> disabled, v -> disabled = v));
        e.add(intEntry("maxThreads", null,
                "Maximum worker threads in the fabrolia pool. -1 = auto (cores). Hard-clamped to core count when positive.",
                () -> maxThreads, v -> maxThreads = v));
        e.add(intEntry("threadPriority", null,
                "Worker OS priority (1..10). Default 4 sits below the server/render thread (5) so they stay responsive on a saturated CPU.",
                () -> threadPriority, v -> threadPriority = v));
        e.add(stringList("synchronizedEntities", null,
                """
                Entity types that must tick serially (order-sensitive: TNT chains, item merging, XP orbs).
                  'minecraft:zombie' — a single entity type
                  'minecraft:*'      — every entity in a namespace""",
                () -> new ArrayList<>(synchronizedEntities),
                v -> synchronizedEntities = new HashSet<>(v)));

        // ── Fabrolia subsystems ───────────────────────────────────────────
        e.add(bool("enableFabroliaSpawn",
                banner("Fabrolia subsystems", "Per-subsystem toggles. Each is also runtime-switchable via /fabrolia."),
                "Fabrolia mob spawning (parallel candidate generation + serial cap claim). Profile-validated 2026-06-08.",
                () -> enableFabroliaSpawn, v -> enableFabroliaSpawn = v));
        e.add(bool("fabroliaSpawnLocalCapReservation", null,
                "Atomic pack reservation at spawn chunk-pass approval (local per-player mob cap): parallel chunk passes immediately see approved packs' slots taken, so overshoot stays ≈ cap + one pack while packs still complete like vanilla. false = vanilla plain approval (packs complete, parallel overshoot window returns). Runtime: /fabrolia regions localcapreserve on|off.",
                () -> fabroliaSpawnLocalCapReservation, v -> fabroliaSpawnLocalCapReservation = v));
        e.add(bool("enableFabroliaRandomTicks", null,
                "Fabrolia random block ticks (parallel per-chunk). Default ON.",
                () -> enableFabroliaRandomTicks, v -> enableFabroliaRandomTicks = v));
        e.add(bool("enableFabroliaChunkSend", null,
                "Pre-build ClientboundLevelChunkWithLightPacket on the worker pool during PlayerChunkSender.sendNextChunks. Removes main-thread cost of chunk packet construction on join bursts.",
                () -> enableFabroliaChunkSend, v -> enableFabroliaChunkSend = v));
        e.add(bool("enableFabroliaDespawn", null,
                "Pre-pass despawn checks on workers (TickGuard entity reentry path). Halves the per-entity main-thread despawn cost on high-mob servers.",
                () -> enableFabroliaDespawn, v -> enableFabroliaDespawn = v));
        e.add(bool("enableFabroliaAdvancements", null,
                "Buffer SimpleCriterionTrigger.trigger calls during ServerPlayer.tick, evaluate listener predicates in parallel, apply matches serially on main. Aimed at heavy advancement packs (e.g. BlazeAndCave ~3000 advancements).",
                () -> enableFabroliaAdvancements, v -> enableFabroliaAdvancements = v));
        e.add(bool("enableFabroliaVmpTracking", null,
                "Offload VMP's NearbyEntityTracking work (tryTickTracker + tryUpdateTracker, grouped per-tracker to avoid seenBy races) to the fabrolia pool. No effect without VMP installed.",
                () -> enableFabroliaVmpTracking, v -> enableFabroliaVmpTracking = v));

        // ── Region threading ───────────────────────────────────────────
        e.add(bool("enableRegionThreading",
                banner("Region threading", "Folia-style region-owned entity ticks. Core architecture toggle."),
                "Master switch for region threading. ON = chunks partition into a fixed grid; entity ticks group by owning region and run on a dedicated worker pool (cross-region parallel). Runtime: /fabrolia regions on|off.",
                () -> enableRegionThreading, v -> enableRegionThreading = v));
        e.add(bool("enablePlayerTickActor", null,
                "Player-tick ownership mode. With async islands, supported real players and audited Carpet fakes tick on their autonomous island; otherwise the awaited exact-ordinal compatibility actor is used. The saved preference is restored after scheduler startup. Runtime: /fabrolia regions playerticks on|off.",
                () -> enablePlayerTickActor, v -> enablePlayerTickActor = v));
        e.add(intEntry("regionWorkerCount", null,
                "Region worker thread count. -1 = auto (cores-2). No effect unless enableRegionThreading=true.",
                () -> regionWorkerCount, v -> regionWorkerCount = v));
        e.add(bool("enableSpawnBarrierOverlap", null,
                "Let the parallel-spawn future ride to the entity-phase barrier instead of awaiting it at the end of tickChunks. Main parks once for max(spawn, entities). Random/fluid ticks are still awaited before chunk phase ends (vanilla order preserved).",
                () -> enableSpawnBarrierOverlap, v -> enableSpawnBarrierOverlap = v));
        e.add(bool("regionEventScheduler", null,
                "R5 event-driven entity dispatch: regions are the tasks; markDone pushes each dependent when its last color-DAG blocker completes. Both dispatchers preserve the same DAG, chunk-task FIFO and serial entity order. false = legacy group dispatch. Runtime: /fabrolia regions eventsched.",
                () -> regionEventScheduler, v -> regionEventScheduler = v));

        // ── Island parallelism ─────────────────────────────────────────
        e.add(bool("fabroliaRegionColorGate",
                banner("Island parallelism", "Cross-region safety + parallel scheduled/BE ticks per island."),
                "R4 stage A: color-DAG scheduling — neighbours never tick concurrently. Required whenever region threading is enabled; false is clamped back to true.",
                () -> fabroliaRegionColorGate, v -> fabroliaRegionColorGate = v));
        e.add(bool("fabroliaRegionEviction", null,
                "Reclaim idle regions (no full chunks, no entities, idle ~5s) at tick head — bounds the region map on long exploration so O(regions) scans and mspt stop climbing. DEFAULT OFF: deadlocked once before the phantom-blocker fix; opt in to exercise it. Runtime: /fabrolia regions eviction on|off.",
                () -> fabroliaRegionEviction, v -> fabroliaRegionEviction = v));
        e.add(bool("fabroliaRedstoneCrossRegionGuard", null,
                "Defer a region worker's out-of-region block writes / neighbour updates to the main thread (post-barrier) so a cascade crossing into a concurrently-ticking region cannot race it. In-region redstone is untouched. Runtime: /fabrolia regions crossredstone on|off.",
                () -> fabroliaRedstoneCrossRegionGuard, v -> fabroliaRedstoneCrossRegionGuard = v));
        e.add(bool("fabroliaIslandScheduledTicks", null,
                "F1: island-partitioned scheduled block/fluid ticks. Due chunks split by loaded-space islands (≥16-chunk unloaded gap between any two); each slice runs vanilla's exact collect/run; heaviest slice stays on main. <2 islands → vanilla. Runtime: /fabrolia regions islandticks on|off.",
                () -> fabroliaIslandScheduledTicks, v -> fabroliaIslandScheduledTicks = v));
        e.add(bool("fabroliaIslandBlockEntityTicks", null,
                "F2 (Stage C): island-partitioned block-entity ticks. Each island's loaded chunks dispatch their LevelChunk.tickersInLevel entries to one worker; islands are spatially separated by ≥16 unloaded chunks so cross-island side effects are impossible by construction. Heaviest island stays on main. <2 islands → vanilla. Runtime: /fabrolia regions islandbeticks on|off.",
                () -> fabroliaIslandBlockEntityTicks, v -> fabroliaIslandBlockEntityTicks = v));
        e.add(bool("fabroliaCrossDimCoupling", null,
                "Cross-dimension cadence coupling: Overworld islands whose down-projected footprint (vanilla portal scale + pairing reach) touches a Nether island's cells are LINKED, and linked members share one deadline anchor + one lower-TPS-wins rate — removes the phase drift that breaks portal farms straddling the boundary. Clocks stay per-island (phase-lock, never value-merge); the End can never be linked. Runtime: /fabrolia regions crossdim on|off.",
                () -> fabroliaCrossDimCoupling, v -> fabroliaCrossDimCoupling = v));
        e.add(intEntry("crossDimUnlinkGraceTicks", null,
                "Unlink hysteresis for cross-dimension links: the intersection must be absent for this many wall ticks before a link drops (reappearance resets the timer). Links themselves form immediately.",
                () -> crossDimUnlinkGraceTicks, v -> crossDimUnlinkGraceTicks = v));
        e.add(stringList("crossDimLinkedPairs", null,
                "Cross-dimension link table. Default and ONLY supported value: 'minecraft:overworld=minecraft:the_nether'. Every other entry — the End in particular — is refused at parse time.",
                () -> new ArrayList<>(crossDimLinkedPairs),
                v -> crossDimLinkedPairs = new ArrayList<>(v)));
        e.add(bool("asyncFailClosed", null,
                "Legacy debug-drill containment toggle. A real detected cross-island live write always fail-stops the current server generation; it is never recovered by continuing in sync mode.",
                () -> asyncFailClosed, v -> asyncFailClosed = v));
        e.add(bool("asyncIslands", null,
                "A2: independent per-island ticking on the island's own scheduling timeline (entity phase increment; further phases land per docs/ASYNC-ISLANDS.md). Requires regionEventScheduler + color gate. DEFAULT OFF while the phase rewire completes. Runtime: /fabrolia regions async.",
                () -> asyncIslands, v -> asyncIslands = v));

        return e;
    }

    private static final Set<String> VALID_KEYS = computeValidKeys();

    private static Set<String> computeValidKeys() {
        Set<String> keys = new HashSet<>();
        for (Entry entry : ENTRIES) keys.add(entry.key());
        return keys;
    }

    public static void init() {
        LOGGER.info("Initializing Fabrolia Config...");
        CONFIG = configSupplier.get();

        if (!CONFIG.getFile().exists()) {
            LOGGER.warn("Configuration not found. Creating defaults...");
            saveConfig();
            com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
        } else {
            CONFIG.load();
            loadConfigValues();
            saveConfig();
            com.axalotl.fabrolia.common.config.FabroliaConfig.onConfigLoaded();
        }
    }

    /** Loader hook: immediate operator-preference persistence (async toggle).
     *  saveConfig serializes the LIVE common fields, so the field is already
     *  set by the caller; this just flushes atomically. */
    public static void registerPersister() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.asyncIslandsPersister =
                v -> saveConfig();
    }

    public static void saveConfig() {
        com.axalotl.fabrolia.common.config.FabroliaConfig.enforceSafetyInvariants();
        // Strip out any stale keys before rewriting — entries removed from
        // the schema will not silently linger in the file.
        removeUnusedKeys();
        for (Entry entry : ENTRIES) {
            CONFIG.set(entry.key(), entry.read());
            CONFIG.setComment(entry.key(), entry.comment());
        }
        CONFIG.save();
        LOGGER.info("Configuration saved.");
    }

    private static void loadConfigValues() {
        removeUnusedKeys();
        for (Entry entry : ENTRIES) {
            Object value = CONFIG.get(entry.key());
            if (value != null) {
                entry.write(value);
            }
        }
        // Re-apply all comments (file may have been edited by the user — we
        // own the comment contents but preserve their value choices).
        for (Entry entry : ENTRIES) {
            if (CONFIG.contains(entry.key())) {
                CONFIG.setComment(entry.key(), entry.comment());
            }
        }
        com.axalotl.fabrolia.common.config.FabroliaConfig.enforceSafetyInvariants();
        CONFIG.save();
    }

    private static void removeUnusedKeys() {
        List<String> keysToRemove = new ArrayList<>();
        for (CommentedConfig.Entry entry : CONFIG.entrySet()) {
            if (!VALID_KEYS.contains(entry.getKey())) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            CONFIG.remove(key);
            LOGGER.warn("Removed unused config key: {}", key);
        }
    }
}
