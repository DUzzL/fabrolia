package com.axalotl.fabrolia.common.mixin.lithium;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Lithium's pushable-entity side index conservative while Fabrolia resolves an
 * {@link Entity#getInBlockState()} cache miss outside the entity cache monitor.
 *
 * <p>Lithium reads {@code inBlockState} both immediately after
 * {@code Level.getBlockState} is assigned and when an entity starts participating in its
 * section pushability index. The revision protocol cannot publish a computed value until it
 * has re-entered the monitor and proved that position and level are still current. Either
 * raw field access would therefore be an unsynchronised read: a stale climbable state can
 * temporarily hide a now-pushable entity from the candidate list.
 *
 * <p>{@code null} is Lithium's documented conservative value: its
 * {@code entityPushableHeuristic(null)} keeps the entity visible and the exact predicate
 * still decides pushability. This gives up only the optional cached exclusion for entities
 * standing on climbable blocks; it cannot create a false-negative candidate or hold an
 * entity monitor across a world/chunk call. The handler and field read are exact pins for
 * Lithium 0.26.2 (mc26.3); a supported update that changes either fails mixin application.
 */
@Mixin(value = Entity.class, priority = 2000)
public abstract class FabroliaLithiumInBlockStateMixin {

    @Dynamic("Lithium cache-publication handler selected through MixinSquared")
    @TargetHandler(
            mixin = "net.caffeinemc.mods.lithium.mixin.entity.collisions.unpushable_cramming.EntityMixin",
            name = "onBlockCached",
            prefix = "handler")
    @ModifyExpressionValue(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/Entity;inBlockState:"
                            + "Lnet/minecraft/world/level/block/state/BlockState;",
                    opcode = Opcodes.GETFIELD),
            require = 1,
            allow = 1,
            remap = false)
    private BlockState fabrolia$conservativeLithiumCacheState(BlockState original) {
        return null;
    }

    @Dynamic("Lithium-added feet-block cache accessor")
    @ModifyReturnValue(
            method = "lithium$getCachedFeetBlockState()"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"),
            require = 1,
            remap = false)
    private BlockState fabrolia$conservativeLithiumFeetBlockState(BlockState original) {
        return null;
    }
}
