package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CopperIslandClaimsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void otherWorkerCanTickAWitnessButNotTheHeldChestOrInheritItsAuthority() throws Exception {
        var source = new IslandRegistry.PIsland(1, 100);
        var chest = new IslandRegistry.PIsland(2, 17);
        var witness = new IslandRegistry.PIsland(3, 39);
        assertTrue(source.tryClaimAutonomousWork());
        source.noteClaim("test source");
        try (var window = new CopperIslandClaims.Window(source, false)) {
            assertTrue(window.test(chest));
            assertTrue(window.test(chest)); // double chest and opener searches can name the same owner
            assertFalse(window.test(null), "a global cell is not covered by an island claim");
            assertFalse(window.missed);
            assertTrue(CopperIslandClaims.ownsExtra(chest, source));
            var peer = new FutureTask<>(() -> {
                assertFalse(CopperIslandClaims.ownsExtra(chest, source));
                assertFalse(chest.tryClaimAutonomousWork());
                assertTrue(witness.tryClaimAutonomousWork());
                witness.unitClaim.set(false);
                return true;
            });
            Thread.ofPlatform().daemon().start(peer);
            assertTrue(peer.get(5, TimeUnit.SECONDS));
            source.waitingOnRoutedCommand = true;
            assertTrue(chest.hasRoutedWaiter());
            assertFalse(witness.hasRoutedWaiter());
        } finally {
            source.waitingOnRoutedCommand = false;
            source.clearClaim();
            source.unitClaim.set(false);
        }
        assertFalse(chest.hasRoutedWaiter());
        assertTrue(chest.tryClaimAutonomousWork());
        chest.unitClaim.set(false);
        assertEquals(17, chest.logicalTick);
        assertEquals(100, source.logicalTick);
    }
}
