package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Lithium's physical-thread probe recognise the authenticated autonomous
 * island owner turn of an A5 player.
 *
 * <p>Lithium merges the target into {@link Level}:
 * <ul>
 *   <li>{@code lithium$getLoadedExistingBlockEntity}, which reads
 *       {@code Level.thread} directly after calling
 *       {@link Thread#currentThread()}.</li>
 * </ul>
 * The logical-owner predicate is fail-closed: A5 publishes its exact player/island
 * turn scope. All ordinary workers, Netty threads and disabled paths receive
 * Lithium's original value.  The later {@code getChunk} call still uses Fabrolia's
 * normal off-main chunk bridge for A5; this hook grants no general physical-main
 * authority.
 *
 * <p>This class targets Lithium-merged members and is therefore cancelled by
 * {@code MixinCanceller} when Lithium is absent. Mixin merges all ordinary
 * methods in its MAIN pass before preparing injectors, but forbids a
 * lower-priority injector from modifying a method contributed by a
 * higher-priority mixin. The priority therefore must remain above Lithium's
 * provider (currently the default {@code 1000}). With Lithium present the
 * {@code require = 1} pin deliberately fails startup if a supported Lithium
 * update changes the bytecode seam.
 */
@Mixin(value = Level.class, priority = 2000)
public abstract class FabroliaLithiumLevelActorThreadMixin {

    @Shadow
    @Final
    private Thread thread;

    @ModifyExpressionValue(
            method = "lithium$getLoadedExistingBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;",
                    ordinal = 0),
            require = 1,
            allow = 1,
            remap = false)
    private Thread fabrolia$logicalLoadedBlockEntityThread(Thread original) {
        return AsyncIslandPlayerTicks.inOwnerTurn()
                ? this.thread
                : original;
    }
}
