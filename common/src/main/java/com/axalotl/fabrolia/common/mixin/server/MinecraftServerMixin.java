package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RconCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.notifications.ServerActivityMonitor;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.debug.ServerDebugSubscribers;
import net.minecraft.world.clock.ServerClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Mixin(value = MinecraftServer.class)
public class MinecraftServerMixin {

    @WrapOperation(
            method = "lambda$spin$0(Ljava/util/concurrent/atomic/AtomicReference;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;runServer()V"),
            require = 1,
            allow = 1)
    private static void fabrolia$markExactServerLoopRoot(
            MinecraftServer server, Operation<Void> original) {
        RconCommandFenceScope.runServerLoopRoot(
                server, () -> original.call(server));
    }

    @WrapMethod(method = "runServer()V", require = 1, allow = 1)
    private void fabrolia$frameExactServerLoopEntry(Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        RconCommandFenceScope.runServerEntry(server, () -> original.call());
    }

    @WrapOperation(
            method = "runServer()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "waitUntilNextTick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactTopLevelWait(
            MinecraftServer server, Operation<Void> original) {
        RconCommandFenceScope.runServerWaitUntilNextTick(
                server, () -> original.call(server));
    }

    @WrapMethod(method = "waitUntilNextTick()V", require = 1, allow = 1)
    private void fabrolia$frameExactTopLevelWaitEntry(Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        RconCommandFenceScope.runWaitUntilNextTickEntry(
                server, () -> original.call());
    }

    @WrapOperation(
            method = "waitUntilNextTick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;runAllTasks()V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactTopLevelRunAllTasks(
            MinecraftServer server, Operation<Void> original) {
        RconCommandFenceScope.runWaitUntilNextTickRunAllTasks(
                server, () -> original.call(server));
    }

    @WrapOperation(
            method = "waitUntilNextTick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "managedBlock(Ljava/util/function/BooleanSupplier;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$markExactTopLevelManagedBlock(
            MinecraftServer server, BooleanSupplier done,
            Operation<Void> original) {
        RconCommandFenceScope.runWaitUntilNextTickManagedBlock(
                server, () -> original.call(server, done));
    }

    @WrapOperation(
            method = "processPacketsAndTick(Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "tickServer(Ljava/util/function/BooleanSupplier;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$drainDeferredLifecycleAfterTickServer(
            MinecraftServer server, BooleanSupplier haveTime,
            Operation<Void> original) {
        // Never put this in a finally: a failed vanilla tick selects fail-stop
        // and cannot expose a queued lifecycle transition as later success.
        original.call(server, haveTime);
        // The complete tickServer stack has returned. In normal ticks that also
        // means tickChildren, its command fences, and every stage ExternalPermit;
        // the empty-server pause branch is covered even though it skips
        // tickChildren and runs tickConnection directly.
        RegionScheduler.drainDeferredLifecycleTransitionsAtPostTickBoundary(server);
    }

    @Inject(method = "pollTask()Z", at = @At("HEAD"), require = 1)
    private void fabrolia$failStopBeforeNextMainTask(CallbackInfoReturnable<Boolean> cir) {
        // BlockableEventLoop#doRunTask logs and swallows ordinary task
        // Exceptions.  The next poll is the first boundary outside that catch:
        // fail here before MinecraftServer removes or starts another queued task.
        RegionScheduler.throwWorkerFailureIfAny();
    }

    @Inject(method = "getRunningThread()Ljava/lang/Thread;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void fabrolia$logicalPlayerActorThread(CallbackInfoReturnable<Thread> cir) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        Thread actor = RegionalPlayerEntityTicksV2.logicalServerThread(self);
        if (actor != null) cir.setReturnValue(actor);
    }

    @WrapMethod(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V")
    private void fabrolia$regionalPlayerServerPhase(BooleanSupplier haveTime,
                                                     Operation<Void> original) {
        if (!RegionalPlayerEntityTicksV2.enabled()) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(haveTime));
            return;
        }
        MinecraftServer self = (MinecraftServer) (Object) this;
        boolean[] phaseBegan = new boolean[1];
        boolean[] completed = new boolean[1];
        boolean bodyReturned = false;
        Throwable failure = null;
        try {
            // This short permit is the exact root-entry cut. It deliberately
            // ends after beginServerTick: tickChildren later requests the exact
            // pool fixed point, and retaining our own external permit across
            // that wait would make the root wait on itself forever.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                RegionalPlayerEntityTicksV2.beginServerTick(self);
                phaseBegan[0] = true;
            });
            original.call(haveTime);
            bodyReturned = true;
        } catch (Throwable thrown) {
            // Whether phase setup, its terminal admission, or vanilla failed,
            // select exact W before any phase fence can be released.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (failure == null && bodyReturned) {
            try {
                // Atomically decide normal finish versus fail-stop abort. A W
                // which won before this cut keeps the exact phase fence held.
                RegionScheduler.commitSuccessIfHealthy(() -> completed[0] = true);
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (phaseBegan[0]) {
            Throwable cleanup = null;
            try {
                // A successful body retains the final worker fence through connection,
                // PlayerList and chunk-send tails, then releases it here. An exceptional
                // body seals/awaits every accepted obligation before preserving the
                // original vanilla crash as primary.
                cleanup = completed[0]
                        ? RegionalPlayerEntityTicksV2.finishServerTick()
                        : RegionalPlayerEntityTicksV2.abortServerTick();
            } catch (Throwable cleanupFailure) {
                cleanup = cleanupFailure;
            }
            if (cleanup != null) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        fabrolia$mergeFailure(failure, cleanup));
            }
        }
        if (failure == null) {
            try {
                // finish/abort is mandatory cleanup, not itself a normal-return
                // edge. Refuse success if peer W won during that cleanup.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            MinecraftServerMixin.<RuntimeException>fabrolia$throwUnchecked(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$throwUnchecked(Throwable thrown) throws T {
        throw (T) thrown;
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Physical Main is already fail-stop with primary; suppression
                // is diagnostic and must never replace the vanilla crash.
            }
        }
        return primary;
    }

    /*
     * The enabled A5 path cannot retain one external success-body permit around
     * all of tickChildren: ServerLevel's exact-ordinal fence and the connection
     * fixed point must be allowed to await the pool without waiting on that same
     * permit.  Each otherwise-raw top-level vanilla stage therefore gets its own
     * W-vs-entry cut. ServerFunctionManager.tick and ServerLevel.tick segment
     * themselves because their internal cleanup/fixed-point boundaries cannot
     * share a caller-owned long permit.
     */

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitSuspendFlushing(
            List<ServerPlayer> players, Consumer<? super ServerPlayer> action,
            Operation<Void> original) {
        Consumer<ServerPlayer> admitted = player ->
                RegionScheduler.runSuccessBodyIfHealthy(() -> action.accept(player));
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(players, admitted));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/clock/ServerClockManager;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitClockTick(
            ServerClockManager instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "forceGameTimeSynchronization()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitGameTimeSynchronization(
            MinecraftServer instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "updateEffectiveRespawnData()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitRespawnDataUpdate(
            MinecraftServer instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"),
            require = 1,
            allow = 1)
    private void fabrolia$assertPlayerBarrierBeforeConnections(
            MinecraftServer instance, Operation<Void> original) {
        RegionScheduler.throwWorkerFailureIfAny();
        RegionalPlayerEntityTicksV2.beforeConnections(instance);
        // The barrier above acquires the exact-turn fixed point. Its permit must
        // not include that wait; only the subsequent vanilla connection body is
        // atomically admitted against a W published after the barrier returned.
        Runnable connectionBody = () -> original.call(instance);
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (instance instanceof net.minecraft.server.dedicated.DedicatedServer) {
                com.axalotl.fabrolia.common.regions.ConsoleCommandFenceScope
                        .runDedicatedTickConnection(
                                instance, connectionBody);
            } else {
                connectionBody.run();
            }
        });
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitPlayerListTick(
            PlayerList instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/debug/ServerDebugSubscribers;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitDebugSubscribersTick(
            ServerDebugSubscribers instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/GameTestTicker;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitGameTestTick(
            GameTestTicker instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitTickable(
            Runnable instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;"
                            + "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitSendNextChunks(
            PlayerChunkSender instance, ServerPlayer player,
            Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> original.call(instance, player));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/"
                            + "ServerGamePacketListenerImpl;resumeFlushing()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitResumeFlushing(
            ServerGamePacketListenerImpl instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    @WrapOperation(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/notifications/ServerActivityMonitor;tick()V"),
            require = 1,
            allow = 1)
    private void fabrolia$admitActivityMonitorTick(
            ServerActivityMonitor instance, Operation<Void> original) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(instance));
    }

    /**
     * Keep the all-island receipt through server-global SavedDataStorage
     * encoding and dirty-bit retirement, after the nested level saves return.
     *
     * <p>The quiesce covers only snapshots: level.dat is queued on the save
     * writer like the player files of the preceding saveAll. A flushing save
     * ({@code save-all flush}, shutdown) waits for every queued save file after
     * the islands run again.
     */
    @WrapMethod(method = "saveAllChunks(ZZZ)Z", require = 1, allow = 1)
    private boolean fabrolia$quiesceIslandsForServerSave(
            boolean silent, boolean flush, boolean force,
            Operation<Boolean> original) {
        boolean took = IslandCommands.quiesceAllExcept(null);
        boolean result = false;
        Throwable failure = null;
        try {
            result = RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> com.axalotl.fabrolia.common.save.SaveDurability.callDeferred(
                            () -> original.call(silent, flush, force)));
        } catch (Throwable primary) {
            failure = RegionScheduler.publishFatalAndSelectWinner(primary);
        }
        if (took) {
            IslandCommands.releaseAllExceptAndPropagate(failure);
        } else if (failure != null) {
            // Borrowed ownership belongs to the outer caller, even on failure.
            IslandCommands.publishAndPropagate(failure);
        } else {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
        }
        if (flush) {
            com.axalotl.fabrolia.common.save.AsyncSaveWriter.INSTANCE.awaitAll();
        }
        return result;
    }

    /**
     * Shutdown barrier for every queued save file. The normal path already
     * drained in saveAllChunks(flush); this covers a stop that failed before it.
     */
    @WrapMethod(method = "stopServer()V", require = 1, allow = 1)
    private void fabrolia$awaitSaveFilesOnStop(Operation<Void> original) {
        Throwable failure = null;
        try {
            original.call();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        try {
            com.axalotl.fabrolia.common.save.AsyncSaveWriter.INSTANCE.awaitAll();
        } catch (Throwable writeFailure) {
            if (failure == null) failure = writeFailure;
            else if (writeFailure != failure) failure.addSuppressed(writeFailure);
        }
        if (failure != null) {
            MinecraftServerMixin.<RuntimeException>fabrolia$throwUnchecked(failure);
        }
    }

    @WrapMethod(method = "reloadResources")
    private java.util.concurrent.CompletableFuture<Void> fabrolia$reloadOnPhysicalMain(
            java.util.Collection<String> packs,
            Operation<java.util.concurrent.CompletableFuture<Void>> original) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(self)
                || !self.isSameThread()) {
            return original.call(packs);
        }
        // An exact player actor is logically same-thread, so vanilla's executor
        // would otherwise run reload preparation/application inline on that actor.
        // Return the future, never wait for physical Main while it awaits this turn.
        java.util.List<String> selected = java.util.List.copyOf(packs);
        java.util.concurrent.CompletableFuture<Void> result =
                new java.util.concurrent.CompletableFuture<>();
        RegionScheduler.enqueueGlobalMainOnly(RegionScheduler.cancellableRoot(() -> {
            try {
                original.call(selected).whenComplete((ignored, failure) -> {
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                throw failure;
            }
        }, result::completeExceptionally));
        return result;
    }

    @Redirect(method = "reloadResources", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean onServerExecutionThreadPatch(MinecraftServer minecraftServer) {
        // managedBlock consumes the server queue. Logical player-actor identity
        // grants gameplay authority, never permission to pump physical-Main work.
        return RegionalPlayerEntityTicksV2.isPhysicalServerThread(minecraftServer);
    }
}
