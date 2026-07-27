package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit10 — an entity may tick AT MOST ONCE per wall tick regardless of island
 * rebuilds, merges, splits or ownership changes, and AT LEAST ONCE per epoch.
 *
 * <p>The second clause is what proves the fix cannot freeze anything, so every
 * scenario below ends by advancing the epoch and asserting eligibility returns.
 *
 * <p>Defect being pinned: each {@code PIsland} owns a fresh {@link EpochDomain},
 * and {@code tickToken()} is {@code tickTokenPrefix | epoch}. A rebuild therefore
 * minted a brand-new prefix, silently invalidating every in-flight "already ticked"
 * mark — entities became eligible again inside the same wall tick and took an extra
 * movement tick. The End island was observed going #1g3 -> #1g6 in one session.
 */
class TickIdentityAcrossIslandTransitionsTest {

    /**
     * Mirrors {@code EntityMixin}'s backing field and both mark operations exactly:
     * an AtomicLong, a CAS-loop stamp, and a pure CAS carry.
     */
    private static final class Marked {
        final AtomicLong mark = new AtomicLong();

        /** @return true if this call performed the tick (i.e. was not already stamped). */
        boolean tryMark(long token) {
            long observed;
            do {
                observed = mark.get();
                if (observed == token) return false;
            } while (!mark.compareAndSet(observed, token));
            return true;
        }

        boolean carry(long expectedFrom, long newToken) {
            if (expectedFrom == newToken) return false;
            return mark.compareAndSet(expectedFrom, newToken);
        }
    }

    private static EpochDomain domain(long epoch) {
        EpochDomain d = new EpochDomain();
        d.epoch = epoch;
        return d;
    }

    /** A rebuild/split successor: fresh instance that adopts the predecessor. */
    private static EpochDomain successorOf(EpochDomain pred) {
        EpochDomain d = new EpochDomain();
        d.adoptTickIdentityFrom(pred);
        return d;
    }

    // ------------------------------------------------------------------
    // 1-2. rebuild
    // ------------------------------------------------------------------

    @Test
    void markedEntityCannotTickTwiceAcrossARebuild() {
        EpochDomain before = domain(500);
        Marked e = new Marked();
        assertTrue(e.tryMark(before.tickToken()), "first tick of the epoch");

        EpochDomain after = successorOf(before);
        assertFalse(e.tryMark(after.tickToken()),
                "the rebuild minted a new EpochDomain; without identity adoption its "
                        + "fresh prefix would un-mark this entity and hand it a second "
                        + "movement tick in the same wall tick — the audit8 signature");
    }

    @Test
    void unmarkedEntityStillTicksAfterARebuild() {
        EpochDomain before = domain(500);
        EpochDomain after = successorOf(before);

        Marked fresh = new Marked();
        assertTrue(fresh.tryMark(after.tickToken()),
                "an entity that had not ticked this epoch must still tick — adoption "
                        + "must not suppress work that was never done");
    }

    // ------------------------------------------------------------------
    // 3. merge — survivor keeps its own identity, marks are carried
    // ------------------------------------------------------------------

    @Test
    void mergeCannotDoubleTickEntitiesFromEitherPredecessor() {
        EpochDomain survivor = domain(700);
        EpochDomain absorbed = domain(700);

        Marked fromSurvivor = new Marked();
        Marked fromAbsorbed = new Marked();
        assertTrue(fromSurvivor.tryMark(survivor.tickToken()));
        assertTrue(fromAbsorbed.tryMark(absorbed.tickToken()));

        // indexMove carries the absorbed island's mark onto the survivor's token.
        assertTrue(fromAbsorbed.carry(absorbed.tickToken(), survivor.tickToken()),
                "a ticked entity migrating into the survivor carries its mark");

        assertFalse(fromSurvivor.tryMark(survivor.tickToken()),
                "the survivor's own entities are unaffected");
        assertFalse(fromAbsorbed.tryMark(survivor.tickToken()),
                "and the migrated one must not tick a second time under the survivor");
    }

    @Test
    void mergeDoesNotMarkAnEntityThatHadNotTicked() {
        EpochDomain survivor = domain(700);
        EpochDomain absorbed = domain(700);

        Marked idle = new Marked();   // never ticked in either island
        assertFalse(idle.carry(absorbed.tickToken(), survivor.tickToken()),
                "carry is a CAS: nothing to carry means nothing is written");
        assertTrue(idle.tryMark(survivor.tickToken()),
                "so it is still owed its tick — this is the freeze case the CAS prevents");
    }

    // ------------------------------------------------------------------
    // 4. split
    // ------------------------------------------------------------------

    @Test
    void splitCannotDoubleTickMigratedEntities() {
        EpochDomain source = domain(900);
        Marked migrated = new Marked();
        assertTrue(migrated.tryMark(source.tickToken()));

        // The identity-retaining fragment keeps `source`; fresh fragments adopt it.
        EpochDomain freshFragment = successorOf(source);
        assertFalse(migrated.tryMark(freshFragment.tickToken()),
                "an entity that already ticked in the pre-split island must not tick "
                        + "again in the fragment it lands in");
    }

    // ------------------------------------------------------------------
    // 5. liveness — the no-freeze clause
    // ------------------------------------------------------------------

    @Test
    void everyEntityBecomesEligibleAgainOnTheNextEpoch() {
        EpochDomain before = domain(1000);
        Marked rebuilt = new Marked();
        Marked merged = new Marked();
        assertTrue(rebuilt.tryMark(before.tickToken()));

        EpochDomain survivor = domain(1000);
        assertTrue(merged.tryMark(survivor.tickToken()));

        EpochDomain after = successorOf(before);
        assertFalse(rebuilt.tryMark(after.tickToken()));
        assertFalse(merged.tryMark(survivor.tickToken()));

        after.epoch++;      // the successor's next unit
        survivor.epoch++;
        assertTrue(rebuilt.tryMark(after.tickToken()),
                "an inherited mark suppresses at most the unit already accounted for");
        assertTrue(merged.tryMark(survivor.tickToken()),
                "a carried mark likewise expires with its epoch — no permanent skip");
    }

    @Test
    void repeatedTransitionsInOneWallTickNeitherDoubleTickNorFreeze() {
        EpochDomain d = domain(1234);
        Marked e = new Marked();
        assertTrue(e.tryMark(d.tickToken()), "ticked once this wall tick");

        for (int i = 0; i < 25; i++) {     // rebuild churn while a player walks through
            d = successorOf(d);
            assertFalse(e.tryMark(d.tickToken()),
                    "transition " + i + " must not resurrect eligibility");
        }

        d.epoch++;
        assertTrue(e.tryMark(d.tickToken()),
                "after 25 transitions the entity is still alive and ticks next epoch");
    }

    // ------------------------------------------------------------------
    // the carry operation itself
    // ------------------------------------------------------------------

    @Test
    void carryReplacesOnlyAnExactlyMatchingToken() {
        EpochDomain from = domain(10);
        EpochDomain to = domain(10);
        Marked e = new Marked();
        e.tryMark(from.tickToken());

        assertTrue(e.carry(from.tickToken(), to.tickToken()));
        assertEquals(to.tickToken(), e.mark.get());
    }

    @Test
    void carryLeavesANonMatchingMarkCompletelyUntouched() {
        EpochDomain from = domain(10);
        EpochDomain to = domain(10);
        EpochDomain unrelated = domain(10);
        Marked e = new Marked();
        e.tryMark(unrelated.tickToken());
        long before = e.mark.get();

        assertFalse(e.carry(from.tickToken(), to.tickToken()));
        assertEquals(before, e.mark.get(), "a mark from a third domain must not move");
    }

    @Test
    void carryNeverMarksAnEntityThatWasNotTicked() {
        EpochDomain from = domain(10);
        EpochDomain to = domain(10);
        Marked e = new Marked();
        long pristine = e.mark.get();

        assertFalse(e.carry(from.tickToken(), to.tickToken()));
        assertEquals(pristine, e.mark.get());
        assertTrue(e.tryMark(to.tickToken()), "still owed its tick");
    }

    @Test
    void repeatedCarryIsIdempotentAndCannotRevertANewerMark() {
        EpochDomain from = domain(10);
        EpochDomain to = domain(10);
        Marked e = new Marked();
        e.tryMark(from.tickToken());

        assertTrue(e.carry(from.tickToken(), to.tickToken()));
        assertFalse(e.carry(from.tickToken(), to.tickToken()),
                "a second carry finds nothing to move");
        assertEquals(to.tickToken(), e.mark.get());

        // A newer stamp (next epoch) must not be dragged back to the old token.
        to.epoch++;
        long newer = to.tickToken();
        assertTrue(e.tryMark(newer));
        assertFalse(e.carry(from.tickToken(), to.tickToken()));
        assertEquals(newer, e.mark.get(), "carry cannot rewind a newer mark");
    }

    @Test
    void carryIntoTheSameTokenIsANoOp() {
        EpochDomain d = domain(10);
        Marked e = new Marked();
        e.tryMark(d.tickToken());
        assertFalse(e.carry(d.tickToken(), d.tickToken()));
        assertEquals(d.tickToken(), e.mark.get());
    }
}
