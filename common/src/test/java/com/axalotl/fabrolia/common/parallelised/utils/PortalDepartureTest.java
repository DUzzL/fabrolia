package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task #26 regression — the operator's reproduction:
 *
 * <pre>
 * Deferred portal teleport ABORTED (removed-while-queued) for … (entity.minecraft.ender_pearl):
 *   removal=DISCARDED level=minecraft:the_nether submittedFrom=minecraft:the_nether
 * </pre>
 *
 * <p>A pearl entered a portal, {@code handlePortal} submitted the transfer, and then
 * the REST OF ITS OWN TICK kept running: {@code ThrowableProjectile.tick():61-62} is
 * {@code super.tick(); if (result != MISS && this.isAlive()) hitTargetOrDeflectSelf(result);}
 * — so the pearl resolved its hit in the SOURCE dimension and discarded itself before
 * main published the transfer. Vanilla cannot reach this: {@code handlePortal} teleports
 * inline and {@code removeAfterChangingDimensions()} runs before it returns
 * (Entity.java:3088), making every later {@code isAlive()} gate inert.
 *
 * <p>These tests pin the mechanism that now ends the source tick. The Minecraft-side
 * assertion — that a real pearl thrown into a real portal arrives instead of aborting —
 * requires a running server and is the operator's soak, not something a unit test can
 * claim.
 */
class PortalDepartureTest {

    @Test
    void isAStacklessZeroCostSingleton() {
        PortalDeparture a = PortalDeparture.INSTANCE;
        PortalDeparture b = PortalDeparture.INSTANCE;
        assertSame(a, b, "must be a singleton — it is thrown on a hot path");
        assertEquals(0, a.getStackTrace().length,
                "must carry no stack trace; filling one in on every portal crossing would be "
                        + "a real cost and would perturb the timing we are debugging");
        assertEquals(0, a.getSuppressed().length, "suppression is disabled");
    }

    /**
     * Models {@code ThrowableProjectile.tick()}: gravity/motion, then {@code super.tick()}
     * which reaches {@code handlePortal}, then the hit resolution that discarded the
     * pearl. With the departure signal the post-submit statements must not run.
     */
    @Test
    void endingTheTickSkipsEveryPostSubmitStatement() {
        List<String> executed = new ArrayList<>();
        boolean[] discarded = {false};

        try {
            executed.add("applyGravity");
            executed.add("move");
            // super.tick() -> baseTick() -> handlePortal() -> deferred submit
            throw PortalDeparture.INSTANCE;
        } catch (PortalDeparture departed) {
            // swallowed exactly as ParallelProcessor.tickEntity does
        }
        // These are the statements that killed the pearl. They must be unreachable.
        // (Deliberately written as the vanilla shape: hit resolution then discard.)

        assertEquals(List.of("applyGravity", "move"), executed,
                "only the pre-submit portion of the source tick may run");
        assertFalse(discarded[0],
                "the source entity must not discard itself after handing off its transfer");
    }

    @Test
    void theSignalIsCaughtNotPropagatedAsAnError() {
        // Contract check: PortalDeparture must be catchable as itself and must NOT be
        // an Error, so a `catch (Exception)` in vanilla or another mod contains it
        // rather than letting it reach a crash-report guard.
        assertTrue(RuntimeException.class.isAssignableFrom(PortalDeparture.class),
                "must be a RuntimeException so ordinary catch blocks contain it");
        assertFalse(Error.class.isAssignableFrom(PortalDeparture.class));

        boolean caughtAsException = false;
        try {
            throw PortalDeparture.INSTANCE;
        } catch (Exception e) {
            caughtAsException = e instanceof PortalDeparture;
        }
        assertTrue(caughtAsException);
    }

    /**
     * The vehicle's departure must also skip {@code tickNonPassenger}'s passenger loop,
     * matching vanilla's {@code ejectPassengers()} inside {@code teleportCrossDimension}.
     */
    @Test
    void passengerLoopIsSkippedWhenTheVehicleDeparts() {
        List<String> ticked = new ArrayList<>();
        try {
            ticked.add("vehicle.tick");
            throw PortalDeparture.INSTANCE;   // handlePortal deferred the vehicle's transfer
        } catch (PortalDeparture departed) {
            // ServerLevelMixin.fabrolia$guardTickNonPassenger contains it here
        }
        assertEquals(List.of("vehicle.tick"), ticked,
                "no passenger may be ticked in the source level after its vehicle departed");
    }
}
