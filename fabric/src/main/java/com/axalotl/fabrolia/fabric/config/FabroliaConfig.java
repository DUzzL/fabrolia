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
    //  Section banner helper — a one-line title that introduces a
    //  group of keys in the TOML file. The banner becomes the leading
    //  comment block of the first key in each section.
    // ────────────────────────────────────────────────────────────────────

    private static String banner(String title) {
        return "\n── " + title + " ──\n";
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
            public void write(Object v) {
                if (!(v instanceof List<?> l)) return;
                // A hand-edited TOML may hold non-string items; they must not
                // reach the String-typed config sets.
                List<String> strings = new ArrayList<>(l.size());
                for (Object item : l) {
                    if (item instanceof String s) strings.add(s);
                    else LOGGER.warn("Ignoring non-string entry {} in config key {}", item, key);
                }
                set.accept(strings);
            }
        };
    }

    private static final List<Entry> ENTRIES = buildSchema();

    private static List<Entry> buildSchema() {
        List<Entry> e = new ArrayList<>();

        e.add(bool("disabled", banner("Core"),
                "true = Fabrolia stays loaded but everything runs as vanilla. Runtime: /fabrolia config toggle.",
                () -> disabled, v -> disabled = v));
        e.add(intEntry("maxThreads", null,
                "Threads of the helper pool. -1 = number of cores.",
                () -> maxThreads, v -> maxThreads = v));
        e.add(intEntry("threadPriority", null,
                "OS priority of the worker threads (1..10).",
                () -> threadPriority, v -> threadPriority = v));
        e.add(stringList("synchronizedEntities", null,
                "Entity types ticked serially, 'minecraft:zombie' or 'minecraft:*'. Only used with enableRegionThreading=false.",
                () -> new ArrayList<>(synchronizedEntities),
                v -> synchronizedEntities = new HashSet<>(v)));

        e.add(bool("enableRegionThreading", banner("Regions"),
                "Tick islands on worker threads. Runtime: /fabrolia regions on|off.",
                () -> enableRegionThreading, v -> enableRegionThreading = v));
        e.add(bool("enablePlayerTickActor", null,
                "Players tick on their own island. Runtime: /fabrolia regions playerticks on|off.",
                () -> enablePlayerTickActor, v -> enablePlayerTickActor = v));
        e.add(intEntry("regionWorkerCount", null,
                "Region worker threads. -1 = cores minus 2.",
                () -> regionWorkerCount, v -> regionWorkerCount = v));
        e.add(bool("islandEntityParallel", null,
                "Tick the entities of one island in parallel per region. false = one serial pass in vanilla order. Runtime: /fabrolia regions islandentities vanilla|parallel.",
                () -> islandEntityParallel, v -> islandEntityParallel = v));
        e.add(bool("fabroliaCrossDimCoupling", null,
                "Overworld and Nether islands that overlap share one clock anchor and the lower TPS. Runtime: /fabrolia regions crossdim on|off.",
                () -> fabroliaCrossDimCoupling, v -> fabroliaCrossDimCoupling = v));

        e.add(bool("fabroliaSpawnLocalCapReservation", banner("Compatibility"),
                "Parallel spawning reserves a pack's slots in the per-player mob cap. false = vanilla approval. Runtime: /fabrolia regions localcapreserve on|off.",
                () -> fabroliaSpawnLocalCapReservation, v -> fabroliaSpawnLocalCapReservation = v));
        e.add(bool("enableFabroliaVmpTracking", null,
                "Run VMP's entity tracking on the Fabrolia pool. No effect without VMP. Runtime: /fabrolia config setFabroliaVmpTracking.",
                () -> enableFabroliaVmpTracking, v -> enableFabroliaVmpTracking = v));

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

    public static void saveConfig() {
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

    /** Reads the user's values. {@link #init} writes the file once afterwards
     *  through {@link #saveConfig}, which also drops stale keys, restores our
     *  comments and applies the safety invariants. */
    private static void loadConfigValues() {
        // asyncIslands left the config file; saveConfig drops the stale key, so say so once.
        if (Boolean.FALSE.equals(CONFIG.get("asyncIslands"))) {
            LOGGER.warn("Config key asyncIslands=false is ignored; islands now always run async. "
                    + "Start the server with -D{}=true for the synchronous diagnostic mode.",
                    com.axalotl.fabrolia.common.config.FabroliaConfig.SYNC_MODE_PROPERTY);
        }
        for (Entry entry : ENTRIES) {
            Object value = CONFIG.get(entry.key());
            if (value != null) {
                entry.write(value);
            }
        }
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
