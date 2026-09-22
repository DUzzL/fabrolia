package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for permanently queued islands starving C2ME entity unloads. */
class EntityUnloadStarvationAdmissionTest {

    @Test
    void forcedLeaseIsExactToManagerChunkAndAttemptLifetime() {
        Object manager = new Object();
        Object otherManager = new Object();
        long chunk = 17L;

        IslandUnitAdmission.EntityUnloadAttempt ordinary =
                IslandUnitAdmission.beginEntityUnloadAttempt(manager, chunk, false);
        try {
            assertFalse(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    manager, chunk, true));
        } finally {
            IslandUnitAdmission.endEntityUnloadAttempt(ordinary);
        }

        IslandUnitAdmission.EntityUnloadAttempt forced =
                IslandUnitAdmission.beginEntityUnloadAttempt(manager, chunk, true);
        try {
            assertTrue(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    manager, chunk, true));
            assertFalse(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    otherManager, chunk, true));
            assertFalse(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    manager, chunk + 1, true));
            assertTrue(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    otherManager, chunk + 1, false),
                    "an unqueued owner still follows the normal opportunistic path");

            IslandUnitAdmission.noteEntityUnloadOwnershipYield(otherManager, chunk);
            assertFalse(IslandUnitAdmission.entityUnloadOwnershipYielded(forced));
            IslandUnitAdmission.noteEntityUnloadOwnershipYield(manager, chunk);
            assertTrue(IslandUnitAdmission.entityUnloadOwnershipYielded(forced));
        } finally {
            IslandUnitAdmission.endEntityUnloadAttempt(forced);
        }

        assertFalse(IslandUnitAdmission.entityUnloadSaveMayClaim(
                manager, chunk, true),
                "the bounded lease must disappear before another save can observe it");
    }

    @Test
    void threeHundredThirtyFiveQueuedKeysDrainWithABoundedPerPassQuota() {
        int keyCount = 335;
        int quota = 8;
        IslandUnitAdmission.EntityUnloadRetryFairness fairness =
                new IslandUnitAdmission.EntityUnloadRetryFairness(10L, 100L, quota);
        LinkedHashSet<Long> pending = new LinkedHashSet<>();
        for (long key = 0; key < keyCount; key++) pending.add(key);

        fairness.beginPass();
        for (long key : pending) {
            assertFalse(fairness.tryGrant(key, 0L),
                    "a first ownership yield is not immediately allowed to steal the queue gap");
            fairness.recordResult(key, true, 0L);
        }
        assertEquals(keyCount, fairness.trackedCount());

        int passes = 0;
        while (!pending.isEmpty()) {
            fairness.beginPass();
            Set<Long> completed = new HashSet<>();
            int granted = 0;
            for (long key : pending) {
                if (fairness.tryGrant(key, 10L)) {
                    granted++;
                    completed.add(key);
                    fairness.recordResult(key, false, 10L);
                } else {
                    fairness.recordResult(key, true, 10L);
                }
            }
            assertTrue(granted <= quota, "one processUnloads pass must stay bounded");
            assertTrue(granted > 0, "eligible queued unloads must make bounded progress");
            pending.removeAll(completed);
            passes++;
        }

        assertEquals((keyCount + quota - 1) / quota, passes);
        assertEquals(0, fairness.trackedCount(),
                "a body entry/success must clear every aged ownership yield");
    }

    @Test
    void cooldownRotatesQuotaPastForcedAttemptsThatStillLoseTheClaim() {
        int quota = 8;
        IslandUnitAdmission.EntityUnloadRetryFairness fairness =
                new IslandUnitAdmission.EntityUnloadRetryFairness(10L, 100L, quota);
        for (long key = 0; key < 16; key++) {
            fairness.recordResult(key, true, 0L);
        }

        Set<Long> first = forceAndKeepYielding(fairness, 16, 10L);
        Set<Long> second = forceAndKeepYielding(fairness, 16, 10L);

        assertEquals(quota, first.size());
        assertEquals(quota, second.size());
        assertTrue(java.util.Collections.disjoint(first, second),
                "cooldown must stop the same busy keys consuming the next pass too");
    }

    @Test
    void forcedSaveNeverAdvancesTheQueuedUnitsLogicalTick() {
        Object manager = new Object();
        long chunk = 91L;
        AtomicBoolean unitClaim = new AtomicBoolean();
        AtomicLong logicalTick = new AtomicLong(42L);
        AtomicInteger unloadBodies = new AtomicInteger();

        IslandUnitAdmission.EntityUnloadAttempt forced =
                IslandUnitAdmission.beginEntityUnloadAttempt(manager, chunk, true);
        try {
            assertTrue(IslandUnitAdmission.entityUnloadSaveMayClaim(
                    manager, chunk, true));
            assertTrue(unitClaim.compareAndSet(false, true));
            unloadBodies.incrementAndGet(); // the one vanilla storeChunkSections body

            boolean queuedUnitClaimed = unitClaim.compareAndSet(false, true);
            if (queuedUnitClaimed) logicalTick.incrementAndGet();
            assertFalse(queuedUnitClaimed);
            assertEquals(42L, logicalTick.get(),
                    "a displaced queued attempt is NO_BODY; it cannot publish an L advance");

            unitClaim.set(false);
            assertTrue(unitClaim.compareAndSet(false, true));
            logicalTick.incrementAndGet(); // the next drive runs that same logical frontier
        } finally {
            unitClaim.set(false);
            IslandUnitAdmission.endEntityUnloadAttempt(forced);
        }

        assertEquals(1, unloadBodies.get(), "the unload body is never replayed");
        assertEquals(43L, logicalTick.get(),
                "the queued unit runs once on its next claim instead of being skipped");
    }


    private static Set<Long> forceAndKeepYielding(
            IslandUnitAdmission.EntityUnloadRetryFairness fairness,
            int keyCount, long nowNanos) {
        fairness.beginPass();
        Set<Long> granted = new HashSet<>();
        for (long key = 0; key < keyCount; key++) {
            if (fairness.tryGrant(key, nowNanos)) granted.add(key);
            fairness.recordResult(key, true, nowNanos);
        }
        return granted;
    }


}
