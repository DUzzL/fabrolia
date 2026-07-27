package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.Entity;

/**
 * task #28 — the single definition of "does this thrown ender pearl still belong
 * in its owner's registered-pearl set?".
 *
 * <p>This mirrors vanilla {@code ThrownEnderpearl.onRemoval} exactly:
 *
 * <pre>
 *   public void onRemoval(Entity.RemovalReason reason) {
 *       if (reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
 *           this.deregisterFromCurrentOwner();
 *       }
 *       super.onRemoval(reason);
 *   }
 * </pre>
 *
 * <p>{@code UNLOADED_WITH_PLAYER} is excluded deliberately and must stay
 * excluded. Vanilla keeps those registered because they belong to the player's
 * SAVED pearl list rather than to the level — {@code PlayerList.remove} saves the
 * player first and only then marks the pearls with that reason, and deregistering
 * during that pass would modify the set it is iterating. Dropping them here would
 * delete players' in-flight ender pearls.
 *
 * <p>Extracted from the mixin so the policy is unit-testable against the REAL
 * {@code RemovalReason} enum. If Mojang adds or renames a reason, the test fails
 * loudly instead of the policy silently drifting away from vanilla.
 */
public final class PearlRegistration {

    private PearlRegistration() {}

    /**
     * @param reason the pearl's removal reason, or {@code null} if it is still alive
     * @return true iff vanilla's {@code onRemoval} would have deregistered it
     */
    public static boolean noLongerBelongsToOwner(Entity.RemovalReason reason) {
        return reason != null && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }

    /**
     * Would vanilla {@code saveEnderPearls} write this pearl?
     *
     * <p>Vanilla's rule is exactly {@code if (enderPearl.isRemoved()) warn/skip;
     * else save}, i.e. ONLY a live pearl is written — including the
     * {@code UNLOADED_WITH_PLAYER} case, which stays registered but is not
     * serialized. Keeping this as the single definition means our reimplementation
     * of the loop cannot drift from vanilla's on-disk output.
     *
     * @param reason the pearl's removal reason, or {@code null} if still alive
     */
    public static boolean serializable(Entity.RemovalReason reason) {
        return reason == null;
    }
}
