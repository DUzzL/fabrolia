package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.CrossDimArrivals;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * End-platform regen staging (2026-07-15 excess-output fix): vanilla runs
 * createEndPlatform(dropResources=true) for EVERY entity entering the End —
 * inline on main that's idempotent per cohort (regen #1 clears, #2..#12
 * no-op). On a source-island worker the destroyBlock item pops ran inline
 * while the clearing writes were cross-region DEFERRED — teleports #2..#12
 * re-scanned the still-uncleared platform and re-popped the same block:
 * duplicate item drops per burst. Staged into the same cohort batch as the
 * entity insertions, the regens execute at the destination owner's phase
 * head in order: regen #1 really clears before #2 scans — vanilla-exact
 * output, and no destination block writes off-owner.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    /**
     * audit9: does this End-platform regeneration have to be STAGED onto the
     * destination island, or may it run inline?
     *
     * <p>It must be staged whenever the destination is owned by an async island —
     * <em>including when the caller is the main Server thread.</em> That case is the
     * bug this fixes. {@code CrossDimTeleportMixin} already stages main-thread
     * arrivals into island-owned destinations (its "not-worker-thread-but-staged"
     * path), but this mixin required {@code instanceof RegionWorkerThread}, so a
     * player walking into the End had their ARRIVAL staged while their platform
     * REGEN ran inline on main. The regen then wrote blocks into the End island's
     * chunks with no claim, concurrently with that island's entity phase on a
     * region worker.
     *
     * <p>The audit8 capture shows exactly that: a regen on {@code thread=Server
     * thread} with {@code L=-1} (no island clock context) cleared (100,49,0)
     * microseconds after a falling block had placed there, mid-phase. Every regen
     * that is correctly staged carries a real {@code L=} and runs on a worker.
     *
     * <p>Staging preserves vanilla order: {@code getPortalDestination} runs before
     * {@code addDuringTeleport}, so the regen enqueues ahead of the arrival it
     * belongs to and the pair stays atomic, applied at the destination unit head
     * before the entity phase — which is the vanilla-equivalent boundary.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$shouldStageRegen(ServerLevel dest, BlockPos pos) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            return true;
        }
        com.axalotl.fabrolia.common.regions.RegionizedServer destRs =
                RegionScheduler.forLevel(dest);
        if (destRs == null || !destRs.asyncActive) {
            return false;   // sequential level: main IS the executor, vanilla order holds
        }
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        return CrossDimArrivals.islandOwnedDestination(destRs, chunkKey);
    }

    @WrapOperation(method = "getPortalDestination",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/feature/EndPlatformFeature;createEndPlatform(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Z)V"))
    private void fabrolia$stageRegen(ServerLevelAccessor level, BlockPos pos, boolean dropResources,
                                     Operation<Void> original) {
        if (!CrossDimArrivals.PUBLISHING.get()
                && RegionScheduler.isActive()
                && level instanceof ServerLevel sl
                && fabrolia$shouldStageRegen(sl, pos)) {
            long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            CrossDimArrivals.stage(sl, chunkKey,
                    () -> EndPlatformFeature.createEndPlatform(sl, pos, dropResources),
                    "regen");
            return;
        }
        original.call(level, pos, dropResources);
    }
}
