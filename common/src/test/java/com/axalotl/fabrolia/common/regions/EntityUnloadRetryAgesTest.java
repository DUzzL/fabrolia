package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Diagnostics for deferred entity unloads: only a chunk that keeps failing ages. */
class EntityUnloadRetryAgesTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void repeatedFailureKeepsTheFirstFailureTime() {
        var ages = new IslandUnitAdmission.EntityUnloadRetryAges();
        pass(ages, 10 * SECOND, Set.of(1L, 2L), 1L);
        pass(ages, 70 * SECOND, Set.of(1L, 2L), 1L);

        assertEquals(60 * SECOND, ages.oldestAgeNanos(70 * SECOND));
        assertEquals(1L, ages.oldestChunk());
        assertEquals(1, ages.pending());
    }

    @Test
    void storedOrRepromotedChunkDropsOut() {
        var ages = new IslandUnitAdmission.EntityUnloadRetryAges();
        pass(ages, 0L, Set.of(), 5L);
        // 5 was stored this pass: not retried and no longer queued.
        pass(ages, 30 * SECOND, Set.of(), new long[0]);

        assertEquals(0L, ages.oldestAgeNanos(30 * SECOND));
        assertEquals(0, ages.pending());

        // A later failure of the same chunk starts a fresh age.
        pass(ages, 40 * SECOND, Set.of(5L), 5L);
        assertEquals(SECOND, ages.oldestAgeNanos(41 * SECOND));
    }

    @Test
    void queuedChunkTheBoundedLoopDidNotReachKeepsItsAge() {
        var ages = new IslandUnitAdmission.EntityUnloadRetryAges();
        pass(ages, 0L, Set.of(7L, 8L), 7L, 8L);
        // C2ME's pop loop only reached 8; 7 is still queued.
        pass(ages, 20 * SECOND, Set.of(7L, 8L), 8L);
        pass(ages, 90 * SECOND, Set.of(7L, 8L), 7L);

        assertEquals(90 * SECOND, ages.oldestAgeNanos(90 * SECOND));
        assertEquals(2, ages.pending());
    }

    @Test
    void oldestUsesWrapSafeNanoTimeComparison() {
        var ages = new IslandUnitAdmission.EntityUnloadRetryAges();
        long beforeWrap = Long.MAX_VALUE - SECOND;
        pass(ages, beforeWrap, Set.of(1L), 1L);
        long afterWrap = beforeWrap + 3 * SECOND; // overflows past Long.MAX_VALUE
        pass(ages, afterWrap, Set.of(1L, 2L), 1L, 2L);

        assertEquals(1L, ages.oldestChunk());
        assertEquals(3 * SECOND, ages.oldestAgeNanos(afterWrap));
    }

    private static void pass(IslandUnitAdmission.EntityUnloadRetryAges ages, long now,
                             Set<Long> queuedAfterPass, long... retried) {
        ages.beginPass();
        for (long chunk : retried) ages.retried(chunk, now);
        ages.endPass(queuedAfterPass::contains);
    }
}
