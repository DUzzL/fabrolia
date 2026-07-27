package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(value = ChunkMap.TrackedEntity.class)
public class ChunkMapTrackedEntityMixin {

    @Shadow
    private final Set<ServerGamePacketListenerImpl> seenBy = ConcurrentCollections.newHashSet();

    @Shadow
    @Final
    public Entity entity;

    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void updatePlayerNull(ServerPlayer player, CallbackInfo ci) {
        // entity.isRemoved(): tracked entity is being torn down — don't pair anyone new to it
        // player.isRemoved(): player is mid-dim-change. setRemoved(CHANGED_DIMENSION) fired,
        //   but ServerLevel$EntityCallbacks.onTrackingEnd calls chunkSource.removeEntity
        //   BEFORE players.remove(player) — so VMP NearbyEntityTracking.removeEntityTracker
        //   sees the player still in world.getPlayers() and its recursive tick0 →
        //   tickStaging re-adds the leaving player to seenBy of every staged tracker in
        //   range, leaking cross-dimension entity visibility until the connection drops.
        // player.level() mismatch: defence-in-depth for any other stale-path seenBy mutation.
        if (this.entity.isRemoved()
                || player.isRemoved()
                || player.level() != this.entity.level()) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V")
    private synchronized void updatePlayer(ServerPlayer player, Operation<Void> original) {
        original.call(player);
    }
}