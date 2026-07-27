package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.FallingBlockPlacementTrace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures the End-portal falling-block PLACEMENT DECISION (task #26 field
 * forensics). Entirely inert unless {@code -Dfabrolia.debug.fbePlace=true}: both
 * hooks call the original unconditionally and only branch on a static-final
 * boolean, so no ordering, allocation or lock is added when off.
 *
 * <p>Vanilla {@code FallingBlockEntity.tick()} has exactly one
 * {@code Level.setBlock} (the "became a block" outcome) and two
 * {@code spawnAtLocation} calls (the "scattered as an item" outcomes) — verified
 * by javap against both the vanilla-deobf and NeoForge-patched 26.2 classes, with
 * owner {@code net/minecraft/world/level/Level} on both.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockPlacementTraceMixin {

    @org.spongepowered.asm.mixin.Shadow
    private BlockState blockState;

    @org.spongepowered.asm.mixin.Shadow
    private boolean cancelDrop;

    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean fabrolia$tracePlacement(Level level, BlockPos pos, BlockState state, int flags,
                                            Operation<Boolean> original) {
        if (FallingBlockPlacementTrace.ON) {
            FallingBlockPlacementTrace.decision((FallingBlockEntity) (Object) this, "PLACE", pos,
                    this.blockState, this.cancelDrop);
        }
        return original.call(level, pos, state, flags);
    }

    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;spawnAtLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ItemLike;)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity fabrolia$traceScatter(FallingBlockEntity self, ServerLevel level, ItemLike item,
                                             Operation<ItemEntity> original) {
        if (FallingBlockPlacementTrace.ON) {
            FallingBlockPlacementTrace.decision(self, "DROP", self.blockPosition(),
                    this.blockState, this.cancelDrop);
        }
        return original.call(self, level, item);
    }
}
