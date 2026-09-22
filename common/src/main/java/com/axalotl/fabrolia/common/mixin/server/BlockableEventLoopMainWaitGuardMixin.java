package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.RegionMainBlockingWaitGuard;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RconCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards the declaration-level server queue wait without affecting other loops. */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMainWaitGuardMixin implements RconCommandFenceScope.NativeBlockingDepth {

    @Shadow private int blockingCount;

    @Override
    public int fabrolia$nativeBlockingDepth() {
        return blockingCount;
    }

    @WrapOperation(
            method = "runAllTasks()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/BlockableEventLoop;pollTask()Z"),
            require = 1, allow = 1)
    private boolean fabrolia$frameRunAllTasksPoll(
            BlockableEventLoop<?> loop, Operation<Boolean> original) {
        if (loop instanceof MinecraftServer server) {
            return RconCommandFenceScope.callRunAllTasksPoll(
                    server, () -> original.call(loop));
        }
        return original.call(loop);
    }

    @WrapOperation(
            method = "managedBlock(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/BlockableEventLoop;pollTask()Z"),
            require = 1, allow = 1)
    private boolean fabrolia$frameManagedBlockPoll(
            BlockableEventLoop<?> loop, Operation<Boolean> original) {
        if (loop instanceof MinecraftServer server) {
            return RconCommandFenceScope.callManagedBlockPoll(
                    server, () -> original.call(loop));
        }
        return original.call(loop);
    }

    /** Acquire gameplay authority before a queued callback enters any native monitor. */
    @WrapOperation(
            method = {
                    "doRunTask(Ljava/lang/Runnable;)V",
                    "submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
                    "executeBlocking(Ljava/lang/Runnable;)V"
            },
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 3,
            allow = 3)
    private void fabrolia$ownMainTask(Runnable task, Operation<Void> original) {
        if ((Object) this instanceof MinecraftServer server) {
            if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                    && RegionScheduler.ownsExclusiveGameplay()
                    && (RegionalPlayerEntityTicksV2.inExactPlayerActor()
                        || RegionScheduler.exclusiveGameplayWorkerOrNull(server)
                        == Thread.currentThread())) {
                // Logical-owner execute may call doRunTask inline. This is the
                // already-selected body, never a migration or a new Main permit.
                try {
                    RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(task));
                } catch (Throwable failure) {
                    IslandCommands.publishAndPropagate(failure);
                }
            } else if (RegionScheduler.isMainControlTask(task)) {
                RegionScheduler.runMainControlBody(server, () -> original.call(task));
            } else {
                RegionScheduler.runGlobalMainBody(server, () -> original.call(task));
            }
        } else if ((Object) this instanceof RegionScheduler.NativeChunkServiceExecutor) {
            // C2ME also polls the inner executor directly, bypassing cache.pollTask.
            RegionScheduler.runNativeMainServiceBody(() -> original.call(task));
        } else {
            original.call(task);
        }
    }

    @WrapOperation(
            method = "submit(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"),
            require = 1, allow = 1)
    private Object fabrolia$ownInlineSupplier(
            java.util.function.Supplier<?> task, Operation<Object> original) {
        if ((Object) this instanceof MinecraftServer server) {
            if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
                return RegionScheduler.callGlobalMainBody(server, () -> original.call(task));
            }
            try {
                if (!RegionScheduler.ownsExclusiveGameplay()
                        || !(RegionalPlayerEntityTicksV2.inExactPlayerActor()
                        || RegionScheduler.exclusiveGameplayWorkerOrNull(server)
                        == Thread.currentThread())) {
                    throw new IllegalStateException("inline server supplier has no selected gameplay owner");
                }
                RegionScheduler.throwWorkerFailureIfAny();
                Object result = original.call(task);
                RegionScheduler.throwWorkerFailureIfAny();
                return result;
            } catch (Throwable failure) {
                return BlockableEventLoopMainWaitGuardMixin
                        .<Object, RuntimeException>fabrolia$throwInlineFailure(failure);
            }
        }
        if ((Object) this instanceof RegionScheduler.NativeChunkServiceExecutor) {
            return RegionScheduler.callNativeMainServiceBody(() -> original.call(task));
        }
        return original.call(task);
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T fabrolia$throwInlineFailure(Throwable failure) throws E {
        throw (E) RegionScheduler.publishFatalAndSelectWinner(failure);
    }

    @Inject(
            method = "executeBlocking(Ljava/lang/Runnable;)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private void fabrolia$failBeforeCausalServerMainWait(
            Runnable task, CallbackInfo ci) {
        Object receiver = this;
        if (receiver instanceof MinecraftServer server
                && !server.isSameThread()) {
            RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates(
                    "MinecraftServer.executeBlocking(Runnable)");
        }
    }
}
