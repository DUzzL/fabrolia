package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerPlayer;

/**
 * JMM-visible successor link for a replaced {@link ServerPlayer} lifecycle
 * object. A pearl tick may resolve the old object immediately before main
 * publishes a duplicate-login, respawn, or Carpet-shadow replacement. The
 * volatile link lets that already-running tick distinguish the old object's
 * unload from the logical owner's death without reading PlayerList's plain
 * HashMap from a region worker.
 */
public interface PearlOwnerSessionAccess {

    ServerPlayer fabrolia$pearlSessionSuccessor();

    void fabrolia$publishPearlSessionSuccessor(
            ServerPlayer successor, boolean liveHandoff);

    boolean fabrolia$pearlSessionReplacementWasLive();

    /** Start this exact lifecycle object's one legal PlayerList.remove. */
    boolean fabrolia$beginPlayerListRemoval();

    /** Mark a normal completion, or reopen the lifecycle after a thrown remove. */
    void fabrolia$finishPlayerListRemoval(boolean completed);

    boolean fabrolia$pearlHandoffSaveConsumed();

    boolean fabrolia$tryConsumePearlHandoffSave();

    void fabrolia$publishPearlLogoutSnapshot(
            java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
                    savedPearls);

    java.util.Set<net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl>
    fabrolia$pearlLogoutSnapshot();
}
