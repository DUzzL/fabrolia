package com.axalotl.fabrolia.common.regions;

import net.minecraft.resources.Identifier;

/**
 * Per-function execution plans for datapack functions under async islands.
 *
 * <p>{@link #ownerLocal} answers whether a function's whole call-graph closure is
 * proven to touch only state owned by the executing entity's island ({@code @s}
 * and bounded boxes around it), so it may run synchronously on the owner thread
 * with vanilla semantics. {@code false} means "not proven": the caller keeps the
 * fenced Main path.
 */
public final class FunctionPlans {
    private FunctionPlans() {}

    public static boolean ownerLocal(Identifier functionId) {
        return false;
    }
}
