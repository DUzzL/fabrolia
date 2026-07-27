package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #32 structural pins — owner-thread packet execution coverage and the
 * wait-under-lock invariants that unit tests cannot execute (they live in
 * mixin wiring applied only at runtime).
 *
 * <p>Same rationale as {@link RegionalPlayerTickWiringTest}: no compiler,
 * mixin validator or behavioral test catches a silently dropped handler wrap,
 * and each of these carries a production deadlock or reorder behind it.
 * Skipped (not failed) when the source tree is absent (jar-only CI).
 */
class PacketOwnerRoutingWiringTest {

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

    /** Every simulation-ordered handler must go through the per-player lane —
     *  an unrouted order-sensitive handler executes immediately on main and
     *  overtakes earlier routed packets (hotbar switch applying before the
     *  use-item that preceded it selects the wrong item). */
    @Test
    void simulationHandlersAreLaneRouted() throws IOException {
        Path routing = findSource(MIXIN_DIR + "ServerGamePacketRoutingMixin.java");
        Assumptions.assumeTrue(routing != null, "source tree not available");
        String src = Files.readString(routing);
        for (String handler : new String[]{
                "handleMovePlayer", "handleInteract", "handleAttack", "handleUseItem",
                "handleUseItemOn", "handlePlayerAction", "handleSetCarriedItem",
                "handleAnimate", "handlePlayerInput", "handlePlayerCommand",
                "handlePickItemFromBlock", "handlePickItemFromEntity",
                "handleContainerClick", "handleContainerClose",
                "handleContainerButtonClick", "handleSelectTrade", "handlePlaceRecipe",
                "handleRenameItem", "handleSetBeaconPacket", "handleSetCreativeModeSlot"}) {
            assertTrue(src.contains("\"" + handler + "\""),
                    handler + " must be lane-routed (ServerGamePacketRoutingMixin) — "
                            + "an unrouted simulation handler reorders against the lane "
                            + "and mutates island state without the ownership contract");
        }
    }

    /** Main-venue island claims: every handler that mutates island-owned
     *  state from main (feature off / ineligible players) must take the
     *  owner claim — 26.2's split-out handleAttack and the vehicle pair were
     *  unclaimed island mutations. */
    @Test
    void mainVenueIslandMutatorsTakeTheOwnerClaim() throws IOException {
        Path owner = findSource(MIXIN_DIR + "PlayerInteractionOwnerMixin.java");
        Assumptions.assumeTrue(owner != null, "source tree not available");
        String src = Files.readString(owner);
        for (String handler : new String[]{
                "handleUseItemOn", "handlePlayerAction", "handleUseItem",
                "handleInteract", "handleAttack", "handleContainerClick",
                "handleContainerClose", "handleMoveVehicle", "handlePaddleBoat",
                "handlePlayerCommand", "handlePickItemFromBlock",
                "handlePickItemFromEntity", "handleSetCreativeModeSlot",
                "handleContainerButtonClick", "handleSelectTrade",
                "handlePlaceRecipe", "handleRenameItem", "handleSetBeaconPacket",
                "updateSignText"}) {
            assertTrue(src.contains("\"" + handler + "\""),
                    handler + " must take the owner-island claim on the main venue "
                            + "(PlayerInteractionOwnerMixin)");
        }
    }

    /**
     * The wait-under-lock invariant (the production watchdog crash): routed
     * applies run under the lane LOCK, and main-side acquirers of that lock
     * must PUMP chunk services while waiting. A raw {@code synchronized
     * (connection)} / {@code synchronized (this)} on either side re-arms the
     * cycle: worker parks in awaitMainThreadResult holding the mutex, main
     * blocks on the mutex without pumping, watchdog fires.
     */
    @Test
    void listenerSerializationMustBePumpingLockNotMonitor() throws IOException {
        Path routing = findSource(MIXIN_DIR + "ServerGamePacketRoutingMixin.java");
        Path lane = findSource(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionalPlayerTicks.java");
        Assumptions.assumeTrue(routing != null && lane != null, "source tree not available");
        String routingSrc = Files.readString(routing);
        String laneSrc = Files.readString(lane);

        assertTrue(routingSrc.contains("drainMainChunkServices"),
                "main-side lane-lock acquire must pump chunk services while waiting "
                        + "(fabrolia$lockPumping) — a blocking acquire re-arms the "
                        + "worker-bridge deadlock");
        assertTrue(!routingSrc.contains("synchronized (this)"),
                "listener tick/teleport-ack must serialize on the pumping lane lock, "
                        + "never a raw monitor");
        assertTrue(!laneSrc.contains("synchronized (p.connection)"),
                "routed applies must serialize on the lane lock, never the connection "
                        + "monitor — a worker-venue apply can park in the chunk "
                        + "main-bridge while holding it");
        assertTrue(laneSrc.contains("fabrolia$laneLock"),
                "drainLane must take the FabroliaListenerLane lock around applies");
    }

    /** Block-interaction sequence/ACK: routed handlers still run the vanilla
     *  body (ackBlockChangesUpTo inside handleUseItemOn / handleUseItem /
     *  handlePlayerAction), and the per-tick flush lives in listener.tick()
     *  under the same lane lock — monotonic Math.max semantics hold across
     *  venues. This pin guards the routing style: routes must re-invoke the
     *  REAL handler (self.handleX), never a re-implementation. */
    @Test
    void routesReinvokeTheVanillaHandlers() throws IOException {
        Path routing = findSource(MIXIN_DIR + "ServerGamePacketRoutingMixin.java");
        Assumptions.assumeTrue(routing != null, "source tree not available");
        String src = Files.readString(routing);
        for (String handler : new String[]{"handleUseItemOn", "handleUseItem", "handlePlayerAction"}) {
            assertTrue(src.contains("self." + handler + "(packet)"),
                    handler + " route must re-invoke the vanilla handler (sequence/ACK "
                            + "bookkeeping lives inside it)");
        }
    }

    /** The worker-venue same-thread assertion acceptance must stay scoped to
     *  ROUTED applies only (inRoutedApply) — a blanket acceptance would let
     *  arbitrary worker-context packet paths skip vanilla's main reschedule. */
    @Test
    void workerAssertionAcceptanceStaysScopedToRoutedApplies() throws IOException {
        Path utils = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/network/PacketUtilsWorkerMixin.java");
        Assumptions.assumeTrue(utils != null, "source tree not available");
        String src = Files.readString(utils);
        assertTrue(src.contains("inRoutedApply"),
                "PacketUtils acceptance must check RegionalPlayerTicks.inRoutedApply()");
        assertTrue(src.contains("RegionWorkerThread"),
                "PacketUtils acceptance must be limited to region worker threads");
    }
}
