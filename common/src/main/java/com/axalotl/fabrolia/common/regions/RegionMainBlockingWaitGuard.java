package com.axalotl.fabrolia.common.regions;

/**
 * Refuses a causal region participant before it can synchronously wait for a
 * task on physical Main.
 *
 * <p>A worker which owns or helps an island can otherwise enqueue behind a
 * Main task which is waiting to acquire that same island.  Neither side can
 * make progress.  There is no semantics-preserving queue pump at this edge:
 * entering the blocking call is therefore a generation-fatal invariant
 * violation, and the original blocking body must remain unentered.</p>
 */
public final class RegionMainBlockingWaitGuard {
    private RegionMainBlockingWaitGuard() {}

    /**
     * Publish and throw the scheduler generation's exact first failure when the
     * calling thread is a live causal region participant.  The caller names the
     * concrete blocking edge for crash diagnostics.
     */
    public static void failIfCurrentThreadParticipates(String operation) {
        if (!RegionScheduler.isActive() || !currentThreadParticipates()) return;

        Thread current = Thread.currentThread();
        IllegalStateException violation = new IllegalStateException(
                "causal region participant attempted a blocking physical-Main wait: "
                        + operation + " [thread=" + current.getName() + "]");
        Throwable winner = RegionScheduler.publishFatalAndSelectWinner(violation);
        RegionMainBlockingWaitGuard.<RuntimeException>rethrow(winner);
    }

    static boolean currentThreadParticipates() {
        Thread current = Thread.currentThread();
        boolean workerSurface = current instanceof RegionWorkerThread worker
                // Dense fan-out helpers publish currentRegion even though another
                // thread owns the enclosing island/unit claim.
                && (worker.currentRegion != null || worker.currentDomain != null);
        return hasParticipationWitness(
                workerSurface,
                RegionWorkerPool.currentThreadHasLiveCausalStep(),
                RegionScheduler.currentThreadHoldsLiveIslandClaim());
    }

    static boolean hasParticipationWitness(boolean workerSurface,
                                           boolean liveCausalStep,
                                           boolean liveIslandClaim) {
        return workerSurface || liveCausalStep || liveIslandClaim;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
