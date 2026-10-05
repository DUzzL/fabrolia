package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.axalotl.fabrolia.common.regions.CrossDimProjection.cellKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sprint session whose ticks are used up stays in the per-level list unless
 * something removes it; every unit completion of every island in the level then
 * scans it. The topology remap and the next sprint command drop such sessions,
 * and keep live sprints, rate overrides and coherence pins.
 */
class TemporalSessionPruneTest {

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = RegionizedServer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Two islands, (0,0) and (10,10), on a runtime with no live scheduler. */
    private static RegionizedServer runtime() throws Exception {
        RegionizedServer rs = (RegionizedServer) UNSAFE.allocateInstance(RegionizedServer.class);
        setField(rs, "islandRegistry", new IslandRegistry());
        setField(rs, "temporalSessions", new CopyOnWriteArrayList<TemporalControl.Session>());
        rs.asyncActive = true;
        LongArrayList a = new LongArrayList(new long[]{cellKey(0, 0)});
        LongArrayList b = new LongArrayList(new long[]{cellKey(10, 10)});
        rs.islandRegistry.applyRebuildCells(List.of(a, b), 1_000L);
        return rs;
    }

    private static TemporalControl.Session finishedSprint(RegionizedServer rs, IslandRegistry.PIsland island) {
        TemporalControl.Session s = TemporalControl.addSprintSession(rs, island, 1, "sprint");
        TemporalControl.prepareUnitCompletion(rs, island, island.logicalTick).commit();
        assertEquals(0L, s.sprintRemaining.get());
        assertFalse(TemporalControl.isSprinting(rs, island));
        return s;
    }

    @Test
    void remapDropsFinishedSprintsOnly() throws Exception {
        RegionizedServer rs = runtime();
        IslandRegistry.PIsland a = rs.islandRegistry.byCell(cellKey(0, 0));
        IslandRegistry.PIsland b = rs.islandRegistry.byCell(cellKey(10, 10));
        TemporalControl.Session done = finishedSprint(rs, a);
        TemporalControl.Session live = TemporalControl.addSprintSession(rs, b, 50, "live");
        TemporalControl.Session rate = TemporalControl.addRateSession(rs, a, 10.0, "rate");
        TemporalControl.Session pin = TemporalControl.addCoherentSession(rs, b, "pin");

        TemporalControl.remap(rs);

        assertFalse(rs.temporalSessions.contains(done), "finished sprint must not outlive the remap");
        assertTrue(rs.temporalSessions.contains(live));
        assertTrue(rs.temporalSessions.contains(rate));
        assertTrue(rs.temporalSessions.contains(pin));
        assertEquals(100_000_000L, TemporalControl.effectiveTargetNanos(rs, a));
        assertTrue(TemporalControl.isCoherent(rs, b));
    }

    @Test
    void nextSprintCommandDropsFinishedSprintsOfOtherIslands() throws Exception {
        RegionizedServer rs = runtime();
        IslandRegistry.PIsland a = rs.islandRegistry.byCell(cellKey(0, 0));
        IslandRegistry.PIsland b = rs.islandRegistry.byCell(cellKey(10, 10));
        TemporalControl.Session done = finishedSprint(rs, a);

        TemporalControl.Session next = TemporalControl.addSprintSession(rs, b, 20, "next");

        assertEquals(List.of(next), List.copyOf(rs.temporalSessions));
        assertTrue(TemporalControl.isSprinting(rs, b));
        assertFalse(rs.temporalSessions.contains(done));
    }
}
