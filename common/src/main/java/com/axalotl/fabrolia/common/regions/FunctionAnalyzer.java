package com.axalotl.fabrolia.common.regions;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CacheableFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A8 Phase 3 source analysis and its reload transaction.
 *
 * <p>Analysis produced by a candidate {@code ServerFunctionLibrary} is never
 * published by parse threads. It is accumulated in a reload-local
 * {@link ReloadStage}, sealed to an immutable {@link Prepared} snapshot only
 * after vanilla successfully applies that library, and activated together
 * with {@link ServerFunctionManager}'s exact library replacement. A failed or
 * abandoned reload therefore cannot leak metadata into the live generation.
 *
 * <p>Each staged function carries its {@link FunctionIR} (the exact vanilla
 * parse). At activation the {@link FunctionPlanner} resolves the call-graph
 * closure against that generation's tags, predicates and advancement rewards;
 * the resulting {@link FunctionPlan} is published with the library under the
 * same write lock. The IR is planner input only: building the plan consumes
 * it, so the live generation retains analyses and the plan, not the parse.
 */
public final class FunctionAnalyzer {

    private FunctionAnalyzer() {}

    public enum Kind { PURE_LOCAL_REL, PURE_LOCAL_ABS, ROUTED }

    /** Result of resolving an id against the exact live function object. */
    public enum Exactness {
        EXACT,
        ABSENT,
        STALE
    }

    /** minX..maxZ are meaningful only for PURE_LOCAL_ABS. */
    public record Analysis(Kind kind, int maxRelRadius,
                           int minX, int minZ, int maxX, int maxZ) {}

    /** Callback runs while the fair live-generation read lock is held. */
    @FunctionalInterface
    public interface ExactAnalysisAction<T> {
        T run(Exactness exactness, Analysis analysis);
    }

    private record Staged(CommandFunction<?> function,
                          Analysis analysis,
                          FunctionIR.Function ir) {}

    private record ActiveEntry(CommandFunction<CommandSourceStack> function,
                               Analysis analysis) {}

    /** One exact candidate-library transaction; parse tasks write concurrently. */
    public static final class ReloadStage {
        private final ConcurrentHashMap<Identifier, Staged> analyzed =
                new ConcurrentHashMap<>();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        private ReloadStage() {}
    }

    /**
     * Immutable, allocation-complete snapshot attached to one candidate library,
     * plus the planner input ({@link FunctionIR}) that exactly one
     * {@link #plan(Prepared, ServerFunctionLibrary, MinecraftServer)} consumes.
     */
    public static final class Prepared {
        private final Map<Identifier, ActiveEntry> entries;
        private Map<Identifier, FunctionIR.Function> irs;

        private Prepared(Map<Identifier, ActiveEntry> entries, Map<Identifier, FunctionIR.Function> irs) {
            this.entries = entries;
            this.irs = irs;
        }

        /** Hand the IR to the one plan of this generation; a second plan is a wiring bug. */
        private synchronized Map<Identifier, FunctionIR.Function> takeIrs() {
            Map<Identifier, FunctionIR.Function> taken = irs;
            if (taken == null) {
                throw new IllegalStateException("function analysis already planned; its IR was released");
            }
            if (!taken.isEmpty()) irs = null;
            return taken;
        }
    }

    private static final Prepared EMPTY = new Prepared(Map.of(), Map.of());

    /** Queued reload writers must not be starved by later local-function readers. */
    private static final ReentrantReadWriteLock LIVE_GENERATION =
            new ReentrantReadWriteLock(true);

    private static final ScopedValue<ReloadStage> CURRENT_RELOAD =
            ScopedValue.newInstance();

    private static volatile Prepared ACTIVE = EMPTY;
    private static volatile FunctionPlan PLAN = FunctionPlan.EMPTY;

    public static ReloadStage beginReload() {
        return new ReloadStage();
    }

    public static Prepared emptyPrepared() {
        return EMPTY;
    }

    /** Structurally binds compiler registration to the exact candidate library. */
    public static <T, X extends Throwable> T withReloadStage(
            ReloadStage stage, ScopedValue.CallableOp<? extends T, X> body) throws X {
        return ScopedValue.where(CURRENT_RELOAD, Objects.requireNonNull(stage, "stage"))
                .call(body);
    }

    /**
     * Bind every task accepted by a reload's preparation executor to that
     * reload's exact stage. Concurrent reloads therefore cannot exchange
     * compiler metadata through a mutable listener field.
     */
    public static Executor scopedReloadExecutor(ReloadStage stage, Executor executor) {
        ReloadStage exactStage = Objects.requireNonNull(stage, "stage");
        Executor delegate = Objects.requireNonNull(executor, "executor");
        return task -> delegate.execute(() ->
                ScopedValue.where(CURRENT_RELOAD, exactStage).run(task));
    }

    /** The exact reload stage inherited by the current preparation task. */
    public static ReloadStage currentReloadStage() {
        if (!CURRENT_RELOAD.isBound()) {
            throw new IllegalStateException(
                    "function parse escaped its reload preparation scope");
        }
        return CURRENT_RELOAD.get();
    }

    /** Called only after vanilla {@code CommandFunction.fromLines} returned. */
    public static void analyzeInto(ReloadStage stage, Identifier id,
                                   CommandFunction<?> function, List<String> lines) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(function, "function");
        Analysis analysis = analyze(lines);
        FunctionIR.Function ir = FunctionIR.of(id, function, lines);
        stage.analyzed.put(id, new Staged(function, analysis, ir));
    }

    /**
     * Preserve the exact analyzer failure across vanilla's per-function load
     * handler. The parse invocation rethrows the same object; seal() later
     * rethrows the recorded winner at listener scope, so it cannot become a
     * silently omitted function.
     */
    public static void recordFailure(ReloadStage stage, Throwable failure) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(failure, "failure");
        stage.firstFailure.compareAndSet(null, failure);
    }

    /**
     * Seal only functions present by exact identity in vanilla's successfully
     * applied candidate map. Parse-failure and superseded-return entries are
     * conservatively discarded.
     */
    public static Prepared seal(
            ReloadStage stage,
            Map<Identifier, CommandFunction<CommandSourceStack>> functions) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(functions, "functions");
        Throwable failure = stage.firstFailure.get();
        if (failure != null) {
            return FailStops.<Prepared, RuntimeException>rethrowValue(failure);
        }

        HashMap<Identifier, ActiveEntry> sealed = new HashMap<>(functions.size());
        HashMap<Identifier, FunctionIR.Function> irs = new HashMap<>(functions.size());
        functions.forEach((id, function) -> {
            Staged staged = stage.analyzed.get(id);
            if (staged == null || staged.function() != function) return;
            sealed.put(id, new ActiveEntry(function, staged.analysis()));
            irs.put(id, staged.ir());
        });
        return sealed.isEmpty() ? EMPTY : new Prepared(Map.copyOf(sealed), Map.copyOf(irs));
    }

    /**
     * Resolve the per-tick plan of {@code prepared} against the generation it
     * will be activated with: {@code library}'s tags and {@code server}'s
     * reloadable registries/advancements (already swapped to the same reload
     * when vanilla calls {@code replaceLibrary}). Runs before the write lock.
     */
    public static FunctionPlan plan(Prepared prepared, ServerFunctionLibrary library,
                                    MinecraftServer server) {
        return FunctionPlanner.build(new LibrarySources(prepared, prepared.takeIrs(), library, server));
    }

    /** Initial-boot activation; no function body may observe a partial map. */
    public static void activateInitial(Prepared prepared, FunctionPlan plan) {
        Prepared next = Objects.requireNonNull(prepared, "prepared");
        FunctionPlan nextPlan = Objects.requireNonNull(plan, "plan");
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            ACTIVE = next;
            PLAN = nextPlan;
        } finally {
            write.unlock();
        }
    }

    /**
     * Live reload commit. All fallible allocation and analysis completed
     * before this call. Under the write lock vanilla replaces its library
     * exactly once, then activation is two volatile reference assignments.
     */
    public static void replaceAndActivate(Prepared prepared, FunctionPlan plan,
                                          Runnable vanillaReplace) {
        Prepared next = Objects.requireNonNull(prepared, "prepared");
        FunctionPlan nextPlan = Objects.requireNonNull(plan, "plan");
        Runnable replace = Objects.requireNonNull(vanillaReplace, "vanillaReplace");
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            replace.run();
            ACTIVE = next;
            PLAN = nextPlan;
        } finally {
            write.unlock();
        }
    }

    /** The active plan (lock-free; identity changes exactly at activation). */
    public static FunctionPlan plan() {
        return PLAN;
    }

    /** Callback runs while the fair live-generation read lock is held. */
    @FunctionalInterface
    public interface ExactProofAction<T> {
        T run(boolean proven);
    }

    /**
     * C3: {@code function} is the manager's current object for its id and the
     * active plan proves its whole closure owner-local. The callback (which
     * runs the body when proven) holds the read lease, so a reload cannot swap
     * the library between proof and execution.
     */
    public static <T> T withExactOwnerLocalProof(ServerFunctionManager manager,
                                                 CommandFunction<CommandSourceStack> function,
                                                 ExactProofAction<T> action) {
        Lock read = LIVE_GENERATION.readLock();
        read.lock();
        try {
            boolean proven = PLAN.ownerLocal(function)
                    && manager.get(function.id()).orElse(null) == function;
            return action.run(proven);
        } finally {
            read.unlock();
        }
    }

    /**
     * Resolve and consume an analysis under one read lease. A caller that uses
     * the proof to enter a local body must do that inside the callback.
     */
    public static <T> T withExactCurrentAnalysis(
            ServerFunctionManager manager, Identifier id,
            ExactAnalysisAction<T> action) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Lock read = LIVE_GENERATION.readLock();
        read.lock();
        try {
            ActiveEntry entry = ACTIVE.entries.get(id);
            CommandFunction<CommandSourceStack> current = manager.get(id).orElse(null);
            if (entry == null) {
                return action.run(Exactness.ABSENT, null);
            }
            if (entry.function() != current) {
                return action.run(Exactness.STALE, null);
            }
            return action.run(Exactness.EXACT, entry.analysis());
        } finally {
            read.unlock();
        }
    }

    /** Compatibility read; routing code must use withExactCurrentAnalysis(). */
    public static Analysis analysisOf(Identifier id) {
        Lock read = LIVE_GENERATION.readLock();
        read.lock();
        try {
            ActiveEntry entry = ACTIVE.entries.get(id);
            return entry == null ? null : entry.analysis();
        } finally {
            read.unlock();
        }
    }

    public static int registered() {
        Lock read = LIVE_GENERATION.readLock();
        read.lock();
        try {
            return ACTIVE.entries.size();
        } finally {
            read.unlock();
        }
    }

    /** Clearing is conservative: every function returns to the fenced path. */
    public static void clear() {
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            ACTIVE = EMPTY;
            PLAN = FunctionPlan.EMPTY;
        } finally {
            write.unlock();
        }
    }

    /** Planner input of one exact generation (library + server registries). */
    private static final class LibrarySources implements FunctionPlanner.Sources {
        private final Prepared prepared;
        private final Map<Identifier, FunctionIR.Function> irs;
        private final ServerFunctionLibrary library;
        private final MinecraftServer server;
        private final Set<Identifier> tags = new HashSet<>();
        private final HashMap<String, JsonElement> predicates = new HashMap<>();
        private RegistryOps<JsonElement> ops;

        LibrarySources(Prepared prepared, Map<Identifier, FunctionIR.Function> irs,
                       ServerFunctionLibrary library, MinecraftServer server) {
            this.prepared = prepared;
            this.irs = irs;
            this.library = library;
            this.server = server;
            library.getAvailableTags().forEach(tags::add);
        }

        @Override
        public FunctionIR.Function ir(Identifier id) {
            ActiveEntry entry = prepared.entries.get(id);
            if (entry == null) return null;
            return library.getFunction(id).orElse(null) == entry.function() ? irs.get(id) : null;
        }

        @Override
        public CommandFunction<CommandSourceStack> function(Identifier id) {
            return library.getFunction(id).orElse(null);
        }

        @Override
        public Iterable<Identifier> functionIds() {
            return library.getFunctions().keySet();
        }

        @Override
        public List<Identifier> tag(Identifier id) {
            if (!tags.contains(id)) return null;
            List<CommandFunction<CommandSourceStack>> members = library.getTag(id);
            ArrayList<Identifier> out = new ArrayList<>(members.size());
            for (CommandFunction<CommandSourceStack> f : members) out.add(f.id());
            return out;
        }

        @Override
        public JsonElement predicate(String id) {
            if (predicates.containsKey(id)) return predicates.get(id);
            Identifier key = Identifier.tryParse(id);
            JsonElement json = null;
            if (key != null) {
                if (ops == null) ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
                json = server.reloadableRegistries().lookup().lookup(Registries.PREDICATE)
                        .flatMap(lookup -> lookup.get(ResourceKey.create(Registries.PREDICATE, key)))
                        .flatMap(holder -> LootItemCondition.DIRECT_CODEC
                                .encodeStart(ops, holder.value()).result())
                        .orElse(null);
            }
            predicates.put(id, json);
            return json;
        }

        @Override
        public List<Identifier> rewardFunctions() {
            ArrayList<Identifier> out = new ArrayList<>();
            for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                holder.value().rewards().function().map(CacheableFunction::getId).ifPresent(out::add);
            }
            return out;
        }
    }

    /** Total source classifier: every expected miss becomes ROUTED. */
    static Analysis analyze(List<String> lines) {
        boolean anyRel = false, anyAbs = false, routed = false;
        int radius = 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            if (line.charAt(0) == '$' || line.indexOf('@') >= 0) {
                routed = true;
                break;
            }
            String[] tok = line.split("\\s+");
            int[][] triples = localTriples(tok);
            if (triples == null) {
                routed = true;
                break;
            }
            for (int[] t : triples) {
                String xs = tok[t[0]], ys = tok[t[0] + 1], zs = tok[t[0] + 2];
                Boolean rel = tripleKind(xs, ys, zs);
                if (rel == null) {
                    routed = true;
                    break;
                }
                if (rel) {
                    anyRel = true;
                    radius = Math.max(radius,
                            Math.max(relOff(xs), Math.max(relOff(ys), relOff(zs))));
                    if (radius > 64) {
                        routed = true;
                        break;
                    }
                } else {
                    anyAbs = true;
                    int x = (int) Double.parseDouble(xs);
                    int z = (int) Double.parseDouble(zs);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            if (routed) break;
            if (anyRel && anyAbs) {
                routed = true;
                break;
            }
        }
        if (routed || (!anyRel && !anyAbs)) {
            return new Analysis(Kind.ROUTED, 0, 0, 0, 0, 0);
        }
        if (anyRel) {
            return new Analysis(Kind.PURE_LOCAL_REL, radius, 0, 0, 0, 0);
        }
        return new Analysis(Kind.PURE_LOCAL_ABS, 0, minX, minZ, maxX, maxZ);
    }

    /** Coordinate-triple token indices for the local allowlist, or null. */
    private static int[][] localTriples(String[] tok) {
        if (tok.length == 0) return null;
        return switch (tok[0]) {
            case "setblock" -> tok.length >= 4 ? new int[][]{{1}} : null;
            case "fill" -> tok.length >= 7 ? new int[][]{{1}, {4}} : null;
            // clone's destination extends by the source dimensions; the
            // coordinate-triple bounds are not a proof of that footprint.
            case "clone" -> null;
            case "summon" -> tok.length == 2
                    ? new int[0][] : tok.length >= 5 ? new int[][]{{2}} : null;
            case "data" -> tok.length >= 6 && "block".equals(tok[2])
                    && IslandCommands.dataReadsOnlyTarget(tok)
                    ? new int[][]{{3}} : null;
            case "particle" -> tok.length >= 5 ? new int[][]{{2}} : null;
            default -> null;
        };
    }

    /** true = all-relative, false = all-absolute, null = mixed/^/unparsable. */
    private static Boolean tripleKind(String x, String y, String z) {
        int rel = 0, abs = 0;
        for (String s : new String[]{x, y, z}) {
            if (s.isEmpty() || s.charAt(0) == '^') return null;
            if (s.charAt(0) == '~') {
                rel++;
            } else {
                try {
                    Double.parseDouble(s);
                } catch (NumberFormatException expected) {
                    return null;
                }
                abs++;
            }
        }
        return rel == 3 ? Boolean.TRUE : abs == 3 ? Boolean.FALSE : null;
    }

    private static int relOff(String s) {
        if (s.charAt(0) != '~' || s.length() == 1) return 0;
        try {
            return (int) Math.ceil(Math.abs(Double.parseDouble(s.substring(1))));
        } catch (NumberFormatException expected) {
            return 65;
        }
    }
}
