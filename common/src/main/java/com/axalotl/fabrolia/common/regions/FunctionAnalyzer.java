package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.api.FabroliaFunctionScopes;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A8 Phase 3 — load-time source analysis of datapack functions (the
 * Fabrolia-side analyzer; MCFC metadata can override via the same
 * {@link FabroliaFunctionScopes} registry, and analysis applies identically
 * to interpreted and compiled execution because it is SOURCE-level — MCFC's
 * contract is observable equivalence with the interpreted body).
 *
 * <p>Classification per function (conservative: any doubt → ROUTED):
 * <ul>
 *   <li><b>PURE_LOCAL_REL</b> — every line is an allowlisted local command
 *       with RELATIVE-ONLY (~) coordinates; the analysis records the maximum
 *       Chebyshev offset. Position-independent proof: callable inline on any
 *       island whose cells cover source±radius (checked per call).</li>
 *   <li><b>PURE_LOCAL_ABS</b> — every line allowlisted with ABSOLUTE
 *       coordinates only; records the bounding box. A tick-tag function of
 *       this class dispatches to the single island owning the box and runs
 *       at THAT island's cadence (the tick-function hybrid: a slow island's
 *       local tick work slows with it, global work never duplicates).</li>
 *   <li><b>ROUTED</b> — anything else (selectors, macros ($), globals,
 *       nested functions, unknown roots, mixed rel/abs): the fenced
 *       exactly-once path.</li>
 * </ul>
 */
public final class FunctionAnalyzer {

    private FunctionAnalyzer() {}

    public enum Kind { PURE_LOCAL_REL, PURE_LOCAL_ABS, ROUTED }

    /** minX..maxZ only meaningful for PURE_LOCAL_ABS. */
    public record Analysis(Kind kind, int maxRelRadius,
                           int minX, int minZ, int maxX, int maxZ) {}

    private static final Map<Identifier, Analysis> ANALYSES = new ConcurrentHashMap<>();

    public static Analysis analysisOf(Identifier id) {
        return ANALYSES.get(id);
    }

    public static void clear() {
        ANALYSES.clear();
        FabroliaFunctionScopes.clear();
    }

    /** Called from the function-parse seam with the RAW lines. */
    public static void analyze(Identifier id, List<String> lines) {
        boolean anyRel = false, anyAbs = false, routed = false;
        int radius = 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            if (line.charAt(0) == '$' || line.indexOf('@') >= 0) { routed = true; break; }
            String[] tok = line.split("\\s+");
            int[][] triples = localTriples(tok);
            if (triples == null) { routed = true; break; }
            for (int[] t : triples) {
                String xs = tok[t[0]], ys = tok[t[0] + 1], zs = tok[t[0] + 2];
                Boolean rel = tripleKind(xs, ys, zs);
                if (rel == null) { routed = true; break; }
                if (rel) {
                    anyRel = true;
                    radius = Math.max(radius, Math.max(relOff(xs), Math.max(relOff(ys), relOff(zs))));
                    if (radius > 64) { routed = true; break; } // unprovable reach
                } else {
                    anyAbs = true;
                    int x = (int) Double.parseDouble(xs), z = (int) Double.parseDouble(zs);
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
            if (routed) break;
            if (anyRel && anyAbs) { routed = true; break; } // mixed: unprovable
        }
        Analysis a;
        if (routed || (!anyRel && !anyAbs)) {
            a = new Analysis(Kind.ROUTED, 0, 0, 0, 0, 0);
        } else if (anyRel) {
            a = new Analysis(Kind.PURE_LOCAL_REL, radius, 0, 0, 0, 0);
        } else {
            a = new Analysis(Kind.PURE_LOCAL_ABS, 0, minX, minZ, maxX, maxZ);
        }
        ANALYSES.put(id, a);
        FabroliaFunctionScopes.register(id, new FabroliaFunctionScopes.Scope(
                a.kind() != Kind.ROUTED, false, a.kind() == Kind.ROUTED,
                a.kind() == Kind.ROUTED, a.kind() == Kind.ROUTED, false,
                "source analysis: " + a.kind()));
    }

    /** Coordinate-triple token indices for the local allowlist, or null. */
    private static int[][] localTriples(String[] tok) {
        if (tok.length == 0) return null;
        switch (tok[0]) {
            case "setblock":
                return tok.length >= 4 ? new int[][]{{1}} : null;
            case "fill":
                return tok.length >= 7 ? new int[][]{{1}, {4}} : null;
            case "clone":
                return tok.length >= 10 ? new int[][]{{1}, {4}, {7}} : null;
            case "summon":
                if (tok.length == 2) return new int[0][];
                return tok.length >= 5 ? new int[][]{{2}} : null;
            case "data":
                return tok.length >= 6 && "block".equals(tok[2]) ? new int[][]{{3}} : null;
            case "particle":
                return tok.length >= 5 ? new int[][]{{2}} : null;
            default:
                return null;
        }
    }

    /** true = all-relative, false = all-absolute, null = mixed/^/unparsable. */
    private static Boolean tripleKind(String x, String y, String z) {
        int rel = 0, abs = 0;
        for (String s : new String[]{x, y, z}) {
            if (s.isEmpty() || s.charAt(0) == '^') return null;
            if (s.charAt(0) == '~') rel++;
            else {
                try { Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
                abs++;
            }
        }
        return rel == 3 ? Boolean.TRUE : abs == 3 ? Boolean.FALSE : null;
    }

    private static int relOff(String s) {
        if (s.charAt(0) != '~' || s.length() == 1) return 0;
        try { return (int) Math.ceil(Math.abs(Double.parseDouble(s.substring(1)))); }
        catch (NumberFormatException e) { return 65; } // forces ROUTED via radius cap
    }
}
