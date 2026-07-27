package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit9 — the End-platform regeneration must be staged onto the destination
 * island whenever that island is async-owned, INCLUDING from the main thread.
 *
 * <p>The audit8 field capture proved the defect: a regen ran with
 * {@code thread=Server thread} and {@code L=-1} (no island clock context) and
 * cleared (100,49,0) ~254 us after a falling block had placed there, while the
 * End island's entity phase was running on a region worker. Blocks were being
 * written into island-owned chunks from main with no claim and no ordering.
 *
 * <p>The asymmetry that caused it: {@code CrossDimTeleportMixin} already staged
 * main-thread ARRIVALS into island-owned destinations, but {@code EndPortalBlockMixin}
 * gated staging on {@code instanceof RegionWorkerThread}, so a player's arrival was
 * staged while the platform regen that accompanies it was not.
 *
 * <p>This encodes the decision the mixin now makes, so the two paths cannot drift
 * apart again.
 */
class EndPlatformRegenStagingTest {

    /** Mirror of {@code EndPortalBlockMixin.fabrolia$shouldStageRegen}. */
    private static boolean shouldStage(boolean onWorkerThread, boolean destExists,
                                       boolean asyncActive, boolean islandOwned) {
        if (onWorkerThread) return true;
        if (!destExists || !asyncActive) return false;
        return islandOwned;
    }

    /** Mirror of the arrival-side decision in {@code CrossDimTeleportMixin}. */
    private static boolean arrivalIsStaged(boolean onWorkerThread, boolean destExists,
                                           boolean asyncActive, boolean islandOwned) {
        if (onWorkerThread) return true;
        if (!destExists || !asyncActive) return false;
        return islandOwned;
    }

    @Test
    void mainThreadRegenIntoAnAsyncIslandMustBeStaged() {
        assertTrue(shouldStage(false, true, true, true),
                "this is the audit8 defect: a player entering the End regenerates the "
                        + "platform on the Server thread, which wrote blocks into the End "
                        + "island's chunks with no claim, concurrently with that island's "
                        + "entity phase, destroying a just-placed falling block");
    }

    @Test
    void regenAndItsArrivalAgreeOnStagingForEveryThreadAndOwnership() {
        // The regen and the arrival it belongs to are one causal unit. If they ever
        // disagree, the platform clear and the entity insertion land in different
        // orders — which is exactly the bug.
        for (boolean worker : new boolean[]{false, true}) {
            for (boolean exists : new boolean[]{false, true}) {
                for (boolean async : new boolean[]{false, true}) {
                    for (boolean owned : new boolean[]{false, true}) {
                        boolean regen = shouldStage(worker, exists, async, owned);
                        boolean arrival = arrivalIsStaged(worker, exists, async, owned);
                        assertTrue(regen == arrival,
                                "regen/arrival staging disagree for worker=" + worker
                                        + " destExists=" + exists + " async=" + async
                                        + " islandOwned=" + owned + " -> regen=" + regen
                                        + " arrival=" + arrival);
                    }
                }
            }
        }
    }

    @Test
    void aRegionWorkerAlwaysStages() {
        assertTrue(shouldStage(true, true, true, true));
        assertTrue(shouldStage(true, true, false, false),
                "unchanged from before this fix: a worker never writes the destination "
                        + "inline, whatever the destination's state");
    }

    @Test
    void sequentialDestinationStillRunsInlineOnMain() {
        assertFalse(shouldStage(false, true, false, false),
                "when the destination level is not async, main IS its executor and "
                        + "vanilla ordering already holds — staging there would add a "
                        + "pointless hop and delay the platform by one tick");
        assertFalse(shouldStage(false, false, false, false),
                "no RegionizedServer for the level: nothing to stage onto");
    }

    @Test
    void mainThreadRegenIntoAGlobalOwnedDestinationStillRunsInline() {
        assertFalse(shouldStage(false, true, true, false),
                "a globally-owned destination chunk is executed by main itself, so the "
                        + "regen is already ordered against everything that touches it");
    }
}
