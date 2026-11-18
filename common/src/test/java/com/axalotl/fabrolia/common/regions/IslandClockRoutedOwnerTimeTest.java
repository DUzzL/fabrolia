package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The routed-island clock binding behind R4/R2 (night campaign 2026-11-18):
 * bound only for the body's duration, on the executing thread only, restored
 * on nesting and failure, and never a second {@link IslandClock#contextTime()}.
 */
class IslandClockRoutedOwnerTimeTest {

    @Test
    void bindingLastsExactlyForTheBodyAndRestoresTheOuterBinding() {
        assertEquals(-1L, IslandClock.routedOwnerTime());
        AtomicLong inner = new AtomicLong();
        AtomicLong outerAfterInner = new AtomicLong();
        assertTrue(IslandClock.callWithRoutedOwnerTime(101L, () -> {
            assertEquals(101L, IslandClock.routedOwnerTime());
            assertFalse(IslandClock.callWithRoutedOwnerTime(102L, () -> {
                inner.set(IslandClock.routedOwnerTime());
                return false;
            }));
            outerAfterInner.set(IslandClock.routedOwnerTime());
            return true;
        }));
        assertEquals(102L, inner.get());
        assertEquals(101L, outerAfterInner.get());
        assertEquals(-1L, IslandClock.routedOwnerTime(), "no binding survives its body");
    }

    @Test
    void aWallClockRouterBindsNothing() {
        assertTrue(IslandClock.callWithRoutedOwnerTime(-1L, () -> {
            assertEquals(-1L, IslandClock.routedOwnerTime());
            return true;
        }));
        IslandClock.callWithRoutedOwnerTime(7L, () -> {
            IslandClock.callWithRoutedOwnerTime(-1L, () -> {
                assertEquals(7L, IslandClock.routedOwnerTime(),
                        "an unbound nested body keeps the enclosing routed clock");
                return true;
            });
            return true;
        });
    }

    @Test
    void bodyFailureEscapesUnchangedAndClearsTheBinding() {
        IllegalStateException failure = new IllegalStateException("body");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> IslandClock.callWithRoutedOwnerTime(55L, () -> {
                    throw failure;
                })));
        assertEquals(-1L, IslandClock.routedOwnerTime());
    }

    @Test
    void bindingIsConfinedToTheExecutingThreadAndIsNotAContextClock() throws Exception {
        AtomicLong other = new AtomicLong(Long.MIN_VALUE);
        IslandClock.callWithRoutedOwnerTime(900L, () -> {
            // Main's world effects keep W: the caller-clock resolvers stay global.
            assertEquals(-1L, IslandClock.contextTime());
            assertNull(TimerClocks.currentBasis());
            Thread reader = new Thread(() -> other.set(IslandClock.routedOwnerTime()));
            reader.start();
            try {
                reader.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return true;
        });
        assertEquals(-1L, other.get(), "another thread never sees Main's routed binding");
    }
}
