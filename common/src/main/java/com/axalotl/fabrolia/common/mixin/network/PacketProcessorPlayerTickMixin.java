package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PacketUtils delegates its thread decision to PacketProcessor, whose captured
 * runningThread is the physical server thread. During the exclusive player actor
 * turn that actor is the logical server owner, so packet handlers reached
 * synchronously from the transformed tick must remain inline at the same ordinal.
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorPlayerTickMixin {

    /** The server packet queue is independent of MinecraftServer's task queue. */
    @WrapMethod(method = "processQueuedPackets()V", require = 1, allow = 1)
    private void fabrolia$ownServerPacketQueue(Operation<Void> original) {
        var server = ParallelProcessor.getServer();
        if (server != null && server.packetProcessor() == (Object) this) {
            RegionScheduler.runGlobalMainBody(server, () -> original.call());
        } else {
            original.call();
        }
    }

    @ModifyReturnValue(method = "isSameThread()Z", at = @At("RETURN"), require = 1)
    private boolean fabrolia$logicalPlayerActorThread(boolean original) {
        return original || RegionalPlayerEntityTicksV2.inExactPlayerActor()
                || AsyncIslandPlayerTicks.inPacketReplay()
                || RegionScheduler.exclusiveGameplayWorkerOrNull(ParallelProcessor.getServer())
                == Thread.currentThread();
    }
}
