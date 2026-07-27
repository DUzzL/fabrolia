package com.axalotl.fabrolia.common.parallelised.utils;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Duck on {@code ServerGamePacketListenerImpl} (task #32): the per-listener
 * mutual-exclusion lock between routed packet applies (any venue) and main's
 * listener tick / teleport-ack bookkeeping.
 *
 * <p>This replaces the previous {@code synchronized (connection)} discipline,
 * which re-created the production deadlock shape through the connection
 * monitor: a worker-venue apply may park in the chunk main-bridge
 * ({@code awaitMainThreadResult}) while holding the apply mutex, and a raw
 * monitor entry from main ({@code listener.tick()}) is a non-pumping block —
 * main then cannot service {@code mainThreadProcessor} and the worker's
 * future never completes. With a {@link ReentrantLock}, main-side acquirers
 * spin with {@code tryLock} + {@code IslandCommands.drainMainChunkServices()}
 * so the worker's bridge always completes and the lock is always released.
 */
public interface FabroliaListenerLane {

    ReentrantLock fabrolia$laneLock();
}
