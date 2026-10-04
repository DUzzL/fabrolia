package com.axalotl.fabrolia.common.api;

import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import com.axalotl.fabrolia.common.regions.FunctionPlans;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Read-only view of Fabrolia's datapack function plan (async islands).
 *
 * <p>Fabrolia analyzes every function exactly as vanilla parsed it and, per
 * reload generation, publishes a plan: per-tick roots ({@code #minecraft:tick}
 * and unconditional 1t self-rearming loops) are partitioned per root line
 * across island heads and Main, and functions whose whole call-graph closure
 * touches only the executing entity are marked owner-local. Nothing outside
 * that analysis can widen it: absent or unproven metadata means the fenced
 * Main path, and vanilla command semantics remain the only source of truth.
 */
public final class FabroliaFunctionScopes {

    private FabroliaFunctionScopes() {}

    /** Whether {@code function} of the active generation is proven owner-local. */
    public static boolean ownerLocal(Identifier function) {
        return FunctionPlans.ownerLocal(function);
    }

    /** Human-readable per-tick plan with each root's verdict and blocking line. */
    public static List<String> describePlan() {
        return FunctionAnalyzer.plan().describe();
    }

    /** Number of functions analyzed in the active generation. */
    public static int analyzed() {
        return FunctionAnalyzer.registered();
    }
}
