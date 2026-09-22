package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.timers.FunctionCallback;
import net.minecraft.world.level.timers.FunctionTagCallback;
import net.minecraft.world.level.timers.TimerCallback;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Grants the unbounded function-fence capability only at vanilla's exact
 * scheduled FunctionCallback/FunctionTagCallback dispatch edge. Custom timer
 * callbacks keep the bounded unknown-caller path even if they invoke a
 * function while holding a mod monitor.
 */
@Mixin(TimerQueue.class)
public abstract class TimerQueueMixin<T> {

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
