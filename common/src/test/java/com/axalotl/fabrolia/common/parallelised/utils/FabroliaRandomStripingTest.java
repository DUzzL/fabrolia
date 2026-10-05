package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabroliaRandomStripingTest {
    private static final long MULTIPLIER = 25214903917L;
    private static final long MASK = (1L << 48) - 1;

    @Test
    void eachSourceKeepsItsOwnLcgStreamAcrossInterleavingAndGrowth() {
        FabroliaRandomStriping.Stripes stripes = new FabroliaRandomStriping.Stripes();
        int count = 10; // past two capacity doublings
        Object[] sources = new Object[count];
        long[] expected = new long[count];
        AtomicLong master = new AtomicLong(1234L);
        for (int i = 0; i < count; i++) {
            sources[i] = new Object();
            FabroliaRandomStriping.draw(stripes, sources[i], master, 32);
            expected[i] = stripes.seeds[i];
        }
        for (int round = 0; round < 5; round++) {
            for (int i = count - 1; i >= 0; i--) {
                expected[i] = (expected[i] * MULTIPLIER + 11L) & MASK;
                assertEquals((int) (expected[i] >>> 16),
                        FabroliaRandomStriping.draw(stripes, sources[i], master, 32),
                        "source " + i + " round " + round);
            }
        }
        assertEquals(count, stripes.size);
    }
}
