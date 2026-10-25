package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim ledger the heal forensic reads through {@link Region#snapshotClaim}. */
class RegionClaimLedgerTest {

    @Test
    void stampedAcquisitionIsInsideItsBodyUntilTheReleaseStamp() {
        Fixture f = fixture(17L);
        Thread holder = Thread.currentThread();

        assertTrue(f.region.tryClaim(holder, f.domain, f.epoch));
        f.region.stampAcquire("stealOne");
        Region.ClaimSnapshot working = f.region.snapshotClaim();
        assertSame(holder, working.holder());
        assertTrue(working.stamped());
        assertTrue(working.inBody(),
                "a stealOne holder draining its queue publishes no currentRegion but is working the claim");
        assertEquals("stealOne", working.acquirePath());

        f.region.stampRelease("stealOne");
        Region.ClaimSnapshot finished = f.region.snapshotClaim();
        assertEquals(working.seq(), finished.seq());
        assertTrue(finished.stamped());
        assertFalse(finished.inBody(), "release stamped: the body is over");

        f.region.release(holder);
        f.region.finishClaimTransaction();
        assertNull(f.region.snapshotClaim().holder());
    }

    @Test
    void unstampedAcquisitionDescribesTheLastStampedClaimOnly() {
        Fixture f = fixture(23L);
        Thread holder = Thread.currentThread();
        claimStampedAndRelease(f, holder, "workOnce");

        assertTrue(f.region.tryClaim(holder, f.domain, f.epoch));
        Region.ClaimSnapshot unstamped = f.region.snapshotClaim();
        assertFalse(unstamped.stamped());
        assertFalse(unstamped.inBody());
        assertEquals("workOnce", unstamped.acquirePath(),
                "path/age of an unstamped acquisition belong to the previous stamped claim");
        f.region.release(holder);
        f.region.finishClaimTransaction();
    }

    @Test
    void reclaimBySameThreadIsANewAcquisitionAlthoughTheGenerationRepeats() {
        Fixture f = fixture(31L);
        Thread holder = Thread.currentThread();

        assertTrue(f.region.tryClaim(holder, f.domain, f.epoch));
        Region.ClaimSnapshot first = f.region.snapshotClaim();
        f.region.release(holder);
        f.region.finishClaimTransaction();
        assertTrue(f.region.tryClaim(holder, f.domain, f.epoch));
        Region.ClaimSnapshot second = f.region.snapshotClaim();

        // Holder and generation alone (the former forensic key) cannot tell
        // these two unstamped claims apart.
        assertSame(first.holder(), second.holder());
        assertEquals(first.gen(), second.gen());
        assertNotEquals(first.seq(), second.seq());
        f.region.release(holder);
        f.region.finishClaimTransaction();
    }

    @Test
    void refusedClaimsAreNoAcquisitions() {
        Fixture f = fixture(43L);
        Thread other = new Thread(() -> { });
        assertTrue(f.region.tryClaim(other, f.domain, f.epoch));
        long held = f.region.claimSeq;

        assertFalse(f.region.tryClaim(Thread.currentThread(), f.domain, f.epoch));
        assertEquals(held, f.region.claimSeq, "a lost CAS is no acquisition");
        f.region.release(other);
        f.region.finishClaimTransaction();

        assertTrue(ColorGate.publishDoneIfQuiescent(
                f.region, f.domain, f.epoch, true, false));
        assertFalse(f.region.tryClaim(Thread.currentThread(), f.domain, f.epoch));
        assertEquals(held, f.region.claimSeq, "a post-CAS refusal is no acquisition");
    }

    private static void claimStampedAndRelease(Fixture f, Thread holder, String path) {
        assertTrue(f.region.tryClaim(holder, f.domain, f.epoch));
        f.region.stampAcquire(path);
        f.region.stampRelease(path);
        f.region.release(holder);
        f.region.finishClaimTransaction();
    }

    private static Fixture fixture(long epoch) {
        EpochDomain domain = new EpochDomain();
        domain.epoch = epoch;
        domain.dispatchedEpoch = epoch;
        Region region = new Region(null, 0L);
        region.domain = domain;
        region.doneEpoch = epoch - 1;
        region.groupedEpoch = epoch;
        region.entityEpoch = epoch;
        return new Fixture(region, domain, epoch);
    }

    private record Fixture(Region region, EpochDomain domain, long epoch) {}
}
