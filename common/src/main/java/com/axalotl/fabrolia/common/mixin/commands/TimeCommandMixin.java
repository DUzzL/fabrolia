package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandClock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code time query gametime} inside a routed island body (a command block or
 * an escalated function line that executes on Main for an island) answers the
 * routing island's clock L, as the same command does when it runs inline on
 * the island: consecutive executions of one block differ by exactly one island
 * tick, like vanilla's per-tick delta. Outside a routed island body the read is
 * vanilla (Main: W; island worker: L via the getGameTime redirect).
 *
 * <p>Deliberate boundary, same as on an island head: {@code schedule} still
 * queues on the W-ticked server TimerQueue, so a datapack that compares a
 * stored gametime with a scheduled callback's gametime compares L with W.
 */
@Mixin(TimeCommand.class)
public abstract class TimeCommandMixin {

    @WrapOperation(method = "queryGameTime",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getGameTime()J"),
            require = 1, allow = 1)
    private static long fabrolia$routedOwnerGameTime(ServerLevel level, Operation<Long> original) {
        long owner = IslandClock.routedOwnerTime();
        return owner >= 0L ? owner : original.call(level);
    }
}
