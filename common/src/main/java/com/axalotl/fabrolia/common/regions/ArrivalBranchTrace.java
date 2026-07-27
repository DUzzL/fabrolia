package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.LoggerFactory;

/**
 * audit7 — which branch of {@code CrossDimTeleportMixin.fabrolia$stageArrival}
 * every cross-dimension arrival actually takes.
 *
 * <p>The audit6 capture showed only 1 of 12 End arrivals per duper cycle passing
 * through the staged-publication closure; the other 11 had no arrival record at
 * all. If eleven twelfths of the traffic bypasses staging, then cohort atomicity,
 * the {@code [regen, add]} ordering and the arrival release rule govern one entity
 * per cycle, and every ordering theory built on them was aimed at the wrong path.
 *
 * <p>Primary question this answers: are the other 11 nested INSIDE the first one's
 * publication (so {@code PUBLISHING} is true and they pass straight through to the
 * vanilla insertion)? {@link #depth()} is the publish nesting depth on this thread,
 * so a non-zero depth on a bypassing arrival proves it.
 *
 * <p>Diagnostics only — nothing here changes behaviour. Default ON because JVM
 * system properties provably do not reach this operator's JVM (audit5); disable
 * with {@code -Dfabrolia.debug.fbeBranch=false}. Output is bounded.
 */
public final class ArrivalBranchTrace {

    public static final boolean ON =
            !"false".equalsIgnoreCase(System.getProperty("fabrolia.debug.fbeBranch", "true"));

    private ArrivalBranchTrace() {}

    /** Publish nesting depth per thread, maintained by CrossDimArrivals.runPublished. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    public static void enterPublish() { if (ON) DEPTH.get()[0]++; }
    public static void exitPublish()  { if (ON) { int[] d = DEPTH.get(); if (d[0] > 0) d[0]--; } }
    public static int depth()         { return ON ? DEPTH.get()[0] : -1; }

    private static final int BURST = 800;
    private static final java.util.concurrent.atomic.AtomicInteger EMITTED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong WINDOW =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger IN_WINDOW =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.LongAdder SUPPRESSED =
            new java.util.concurrent.atomic.LongAdder();

    private static boolean allow() {
        if (EMITTED.incrementAndGet() <= BURST) return true;
        long now = System.nanoTime();
        long start = WINDOW.get();
        if (now - start > 1_000_000_000L && WINDOW.compareAndSet(start, now)) IN_WINDOW.set(0);
        if (IN_WINDOW.incrementAndGet() <= 40) return true;
        SUPPRESSED.increment();
        return false;
    }

    /**
     * @param branch one of PUBLISHING / not-worker-thread / async-off /
     *               dest-not-island-owned / STAGED / feature-off / player / no-scheduler
     */
    public static void branch(String branch, Entity entity, ServerLevel dest, long chunkKey) {
        if (!ON || !allow()) return;
        try {
            long srcTick = IslandClock.contextTime();
            long srcDomain = CrossDimArrivals.currentDomainKey();
            String srcDim = entity.level() instanceof ServerLevel sl
                    ? sl.dimension().identifier().toString() : "?";
            String destDim = dest != null ? dest.dimension().identifier().toString() : "?";

            // Destination island identity, when it is resolvable without a claim.
            String destIsland = "?";
            if (dest != null) {
                RegionizedServer rs = RegionScheduler.forLevel(dest);
                if (rs != null) {
                    Region r = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(chunkKey));
                    if (r != null && r.domain != null && r.domain.owner != null) {
                        destIsland = "#" + r.domain.owner.id + "g" + r.domain.owner.gen;
                    } else {
                        destIsland = "global";
                    }
                }
            }

            LoggerFactory.getLogger("Fabrolia-FbeBranch").info(
                    "[fbebranch] {} uuid={} id={} srcDim={} destDim={} cohort={}@{} "
                            + "publishing={} depth={} destIsland={} chunk={},{} thread={}{}",
                    branch, entity.getUUID(), entity.getId(), srcDim, destDim,
                    srcTick, srcDomain,
                    CrossDimArrivals.PUBLISHING.get(), depth(), destIsland,
                    net.minecraft.world.level.ChunkPos.getX(chunkKey),
                    net.minecraft.world.level.ChunkPos.getZ(chunkKey),
                    Thread.currentThread().getName(),
                    SUPPRESSED.sum() > 0 ? " suppressed=" + SUPPRESSED.sumThenReset() : "");
        } catch (Throwable ignored) {
            // forensics must never influence the path being diagnosed
        }
    }
}
