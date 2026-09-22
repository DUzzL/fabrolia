package com.axalotl.fabrolia.common.mixin.vmp;

import com.ishland.vmp.common.playerwatching.TACSExtension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Removes vanilla's O(spawn candidates x all players) physical-Main scan while
 * retaining the exact installed player predicate.
 *
 * <p>26.2 {@link ChunkMap#collectSpawningChunks} calls its private full-player
 * fallback directly for every candidate. VMP already maintains a 128-block
 * general-area index, but that call site bypasses it. We consume VMP's detached
 * area snapshot and invoke the original per-player predicate until the first
 * success. This deliberately does not use the public TriState shortcut: Vanish
 * wraps {@code playerIsCloseEnoughForSpawning}, and a definite tracker TRUE
 * would otherwise skip Vanish's mob-spawning exclusion for an interior chunk.
 * Calling the target predicate for every local candidate keeps that hook, the
 * VMP candidate order, short-circuiting, and thrown failures intact.</p>
 */
@Pseudo
@Mixin(value = ChunkMap.class, priority = 1600)
public abstract class FabroliaVmpSpawnCandidateMixin {

    @Shadow
    protected abstract boolean playerIsCloseEnoughForSpawning(
            ServerPlayer player, ChunkPos pos);

    @WrapOperation(
            method = "collectSpawningChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;"
                            + "anyPlayerCloseEnoughForSpawningInternal("
                            + "Lnet/minecraft/world/level/ChunkPos;)Z"),
            require = 1,
            allow = 1)
    private boolean fabrolia$useVmpSpawnCandidates(
            ChunkMap instance, ChunkPos pos, Operation<Boolean> original) {
        Object[] candidates = ((TACSExtension) instance)
                .getAreaPlayerChunkWatchingManager()
                .getPlayersInGeneralAreaMap(pos.pack());
        for (Object candidate : candidates) {
            if (candidate instanceof ServerPlayer player
                    && playerIsCloseEnoughForSpawning(player, pos)) {
                return true;
            }
        }
        return false;
    }
}
