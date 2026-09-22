package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedstoneTorchHistoryTest {
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(1024, 64, 0);

    @Test
    void aFastIslandCannotEraseSevenFreshTogglesOnASlowIsland() {
        RedstoneTorchHistory.History history = new RedstoneTorchHistory.History();
        for (int i = 0; i < 7; i++) assertFalse(history.toggled(A, 100, true));
        history.toggled(B, 1000, true);
        history.purge(pos -> pos.equals(A) ? 100 : 1000);
        assertTrue(history.toggled(A, 100, true), "the eighth local toggle must burn out");
        history.purge(pos -> pos.equals(A) ? 160 : 5000);
        assertTrue(history.toggled(A, 160, false), "sixty local ticks are still in the window");
        history.purge(pos -> pos.equals(A) ? 161 : 5000);
        assertFalse(history.toggled(A, 161, false), "sixty-one local ticks expire the window");
        assertFalse(history.toggled(B, 5000, false), "abandoned foreign history also expires on its own clock");
    }

    @Test
    void mergeAndGlobalCutoverPreserveRemainingLocalWindow() {
        RedstoneTorchHistory.History history = new RedstoneTorchHistory.History();
        for (int i = 0; i < 8; i++) history.toggled(A, 100, true);
        for (int i = 0; i < 8; i++) history.toggled(B, 2000, true);
        // A migrates at L120 to a younger island at L20: its history is 20 ticks old.
        history.rebase(A::equals, 20 - 120);
        assertTrue(history.toggled(A, 60, false));
        assertFalse(history.toggled(A, 61, false));
        assertTrue(history.toggled(B, 2060, false), "unaffected positions must not be rebased");
        // B then migrates from L2060 into global time 10000, with one tick remaining.
        history.rebase(B::equals, 10000 - 2060);
        assertTrue(history.toggled(B, 10000, false));
        assertFalse(history.toggled(B, 10001, false));
    }
}
