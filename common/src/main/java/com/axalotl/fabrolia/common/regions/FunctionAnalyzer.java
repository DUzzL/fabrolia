package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.api.FabroliaFunctionScopes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerFunctionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                          FabroliaFunctionScopes.Scope sourceScope) {}

    private record ActiveEntry(CommandFunction<CommandSourceStack> function,
                               Analysis analysis,
                               FabroliaFunctionScopes.Scope scope) {}

    /** One exact candidate-library transaction; parse tasks write concurrently. */
    public static final class ReloadStage {
        private final ConcurrentHashMap<Identifier, Staged> analyzed =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Identifier, FabroliaFunctionScopes.Scope> registeredScopes =
                new ConcurrentHashMap<>();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        private ReloadStage() {}
    }

    /** Immutable, allocation-complete snapshot attached to one candidate library. */
    public static final class Prepared {
        private final Map<Identifier, ActiveEntry> entries;

        private Prepared(Map<Identifier, ActiveEntry> entries) {
            this.entries = entries;
        }
    }

    private static final Prepared EMPTY = new Prepared(Map.of());

    /** Queued reload writers must not be starved by later local-function readers. */
    private static final ReentrantReadWriteLock LIVE_GENERATION =
            new ReentrantReadWriteLock(true);

    private static final ScopedValue<ReloadStage> CURRENT_RELOAD =
            ScopedValue.newInstance();

    private static volatile Prepared ACTIVE = EMPTY;

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
        FabroliaFunctionScopes.Scope sourceScope = scopeFor(analysis);
        stage.analyzed.put(id, new Staged(function, analysis, sourceScope));
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
            return FunctionAnalyzer.<Prepared, RuntimeException>rethrow(failure);
        }

        HashMap<Identifier, ActiveEntry> sealed = new HashMap<>(functions.size());
        functions.forEach((id, function) -> {
            Staged staged = stage.analyzed.get(id);
            if (staged == null || staged.function() != function) return;
            FabroliaFunctionScopes.Scope scope =
                    stage.registeredScopes.getOrDefault(id, staged.sourceScope());
            sealed.put(id, new ActiveEntry(function, staged.analysis(), scope));
        });
        return sealed.isEmpty() ? EMPTY : new Prepared(Map.copyOf(sealed));
    }

    /** Initial-boot activation; no function body may observe a partial map. */
    public static void activateInitial(Prepared prepared) {
        Prepared next = Objects.requireNonNull(prepared, "prepared");
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            ACTIVE = next;
        } finally {
            write.unlock();
        }
    }

    /**
     * Live reload commit. All fallible allocation and analysis completed in
     * seal(). Under the write lock vanilla replaces its library exactly once,
     * then activation is only a volatile reference assignment.
     */
    public static void replaceAndActivate(Prepared prepared, Runnable vanillaReplace) {
        Prepared next = Objects.requireNonNull(prepared, "prepared");
        Runnable replace = Objects.requireNonNull(vanillaReplace, "vanillaReplace");
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            replace.run();
            ACTIVE = next;
        } finally {
            write.unlock();
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

    public static FabroliaFunctionScopes.Scope scopeOf(Identifier id) {
        Lock read = LIVE_GENERATION.readLock();
        read.lock();
        try {
            ActiveEntry entry = ACTIVE.entries.get(id);
            return entry == null ? FabroliaFunctionScopes.Scope.UNKNOWN : entry.scope();
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

    /** Compiler registration is staged when called from the parse scope. */
    public static void registerScope(Identifier id, FabroliaFunctionScopes.Scope scope) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        if (CURRENT_RELOAD.isBound()) {
            CURRENT_RELOAD.get().registeredScopes.put(id, scope);
        } else {
            // An id alone is not a receipt for the active function object: a
            // late compiler callback from an aborted/older reload could refine
            // a newer generation. Without an exact reload scope, fail closed.
            return;
        }
    }

    /** Clearing is conservative: every function returns to the fenced path. */
    public static void clear() {
        Lock write = LIVE_GENERATION.writeLock();
        write.lock();
        try {
            ACTIVE = EMPTY;
        } finally {
            write.unlock();
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

    private static FabroliaFunctionScopes.Scope scopeFor(Analysis analysis) {
        boolean routed = analysis.kind() == Kind.ROUTED;
        return new FabroliaFunctionScopes.Scope(
                !routed, false, routed, routed, routed, false,
                "source analysis: " + analysis.kind());
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

    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> T rethrow(Throwable failure) throws X {
        throw (X) failure;
    }
}
