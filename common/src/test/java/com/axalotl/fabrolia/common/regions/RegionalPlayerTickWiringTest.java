package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural pin for the audit2 regression (operator field report, 2026-07-27:
 * sculk sensors deaf to players).
 *
 * <p>Regional player ticking is a TWO-SIDED contract split across two mixins:
 * {@code ServerGamePacketRoutingMixin} makes main's {@code tickPlayer} SKIP
 * {@code doTick} for region-owned players ({@code mainShouldSkipDoTick}), and
 * {@code ServerPlayerMixin}'s {@code tick()} wrap tail runs {@code doTick} on
 * the owning island worker ({@code shouldWorkerDoTick}). Commit 7859a3d
 * (audit2) rewrote ServerPlayerMixin for an unrelated fix and silently dropped
 * the worker side while the skip side survived — so with
 * {@code /fabrolia regions playerticks on} a player's simulation tick ran on
 * NO thread: no aiStep, no hunger, none of the doTick-driven game events sculk
 * listens for. No compiler, mixin validator or behavioral test catches a
 * one-sided deletion of an injected call site, so this test pins the pairing
 * at the source level: if either half is present, BOTH must be.
 *
 * <p>Skipped (not failed) when the source tree is not on disk (jar-only CI).
 */
class RegionalPlayerTickWiringTest {

    private static final String MIXIN_DIR =
            "src/main/java/com/axalotl/fabrolia/common/mixin/server/";

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

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    @Test
    void workerDoTickAndMainSkipMustBePaired() throws IOException {
        Path routing = findSource(MIXIN_DIR + "ServerGamePacketRoutingMixin.java");
        Path player = findSource(MIXIN_DIR + "ServerPlayerMixin.java");
        Assumptions.assumeTrue(routing != null && player != null,
                "source tree not available — pairing pin skipped");

        String routingSrc = read(routing);
        String playerSrc = read(player);

        boolean mainSkips = routingSrc.contains("mainShouldSkipDoTick");
        boolean workerRuns = playerSrc.contains("shouldWorkerDoTick")
                && playerSrc.contains(".doTick()");

        assertTrue(!mainSkips || workerRuns,
                "ServerGamePacketRoutingMixin skips main's doTick for region-owned "
                        + "players, but ServerPlayerMixin no longer runs doTick on the "
                        + "island worker (shouldWorkerDoTick + self.doTick() in the "
                        + "tick()V wrap tail). This is the exact audit2 clobber: with "
                        + "playerticks on, player simulation runs on NO thread and "
                        + "sculk goes deaf to players. Restore the worker half (see "
                        + "commit 8e9098d) or remove the skip.");
        assertTrue(!workerRuns || mainSkips,
                "ServerPlayerMixin runs doTick on the island worker but main's "
                        + "tickPlayer no longer skips it — region-owned players would "
                        + "simulate twice per tick.");
    }

    /** The worker half must sit in a tick()V wrap so it shares the island
     *  unit's frame, claim and timeline with the sensors observing it. */
    @Test
    void workerDoTickLivesInTheTickWrapTail() throws IOException {
        Path player = findSource(MIXIN_DIR + "ServerPlayerMixin.java");
        Assumptions.assumeTrue(player != null, "source tree not available");
        String playerSrc = read(player);
        Assumptions.assumeTrue(playerSrc.contains("shouldWorkerDoTick"),
                "worker half absent — covered by the pairing test");
        assertTrue(playerSrc.contains("@WrapMethod(method = \"tick()V\")"),
                "shouldWorkerDoTick must be driven from the ServerPlayer.tick() "
                        + "wrap tail (the island entity-tick venue), not from an "
                        + "arbitrary call site");
    }

    /**
     * audit13 — the eligibility-flip seam guard must exist in BOTH directions.
     * The class doc always promised the exactly-once stamp is "consulted by
     * BOTH venues", but only main consulted one: {@code lastSimGlobalGt}
     * (worker writes, main reads) killed the worker→main double, while a flip
     * the other way — main reads ineligible (still a passenger) and runs
     * doTick, then a lane-applied dismount makes the island worker read
     * eligible later the SAME global tick — simulated the player twice in one
     * tick. The symmetric stamp {@code lastMainSimGt} (main writes on the
     * ineligible-fallback path, worker reads) closes it. Structural pin, same
     * rationale as the pairing test above: no compiler or behavioral test
     * catches a one-sided deletion.
     */
    @Test
    void eligibilityFlipSeamIsGuardedInBothDirections() throws IOException {
        Path ticks = findSource(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionalPlayerTicks.java");
        Assumptions.assumeTrue(ticks != null, "source tree not available");
        String src = read(ticks);

        String mainVenue = src.substring(src.indexOf("mainShouldSkipDoTick"));
        String workerVenue = src.substring(src.indexOf("shouldWorkerDoTick"),
                src.indexOf("mainShouldSkipDoTick"));

        assertTrue(workerVenue.contains("lastSimGlobalGt ="),
                "worker venue must stamp lastSimGlobalGt (consumed by main's seam guard)");
        assertTrue(mainVenue.contains("lastSimGlobalGt =="),
                "main venue must consult lastSimGlobalGt (worker→main flip guard)");
        assertTrue(mainVenue.contains("lastMainSimGt ="),
                "main venue must stamp lastMainSimGt on its ineligible-fallback doTick "
                        + "path (consumed by the worker's seam guard)");
        assertTrue(workerVenue.contains("lastMainSimGt =="),
                "worker venue must consult lastMainSimGt (main→worker flip guard — "
                        + "without it a mid-tick eligibility flip double-simulates the player)");
    }

    /** The vibration clock-domain mixins (the other half of the sculk fix)
     *  must stay registered — an unregistered mixin fails silently. */
    @Test
    void vibrationClockMixinsAreRegistered() throws IOException {
        Path json = findSource("src/main/resources/fabrolia.common.mixins.json");
        Assumptions.assumeTrue(json != null, "resources not available");
        String cfg = read(json);
        assertTrue(cfg.contains("world.VibrationListenerClockMixin"),
                "listener-domain stamping mixin must be registered");
        assertTrue(cfg.contains("world.VibrationSelectorMixin"),
                "selector heal/synchronization mixin must be registered");
    }
}
