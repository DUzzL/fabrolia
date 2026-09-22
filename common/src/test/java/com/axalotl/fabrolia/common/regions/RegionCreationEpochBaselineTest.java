package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RegionCreationEpochBaselineTest {

    @Test
    void preCutBirthRemainsEligibleForTheCurrentPlan() {
        EpochDomain domain = new EpochDomain();
        domain.epoch = 8L;
        domain.entityDispatchCutEpoch = 7L;
        Region region = new Region(null, 1L);

        region.initializeDomainAtCreation(domain);

        assertBaseline(region, domain, 7L);
    }

    @Test
    void postCutBirthCannotContributeToAnUncountedBarrier() {
        EpochDomain domain = new EpochDomain();
        domain.epoch = 8L;
        domain.entityDispatchCutEpoch = 8L;
        domain.dispatchedEpoch = 7L; // final dispatch receipt has not published yet
        Region region = new Region(null, 2L);

        region.initializeDomainAtCreation(domain);

        assertBaseline(region, domain, 8L);
        assertEquals(domain.epoch, region.entityBarrierEpoch.get(),
                "the contribution CAS must already be closed for an uncounted region");
    }

    @Test
    void creationBaselineCannotReplaceAPublishedDomain() {
        EpochDomain domain = new EpochDomain();
        Region region = new Region(null, 3L);
        region.initializeDomainAtCreation(domain);

        assertThrows(IllegalStateException.class,
                () -> region.initializeDomainAtCreation(new EpochDomain()));
    }

    private static void assertBaseline(Region region, EpochDomain domain, long baseline) {
        assertSame(domain, region.domain);
        assertEquals(baseline, region.sealedDoneEpoch);
        assertEquals(baseline, region.doneEpoch);
        assertEquals(baseline, region.groupedEpoch);
        assertEquals(baseline, region.entityEpoch);
        assertEquals(baseline, region.entityEnqueuedEpoch.get());
        assertEquals(baseline, region.entityBarrierEpoch.get());
    }
}
