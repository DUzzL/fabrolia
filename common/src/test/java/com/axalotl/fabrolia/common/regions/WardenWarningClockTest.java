package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The warden warning cooldown measured in the clock that armed it (warden switch fix). */
final class WardenWarningClockTest {

    private static WardenWarningClock.Arm armOn(IslandRegistry.PIsland island, long stamp) {
        return new WardenWarningClock.Arm(null, island.domain, island, stamp);
    }

    @Test
    void withoutReadableArmTheVanillaCounterDecides() {
        assertTrue(WardenWarningClock.onCooldown(WardenWarningClock.UNREADABLE, 1, 0));
        assertFalse(WardenWarningClock.onCooldown(WardenWarningClock.UNREADABLE, 0, 0));
        assertEquals(WardenWarningClock.UNREADABLE, WardenWarningClock.elapsed(null, null));
    }

    @Test
    void readableArmEndsExactlyAfterTwoHundredArmingTicks() {
        assertTrue(WardenWarningClock.onCooldown(0, 200, 0));
        assertTrue(WardenWarningClock.onCooldown(199, 0, 210));
        assertFalse(WardenWarningClock.onCooldown(200, 1, 199));
        assertFalse(WardenWarningClock.onCooldown(240, 0, 240));
    }

    @Test
    void switchReArmsInTheTickTheCooldownEndsWhateverThePlayersCounterSays() {
        // The switch island armed at L=1000; its loop comes back in unit L=1200.
        IslandRegistry.PIsland switchIsland = new IslandRegistry.PIsland(1L, 1000L);
        WardenWarningClock.Arm arm = armOn(switchIsland, 1000L);
        switchIsland.logicalTick = 1199L;
        switchIsland.domain.unitGameTime = 1200L; // unit L=1200 running
        WardenWarningClock.Arm switchShrieker = armOn(switchIsland, 1200L);
        long elapsed = WardenWarningClock.elapsed(arm, switchShrieker);
        assertEquals(200L, elapsed);
        // The player's island is one tick behind: its counter still reads 1.
        // Counted in the player's clock the warning would be skipped for a
        // whole 40-tick loop; in the arming clock it re-arms now, as vanilla.
        assertFalse(WardenWarningClock.onCooldown(elapsed, 1, 199));
    }

    @Test
    void foreignReaderNeverSeesTheGapInsideTheReArmingTick() {
        IslandRegistry.PIsland switchIsland = new IslandRegistry.PIsland(1L, 1000L);
        IslandRegistry.PIsland playerIsland = new IslandRegistry.PIsland(2L, 5000L);
        WardenWarningClock.Arm arm = armOn(switchIsland, 1000L);
        WardenWarningClock.Arm cityShrieker = armOn(playerIsland, 5300L);
        switchIsland.logicalTick = 1199L;
        switchIsland.domain.unitGameTime = 1200L; // the re-arming unit is still running
        // A shrieker in another ancient city whose counter already reached 0.
        assertTrue(WardenWarningClock.onCooldown(WardenWarningClock.elapsed(arm, cityShrieker), 0, 201));
        switchIsland.logicalTick = 1200L; // completed without a re-arm: a real gap
        assertFalse(WardenWarningClock.onCooldown(WardenWarningClock.elapsed(arm, cityShrieker), 0, 202));
    }

    @Test
    void stalledArmingIslandCannotHoldTheCooldownPastThePlayerTickBound() {
        IslandRegistry.PIsland frozen = new IslandRegistry.PIsland(1L, 1000L);
        WardenWarningClock.Arm arm = armOn(frozen, 1000L);
        frozen.logicalTick = 1050L; // stopped advancing
        long elapsed = WardenWarningClock.elapsed(arm, null);
        assertTrue(WardenWarningClock.onCooldown(elapsed, 0, WardenWarningClock.PLAYER_TICK_BOUND - 1));
        assertFalse(WardenWarningClock.onCooldown(elapsed, 0, WardenWarningClock.PLAYER_TICK_BOUND));
    }

    @Test
    void retiredReplacedOrRebasedClocksFallBackToTheCounter() {
        IslandRegistry.PIsland retired = new IslandRegistry.PIsland(1L, 500L);
        WardenWarningClock.Arm retiredArm = armOn(retired, 500L);
        retired.retired = true;
        assertEquals(WardenWarningClock.UNREADABLE, WardenWarningClock.elapsed(retiredArm, null));

        IslandRegistry.PIsland replaced = new IslandRegistry.PIsland(2L, 500L);
        WardenWarningClock.Arm replacedArm = armOn(replaced, 500L);
        replaced.domain.owner = new IslandRegistry.PIsland(3L, 500L);
        assertEquals(WardenWarningClock.UNREADABLE, WardenWarningClock.elapsed(replacedArm, null));

        IslandRegistry.PIsland rebased = new IslandRegistry.PIsland(4L, 500L);
        WardenWarningClock.Arm rebasedArm = armOn(rebased, 500L);
        rebased.logicalTick = 400L;
        assertEquals(WardenWarningClock.UNREADABLE, WardenWarningClock.elapsed(rebasedArm, null));
        assertTrue(WardenWarningClock.onCooldown(WardenWarningClock.elapsed(rebasedArm, null), 7, 500));
    }

    @Test
    void warningScopesNestAndRestore() {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);
        WardenWarningClock.Arm outer = armOn(island, 1L);
        WardenWarningClock.Arm inner = armOn(island, 2L);
        assertNull(WardenWarningClock.currentWarning());
        WardenWarningClock.Arm none = WardenWarningClock.beginWarning(outer);
        WardenWarningClock.Arm prev = WardenWarningClock.beginWarning(inner);
        assertSame(inner, WardenWarningClock.currentWarning());
        WardenWarningClock.endWarning(prev);
        assertSame(outer, WardenWarningClock.currentWarning());
        WardenWarningClock.endWarning(none);
        assertNull(WardenWarningClock.currentWarning());
    }
}
