package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMoveReadSnapshotTest {

    @Test
    void stableTupleSurvivesLiveRereadsAndNestedTransitionMasking() {
        SectionPos admittedLast = SectionPos.of(1, 2, 3);
        SectionPos admittedCurrent = SectionPos.of(1, 2, 3);
        SectionPos changedLast = SectionPos.of(4, 5, 6);
        SectionPos changedCurrent = SectionPos.of(7, 8, 9);

        PlayerMoveReadSnapshot.pushStable(
                admittedLast, admittedCurrent, true, true);
        try {
            assertSame(admittedLast,
                    PlayerMoveReadSnapshot.lastSectionOr(changedLast));
            assertSame(admittedCurrent,
                    PlayerMoveReadSnapshot.currentSectionOr(changedCurrent));
            assertTrue(PlayerMoveReadSnapshot.ignoredOr(false));
            assertTrue(PlayerMoveReadSnapshot.skipOr(false));

            PlayerMoveReadSnapshot.pushLive();
            try {
                assertSame(changedLast,
                        PlayerMoveReadSnapshot.lastSectionOr(changedLast));
                assertSame(changedCurrent,
                        PlayerMoveReadSnapshot.currentSectionOr(changedCurrent));
                assertFalse(PlayerMoveReadSnapshot.ignoredOr(false));
                assertFalse(PlayerMoveReadSnapshot.skipOr(false));
            } finally {
                PlayerMoveReadSnapshot.pop();
            }

            assertSame(admittedLast,
                    PlayerMoveReadSnapshot.lastSectionOr(changedLast));
            assertTrue(PlayerMoveReadSnapshot.ignoredOr(false));
        } finally {
            PlayerMoveReadSnapshot.pop();
        }

        assertSame(changedLast, PlayerMoveReadSnapshot.lastSectionOr(changedLast));
        assertFalse(PlayerMoveReadSnapshot.ignoredOr(false));
    }
}
