package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PacketUtils delegates its thread decision to PacketProcessor, whose captured
 * runningThread is the physical server thread. During an A5 packet replay the
 * island owner turn is the logical server owner, so packet handlers reached from
 * that replay must remain inline.
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorPlayerTickMixin {

    @ModifyReturnValue(method = "isSameThread()Z", at = @At("RETURN"), require = 1)
    private boolean fabrolia$logicalPlayerActorThread(boolean original) {
        return original || AsyncIslandPlayerTicks.inPacketReplay();
    }
}
