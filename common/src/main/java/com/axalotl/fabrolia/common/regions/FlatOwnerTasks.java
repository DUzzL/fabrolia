package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Server-lifetime callbacks. Flat owner callbacks use the native FIFO; shared-clock owner
 * callbacks and unaddressed region submissions wait for an exclusive Main turn. The stop
 * receipt preserves accepted writes even when the server never ticks again. */
public final class FlatOwnerTasks {
    private static volatile FlatOwnerTasks live;

    private final MinecraftServer server;
    private final ReferenceLinkedOpenHashSet<Delivery> pending = new ReferenceLinkedOpenHashSet<>();
    private volatile Delivery firstShared; // Main's lock-free empty check; links under pending
    private Delivery lastShared;
    private volatile boolean callbackCutWaiting;
    private boolean closed;
    private boolean inAcceptedBody; // physical Main only

    private FlatOwnerTasks(MinecraftServer server) { this.server = server; }

    /** Both loaders call this on physical Main once, not on a regions on/off toggle. */
    public static void start(MinecraftServer server) {
        if (live != null || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("flat owner dispatch started outside server startup");
        }
        live = new FlatOwnerTasks(server);
    }

    static void execute(ServerLevel level, ChunkPos pos, Entity entity, Runnable task) {
        submit(level, pos, entity, task, false);
    }

    static void postShared(ServerLevel level, ChunkPos pos, Entity entity, Runnable task) {
        submit(level, pos, entity, task, true);
    }

    /** No position to claim. Never enter inline, even on an exact-turn owner. */
    static void postUnaddressed(Runnable task) {
        Objects.requireNonNull(task, "task");
        FlatOwnerTasks generation = live;
        Throwable failure = null;
        if (generation != null) {
            try {
                synchronized (generation.pending) {
                    failure = RegionScheduler.workerFailureOrNull();
                    if (failure == null && !generation.closed && RegionScheduler.acceptsSharedOwnerTasks()
                            && RegionScheduler.getServer() == generation.server) {
                        generation.enqueueLocked(generation.new Delivery(null, null, null, task), true);
                        return;
                    }
                }
            } catch (Throwable thrown) {
                failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }
        }
        if (failure == null) failure = RegionScheduler.workerFailureOrNull();
        if (failure == null) failure = new RejectedExecutionException("unaddressed task has no live region scheduler");
        FailStops.<RuntimeException>rethrow(RegionScheduler.cancelRejectedRoot(null, task, failure));
    }

    private static void submit(ServerLevel level, ChunkPos pos, Entity entity, Runnable task, boolean shared) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(level, "level");
        FlatOwnerTasks generation = live;
        Throwable failure = RegionScheduler.workerFailureOrNull();
        boolean inline = false;
        try {
            if (failure == null && generation != null && generation.server == level.getServer()) {
                MinecraftServer server = generation.server;
                boolean main = RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
                // An accepted transfer/reward can call back after region deactivation but
                // before its runtime reference is cleared. Only its actual Main owner can pass.
                boolean continuation = main && (generation.inAcceptedBody || AdvancementBatchScope.rewardFunctionActive()
                        || NetherPortalTransfers.currentTransfer() != null && NetherPortalTransfers.owns(level,
                            entity == null ? pos.getWorldPosition() : entity.blockPosition()));
                synchronized (generation.pending) {
                    if ((!shared || RegionScheduler.acceptsSharedOwnerTasks())
                            && (continuation || !generation.closed
                            && (RegionScheduler.getServer() == null || RegionScheduler.isActive()))) {
                        if (!main || shared) {
                            generation.enqueueLocked(generation.new Delivery(level, pos, entity, task), shared);
                            return;
                        }
                        inline = true;
                    }
                }
                if (inline) inline = server.getLevel(level.dimension()) == level;
            }
        } catch (Throwable unexpected) {
            failure = RegionScheduler.publishFatalAndSelectWinner(unexpected);
        }
        if (inline && failure == null) {
            RegionScheduler.runRootInlineIfHealthy(task);
            return;
        }
        Throwable winner = RegionScheduler.workerFailureOrNull();
        if (winner != null) failure = FailStops.mergeFailureCapped(winner, failure);
        if (failure == null) failure = new RejectedExecutionException("flat owner dispatch has no live target");
        FailStops.<RuntimeException>rethrow(RegionScheduler.cancelRejectedRoot(null, task, failure));
    }

    private void enqueueLocked(Delivery delivery, boolean shared) {
        try {
            pending.add(delivery);
            if (shared) {
                if (lastShared == null) firstShared = delivery;
                else lastShared.nextShared = delivery;
                lastShared = delivery;
            } else server.schedule(server.wrapRunnable(delivery));
        } catch (Throwable thrown) {
            // Even enqueue-then-throw cannot give a queued native copy the body.
            pending.remove(delivery);
            delivery.clear();
            throw thrown;
        }
    }

    /** Called only on physical Main after both region pool references have retired. */
    static boolean ownsFinalCallback(ServerLevel level) {
        FlatOwnerTasks generation = live;
        if (generation == null || !generation.inAcceptedBody || generation.server != level.getServer()) return false;
        var helpers = com.axalotl.fabrolia.common.ParallelProcessor.executor;
        return (helpers == null || helpers.isTerminated()) && generation.server.getLevel(level.dimension()) == level;
    }

    /** A complete Main tick has offered the waiting cut. Only new multi-owner windows
     * yield; an entered window keeps every claim it needs to finish, and independent
     * units do not consult this flag. Repeated overlapping windows cannot starve Main. */
    static boolean newContainerWindowMustYield() {
        FlatOwnerTasks generation = live;
        return generation != null && generation.callbackCutWaiting;
    }

    /** One finite FIFO prefix at the physical post-tick boundary, never from a worker wait.
     * The next producer can append while a callback runs; it does not extend this cut. */
    static void drainSharedAtTickBoundary(MinecraftServer server) {
        FlatOwnerTasks generation = live;
        if (generation == null || generation.firstShared == null) return;
        if (generation.server != server || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException("shared owner dispatch outside its server");
        }
        synchronized (generation.pending) {
            if (generation.closed || generation.firstShared == null) return;
            generation.callbackCutWaiting = true;
        }
        RegionScheduler.runPostTickCallbackTurn(generation::deliverSharedCut);
    }

    /** Runtime /regions off: accepting is already false. Taking the admission lock pairs with
     * the last producer which could have read true. A whole-server stop settles after both joins. */
    static void drainBeforeRegionStop(MinecraftServer server) {
        FlatOwnerTasks generation = live;
        if (generation == null || generation.server != server) return;
        synchronized (generation.pending) {
            if (generation.closed || generation.firstShared == null) return;
        }
        RegionScheduler.runSharedClockTurn(generation::deliverSharedCut);
    }

    private void deliverSharedCut() {
        Throwable failure = settleSharedCut(null);
        if (failure != null) FailStops.<RuntimeException>rethrow(failure);
    }

    /** Called after region admission closes, including rollback of an already published start. */
    static Throwable cancelFailedRegionCut(MinecraftServer server, Throwable failure) {
        FlatOwnerTasks generation = live;
        if (generation == null || generation.server != server) return failure;
        return generation.settleSharedCut(Objects.requireNonNull(failure));
    }

    private Throwable settleSharedCut(Throwable failure) {
        Delivery next;
        synchronized (pending) {
            callbackCutWaiting = false;
            if (closed && failure == null) return null; // whole-server stop settles after both joins
            next = firstShared;
            firstShared = lastShared = null;
        }
        while (next != null) {
            Delivery current = next;
            next = current.nextShared;
            current.nextShared = null;
            synchronized (pending) {
                if (!pending.remove(current)) continue; // already settled by a reentrant stop
            }
            try {
                if (failure == null) current.deliver();
                else failure = RegionScheduler.cancelRejectedRoot(null, current.takeTask(), failure);
            } catch (Throwable thrown) {
                failure = FailStops.mergeFailureCapped(failure, thrown);
            } finally { current.clear(); }
        }
        return failure;
    }

    public static void closeAdmission() {
        FlatOwnerTasks generation = live;
        if (generation != null) synchronized (generation.pending) {
            generation.closed = true;
            generation.callbackCutWaiting = false;
        }
    }

    /** Both worker pools have finished. Settle accepted callbacks before vanilla saves the world. */
    public static Throwable finish(Throwable failure) {
        FlatOwnerTasks generation = live;
        if (generation == null) return failure;
        if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(generation.server)) {
            throw new IllegalStateException("flat owner dispatch shutdown requires physical Main");
        }
        synchronized (generation.pending) {
            generation.closed = true;
            generation.callbackCutWaiting = false;
            generation.firstShared = generation.lastShared = null;
        }
        while (true) {
            Delivery delivery;
            synchronized (generation.pending) {
                if (generation.pending.isEmpty()) break;
                delivery = generation.pending.removeFirst();
            }
            Throwable winner = RegionScheduler.workerFailureOrNull();
            if (winner != null) failure = FailStops.mergeFailureCapped(winner, failure);
            try {
                if (failure == null) delivery.deliver();
                else failure = RegionScheduler.cancelRejectedRoot(null, delivery.takeTask(), failure);
            } catch (Throwable thrown) {
                failure = FailStops.mergeFailureCapped(failure, thrown);
            } finally {
                delivery.clear();
            }
        }
        return failure;
    }

    /** The final advancement/portal drains can still call an owner synchronously.
     * Retire after those continuations, without reopening foreign publication. */
    public static void retire() {
        FlatOwnerTasks generation = live;
        if (generation == null) return;
        synchronized (generation.pending) {
            if (!generation.closed || !generation.pending.isEmpty() || generation.inAcceptedBody) {
                throw new IllegalStateException("flat owner dispatch retired before settlement");
            }
            live = null;
        }
    }

    private final class Delivery implements Runnable {
        private ServerLevel level;
        private ChunkPos pos;
        private Entity entity;
        private Runnable task;
        private Delivery nextShared;

        Delivery(ServerLevel level, ChunkPos pos, Entity entity, Runnable task) {
            this.level = level;
            this.pos = pos;
            this.entity = entity;
            this.task = task;
        }

        @Override public void run() {
            synchronized (pending) {
                if (closed || !pending.remove(this)) return; // shutdown owns this receipt
            }
            try { deliver(); } finally { clear(); }
        }

        private void deliver() {
            try {
                deliverToOwner();
            } catch (Throwable unexpected) {
                Throwable winner = RegionScheduler.publishFatalAndSelectWinner(unexpected);
                Runnable unstarted = takeTask();
                if (unstarted != null) winner = RegionScheduler.cancelRejectedRoot(null, unstarted, winner);
                FailStops.<RuntimeException>rethrow(winner);
            }
        }

        private void deliverToOwner() {
            if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
                throw new IllegalStateException("flat owner dispatch consumed off physical Main");
            }
            if (level == null) { // unaddressed receipt, never placed on vanilla's FIFO
                if (RegionScheduler.isActive() && !RegionScheduler.ownsExactExecutionTurn()) {
                    throw new IllegalStateException("unaddressed callback without exclusive Main turn");
                }
                runMainBody();
                return;
            }
            ServerLevel target = entity == null ? level
                    : entity.level() instanceof ServerLevel current ? current : null;
            if (target == null || target.getServer() != server || server.getLevel(target.dimension()) != target) {
                // The original level can unload while the native task waits. Its replacement
                // must not inherit the body; a lost target is not a failure of other levels.
                if (takeTask() instanceof RegionTaskFanIn.CancellableQueueEntry cancellable) {
                    Throwable winner = RegionScheduler.workerFailureOrNull();
                    cancellable.cancelQueuedBeforeStartWithFailure(winner != null ? winner :
                            new RejectedExecutionException("flat owner target left its server"));
                }
                return;
            }
            if (!RegionScheduler.isActive()) {
                runMainBody();
                return;
            }
            // /regions on won before this native queue entry. Main alone is no longer the owner.
            Runnable routed = task instanceof RegionTaskFanIn.CancellableQueueEntry cancellable
                    ? RegionScheduler.cancellableRoot(task, cancellable::cancelQueuedBeforeStartWithFailure) : task;
            task = null; // the region route is now responsible for cancellation
            try {
                if (entity == null) RegionScheduler.executeAt(target, pos, routed);
                else RegionScheduler.executeForEntity(entity, routed);
            } catch (Throwable failure) {
                Throwable winner = RegionScheduler.publishFatalAndSelectWinner(failure);
                FailStops.<RuntimeException>rethrow(RegionScheduler.cancelRejectedRoot(null, routed, winner));
            }
        }

        private void runMainBody() {
            boolean stopBody = closed;
            if (stopBody) inAcceptedBody = true;
            try {
                RegionScheduler.runRootInlineIfHealthy(takeTask());
            } finally {
                if (stopBody) inAcceptedBody = false;
            }
        }

        private Runnable takeTask() { Runnable body = task; task = null; return body; }
        private void clear() { level = null; pos = null; entity = null; task = null; }
    }
}
