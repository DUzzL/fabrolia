package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the distinction between per-chunk membership and 8x8-cell topology. */
final class RegionPopulationTopologyTest {

    @Test
    void onlyFirstLoadAndLastUnloadCrossThePopulationBoundary() {
        Region region = new Region(null, 0L);

        assertEquals(Region.FullChunkTransition.POPULATION_BOUNDARY,
                region.applyFullChunkTransition(0L, true));
        for (long chunk = 1L; chunk < 64L; chunk++) {
            assertEquals(Region.FullChunkTransition.MEMBERSHIP_ONLY,
                    region.applyFullChunkTransition(chunk, true),
                    "chunk " + chunk + " is local membership, not island topology");
        }
        assertEquals(64, region.fullChunkCount.get());
        assertEquals(64, region.fullChunks.size());

        for (long chunk = 0L; chunk < 63L; chunk++) {
            assertEquals(Region.FullChunkTransition.MEMBERSHIP_ONLY,
                    region.applyFullChunkTransition(chunk, false),
                    "non-last unload cannot depopulate the cell");
        }
        assertEquals(Region.FullChunkTransition.POPULATION_BOUNDARY,
                region.applyFullChunkTransition(63L, false));
        assertEquals(0, region.fullChunkCount.get());
        assertEquals(0, region.fullChunks.size());
    }

    @Test
    void duplicateSourcesDoNotMoveMembershipOrTopology() {
        Region region = new Region(null, 17L);
        long chunk = 0x0000_0007_0000_0003L;

        assertEquals(Region.FullChunkTransition.POPULATION_BOUNDARY,
                region.applyFullChunkTransition(chunk, true));
        assertEquals(Region.FullChunkTransition.REDUNDANT,
                region.applyFullChunkTransition(chunk, true));
        assertEquals(1, region.fullChunkCount.get());
        assertEquals(1, region.fullChunks.size());

        assertEquals(Region.FullChunkTransition.POPULATION_BOUNDARY,
                region.applyFullChunkTransition(chunk, false));
        assertEquals(Region.FullChunkTransition.REDUNDANT,
                region.applyFullChunkTransition(chunk, false));
        assertEquals(0, region.fullChunkCount.get());
        assertEquals(0, region.fullChunks.size());
    }
}
