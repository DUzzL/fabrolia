package com.axalotl.fabrolia.common.mixin.lithium;

import com.axalotl.fabrolia.common.regions.BlockEntitySleepDomains;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Never-waking-hopper fix (2026-08-04) — Lithium's sleeping-hopper deadlines
 * and failed-search stamps must survive a clock-domain change.
 *
 * <p>Lithium's mixins do not apply on the test classpath, so its contract is
 * emulated with mirrors — the house pattern ({@code TickRemoveIfImplAgnosticTest},
 * {@code VibrationClockDomainTest}). {@link MirrorInvoker} mirrors
 * {@code SleepUntilTimeBlockEntityTickInvoker.tick()} statement for statement,
 * with {@code FabroliaLithiumSleepInvokerMixin}'s residue wrap applied exactly
 * as the production mixin installs it ({@code fixed = true}) or Lithium-raw
 * ({@code fixed = false}); {@link MirrorCollect} mirrors the
 * {@code lithiumGetInputItemEntities} tracker shortcut with
 * {@code FabroliaLithiumHopperSleepMixin}'s pushItemsTick-head heal. The
 * clock-domain decisions under test come from {@link BlockEntitySleepDomains}
 * — the same production functions the mixins call.
 */
class HopperSleepClockDomainTest {

    // ---------------------------------------------------------------- mirrors

    /** Mirror of {@code SleepUntilTimeBlockEntityTickInvoker.tick()}; with
     *  {@code fixed}, the {@code FabroliaLithiumSleepInvokerMixin} wrap runs
     *  first, exactly as merged. */
    private static final class MirrorInvoker {
        final long sleepUntilTickExclusive;
        final boolean fixed;
        boolean awake;
        int delegateTicks;

        MirrorInvoker(long deadlineExclusive, boolean fixed) {
            this.sleepUntilTickExclusive = deadlineExclusive;
            this.fixed = fixed;
        }

        /** One BE-phase evaluation under the evaluating venue's clock. */
        void tick(long evaluatorNow) {
            if (awake) { // post-wake the wrapper holds the delegate again
                delegateTicks++;
                return;
            }
            if (fixed && BlockEntitySleepDomains.deadlineUnreachable(evaluatorNow, sleepUntilTickExclusive)) {
                awake = true;
                delegateTicks++;
                return;
            }
            // lithium verbatim
            if (evaluatorNow >= sleepUntilTickExclusive) {
                awake = true;
                delegateTicks++;
            }
        }
    }

    /** Max-merge movement tracker: lithium's post-Fabrolia
     *  {@code isUnchangedSince} semantics ({@code lastChecked <= max} = changed). */
    private static final class MirrorTracker {
        long maxChangeTime = Long.MIN_VALUE;

        void emitMovement(long atTime) {
            maxChangeTime = Math.max(maxChangeTime, atTime);
        }

        boolean isUnchangedSince(long lastCheckedTime) {
            return lastCheckedTime > maxChangeTime;
        }
    }

    /** Mirror of lithium's collect shortcut in
     *  {@code lithiumGetInputItemEntities} — wasEmpty gate, unchanged-since
     *  shortcut, stamp refresh in BOTH branches — with the
     *  {@code FabroliaLithiumHopperSleepMixin} heal at the tick head. */
    private static final class MirrorCollect {
        final boolean fixed;
        long collectItemEntityAttemptTime = Long.MIN_VALUE;
        boolean wasEmpty = true;
        int realSearches;

        MirrorCollect(boolean fixed) {
            this.fixed = fixed;
        }

        /** @return items collected this tick */
        boolean tick(long tickedGameTime, MirrorTracker tracker, boolean itemsPresent) {
            if (fixed) { // fabrolia$onPushItemsTick, before any shortcut
                collectItemEntityAttemptTime = BlockEntitySleepDomains
                        .healSearchStamp(collectItemEntityAttemptTime, tickedGameTime);
            }
            if (wasEmpty && tracker.isUnchangedSince(collectItemEntityAttemptTime)) {
                collectItemEntityAttemptTime = tickedGameTime;
                return false; // Collections.emptyList()
            }
            realSearches++;
            collectItemEntityAttemptTime = tickedGameTime;
            wasEmpty = !itemsPresent;
            return itemsPresent;
        }
    }

    // ------------------------------------------------- invoker: same timeline

    /** One clock (vanilla / same island throughout): the fix must be
     *  BIT-IDENTICAL to Lithium — still asleep at stamp tick, awake exactly
     *  one tick later. Pins that the residue rule never over-wakes a legal
     *  mid-sleep state. */
    @Test
    void sameTimelineIsBitIdenticalToLithium() {
        long l = 2000;
        for (boolean fixed : new boolean[]{false, true}) {
            MirrorInvoker inv = new MirrorInvoker(l + 1, fixed);
            inv.tick(l); // BE phase of the stamping tick
            assertFalse(inv.awake, "must sleep through the stamping tick (fixed=" + fixed + ")");
            assertEquals(0, inv.delegateTicks);
            inv.tick(l + 1);
            assertTrue(inv.awake, "must wake exactly next tick (fixed=" + fixed + ")");
            assertEquals(1, inv.delegateTicks);
        }
    }

    // --------------------------------------- invoker: cross-clock wedge/heal

    /** The production wedge: deadline stamped on a fresh-born island clock
     *  (born at raw game time, L ≈ W) whose cell then merges into a
     *  long-lived machine island trailing wall by thousands of ticks
     *  (survivor keeps its clock; the Lithium record is invisible to the
     *  rebase). Raw Lithium sleeps for the full clock gap — forever, for any
     *  practical session; the fixed shape wakes on the first evaluation. */
    @Test
    void mergeOntoOlderClockWakesInsteadOfSleepingForever() {
        long freshIslandStamp = 100_000;             // W-born clock at stamp time
        long survivorClock = 2_000;                  // machine island's L

        MirrorInvoker raw = new MirrorInvoker(freshIslandStamp + 1, false);
        for (long now = survivorClock; now < survivorClock + 10_000; now++) {
            raw.tick(now);
        }
        assertFalse(raw.awake, "pre-fix shape: 10k island ticks and the delegate never ran");
        assertEquals(0, raw.delegateTicks);

        MirrorInvoker fixed = new MirrorInvoker(freshIslandStamp + 1, true);
        fixed.tick(survivorClock);
        assertTrue(fixed.awake, "fixed shape: cross-clock residue wakes on first evaluation");
        assertEquals(1, fixed.delegateTicks);
    }

    /** Foreign-venue stamp: main (wall W) sets the cooldown of a sleeping
     *  hopper on an island trailing at L ≪ W. Raw Lithium's deadline W+1 is
     *  unreachable until the island somehow catches up W−L ticks; fixed wakes
     *  immediately (degrades to a vanilla tick — the safe direction). */
    @Test
    void mainThreadStampOnLaggingIslandWakes() {
        long wall = 50_000;
        long islandL = 1_000;

        MirrorInvoker raw = new MirrorInvoker(wall + 1, false);
        for (long now = islandL; now < islandL + 5_000; now++) {
            raw.tick(now);
        }
        assertFalse(raw.awake, "pre-fix shape: W+1 deadline unreachable under island L");

        MirrorInvoker fixed = new MirrorInvoker(wall + 1, true);
        fixed.tick(islandL);
        assertTrue(fixed.awake);
    }

    /** Boundary pin for the residue rule itself: now+1 is the legal mid-sleep
     *  maximum (sleep); now+2 is beyond any legal stamp (residue → wake);
     *  clock jumped PAST the deadline (merge onto a newer clock) wakes via
     *  Lithium's own branch. */
    @Test
    void residueBoundaryIsExactlyMaxRelativeSleep() {
        assertFalse(BlockEntitySleepDomains.deadlineUnreachable(2000, 2001),
                "now+1 is the legal sleepOnlyCurrentTick stamp");
        assertTrue(BlockEntitySleepDomains.deadlineUnreachable(2000, 2002),
                "beyond now+MAX_RELATIVE_SLEEP only cross-domain residue exists");
        MirrorInvoker jumpedPast = new MirrorInvoker(2001, true);
        jumpedPast.tick(90_000);
        assertTrue(jumpedPast.awake, "clock ahead of deadline wakes via lithium's own compare");
    }

    // ------------------------------------------- collect path: residue stamp

    /** The second forever-hole: an attempt stamp left in the hopper's future
     *  by a clock drop swallows the ONE movement event a landing item emits
     *  (verdict "unchanged since 100000" over a change at 2000); the next
     *  tick's refreshed stamp is already past the event and the resting pile
     *  is invisible forever. The heal clamps the future stamp at the tick
     *  head, forcing one real search that finds the pile. */
    @Test
    void futureAttemptStampMissesRestingItemsForever() {
        long residueStamp = 100_000;   // stamped as tickedGameTime pre-merge
        long islandL = 2_000;

        // raw shape
        MirrorCollect raw = new MirrorCollect(false);
        raw.collectItemEntityAttemptTime = residueStamp;
        MirrorTracker rawTracker = new MirrorTracker();
        rawTracker.emitMovement(islandL); // the item lands, then rests: one event
        boolean rawGot = false;
        for (long now = islandL + 1; now <= islandL + 100; now++) {
            rawGot |= raw.tick(now, rawTracker, true);
        }
        assertFalse(rawGot, "pre-fix shape: the resting pile is never searched for");
        assertEquals(0, raw.realSearches);

        // fixed shape
        MirrorCollect fixed = new MirrorCollect(true);
        fixed.collectItemEntityAttemptTime = residueStamp;
        MirrorTracker fixedTracker = new MirrorTracker();
        fixedTracker.emitMovement(islandL);
        assertTrue(fixed.tick(islandL + 1, fixedTracker, true),
                "healed stamp forces one real search that finds the pile");
        assertEquals(1, fixed.realSearches);
    }

    /** Healthy single-timeline collect behaviour is untouched by the heal:
     *  identical search counts with and without the fix — including the
     *  shortcut actually SKIPPING when nothing moved (the optimisation must
     *  survive). */
    @Test
    void sameTimelineCollectShortcutSurvivesTheHeal() {
        for (boolean fixedFlag : new boolean[]{false, true}) {
            MirrorCollect h = new MirrorCollect(fixedFlag);
            MirrorTracker t = new MirrorTracker();
            long now = 3_000;
            assertFalse(h.tick(now, t, false), "empty world: first tick searches, finds nothing");
            assertEquals(1, h.realSearches, "cold stamp (MIN) always searches once");
            int after = h.realSearches;
            assertFalse(h.tick(now + 1, t, false), "no movement: shortcut skips");
            assertEquals(after, h.realSearches, "no movement must not add a search (fixed=" + fixedFlag + ")");
            t.emitMovement(now + 2); // an item arrives
            assertTrue(h.tick(now + 2, t, true), "movement defeats the shortcut and finds the item");
        }
    }
}
