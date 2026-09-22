package com.axalotl.fabrolia.common.regions;

/** Raw native movement state; read only under this listener's real player owner. */
public interface AsyncIslandMovementListenerAccess {
    double fabrolia$lastGoodX();

    double fabrolia$lastGoodY();

    double fabrolia$lastGoodZ();

    boolean fabrolia$hasAwaitingPosition();
}
