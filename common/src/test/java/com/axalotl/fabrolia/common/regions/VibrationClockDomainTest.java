package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sculk deafness fix (2026-07-27) — vibration candidates must live in the
 * LISTENER's clock domain.
 *
 * <p>Vanilla's {@code VibrationSelector} assumes ONE clock: candidates are
 * stamped with the emitter's {@code getGameTime()}, selection requires
 * {@code stamp < listenerNow} strictly, and a pending candidate suppresses
 * any newcomer carrying a different stamp. Under async islands the emitter
 * and listener can read different clocks (main wall W vs island logical L,
 * island A vs island B), so an emitter-stamped candidate can sit strictly in
 * the listener's future — unreachable for W−L ticks (forever on a long-lived
 * lagging island) while ALSO suppressing every correctly-stamped event. That
 * is the operator's exact field matrix: player events (main-emitted) dead on
 * the multiplayer server, delayed/stuttering on a fresh local one, mob events
 * (island-emitted, same clock) mostly fine.
 *
 * <p>{@link MirrorSelector} mirrors vanilla's selector logic statement for
 * statement (addCandidate/shouldReplaceVibration/chosenCandidate/startOver)
 * plus the two Fabrolia additions under test, exactly as
 * {@code VibrationSelectorMixin} installs them: the future-residue heal in
 * addCandidate and the pending-stamp mirror. Stamps come from
 * {@link VibrationDomains}' pure functions — the same ones the production
 * mixin path uses.
 */
class VibrationClockDomainTest {

    /** Statement-for-statement mirror of vanilla {@code VibrationSelector}
     *  with {@code VibrationSelectorMixin}'s wraps applied. */
    private static final class MirrorSelector {
        record Pending(String tag, float distance, int frequency, long tick) {}

        private Pending pending;
        private long pendingStamp = VibrationDomains.NO_STAMP; // @Unique mirror

        /** VibrationSelectorMixin.fabrolia$healThenAdd wrapping vanilla addCandidate. */
        void addCandidate(String tag, float distance, int frequency, long tickTime) {
            if (VibrationDomains.pendingIsFutureResidue(pendingStamp, tickTime)) {
                startOver();
            }
            // vanilla addCandidate
            if (shouldReplaceVibration(distance, frequency, tickTime)) {
                pending = new Pending(tag, distance, frequency, tickTime);
            }
            // mixin tail: the empty→occupied transition is the only stamp change
            if (pendingStamp == VibrationDomains.NO_STAMP) {
                pendingStamp = tickTime;
            }
        }

        /** vanilla shouldReplaceVibration, verbatim. */
        private boolean shouldReplaceVibration(float distance, int frequency, long tickTime) {
            if (pending == null) return true;
            if (tickTime != pending.tick) return false;
            if (distance < pending.distance) return true;
            if (distance > pending.distance) return false;
            return frequency > pending.frequency;
        }

        /** vanilla chosenCandidate, verbatim: strictly-older stamps only. */
        String chosenCandidate(long time) {
            if (pending == null) return null;
            return pending.tick < time ? pending.tag : null;
        }

        /** vanilla startOver + mixin stamp reset. */
        void startOver() {
            pending = null;
            pendingStamp = VibrationDomains.NO_STAMP;
        }

        /** The listener's ticker step (VibrationSystem.Ticker): select, then clear. */
        String tickListener(long listenerNow) {
            String chosen = chosenCandidate(listenerNow);
            if (chosen != null) startOver();
            return chosen;
        }
    }

    // ------------------------------------------------------------------
    // domainNow — the three publication shapes of an island clock
    // ------------------------------------------------------------------

    @Test
    void domainNowCoversAllThreeUnitShapes() {
        // fresh island, no unit ever dispatched: unitGameTime is still 0
        assertEquals(120, VibrationDomains.domainNow(120, 0),
                "before the first unit the completed count is the clock");
        // mid-unit: unitGameTime published as L+1 at the unit head
        assertEquals(121, VibrationDomains.domainNow(120, 121),
                "mid-unit the island's readers see L+1");
        // between units: logicalTick caught up to the last unit's value
        assertEquals(121, VibrationDomains.domainNow(121, 121),
                "between units both counters agree");
    }

    @Test
    void futureResiduePredicateEdges() {
        long now = 120;
        assertFalse(VibrationDomains.pendingIsFutureResidue(VibrationDomains.NO_STAMP, now),
                "empty slot is never residue");
        assertFalse(VibrationDomains.pendingIsFutureResidue(now, now),
                "a stamp equal to now is a legitimate same-tick candidate");
        assertFalse(VibrationDomains.pendingIsFutureResidue(now - 1, now),
                "past stamps are the normal pending shape");
        assertTrue(VibrationDomains.pendingIsFutureResidue(now + 1, now),
                "a stamp the listener clock has not reached cannot have been "
                        + "produced in the listener's own timeline");
    }

    // ------------------------------------------------------------------
    // 1. immediate player-to-sculk delivery
    // ------------------------------------------------------------------

    /** A player event emitted from the MAIN thread (wall clock far ahead of the
     *  sensor's island) is stamped in the LISTENER's domain and is chosen at
     *  the listener's very next tick — the vanilla envelope — instead of being
     *  deferred by the wall−island gap. */
    @Test
    void mainEmittedPlayerEventDeliversAtTheListenersNextTick() {
        long wallNow = 10_000;             // main's getGameTime at emit time
        long islandL = 120;                // sensor island trails by 9880 ticks
        long listenerNow = VibrationDomains.domainNow(islandL, islandL);
        assertTrue(wallNow > listenerNow, "scenario: island trails wall");

        MirrorSelector sensor = new MirrorSelector();
        // production path: VibrationListenerClockMixin replaces the emitter
        // stamp (wallNow) with the listener-domain stamp
        sensor.addCandidate("player-step", 4.0f, 1, listenerNow);

        assertNull(sensor.chosenCandidate(listenerNow),
                "not visible within the emission tick — vanilla's strict <");
        assertEquals("player-step", sensor.tickListener(listenerNow + 1),
                "chosen at the listener's NEXT tick — same-tick-envelope "
                        + "delivery, not deferred by the 9880-tick wall gap");
    }

    /** The defect this fix removes, pinned: with the emitter's wall stamp kept
     *  (pre-fix behavior), the candidate is unreachable until the island clock
     *  crosses the wall value — the multiplayer total-deafness shape. */
    @Test
    void wallStampedCandidateIsUnreachableOnALaggingIsland_theDefect() {
        long wallNow = 10_000;
        long islandL = 120;

        MirrorSelector sensor = new MirrorSelector();
        sensor.addCandidate("player-step", 4.0f, 1, wallNow); // pre-fix stamp

        for (long t = islandL + 1; t <= islandL + 100; t++) {
            assertNull(sensor.chosenCandidate(t),
                    "island ticks past the event for 100 ticks and never sees it");
        }
        assertEquals("player-step", sensor.chosenCandidate(wallNow + 1),
                "only reachable once the island clock crosses the wall stamp — "
                        + "on a permanently-lagging island, never");
    }

    /** And the second half of the defect: the stuck future-stamped candidate
     *  SUPPRESSES correctly-stamped events (different stamp → newcomer
     *  discarded). The mixin's heal clears the residue so the live event
     *  takes the slot and delivers next tick — route, never drop. */
    @Test
    void futureResidueIsHealedByTheNextLiveCandidate() {
        long wallNow = 10_000;
        long islandL = 120;
        long listenerNow = VibrationDomains.domainNow(islandL, islandL);

        MirrorSelector sensor = new MirrorSelector();
        // residue: e.g. loaded from a pre-fix save (codec ctor path) or
        // stamped before the fix deployed
        sensor.addCandidate("stale-wall-stamp", 4.0f, 1, wallNow);
        assertNull(sensor.tickListener(listenerNow + 1), "deaf while poisoned");

        // a live event arrives, stamped in the listener's domain
        sensor.addCandidate("mob-step", 6.0f, 1, listenerNow + 1);
        assertEquals("mob-step", sensor.tickListener(listenerNow + 2),
                "the heal evicts the unreachable residue; without it vanilla's "
                        + "different-stamp rule would discard every live event "
                        + "until the island clock reached " + wallNow);
    }

    // ------------------------------------------------------------------
    // 2. cross-island routing lands in the right clock domain
    // ------------------------------------------------------------------

    /** An event emitted mid-unit by island A (clock far ahead) for a listener
     *  owned by island B is routed onto B's clock and delivered at B's next
     *  tick — not dropped, not deferred by the A−B skew. */
    @Test
    void crossIslandEventLandsInTheListenersDomain() {
        long emitterNow = VibrationDomains.domainNow(5000, 5001);  // A mid-unit
        long listenerNow = VibrationDomains.domainNow(120, 121);   // B mid-unit
        assertEquals(5001, emitterNow);
        assertEquals(121, listenerNow);

        MirrorSelector sensor = new MirrorSelector();
        // production path stamps with B's clock, never A's
        sensor.addCandidate("cross-island", 8.0f, 2, listenerNow);

        assertNull(sensor.chosenCandidate(listenerNow), "strict <, vanilla");
        assertEquals("cross-island", sensor.tickListener(listenerNow + 1),
                "delivered at B's next tick in B's own timeline");
    }

    /** The reverse skew (emitter behind the listener) also delivers next tick:
     *  the stamp is the listener's, so relative island rates are irrelevant. */
    @Test
    void reverseSkewDeliversIdentically() {
        long listenerNow = VibrationDomains.domainNow(5000, 5000); // B between units
        MirrorSelector sensor = new MirrorSelector();
        sensor.addCandidate("from-younger-island", 2.0f, 1, listenerNow);
        assertEquals("from-younger-island", sensor.tickListener(listenerNow + 1));
    }

    // ------------------------------------------------------------------
    // 3. no regression — same-clock (vanilla-shaped) histories are untouched
    // ------------------------------------------------------------------

    /** Non-player sources on the listener's own island: single-clock history,
     *  every vanilla selector rule bit-identical (the heal branch is dead). */
    @Test
    void sameDomainSelectionRulesAreVanilla() {
        long now = 121; // island mid-unit; every stamp below is this clock's
        MirrorSelector sensor = new MirrorSelector();

        // same tick: closer replaces
        sensor.addCandidate("far-step", 8.0f, 1, now);
        sensor.addCandidate("near-step", 3.0f, 1, now);
        assertEquals("near-step", sensor.chosenCandidate(now + 1), "closer wins in-tick");

        // same tick, same distance: higher frequency replaces
        sensor.startOver();
        sensor.addCandidate("step", 4.0f, 1, now);
        sensor.addCandidate("explosion", 4.0f, 15, now);
        assertEquals("explosion", sensor.chosenCandidate(now + 1), "louder wins in-tick");

        // later (PAST-consistent) stamp never replaces a pending candidate —
        // vanilla's rule, still intact because a pending PAST stamp is not
        // future residue
        sensor.startOver();
        sensor.addCandidate("first", 4.0f, 1, now);
        sensor.addCandidate("second", 1.0f, 15, now + 1);
        assertEquals("first", sensor.tickListener(now + 1),
                "the pending candidate keeps the slot exactly as vanilla");

        // and the suppressed newcomer's WINDOW is not lost forever: the next
        // event after consumption delivers normally
        sensor.addCandidate("third", 1.0f, 1, now + 1);
        assertEquals("third", sensor.tickListener(now + 2));
    }

    /** A mob event arriving while the island is between units (stamped with the
     *  completed L) is chosen by the island's next unit (L+1) — the between-
     *  ticks emission shape, delivered exactly one listener tick later. */
    @Test
    void betweenUnitEmissionDeliversAtTheNextUnit() {
        long completedL = 300;
        long stamp = VibrationDomains.domainNow(completedL, completedL);
        MirrorSelector sensor = new MirrorSelector();
        sensor.addCandidate("mob-step", 5.0f, 1, stamp);
        assertEquals("mob-step", sensor.tickListener(completedL + 1));
    }

    /** Sequential vibrations across many listener ticks: every one delivers,
     *  in order, one per selection cycle — no starvation introduced. */
    @Test
    void steadyStreamDeliversEveryEvent() {
        MirrorSelector sensor = new MirrorSelector();
        long clock = 500;
        for (int i = 0; i < 50; i++) {
            sensor.addCandidate("evt" + i, 2.0f, 1, clock);
            clock++;
            assertEquals("evt" + i, sensor.tickListener(clock),
                    "event emitted at tick " + (clock - 1) + " delivers at " + clock);
        }
        assertEquals(550, clock, "50 emission+selection cycles completed");
    }
}
