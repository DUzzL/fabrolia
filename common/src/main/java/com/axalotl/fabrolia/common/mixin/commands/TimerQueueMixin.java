package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
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
 * <p>A tick with two or more due function callbacks takes ONE global fence around
 * the whole {@code tick} instead of one per callback: the nested per-function
 * fence sees the held window and runs inline. A single due callback keeps its own
 * per-function fence, so nothing changes below the point where a merge saves a
 * quiesce.
 */
@Mixin(TimerQueue.class)
public abstract class TimerQueueMixin<T> {

    @Shadow @Final private Queue<TimerQueue.Event<T>> queue;

    @WrapMethod(method = "tick")
    private void fabrolia$fenceDueFunctionsOnce(T context, long currentTick, Operation<Void> original) {
        if (!(context instanceof MinecraftServer server)
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                || !IslandCommands.requiresMainFence()
                || IslandCommands.fenceWindowHeld()
                || fabrolia$dueFunctionCallbacks(currentTick) < 2) {
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
            RegionScheduler.commitSuccessIfHealthy(IslandCommands.SCHEDULED_WINDOWS::increment);
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call(context, currentTick));
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    /** Due events are the heap prefix; the heap itself is unordered, so count all
     *  once the head is due. */
    @org.spongepowered.asm.mixin.Unique
    private int fabrolia$dueFunctionCallbacks(long currentTick) {
        TimerQueue.Event<T> head = this.queue.peek();
        if (head == null || head.triggerTime() > currentTick) return 0;
        int due = 0;
        for (TimerQueue.Event<T> event : this.queue) {
            if (event.triggerTime() <= currentTick
                    && (event.callback() instanceof FunctionCallback
                            || event.callback() instanceof FunctionTagCallback)
                    && ++due >= 2) {
                return due;
            }
        }
        return due;
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
        if (callback instanceof FunctionCallback
                || callback instanceof FunctionTagCallback) {
            IslandCommands.runMainScheduledFunctionCallback(
                    () -> original.call(callback, context, queue, triggerTime));
            return;
        }
        original.call(callback, context, queue, triggerTime);
    }
}
