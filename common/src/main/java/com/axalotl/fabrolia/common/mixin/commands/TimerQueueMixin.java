package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionFenceDiag;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandFunctionHead;
import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.timers.FunctionCallback;
import net.minecraft.world.level.timers.FunctionTagCallback;
import net.minecraft.world.level.timers.TimerCallback;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Queue;

/**
 * Grants the unbounded function-fence capability only at vanilla's exact
 * scheduled FunctionCallback/FunctionTagCallback dispatch edge. Custom timer
 * callbacks keep the bounded unknown-caller path even if they invoke a
 * function while holding a mod monitor.
 *
 * <p>A tick with two or more due Main-resident function callbacks takes ONE
 * global fence around the whole {@code tick} instead of one per callback: the
 * nested per-function fence sees the held window and runs inline. A single due
 * callback keeps its own per-function fence, so nothing changes below the
 * point where a merge saves a quiesce.
 *
 * <p>A due callback of a partitioned 1t self-rearming loop never executes the
 * function here: {@link IslandFunctionHead#dispatchLoopCallback} re-arms it
 * through its exact vanilla {@code schedule} line, arms it for the island
 * heads and runs Main's remainder partition — no fence. Such callbacks do not
 * count towards the shared fence. Island function bodies never touch the
 * queue (guarded).
 */
@Mixin(TimerQueue.class)
public abstract class TimerQueueMixin<T> {

    @Shadow @Final private Queue<TimerQueue.Event<T>> queue;

    @WrapMethod(method = "tick")
    private void fabrolia$fenceDueFunctionsOnce(T context, long currentTick, Operation<Void> original) {
        if (!(context instanceof MinecraftServer server)
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            original.call(context, currentTick);
            return;
        }
        IslandFunctionHead.beginTimerTick();
        try {
            fabrolia$tickServerQueue(server, context, currentTick, original);
        } finally {
            IslandFunctionHead.endTimerTick(server);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$tickServerQueue(MinecraftServer server, T context, long currentTick,
                                          Operation<Void> original) {
        if (!IslandCommands.requiresMainFence()
                || IslandCommands.fenceWindowHeld()
                || fabrolia$dueFunctionCallbacks(server, currentTick) < 2) {
            original.call(context, currentTick);
            return;
        }
        // Same clean Main root as each callback's own unbounded fence; the
        // release is mandatory cleanup behind the exact vanilla primary.
        boolean took = IslandCommands.quiesceAllExcept(null);
        if (!took) {
            // No-claims return is legitimate when the last async level went
            // inactive between the gate above and the acquisition; each
            // callback then keeps its own (now unfenced) vanilla path.
            if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
                IslandCommands.publishAndPropagate(new IllegalStateException(
                        "could not fence the scheduled-function dispatch at game time " + currentTick));
                return;
            }
            original.call(context, currentTick);
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(() -> {
                IslandCommands.SCHEDULED_WINDOWS.increment();
                FunctionFenceDiag.TIMER_DUE_FENCES.increment();
            });
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(context, currentTick));
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    /** Due Main-resident function callbacks (island loops excluded). Due events
     *  are the heap prefix; the heap itself is unordered, so count all once the
     *  head is due. */
    @org.spongepowered.asm.mixin.Unique
    private int fabrolia$dueFunctionCallbacks(MinecraftServer server, long currentTick) {
        TimerQueue.Event<T> head = this.queue.peek();
        if (head == null || head.triggerTime() > currentTick) return 0;
        int due = 0;
        for (TimerQueue.Event<T> event : this.queue) {
            if (event.triggerTime() > currentTick) continue;
            TimerCallback<T> callback = event.callback();
            boolean mainResident = callback instanceof FunctionTagCallback
                    || (callback instanceof FunctionCallback fc
                            && !IslandFunctionHead.islandLoop(server, fc.functionId()));
            if (mainResident && ++due >= 2) return due;
        }
        return due;
    }

    /** Island function bodies never touch the server timer queue. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "schedule", at = @At("HEAD"))
    private void fabrolia$guardSchedule(String id, long time, TimerCallback<T> callback,
                                        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        IslandFunctionScope.forbid("TimerQueue.schedule(" + id + ")");
        Object self = this;
        if (self instanceof TimerQueue<?> q && fabrolia$serverQueue(q)) {
            IslandFunctionHead.timerEventScheduled(id);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "remove", at = @At("HEAD"))
    private void fabrolia$guardRemove(String id,
                                      org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        IslandFunctionScope.forbid("TimerQueue.remove(" + id + ")");
        Object self = this;
        if (self instanceof TimerQueue<?> q && fabrolia$serverQueue(q)) {
            IslandFunctionHead.timerEventRemoved(id);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$serverQueue(TimerQueue<?> q) {
        var server = RegionScheduler.getServer();
        return server != null && server.getScheduledEvents() == q
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/timers/TimerCallback;handle("
                            + "Ljava/lang/Object;"
                            + "Lnet/minecraft/world/level/timers/TimerQueue;J)V"),
            require = 1,
            allow = 1)
    private void fabrolia$markScheduledFunctionCallback(
            TimerCallback<T> callback, T context, TimerQueue<T> queue,
            long triggerTime, Operation<Void> original) {
        if (callback instanceof FunctionCallback fc && context instanceof MinecraftServer server
                && IslandFunctionHead.dispatchLoopCallback(server, fc.functionId())) {
            return;
        }
        if (callback instanceof FunctionCallback
                || callback instanceof FunctionTagCallback) {
            IslandCommands.runMainScheduledFunctionCallback(
                    () -> original.call(callback, context, queue, triggerTime));
            return;
        }
        original.call(callback, context, queue, triggerTime);
    }
}
