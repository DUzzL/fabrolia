package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Boundary event-order tracer window + emitter (tasks #12/#13). Gated on
 * {@code -Dfabrolia.debug.boundaryTrace=x1,x2[,z1,z2]} (block coords; the
 * 2-arg form leaves z unbounded). Default off: one static null check on the
 * hot paths. Every line carries wall tick, context (island) tick, the calling
 * thread and — where the caller supplies it — the claimed-region key, so the
 * per-tick line order IS the intra-tick processing order at the seam.
 */
public final class BoundaryTrace {

    public static final int[] WINDOW = parse();

    private BoundaryTrace() {}

    private static int[] parse() {
        String s = System.getProperty("fabrolia.debug.boundaryTrace");
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            String[] p = s.split(",");
            if (p.length >= 4) {
                return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())};
            }
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.MIN_VALUE, Integer.MAX_VALUE};
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean in(BlockPos pos) {
        int[] w = WINDOW;
        return w != null && pos.getX() >= w[0] && pos.getX() <= w[1]
                && pos.getZ() >= w[2] && pos.getZ() <= w[3];
    }

    public static boolean in(int x, int z) {
        int[] w = WINDOW;
        return w != null && x >= w[0] && x <= w[1] && z >= w[2] && z <= w[3];
    }

    /** Claimed-region annotation for the current thread: the static grid key
     *  a region worker holds, "island#N" coordinator context, or "main". */
    public static String ctx() {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            Region r = w.currentRegion;
            if (r != null) {
                return "region@" + Long.toHexString(r.gridKey);
            }
            EpochDomain d = w.currentDomain;
            if (d != null && d.owner != null) {
                return "island#" + d.owner.id;
            }
            return "worker-unclaimed";
        }
        return "main";
    }

    public static void log(ServerLevel level, String kind, BlockPos pos, String detail) {
        org.slf4j.LoggerFactory.getLogger("Fabrolia-BTrace").info(
                "[btrace] {} {} W={} L={} @{} {} ctx={} thread={}",
                level.dimension().identifier(), kind,
                IslandClock.wallTime(level), IslandClock.contextTime(),
                pos.toShortString(), detail, ctx(), Thread.currentThread().getName());
    }
}
