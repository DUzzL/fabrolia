package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Exact adapter for Carpet 26.2's combined fake-player tick.
 *
 * <p>EntityPlayerMPFake.tick invokes {@code ServerPlayer.tick()} and then
 * {@code doTick()} itself.  The A5 owner therefore treats that one entity consumer as both
 * halves and never schedules a real-player listener tail.  Its ten-tick maintenance clock
 * is translated to island L so a stalled Main does not bunch repeated move() calls.
 */
@Pseudo
@Mixin(targets = "carpet.patches.EntityPlayerMPFake", remap = false)
public abstract class CarpetFakePlayerIslandMixin {

    @ModifyExpressionValue(
            method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getTickCount()I",
                    ordinal = 0,
                    remap = true),
            require = 1,
            allow = 1)
    private int fabrolia$islandLogicalTick(int physicalTick) {
        return AsyncIslandPlayerTicks.carpetTickCount(physicalTick);
    }

    @WrapOperation(
            method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;tick()V",
                    opcode = org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    ordinal = 0,
                    remap = true),
            require = 1)
    private void fabrolia$pinCombinedEarly(
            @Coerce ServerPlayer player, Operation<Void> original) {
        try {
            original.call(player);
        } catch (NullPointerException failure) {
            throw AsyncIslandPlayerTicks.translateCarpetCombinedFailure(player, failure);
        }
    }

    @WrapOperation(
            method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lcarpet/patches/EntityPlayerMPFake;doTick()V",
                    ordinal = 0,
                    remap = false),
            require = 1,
            allow = 1)
    private void fabrolia$pinCombinedLate(
            @Coerce ServerPlayer player, Operation<Void> original) {
        try {
            original.call(player);
        } catch (NullPointerException failure) {
            throw AsyncIslandPlayerTicks.translateCarpetCombinedFailure(player, failure);
        }
    }
}
