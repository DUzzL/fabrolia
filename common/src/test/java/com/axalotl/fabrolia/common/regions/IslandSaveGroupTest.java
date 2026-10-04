package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** F3: during a saveAll pass over players on several islands no unit of an
 *  island is admitted between two of its players' saves, and nothing outlives
 *  the pass. */
class IslandSaveGroupTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void noUnitIsAdmittedBetweenTwoSavesOfOneIslandAcrossInterleavedPlayers() throws Exception {
        IslandRegistry.PIsland a = new IslandRegistry.PIsland(71L, 0L);
        IslandRegistry.PIsland b = new IslandRegistry.PIsland(72L, 0L);
        Map<Object, IslandRegistry.PIsland> owners = new IdentityHashMap<>();
        List<Object> players = new ArrayList<>();
        for (IslandRegistry.PIsland island : new IslandRegistry.PIsland[] {a, b, a, b, a}) {
            Object player = new Object();
            owners.put(player, island);
            players.add(player);
        }

        IslandSaveGroup group = IslandSaveGroup.open(players, owners::get);
        assertNotNull(group);
        try {
            assertTrue(a.mainInterest() && b.mainInterest(),
                    "every island the pass claims stops at its next unit boundary from the start");
            for (Object player : players) {
                // PlayerList.save of this player, claimed exactly as the save hook does.
                IslandRegistry.PIsland owner = owners.get(player);
                assertTrue(IslandCommands.mainClaimScoped(owner));
                IslandCommands.mainRelease(owner);
                assertUnitYieldsWithoutClaim(owner);
            }
        } finally {
            assertNull(group.close(null));
        }
        assertFalse(a.mainInterest() || b.mainInterest(), "no interest outlives the pass");
        assertTrue(a.tryClaimAutonomousWork() && b.tryClaimAutonomousWork(),
                "units are admitted again after the pass");
        a.unitClaim.set(false);
        b.unitClaim.set(false);
    }

    @Test
    void closeWithdrawsEverythingAndReturnsTheBodysFailureItself() {
        assertNull(IslandSaveGroup.open(List.of(new Object()), player -> null),
                "a pass that claims nothing opens nothing");

        IslandRegistry.PIsland a = new IslandRegistry.PIsland(73L, 0L);
        IslandRegistry.PIsland b = new IslandRegistry.PIsland(74L, 0L);
        Object onA = new Object();
        IslandSaveGroup group = IslandSaveGroup.open(List.of(onA, new Object(), onA),
                player -> player == onA ? a : b);
        RuntimeException bodyFailure = new RuntimeException("vanilla save failed");

        assertSame(bodyFailure, group.close(bodyFailure), "the wrapper rethrows the body's failure raw");
        assertFalse(a.mainInterest() || b.mainInterest());
        assertNull(group.close(null), "a second close withdraws nothing more");
        assertFalse(a.mainInterest() || b.mainInterest());
    }

    private static void assertUnitYieldsWithoutClaim(IslandRegistry.PIsland island) throws Exception {
        RegionizedServer runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
        runtime.asyncActive = true;
        island.submitQueued.set(true);
        IslandTickUnit.AttemptReceipt receipt = receipt();

        Method method = IslandTickUnit.class.getDeclaredMethod("runCoordinated",
                RegionizedServer.class, IslandRegistry.PIsland.class, IslandTickUnit.AttemptReceipt.class,
                long.class, boolean.class, long.class, long.class, IslandTickUnit.GlobalSprintRequest.class);
        method.setAccessible(true);
        method.invoke(null, runtime, island, receipt, island.logicalTick, false, Long.MIN_VALUE, 0L, null);

        assertTrue(receipt.isDone());
        assertNull(receipt.failureOrNull());
        assertEquals(IslandTickUnit.AttemptOutcome.NO_BODY, receipt.outcome(), "the unit must not run");
        assertFalse(island.unitClaim.get(), "the unit did not take the free claim");
        assertFalse(island.submitQueued.get());
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
