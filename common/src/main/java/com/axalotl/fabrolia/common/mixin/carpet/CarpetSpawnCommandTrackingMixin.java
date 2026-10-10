package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.regions.CarpetSpawnTrackingGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reject both Carpet 26.3 tracking starters before counters, hopper resets or a sprint change. */
@Pseudo
@Mixin(targets = "carpet.commands.SpawnCommand", remap = false)
public abstract class CarpetSpawnCommandTrackingMixin {
    @Inject(method = "startTracking(Lnet/minecraft/commands/CommandSourceStack;"
            + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)I",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void fabrolia$rejectTracking(CommandSourceStack source, BoundingBox filter,
                                               CallbackInfoReturnable<Integer> cir) {
        if (CarpetSpawnTrackingGuard.blocked()) {
            source.sendFailure(Component.literal(CarpetSpawnTrackingGuard.MESSAGE));
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "runTest(Lnet/minecraft/commands/CommandSourceStack;ILjava/lang/String;)I",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void fabrolia$rejectSpawnTest(CommandSourceStack source, int ticks, String counter,
                                                CallbackInfoReturnable<Integer> cir) {
        if (CarpetSpawnTrackingGuard.blocked()) {
            source.sendFailure(Component.literal(CarpetSpawnTrackingGuard.MESSAGE));
            cir.setReturnValue(0);
        }
    }
}
