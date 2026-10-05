package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A cached answer may only be returned while the uncached C2ME answer it was
 * captured from is still the one the holder would give: no bracketed holder
 * transition of its position may have begun since the lookup was armed.
 * Positions are spread over the plane so no two tests share a stripe by design
 * (they would only miss more, never answer stale).
 */
final class WorkerChunkCacheTest {
    private final Object level = new Object();
    private final Object chunk = new Object();

    private static long pos(int x, int z) {
        return net.minecraft.world.level.ChunkPos.pack(x, z);
    }

    private WorkerChunkCache populated(int x, int z) {
        WorkerChunkCache cache = new WorkerChunkCache();
        cache.arm(level, x, z);
        cache.capture(level, x, z, chunk);
        return cache;
    }

    @Test
    void capturedAnswerIsReturnedUntilAHolderTransitionBegins() {
        WorkerChunkCache cache = populated(1000, -7);
        assertSame(chunk, cache.lookup(level, 1000, -7));
        // Demotion out of SERVER_ACCESSIBLE: the very first write is preceded
        // by begin, so a lookup after it must not answer the old chunk even
        // while the transition is still in flight.
        WorkerChunkCache.beginTransition(pos(1000, -7));
        assertNull(cache.lookup(level, 1000, -7));
        WorkerChunkCache.endTransition(pos(1000, -7));
        assertNull(cache.lookup(level, 1000, -7));
    }

    @Test
    void lookupArmedWhileATransitionIsInFlightIsNeverStored() {
        // A transition that began before the lookup but has not finished may
        // still write after the holder reads: the read answer must not outlive it.
        WorkerChunkCache.beginTransition(pos(-2001, 37));
        WorkerChunkCache cache = populated(-2001, 37);
        WorkerChunkCache.endTransition(pos(-2001, 37));
        assertNull(cache.lookup(level, -2001, 37));
    }

    @Test
    void answerReadWhileATransitionBeganIsNeverReturned() {
        WorkerChunkCache cache = new WorkerChunkCache();
        cache.arm(level, 4096, 4096);
        WorkerChunkCache.beginTransition(pos(4096, 4096));
        WorkerChunkCache.endTransition(pos(4096, 4096));
        cache.capture(level, 4096, 4096, chunk);
        assertNull(cache.lookup(level, 4096, 4096));
    }

    @Test
    void completedTransitionsBeforeArmingDoNotBlockLaterCaching() {
        WorkerChunkCache.beginTransition(pos(77, 7777));
        WorkerChunkCache.endTransition(pos(77, 7777));
        WorkerChunkCache cache = populated(77, 7777);
        assertSame(chunk, cache.lookup(level, 77, 7777));
    }

    @Test
    void entriesAreKeyedByChunkSourceAndExactPosition() {
        WorkerChunkCache cache = populated(-300, 300);
        // Another dimension's ServerChunkCache at the same coordinates.
        assertNull(cache.lookup(new Object(), -300, 300));
        // Same direct-mapped slot (low three bits equal), different chunk.
        assertNull(cache.lookup(level, -300 + 8, 300));
        assertNull(cache.lookup(level, -300, 300 - 16));
        assertSame(chunk, cache.lookup(level, -300, 300));
    }

    @Test
    void captureOnlyStoresTheLookupThatWasArmed() {
        WorkerChunkCache cache = new WorkerChunkCache();
        cache.arm(level, 12, 34);
        // A nested lookup re-arms for its own position and consumes it.
        cache.arm(level, 13, 34);
        cache.capture(level, 13, 34, chunk);
        // The outer capture no longer has an armed lookup.
        cache.capture(level, 12, 34, chunk);
        assertNull(cache.lookup(level, 12, 34));
        assertSame(chunk, cache.lookup(level, 13, 34));

        cache.arm(level, 50, 50);
        cache.capture(level, 51, 50, chunk);
        assertNull(cache.lookup(level, 51, 50));
        cache.arm(level, 60, 60);
        cache.capture(new Object(), 60, 60, chunk);
        assertNull(cache.lookup(level, 60, 60));

        // The shortcut declined (no ready chunk): its return disarms, so a
        // later answer for the same position without a fresh arm is not stored.
        cache.arm(level, 70, 70);
        cache.capture(level, 70, 70, null);
        cache.capture(level, 70, 70, chunk);
        assertNull(cache.lookup(level, 70, 70));
    }

    @Test
    void stepBoundaryDropsEveryEntry() {
        WorkerChunkCache cache = populated(5, 5);
        cache.clear();
        assertNull(cache.lookup(level, 5, 5));
        cache.arm(level, 6, 6);
        cache.clear();
        cache.capture(level, 6, 6, chunk);
        assertNull(cache.lookup(level, 6, 6));
    }
}
