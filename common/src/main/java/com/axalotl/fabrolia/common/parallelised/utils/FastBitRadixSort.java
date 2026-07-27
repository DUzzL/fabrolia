package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;

/**
 * Distance sort for the sensor mixins: squared distances as raw long bits
 * (order-preserving for non-negative doubles), MSB radix partitioning, SIMD
 * batch distance when the Vector API is available.
 *
 * <p><b>Stability:</b> vanilla sorts these lists with TimSort, which is
 * STABLE — equal distances keep encounter order, and ties are routine
 * (an item stack pile at one coordinate, paired mobs in one block). The radix
 * partition alone is not stable, so every comparison tie-breaks on the
 * original index: the result is exactly TimSort's, including which item of a
 * same-position pile a mob picks.
 */
public final class FastBitRadixSort {
    private static final int SMALL_ARRAY_THRESHOLD = 6;

    private static final ThreadLocal<long[]>   BITS_BUFFER = ThreadLocal.withInitial(() -> new long[0]);
    private static final ThreadLocal<int[]>    IDX_BUFFER  = ThreadLocal.withInitial(() -> new int[0]);
    private static final ThreadLocal<double[]> X_BUFFER    = ThreadLocal.withInitial(() -> new double[0]);
    private static final ThreadLocal<double[]> Y_BUFFER    = ThreadLocal.withInitial(() -> new double[0]);
    private static final ThreadLocal<double[]> Z_BUFFER    = ThreadLocal.withInitial(() -> new double[0]);

    /** Rust path scratch: coordinate + permutation direct buffers and an
     *  Object[] for applying the permutation. */
    private static final class NativeBufs {
        java.nio.ByteBuffer xs, ys, zs, perm;
        Object[] scratch = new Object[0];

        void ensure(int n) {
            if (xs == null || xs.capacity() < n * 8) {
                int cap = Integer.highestOneBit(Math.max(64, n) - 1) << 1;
                xs = java.nio.ByteBuffer.allocateDirect(cap * 8).order(java.nio.ByteOrder.nativeOrder());
                ys = java.nio.ByteBuffer.allocateDirect(cap * 8).order(java.nio.ByteOrder.nativeOrder());
                zs = java.nio.ByteBuffer.allocateDirect(cap * 8).order(java.nio.ByteOrder.nativeOrder());
                perm = java.nio.ByteBuffer.allocateDirect(cap * 4).order(java.nio.ByteOrder.nativeOrder());
            }
            if (scratch.length < n) {
                scratch = new Object[Math.max(64, n)];
            }
        }
    }

    private static final ThreadLocal<NativeBufs> NATIVE = ThreadLocal.withInitial(NativeBufs::new);

    public void sort(Object[] entities, int size, net.minecraft.core.Position target) {
        if (size <= 1) {
            return;
        }

        double tx = target.x();
        double ty = target.y();
        double tz = target.z();

        // Batch-FFI path: one Rust call computes squared distances AND the
        // stable permutation (ties by encounter index — TimSort-equivalent);
        // only the Object[] shuffle stays in Java.
        if (DistanceSortBridge.available()) {
            NativeBufs nb = NATIVE.get();
            nb.ensure(size);
            for (int i = 0; i < size; i++) {
                Entity e = (Entity) entities[i];
                nb.xs.putDouble(i * 8, e.getX());
                nb.ys.putDouble(i * 8, e.getY());
                nb.zs.putDouble(i * 8, e.getZ());
            }
            if (DistanceSortBridge.sort(nb.xs, nb.ys, nb.zs, tx, ty, tz, size, nb.perm)) {
                Object[] scratch = nb.scratch;
                for (int k = 0; k < size; k++) {
                    scratch[k] = entities[nb.perm.getInt(k * 4)];
                }
                System.arraycopy(scratch, 0, entities, 0, size);
                return;
            }
        }

        long[]   bits = ensure(BITS_BUFFER, size);
        int[]    idx  = ensureI(IDX_BUFFER, size);
        double[] ex   = ensureD(X_BUFFER,   size);
        double[] ey   = ensureD(Y_BUFFER,   size);
        double[] ez   = ensureD(Z_BUFFER,   size);

        for (int i = 0; i < size; i++) {
            Entity e = (Entity) entities[i];
            ex[i] = e.getX();
            ey[i] = e.getY();
            ez[i] = e.getZ();
            idx[i] = i;
        }

        if (VectorOpsBootstrap.AVAILABLE) {
            VectorOps.batchDistanceSquaredBits(ex, ey, ez, tx, ty, tz, bits, size);
        } else {
            for (int i = 0; i < size; i++) {
                double dx = ex[i] - tx;
                double dy = ey[i] - ty;
                double dz = ez[i] - tz;
                bits[i] = Double.doubleToRawLongBits(dx * dx + dy * dy + dz * dz);
            }
        }

        fastRadixSort(entities, bits, idx, 0, size - 1, 62);
    }

    private static long[] ensure(ThreadLocal<long[]> tl, int size) {
        long[] b = tl.get();
        if (b.length < size) { b = new long[size]; tl.set(b); }
        return b;
    }

    private static int[] ensureI(ThreadLocal<int[]> tl, int size) {
        int[] b = tl.get();
        if (b.length < size) { b = new int[size]; tl.set(b); }
        return b;
    }

    private static double[] ensureD(ThreadLocal<double[]> tl, int size) {
        double[] b = tl.get();
        if (b.length < size) { b = new double[size]; tl.set(b); }
        return b;
    }

    private static void fastRadixSort(Object[] ents, long[] bits, int[] idx, int low, int high, int bit) {
        if (low >= high) {
            return;
        }
        if (bit < 0) {
            // Whole block has identical distance bits — restore encounter
            // order (the stable tie-break).
            insertionSort(ents, bits, idx, low, high);
            return;
        }
        if (high - low <= SMALL_ARRAY_THRESHOLD) {
            insertionSort(ents, bits, idx, low, high);
            return;
        }

        int i = low;
        int j = high;
        final long mask = 1L << bit;

        while (i <= j) {
            while (i <= j && (bits[i] & mask) == 0) i++;
            while (i <= j && (bits[j] & mask) != 0) j--;
            if (i < j) swap(ents, bits, idx, i++, j--);
        }

        if (low < j) fastRadixSort(ents, bits, idx, low, j, bit - 1);
        if (i < high) fastRadixSort(ents, bits, idx, i, high, bit - 1);
    }

    /** Insertion sort on (distanceBits, originalIndex) — the stable order. */
    private static void insertionSort(Object[] ents, long[] bits, int[] idx, int low, int high) {
        for (int i = low + 1; i <= high; i++) {
            int j = i;
            Object currentEntity = ents[j];
            long currentBits = bits[j];
            int currentIdx = idx[j];
            while (j > low && (bits[j - 1] > currentBits
                    || (bits[j - 1] == currentBits && idx[j - 1] > currentIdx))) {
                ents[j] = ents[j - 1];
                bits[j] = bits[j - 1];
                idx[j] = idx[j - 1];
                j--;
            }
            ents[j] = currentEntity;
            bits[j] = currentBits;
            idx[j] = currentIdx;
        }
    }

    private static void swap(Object[] ents, long[] bits, int[] idx, int a, int b) {
        Object tempEntity = ents[a];
        ents[a] = ents[b];
        ents[b] = tempEntity;
        long tempBits = bits[a];
        bits[a] = bits[b];
        bits[b] = tempBits;
        int tempIdx = idx[a];
        idx[a] = idx[b];
        idx[b] = tempIdx;
    }
}
