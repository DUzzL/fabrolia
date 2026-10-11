package com.axalotl.fabrolia.common.mixin.servux;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Keeps Servux's structure tracking on physical Main (production crash
 * 2026-10-10 23:29: {@code ConcurrentModificationException} in
 * {@code StructureDataProvider.sendAndRefreshExpiredStructures}).
 *
 * <p>Servux 0.12.x injects {@code onStartedWatchingChunk} at the head of
 * {@code ChunkMap.markChunkPendingToSend} and records a timeout in a plain
 * per-player {@code HashMap}; its end-of-tick provider iterates that map on
 * Main. Vanilla only marks chunks on Main, but Fabrolia marks them wherever the
 * player's chunk view is updated, including island workers (see
 * {@code ChunkDelivery}: "callable from any thread"). A worker insert during
 * Main's iteration is the crash.
 *
 * <p>Off-Main calls therefore run as a Main-only task, the same route
 * {@code WorkerTicketRouteMixin} uses for ticket writes. Servux re-checks the
 * player's registration there; the timeout lands at most one Main drain later,
 * which only delays a client overlay refresh. Physical Main, the exact player
 * actor (Main parked for its turn) and a stopped region scheduler keep the
 * original inline call.
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.servux.dataproviders.StructureDataProvider", remap = false)
public abstract class ServuxStructureWatchMainRouteMixin {

    @WrapMethod(
            method = "onStartedWatchingChunk(Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            remap = false)
    private void fabrolia$routeStructureWatchToMain(ServerPlayer player, LevelChunk chunk,
                                                    Operation<Void> original) {
        if (!RegionScheduler.isActive()
                || RegionalPlayerEntityTicksV2.isPhysicalServerThread(player.level().getServer())
                || RegionalPlayerEntityTicksV2.inExactPlayerActor()) {
            original.call(player, chunk);
            return;
        }
        RegionScheduler.enqueueGlobalMainOnly(() -> original.call(player, chunk));
    }
}
