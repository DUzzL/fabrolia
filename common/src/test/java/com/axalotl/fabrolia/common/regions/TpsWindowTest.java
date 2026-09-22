package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpsWindowTest {

    @Test
    void repeatedReadersSeeTheSameCompletedWindow() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        assertFalse(window.update(100, 10_000_000_000L));
        assertTrue(window.update(120, 11_000_000_000L));
        assertEquals(20.0, window.value(), 1.0e-9);

        // A second HUD subscriber/admin row used to divide zero completions by
        // a few microseconds after the first reader reset the shared baseline.
        assertFalse(window.update(120, 11_000_000_000L));
        assertEquals(20.0, window.value(), 1.0e-9);
        assertFalse(window.update(120, 11_000_001_000L));
        assertEquals(20.0, window.value(), 1.0e-9);
    }

    @Test
    void subSecondReadsCannotCreateAliasedSpikes() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        window.update(0, 1_000_000_000L);
        window.update(20, 2_000_000_000L);
        assertEquals(20.0, window.value(), 1.0e-9);

        // Five completions in 200ms would be reported as 25 TPS by the old
        // caller-dependent formula. Keep the completed one-second sample.
        assertFalse(window.update(25, 2_200_000_000L));
        assertEquals(20.0, window.value(), 1.0e-9);
    }

    @Test
    void irregularCadenceUsesTheWholeRealTimeWindow() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        window.update(500, 5_000_000_000L);
        assertFalse(window.update(516, 5_980_000_000L));
        assertFalse(window.hasSample());

        assertTrue(window.update(517, 6_041_666_667L));
        assertEquals(16.32, window.value(), 0.01);
    }

    @Test
    void counterOrClockResetStartsACleanWindow() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        window.update(100, 10_000_000_000L);
        window.update(120, 11_000_000_000L);
        assertEquals(20.0, window.value(), 1.0e-9);

        assertFalse(window.update(3, 12_000_000_000L));
        assertFalse(window.hasSample());
        assertTrue(Double.isNaN(window.value()));
        assertTrue(window.update(23, 13_000_000_000L));
        assertEquals(20.0, window.value(), 1.0e-9);

        assertFalse(window.update(24, 12_999_999_999L));
        assertFalse(window.hasSample());
        assertTrue(Double.isNaN(window.value()));
    }

    @Test
    void noSampleThenMeasuredProgressThenMeasuredZeroAreDistinct() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        assertFalse(window.hasSample());
        window.update(100, 10_000_000_000L);
        assertFalse(window.hasSample());
        assertTrue(Double.isNaN(window.value()));
        window.update(120, 11_000_000_000L);
        assertTrue(window.hasSample());
        assertEquals(20.0, window.value());
        window.update(120, 12_000_000_000L);
        assertTrue(window.hasSample());
        assertEquals(0.0, window.value());
    }

    @Test
    void resetInsideUncommittedWindowAlsoInvalidatesTheOldRate() {
        TpsWindow window = new TpsWindow(TpsWindow.ONE_SECOND_NANOS);
        window.update(100, 10_000_000_000L);
        window.update(120, 11_000_000_000L);
        window.update(125, 11_500_000_000L);
        window.update(124, 11_600_000_000L);
        assertFalse(window.hasSample());
        window.update(144, 12_600_000_000L);
        assertEquals(20.0, window.value());
        window.update(145, 12_800_000_000L);
        window.update(146, 12_700_000_000L);
        assertFalse(window.hasSample());
    }
}
