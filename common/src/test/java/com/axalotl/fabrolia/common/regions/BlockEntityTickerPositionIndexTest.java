package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression policy for the 2026-08-21 universal-crafter race: Lithium's
 * fully sleeping ticker reports no live position during an F2 bulk rebuild,
 * but the wrapper must retain its registration cell rather than move to the
 * main/posless lane.
 */
class BlockEntityTickerPositionIndexTest {

    private static Path findSource(String rel) {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path direct = p.resolve(rel);
            if (Files.exists(direct)) return direct;
            Path viaCommon = p.resolve("common").resolve(rel);
            if (Files.exists(viaCommon)) return viaCommon;
        }
        return null;
    }

    @Test
    void sleepingWrapperFallsBackToRegistrationPosition() {
        BlockEntityTickerPositionIndex<Object, String> index =
                new BlockEntityTickerPositionIndex<>();
        Object wrapper = new Object();

        index.remember(wrapper, "cell-at-registration");

        assertEquals("cell-at-registration", index.resolve(wrapper, null),
                "Lithium's null sleeping position must not route the wrapper to main");
    }

    @Test
    void livePositionRemainsAuthoritative() {
        BlockEntityTickerPositionIndex<Object, String> index =
                new BlockEntityTickerPositionIndex<>();
        Object wrapper = new Object();
        index.remember(wrapper, "old-registration-cell");

        assertEquals("live-cell", index.resolve(wrapper, "live-cell"));
    }

    @Test
    void genuinelyPositionlessTickerStaysPositionless() {
        BlockEntityTickerPositionIndex<Object, String> index =
                new BlockEntityTickerPositionIndex<>();

        assertNull(index.resolve(new Object(), null));
    }

    @Test
    void incompleteRegistrationDoesNotCreateAFalseOwner() {
        BlockEntityTickerPositionIndex<Object, String> index =
                new BlockEntityTickerPositionIndex<>();
        Object wrapper = new Object();

        index.remember(wrapper, null);

        assertNull(index.resolve(wrapper, null));
    }

    /**
     * The pure policy is useful only if the production hook records the wrapper
     * before a RegionizedServer exists and both incremental and bulk routes use
     * the fallback. These source pins protect that cross-class wiring.
     */
    @Test
    void productionRoutingRecordsAndUsesTheRegistrationFallback() throws IOException {
        Path scheduler = findSource(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java");
        Path server = findSource(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java");
        Assumptions.assumeTrue(scheduler != null && server != null,
                "source tree not available");

        String schedulerSource = Files.readString(scheduler);
        String serverSource = Files.readString(server);
        int registration = schedulerSource.indexOf(
                "BLOCK_ENTITY_TICKER_POSITIONS.remember(ticker, pos.immutable())");
        int perLevelLookup = schedulerSource.indexOf(
                "RegionizedServer rs = PER_LEVEL.get(sl)", registration);

        assertTrue(registration > 0 && perLevelLookup > registration,
                "registration position must be captured before PER_LEVEL lookup so "
                        + "regions-off sleepers survive the first bulk rebuild");
        assertTrue(schedulerSource.contains(
                        "BLOCK_ENTITY_TICKER_POSITIONS.resolve(ticker, ticker.getPos())"),
                "the production resolver must prefer live position and fall back to registration");
        assertTrue(serverSource.contains(
                        "RegionScheduler.resolveBlockEntityTickerPosition(ticker)"),
                "incremental ticker adds must use the registration fallback");
        assertTrue(serverSource.contains(
                        "RegionScheduler.resolveBlockEntityTickerPosition(t)"),
                "bulk rebuilds must use the registration fallback");
        assertTrue(serverSource.contains("misroutedPosless"),
                "beindex must expose any routable ticker that regresses into the main lane");
    }
}
