package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pearl-freeze trace reports only completed losses of ENTITY_TICKING
 * (level 31 or lower), never a wave's intermediate states.
 */
class EntityTickingLossTraceTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final int ENTITY_TICKING = 31;
    private static final int BLOCK_TICKING = 32;
    private static final int UNLOADED = 33;

    /** One simulated tracker: completed levels of the last wave plus the live map. */
    private static final class Wave {
        final Long2IntOpenHashMap completed = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap live = new Long2IntOpenHashMap();
        final EntityTickingLossTrace trace = new EntityTickingLossTrace();

        Wave() {
            completed.defaultReturnValue(UNLOADED);
            live.defaultReturnValue(UNLOADED);
        }

        void setLevel(long pos, int level) {
            trace.noteLevelChange(pos, live.get(pos), level);
            live.put(pos, level);
        }

        LongArrayList finish() {
            LongArrayList lost = new LongArrayList();
            trace.judge(completed::get, live::get, lost::add);
            completed.clear();
            completed.putAll(live);
            return lost;
        }
    }

    @Test
    void intermediateLiftThatRelaxesBackIsNotALoss() {
        Wave wave = new Wave();
        wave.setLevel(7L, ENTITY_TICKING);
        wave.finish();

        // A ticket moved: the fixed point lifts the node to the maximum, then
        // relaxes it back to entity-ticking within the same wave.
        wave.setLevel(7L, UNLOADED);
        wave.setLevel(7L, ENTITY_TICKING);
        assertTrue(wave.finish().isEmpty(), "the stasis chunk never left entity-ticking");
        assertTrue(wave.trace.isEmpty(), "candidates do not outlive their wave");
    }

    @Test
    void completedDropOutOfEntityTickingIsReportedOnce() {
        Wave wave = new Wave();
        wave.setLevel(7L, ENTITY_TICKING);
        wave.finish();

        wave.setLevel(7L, UNLOADED);
        wave.setLevel(7L, BLOCK_TICKING);
        assertEquals(LongArrayList.of(7L), wave.finish());
        assertTrue(wave.finish().isEmpty(), "a later quiet wave reports nothing again");
    }

    @Test
    void chunkThatOnlyBecameEntityTickingDuringTheWaveIsNotALoss() {
        Wave wave = new Wave();
        wave.setLevel(9L, BLOCK_TICKING);
        wave.finish();

        // Rises to entity-ticking and falls back inside one wave: it was not
        // entity-ticking in the previous completed state, so nothing froze.
        wave.setLevel(9L, ENTITY_TICKING);
        wave.setLevel(9L, BLOCK_TICKING);
        assertTrue(wave.finish().isEmpty());
    }

    @Test
    void changesThatStayOutsideEntityTickingRecordNoCandidate() {
        Wave wave = new Wave();
        wave.setLevel(3L, BLOCK_TICKING);
        wave.setLevel(3L, UNLOADED);
        assertTrue(wave.trace.isEmpty());
        wave.setLevel(4L, 20);
        wave.setLevel(4L, ENTITY_TICKING);
        assertTrue(wave.trace.isEmpty(), "deeper levels are all entity-ticking");
    }
}
