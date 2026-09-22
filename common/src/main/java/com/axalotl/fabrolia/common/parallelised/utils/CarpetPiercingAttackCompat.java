package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;

/**
 * Bridges Carpet's synthetic entity attack to the vanilla 26.2 piercing-weapon path.
 *
 * <p>Carpet 26.2 calls {@link ServerPlayer#attack} for an entity hit and then resets
 * the attack-strength ticker unconditionally.  A spear stab is a different protocol
 * action: it must first pass {@code cannotAttackWithItem(stack, 5)} and then invoke
 * {@link PiercingWeapon#attack} exactly once.  In particular, a rejected attempt must
 * not reset the cooldown or a continuously scheduled action can never become ready.
 *
 * <p>This helper deliberately contains no Carpet types so Carpet remains an optional
 * dependency.  The exact optional callsite is owned by the accompanying {@code @Pseudo}
 * mixin.
 */
public final class CarpetPiercingAttackCompat {

    private static final int VANILLA_PIERCING_GRACE_TICKS = 5;

    private CarpetPiercingAttackCompat() {
    }

    /**
     * Handles a Carpet entity-attack attempt when the main-hand item is a piercing
     * weapon.
     *
     * @return {@code true} when the Carpet attack body must be cancelled, including
     *         cooldown-rejected piercing attempts; {@code false} for non-piercing items
     */
    public static boolean tryHandleAttack(ServerPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        PiercingWeapon piercingWeapon = stack.get(DataComponents.PIERCING_WEAPON);
        if (piercingWeapon == null) {
            return false;
        }

        // Preserve Carpet's activity bookkeeping, but never its unconditional cooldown reset.
        player.resetLastActionTime();
        if (!player.cannotAttackWithItem(stack, VANILLA_PIERCING_GRACE_TICKS)) {
            piercingWeapon.attack(player, EquipmentSlot.MAINHAND);
        }
        return true;
    }
}
