package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A8 — the single choke point of physical command execution
 * (CommandBlockEntity impulse/chain/repeat AND command-block minecarts both
 * funnel through {@link BaseCommandBlock#performCommand}).
 *
 * <p>Island coordinator context: route per invocation — proven island-local
 * commands run INLINE (owner thread, island cadence, island clock);
 * everything else executes exactly once on the fenced server thread while
 * this island's chain blocks for the vanilla boolean result (conditional
 * chains, success counts and comparator outputs keep exact values). Main
 * context (minecarts on the sync lane, console-triggered): the vanilla body
 * runs here and the nested dispatch is fenced by CommandsMixin.
 */
@Mixin(BaseCommandBlock.class)
public abstract class BaseCommandBlockMixin {

    @WrapMethod(method = "performCommand")
    private boolean fabrolia$islandCommandRoute(ServerLevel level, Operation<Boolean> original) {
        BaseCommandBlock self = (BaseCommandBlock) (Object) this;
        // The source stack is exactly what vanilla builds inside performCommand;
        // locality proofs need its position, shard resolution needs the full
        // context (position + rotation for @p-style sorting).
        net.minecraft.commands.CommandSourceStack stack =
                self.createCommandSourceStack(level, net.minecraft.commands.CommandSource.NULL);
        BlockPos src = BlockPos.containing(stack.getPosition());
        return IslandCommands.routeCommandBlock(self.getCommand(), level, src, stack,
                () -> original.call(level));
    }
}
