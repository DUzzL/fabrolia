package com.axalotl.fabrolia.common.regions;

/**
 * Marker thread subclass for region workers. Detected by
 * {@link com.axalotl.fabrolia.common.ParallelProcessor#isServerExecutionThread} so
 * vanilla code that checks "am I on the server tick thread" passes for region
 * workers too — region workers DO drive server-side entity ticks while they hold
 * a region claim, same semantic as Fabrolia-Tick-Pool workers.
 *
 * <p>{@code currentRegion} is set by {@link RegionWorkerPool#workerLoop} before
 * drain and cleared after. {@link FabroliaLocks#tickLockOr(Object)} reads it on
 * the hot path to pick a per-region monitor instead of a global static one —
 * plain field access (one volatile load), no ThreadLocal map lookup (per
 * memory: ThreadLocal.set/remove is expensive on the hot path).
 */
public final class RegionWorkerThread extends Thread {
    public volatile Region currentRegion;

    /** A2: the island timeline this worker is coordinating a tick unit for
     *  (set around IslandTickUnit execution, null otherwise). Secondary input
     *  to {@link RegionizedServer#domainForCreation} — a claimed region's own
     *  domain wins when present — and, later, the anchor for the island-local
     *  getGameTime redirect. */
    volatile EpochDomain currentDomain;

    /** Ownership partition of the owner-local function body this thread is
     *  executing ({@link IslandFunctionScope}); thread-confined, null outside. */
    FunctionPartition functionScope;

    /** R5 targeted wake: set (volatile) BEFORE the final lane re-check preceding park,
     *  cleared after park returns. Submitters publish work first, then scan these flags
     *  and unpark only as many workers as there are new units — Dekker-style: either the
     *  worker's re-check sees the task, or the submitter's scan sees the flag. Replaces
     *  the unpark-ALL storm (1.1s of Unsafe.unpark self-time, 2026-07-01 profile). */
    volatile boolean parked;

    /** Thread-confined level-RNG stripes ({@code FabroliaRandomStriping.Stripes}). */
    public Object randomStripes;

    RegionWorkerThread(Runnable target, String name) {
        super(target, name);
    }
}
