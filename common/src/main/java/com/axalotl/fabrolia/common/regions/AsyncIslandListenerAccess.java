package com.axalotl.fabrolia.common.regions;

/** Exact per-listener A5 session gate, independent of mutable listener.player. */
public interface AsyncIslandListenerAccess {
    AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState();

    boolean fabrolia$bindAsyncIslandPlayerState(
            AsyncIslandPlayerTicks.PlayerState expectedAbsent);

    boolean fabrolia$unbindAsyncIslandPlayerState(
            AsyncIslandPlayerTicks.PlayerState expected);
}
