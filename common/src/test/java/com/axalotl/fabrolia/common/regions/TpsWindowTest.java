package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpsWindowTest {

    @Test
    void repeatedReadersSeeTheSameCompletedWindow() {
        TpsWindow window = newWindow();
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
        TpsWindow window = newWindow();
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
        TpsWindow window = newWindow();
        window.update(500, 5_000_000_000L);
        assertFalse(window.update(516, 5_980_000_000L));
        assertFalse(hasSample(window));

        assertTrue(window.update(517, 6_041_666_667L));
        assertEquals(16.32, window.value(), 0.01);
    }

    @Test
    void counterOrClockResetStartsACleanWindow() {
        TpsWindow window = newWindow();
        window.update(100, 10_000_000_000L);
        window.update(120, 11_000_000_000L);
        assertEquals(20.0, window.value(), 1.0e-9);

        assertFalse(window.update(3, 12_000_000_000L));
        assertFalse(hasSample(window));
        assertTrue(Double.isNaN(window.value()));
        assertTrue(window.update(23, 13_000_000_000L));
        assertEquals(20.0, window.value(), 1.0e-9);

        assertFalse(window.update(24, 12_999_999_999L));
        assertFalse(hasSample(window));
        assertTrue(Double.isNaN(window.value()));
    }

    @Test
    void noSampleThenMeasuredProgressThenMeasuredZeroAreDistinct() {
        TpsWindow window = newWindow();
        assertFalse(hasSample(window));
        window.update(100, 10_000_000_000L);
        assertFalse(hasSample(window));
        assertTrue(Double.isNaN(window.value()));
        window.update(120, 11_000_000_000L);
        assertTrue(hasSample(window));
        assertEquals(20.0, window.value());
        window.update(120, 12_000_000_000L);
        assertTrue(hasSample(window));
        assertEquals(0.0, window.value());
    }

    @Test
    void aFiveWindowSpanSmoothsASlowUnitPushingATickIntoTheNextSecond() {
        // A 20 TPS island whose occasional 60-100 ms unit pushes one tick into
        // the next second: single windows read 19 then 21 (production showed
        // "20.8 TPS" next to "19.8"). Five windows stay within 0.25 of 20 and
        // still refresh every second.
        TpsWindow one = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, Double.NaN);
        TpsWindow five = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, Double.NaN, 5);
        int[] perSecond = {20, 19, 21, 20, 20, 21, 19, 20, 20, 19, 21};
        long count = 0;
        long now = 0;
        one.update(count, now);
        five.update(count, now);
        for (int i = 0; i < perSecond.length; i++) {
            count += perSecond[i];
            now += TpsWindow.ONE_SECOND_NANOS;
            assertTrue(one.update(count, now));
            assertTrue(five.update(count, now), "the span still commits every window");
            assertEquals(perSecond[i], one.value(), 1.0e-9);
            if (i >= 4) assertEquals(20.0, five.value(), 0.25);
        }
    }

    @Test
    void aSpanStartsOverAfterACounterReset() {
        TpsWindow five = new TpsWindow(TpsWindow.ONE_SECOND_NANOS, Double.NaN, 5);
        five.update(0, 0);
        five.update(20, 1_000_000_000L);
        five.update(40, 2_000_000_000L);
        assertEquals(20.0, five.value(), 1.0e-9);
        assertFalse(five.update(5, 3_000_000_000L));
        assertTrue(Double.isNaN(five.value()));
        assertTrue(five.update(15, 4_000_000_000L));
        assertEquals(10.0, five.value(), 1.0e-9, "no point from before the reset may count");
    }

    private static TpsWindow newWindow() {
        return new TpsWindow(TpsWindow.ONE_SECOND_NANOS, Double.NaN);
    }

    private static boolean hasSample(TpsWindow window) {
        return !Double.isNaN(window.value());
    }
}
