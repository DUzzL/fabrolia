package com.axalotl.fabrolia.common.regions;

/**
 * Entity-local storage for the async-island player owner.
 *
 * <p>The state lives on the exact {@code ServerPlayer} lifecycle object.  This avoids
 * UUID aliasing during respawn/Carpet shadow replacement and lets packet threads read
 * one atomically-published session without consulting PlayerList's main-thread maps.
 */
public interface AsyncIslandPlayerAccess {

    AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState();
}
