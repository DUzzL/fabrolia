package com.axalotl.fabrolia.common.parallelised.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

/**
 * Soft bridge to Oxidizium's Rust distance sort (the batch-FFI form of the
 * sensor sort: one call computes squared distances AND the stable permutation;
 * LLVM autovectorizes the distance loop, replacing the Vector-API path).
 * Oxidizium absent → null handle → callers keep the pure-Java path.
 */
public final class DistanceSortBridge {

    private static final MethodHandle SORT = resolve();

    private DistanceSortBridge() {}

    private static MethodHandle resolve() {
        try {
            Class<?> c = Class.forName("com.github.tatercertified.nalim.sort.DistanceSort");
            return MethodHandles.publicLookup().findStatic(c, "sort",
                    MethodType.methodType(boolean.class,
                            ByteBuffer.class, ByteBuffer.class, ByteBuffer.class,
                            double.class, double.class, double.class,
                            int.class, ByteBuffer.class));
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean available() {
        return SORT != null;
    }

    public static boolean sort(ByteBuffer xs, ByteBuffer ys, ByteBuffer zs,
                               double tx, double ty, double tz,
                               int n, ByteBuffer perm) {
        MethodHandle h = SORT;
        if (h == null) {
            return false;
        }
        try {
            return (boolean) h.invokeExact(xs, ys, zs, tx, ty, tz, n, perm);
        } catch (Throwable t) {
            return false;
        }
    }
}
