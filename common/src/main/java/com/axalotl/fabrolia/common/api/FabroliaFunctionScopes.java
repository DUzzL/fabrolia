package com.axalotl.fabrolia.common.api;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A8/MCFC bridge — scope metadata for datapack functions (interpreted or
 * compiled). A function compiler (MCFC) or analyzer registers what a function
 * CAN touch; Fabrolia's command router uses it to dispatch island-local
 * function bodies to island execution lanes at island cadence instead of the
 * fenced global path. Static analysis is never trusted alone: any function
 * whose metadata sets {@link Scope#requiresRuntimeValidation} — dynamic
 * selectors, macros, command storage, runtime coordinates, modded commands,
 * unknown sources, dynamic execute chains — is re-validated per invocation,
 * and every unknown operation falls back to the fenced global path (the safe
 * compatibility path). Absent metadata = fenced global path.
 *
 * <p>Compiled execution must never become faster by changing observable
 * behavior: vanilla command ordering, source context, selector results,
 * random consumption, success counts, return values, recursion limits,
 * exception behavior, scoreboard/storage visibility, scheduling, ownership
 * and merge/split semantics all bind identically on every path.
 */
public final class FabroliaFunctionScopes {

    private FabroliaFunctionScopes() {}

    /** Declared capability envelope of one function (conservative = safe). */
    public record Scope(
            /** Every effect provably within the CALLER's island (relative/
             *  source-anchored positions, no selectors beyond @s, no global
             *  state). Eligible for island-inline execution. */
            boolean provenIslandLocal,
            /** Touches dimension-global state only (time/weather/...). */
            boolean dimensionGlobal,
            /** Touches server-global state (scoreboards, storage, whitelist…). */
            boolean serverGlobal,
            /** May span islands (multi-target selectors, absolute far coords). */
            boolean crossIsland,
            /** Metadata alone is insufficient — validate scope per invocation. */
            boolean requiresRuntimeValidation,
            /** May be partitioned into per-owner shards (Phase 2). */
            boolean partitionable,
            /** Human-readable analysis note (diagnostics). */
            String note) {

        public static final Scope UNKNOWN =
                new Scope(false, false, true, true, true, false, "no metadata: fenced global path");
    }

    private static final Map<Identifier, Scope> SCOPES = new ConcurrentHashMap<>();

    /** Compiler/analyzer registration (datapack reload replaces entries). */
    public static void register(Identifier function, Scope scope) {
        SCOPES.put(function, scope);
    }

    public static void clear() {
        SCOPES.clear();
    }

    public static Scope scopeOf(Identifier function) {
        return SCOPES.getOrDefault(function, Scope.UNKNOWN);
    }

    public static int registered() {
        return SCOPES.size();
    }
}
