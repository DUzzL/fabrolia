package com.axalotl.fabrolia.common.mixin.carpet;

import com.axalotl.fabrolia.common.parallelised.utils.CarpetPiercingAttackCompat;
import net.minecraft.server.level.ServerPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exact optional adapter for fabric-carpet {@code 26.2+v260616}'s ATTACK action.
 *
 * <p>The target is Carpet's anonymous {@code ActionType.ATTACK} implementation.  Its
 * entity-hit arm reads {@code Action.isContinuous} immediately before choosing whether
 * to call the ordinary {@code Player.attack} path.  Intercepting that one field read
 * lets piercing weapons use the canonical STAB gate in once, interval and continuous
 * modes, while every non-piercing and non-entity branch remains byte-for-byte Carpet.
 * Cancelling also bypasses Carpet's unconditional attack-ticker reset on rejected stabs.
 */
@Pseudo
@Mixin(targets = "carpet.helpers.EntityPlayerActionPack$ActionType$2", remap = false)
public abstract class CarpetPiercingAttackMixin {

    @Inject(
            method = "execute(Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lcarpet/helpers/EntityPlayerActionPack$Action;)Z",
            at = @At(value = "FIELD",
                    target = "Lcarpet/helpers/EntityPlayerActionPack$Action;isContinuous:Z",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0,
                    remap = false),
            cancellable = true,
            require = 1,
            allow = 1)
    private void fabrolia$useCanonicalPiercingAttack(
            ServerPlayer player, @Coerce Object action,
            CallbackInfoReturnable<Boolean> cir) {
        if (CarpetPiercingAttackCompat.tryHandleAttack(player)) {
            cir.setReturnValue(true);
        }
    }
}
