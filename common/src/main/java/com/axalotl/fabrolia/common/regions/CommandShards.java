package com.axalotl.fabrolia.common.regions;

/** Cadence-bound work queued to an island's owned unit-head lane. */
public final class CommandShards {

    private CommandShards() {}

    /** Live owners service this FIFO only at unit start. A retired owner has
     *  no successor unit: Main drains its exact queue under the full fence. */
    static void serviceIslandTasks(IslandRegistry.PIsland island) {
        // Each body has one admitted detachment. Generation failure discards
        // the remaining FIFO and keys instead of replaying them after teardown.
        try {
            while (RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> serviceOneIslandTaskEntered(island))) {
                // One receipt, one task. No skip or replay path.
            }
        } catch (Throwable failure) {
            cancelPendingTasks(island);
            throw failure;
        }
    }

    /** Complete accepted work with no successor unit before the rebuild fence opens. */
    static void finishTopology(RegionizedServer runtime,
                               java.util.List<IslandRegistry.PIsland> oldOwners) {
        for (IslandRegistry.PIsland owner : oldOwners) {
            if (!owner.retired && runtime.asyncActive) continue;
            if (!runtime.currentThreadHoldsAllClaims()) {
                throw new IllegalStateException("retired island work lost its rebuild fence");
            }
            serviceIslandTasks(owner);
        }
    }

    private static boolean serviceOneIslandTaskEntered(IslandRegistry.PIsland island) {
        Runnable task;
        synchronized (island.pendingIslandTasks) {
            task = island.pendingIslandTasks.isEmpty() ? null
                    : island.pendingIslandTasks.remove(0);
        }
        if (task == null) return false;
        task.run();
        return true;
    }

    /** Dispatch to the exact owner, retaining FIFO/key identity through retirement. */
    public static void enqueueIslandTask(IslandRegistry.PIsland owner, Object coalesceKey,
                                         Runnable task) {
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                boolean retiredContinuation;
                synchronized (owner.pendingIslandTasks) {
                    if (coalesceKey != null && !owner.pendingIslandTaskKeys.add(coalesceKey)) {
                        return; // one pending instance per key: missed-cycle semantics
                    }
                    retiredContinuation = needsMainDrain(owner) && owner.pendingIslandTasks.isEmpty();
                    owner.pendingIslandTasks.add(coalesceKey == null ? task : () -> {
                        synchronized (owner.pendingIslandTasks) {
                            owner.pendingIslandTaskKeys.remove(coalesceKey);
                        }
                        task.run();
                    });
                }
                if (retiredContinuation) {
                    // Registry retirement uses this same queue monitor. A late
                    // writer never attaches arbitrary work to an unproven survivor.
                    IslandCommands.enqueueFencedBody(new MainTaskDrain(owner));
                } else {
                    RegionScheduler.wakeWorkers(1);
                }
            });
        } catch (Throwable failure) {
            // An already-entered writer may acquire the queue monitor after a
            // fatal sweep. Refused publication must discard that late suffix too.
            cancelPendingTasks(owner);
            throw failure;
        }
    }

    private static void cancelPendingTasks(IslandRegistry.PIsland owner) {
        synchronized (owner.pendingIslandTasks) {
            owner.pendingIslandTasks.clear();
            owner.pendingIslandTaskKeys.clear();
        }
    }

    private record MainTaskDrain(IslandRegistry.PIsland owner)
            implements Runnable, RegionTaskFanIn.CancellableQueueEntry {
        @Override
        public void run() {
            if (needsMainDrain(owner)) serviceIslandTasks(owner);
        }

        @Override
        public void cancelQueuedBeforeStart() {
            cancelPendingTasks(owner);
        }
    }

    private static boolean needsMainDrain(IslandRegistry.PIsland owner) {
        if (owner.retired) return true;
        if (owner.level == null) return false;
        RegionizedServer runtime = RegionScheduler.publishedForLevel(owner.level);
        return runtime == null || !runtime.asyncActive;
    }

}
