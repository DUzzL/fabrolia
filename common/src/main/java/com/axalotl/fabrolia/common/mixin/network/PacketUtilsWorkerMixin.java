package com.axalotl.fabrolia.common.mixin.network;

import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regional player ticking: packet handlers re-executed inside an island task
 * (ServerGamePacketRoutingMixin) run on a region worker holding the island
 * claim — the vanilla "am I on the server thread" reschedule must accept that
 * as a server execution context instead of bouncing the packet back to main
 * (which would defeat the single-timeline routing and double-apply the guard).
 * Region workers already count as server execution threads for every other
 * vanilla same-thread assertion in region mode; this extends the same
 * semantic to the packet dispatch helper. Inert for normal main-thread packet
 * flow and while the feature is off (packets then never reach workers).
 */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsWorkerMixin {

    @Inject(method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"), cancellable = true)
    private static void fabrolia$acceptRegionWorker(Packet<?> packet,
                                                    net.minecraft.network.PacketListener listener,
                                                    ServerLevel level, CallbackInfo ci) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                && com.axalotl.fabrolia.common.regions.RegionalPlayerTicks.inRoutedApply()) {
            ci.cancel();
        }
    }
}
