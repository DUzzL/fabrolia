package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaThreadNeighborUpdaters;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes ServerLevel's four neighbour-update entry points to the calling
 * thread's vanilla updater (see {@link FabroliaThreadNeighborUpdaters}).
 * {@code tick}'s two {@code setDebugListener} reads stay on the primary.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelThreadNeighborUpdaterMixin {

    @ModifyExpressionValue(method = {
                    "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
                            + "Lnet/minecraft/world/level/redstone/Orientation;)V",
                    "updateNeighborsAtExceptFromFacing",
                    "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
                            + "Lnet/minecraft/world/level/redstone/Orientation;)V",
                    "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
                            + "Lnet/minecraft/world/level/redstone/Orientation;Z)V"},
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/level/ServerLevel;neighborUpdater:"
                            + "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;",
                    opcode = Opcodes.GETFIELD),
            require = 4,
            allow = 4)
    private CollectingNeighborUpdater fabrolia$neighborUpdateOnThreadUpdater(CollectingNeighborUpdater primary) {
        return ((FabroliaThreadNeighborUpdaters) this).fabrolia$threadNeighborUpdater();
    }
}
