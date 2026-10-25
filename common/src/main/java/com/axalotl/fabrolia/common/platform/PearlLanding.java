package com.axalotl.fabrolia.common.platform;

import net.minecraft.world.phys.Vec3;

/**
 * Outcome of a player's ender-pearl landing as the loader decides it: vanilla
 * (Fabric) never cancels, teleports to the pearl's previous position and deals
 * 5 damage in the player's level before the teleport; NeoForge fires
 * {@code EntityTeleportEvent.EnderPearl}, whose listeners may cancel, retarget
 * or change the damage, which then applies in the arrival level.
 */
public record PearlLanding(boolean canceled, Vec3 target, float damage, boolean damageInArrivalLevel) {
    public static PearlLanding vanilla(Vec3 previousPosition) {
        return new PearlLanding(false, previousPosition, 5.0F, false);
    }
}
