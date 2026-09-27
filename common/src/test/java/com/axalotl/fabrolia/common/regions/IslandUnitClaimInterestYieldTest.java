package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** An ordinary (non-global-sprint) unit must not take the island claim while
 *  Main has announced interest through either interest flag: a back-to-back
 *  chaining island would otherwise win the tick-head CAS on every pass. */
class IslandUnitClaimInterestYieldTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ordinaryUnitYieldsWithoutClaimingWhileStatusInterestIsRaised() throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(41L, 0L);
        island.statusClaimWanted = true;
        assertYieldsWithNoBody(island);
    }

    @Test
    void ordinaryUnitYieldsWithoutClaimingWhileMainClaimInterestIsRaised() throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(42L, 0L);
        island.claimWanted = true;
        assertYieldsWithNoBody(island);
    }

    private static void assertYieldsWithNoBody(IslandRegistry.PIsland island) throws Exception {
        RegionizedServer runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
        runtime.asyncActive = true;
        island.submitQueued.set(true);
        IslandTickUnit.AttemptReceipt receipt = receipt();

        runCoordinated(runtime, island, receipt);

        assertTrue(receipt.isDone());
        assertNull(receipt.failureOrNull());
        assertEquals(IslandTickUnit.AttemptOutcome.NO_BODY, receipt.outcome());
        assertFalse(island.unitClaim.get(), "the unit must not have taken the claim");
        assertFalse(island.submitQueued.get(), "the queue reservation is released for the retry");
        assertTrue(island.tryClaimAutonomousWork(), "nothing else was left claimed");
        island.unitClaim.set(false);
    }

    private static void runCoordinated(RegionizedServer runtime, IslandRegistry.PIsland island,
                                       IslandTickUnit.AttemptReceipt receipt) throws Exception {
        Method method = IslandTickUnit.class.getDeclaredMethod("runCoordinated",
                RegionizedServer.class, IslandRegistry.PIsland.class, IslandTickUnit.AttemptReceipt.class,
                long.class, boolean.class, long.class, long.class, IslandTickUnit.GlobalSprintRequest.class);
        method.setAccessible(true);
        method.invoke(null, runtime, island, receipt, island.logicalTick, false, Long.MIN_VALUE, 0L, null);
    }

    private static IslandTickUnit.AttemptReceipt receipt() throws Exception {
        Constructor<IslandTickUnit.AttemptReceipt> constructor =
                IslandTickUnit.AttemptReceipt.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
