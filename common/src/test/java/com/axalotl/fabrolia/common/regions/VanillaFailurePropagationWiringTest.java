package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source pins for the vanilla/Fabric fail-stop contract of regional simulation. */
final class VanillaFailurePropagationWiringTest {

    @Test
    void saveAllBodiesSelectWBeforeExhaustiveClaimReleaseAndExactRethrow()
            throws Exception {
        String levelMixin = source("common/mixin/world/ServerLevelMixin.java");
        String levelSave = methodSlice(levelMixin,
                "private void fabrolia$quiesceIslandsForLevelSave(",
                "    @Redirect(method = \"blockEvent\"");
        int levelBypass = levelSave.indexOf(
                "rsq.currentThreadHoldsAllClaims())");
        int levelBypassAdmission = levelSave.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", levelBypass);
        int levelBypassBody = levelSave.indexOf(
                "original.call(listener, flush, skipSave)", levelBypassAdmission);
        int levelQuiesce = levelSave.indexOf("rsq.quiesceAllIslands()",
                levelBypassBody);
        int levelClaimedAdmission = levelSave.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", levelQuiesce);
        int levelClaimedBody = levelSave.indexOf(
                "original.call(listener, flush, skipSave)", levelClaimedAdmission);
        int levelCatch = levelSave.indexOf("catch (Throwable primary)",
                levelClaimedBody);
        int levelWinner = levelSave.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(primary)",
                levelCatch);
        int levelRelease = levelSave.indexOf(
                "failure = rsq.releaseAllIslands(failure)", levelWinner);
        int levelCommit = levelSave.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", levelRelease);
        int levelRethrow = levelSave.indexOf(
                "FailStops.<RuntimeException>rethrow(", levelCommit);
        int levelReselect = levelSave.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(failure)", levelRethrow);

        assertTrue(levelBypass >= 0 && levelBypassAdmission > levelBypass
                        && levelBypassBody > levelBypassAdmission
                        && levelQuiesce > levelBypassBody
                        && levelClaimedAdmission > levelQuiesce
                        && levelClaimedBody > levelClaimedAdmission
                        && levelCatch > levelClaimedBody && levelWinner > levelCatch
                        && levelRelease > levelWinner && levelCommit > levelRelease
                        && levelRethrow > levelCommit && levelReselect > levelRethrow,
                "the whole-level save must atomically admit vanilla under its claims, "
                        + "select W before exhaustive release, then use a post-release "
                        + "success cut and exact rethrow");
        assertEquals(2, count(levelSave,
                        "RegionScheduler.runSuccessBodyIfHealthy("),
                "both the no-claim bypass and claimed whole-level save are real vanilla bodies");
        assertEquals(2, count(levelSave,
                        "original.call(listener, flush, skipSave)"),
                "no third raw whole-level save venue may bypass atomic admission");
        assertTrue(levelSave.lastIndexOf("return;") < levelQuiesce,
                "a caught whole-level save W may not take a normal-return branch");
        assertFalse(levelSave.contains("throwWorkerFailureIfAny"),
                "save entry/return may not be a volatile health check race");

        String chunkMixin = source("common/mixin/server/ServerChunkCacheMixin.java");
        String chunkSave = methodSlice(chunkMixin,
                "private void fabrolia$quiesceIslandsForSave(",
                "public CompletableFuture<Void> fabrolia$takeSpawnFuture()");
        int chunkBypass = chunkSave.indexOf("if (rsq == null || !rsq.asyncActive)");
        int chunkBypassAdmission = chunkSave.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", chunkBypass);
        int chunkBypassBody = chunkSave.indexOf(
                "original.call(flush)", chunkBypassAdmission);
        int chunkQuiesce = chunkSave.indexOf("rsq.quiesceAllIslands()",
                chunkBypassBody);
        int flushAdmission = chunkSave.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", chunkQuiesce);
        int flushBody = chunkSave.indexOf(".flushLevel(rsq)", flushAdmission);
        int chunkClaimedAdmission = chunkSave.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", flushBody);
        int chunkClaimedBody = chunkSave.indexOf(
                "original.call(flush)", chunkClaimedAdmission);
        int chunkCatch = chunkSave.indexOf("catch (Throwable primary)",
                chunkClaimedBody);
        int chunkWinner = chunkSave.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(primary)",
                chunkCatch);
        int chunkRelease = chunkSave.indexOf(
                "failure = rsq.releaseAllIslands(failure)", chunkWinner);
        int chunkCommit = chunkSave.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", chunkRelease);
        int chunkRethrow = chunkSave.indexOf("FailStops.<RuntimeException>rethrow(", chunkCommit);
        int chunkReselect = chunkSave.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(failure)", chunkRethrow);

        assertTrue(chunkBypass >= 0 && chunkBypassAdmission > chunkBypass
                        && chunkBypassBody > chunkBypassAdmission
                        && chunkQuiesce > chunkBypassBody
                        && flushAdmission > chunkQuiesce && flushBody > flushAdmission
                        && chunkClaimedAdmission > flushBody
                        && chunkClaimedBody > chunkClaimedAdmission
                        && chunkCatch > chunkClaimedBody && chunkWinner > chunkCatch
                        && chunkRelease > chunkWinner && chunkCommit > chunkRelease
                        && chunkRethrow > chunkCommit && chunkReselect > chunkRethrow,
                "chunk save must refuse vanilla serialization after a flush W, select exact W "
                        + "before exhaustive release, then commit or exact-rethrow");
        assertEquals(3, count(chunkSave,
                        "RegionScheduler.runSuccessBodyIfHealthy("),
                "bypass save, arrival flush, and claimed vanilla save each need atomic admission");
        assertEquals(2, count(chunkSave, "original.call(flush)"),
                "no third raw chunk-save venue may bypass atomic admission");
        assertTrue(chunkSave.lastIndexOf("return;") < chunkQuiesce,
                "a caught chunk-save W may not take a normal-return branch");
        assertFalse(chunkSave.contains("throwWorkerFailureIfAny"),
                "chunk-save entry/return may not be a volatile health check race");
    }

    @Test
    void ownerClaimedGlobalTicksCommitTheirReturnAfterClaimRelease() throws Exception {
        assertClaimedGlobalTickPostReleaseCommit(
                "common/mixin/world/RaidTickMixin.java",
                "private void fabrolia$ownerSafeRaidTick(", "raid");
        assertClaimedGlobalTickPostReleaseCommit(
                "common/mixin/world/DragonFightTickMixin.java",
                "private void fabrolia$ownerSafeFightTick(", "dragon-fight");
    }


    @Test
    void optionalCompatibilityMayDeclineBeforeEntryButNeverSuppressesAnEnteredBody()
            throws Exception {
        String vanish = source("common/mixin/advancement/SimpleCriterionTriggerMixin.java");
        assertTrue(vanish.contains("catch (ClassNotFoundException absent)"),
                "an actually absent optional mod may select the vanilla path");
        assertTrue(vanish.contains("FailStops.<RuntimeException>rethrow(linkageFailure)"));
        assertTrue(vanish.contains("FailStops.<RuntimeException>rethrow(failure)"));
        assertFalse(vanish.contains("catch (Throwable ignored)"),
                "an entered Vanish decision must not be converted into an advancement award");

        String mixins = resource("fabrolia.common.mixins.json");
        assertFalse(mixins.contains("FabroliaLithiumRandomTickSectionMixin"),
                "the removed random-tick recovery must not survive as an empty mixin dependency");
    }


    @Test
    void chunkTransportFailureCannotBeLoggedAndReLeased() throws Exception {
        String delivery = source("common/regions/ChunkDelivery.java");
        assertFalse(delivery.contains("cas(St.RESERVED, St.TRACKED)"),
                "a missing payload is structural failure, not a next-pass lease");
        assertFalse(delivery.contains("forget-enqueue-fail"),
                "a failed Forget offer must not become a rate-limited warning");
        assertTrue(delivery.contains(
                "chunk publication gate lost RESERVED after batch offer"));

        int caughtOffer = delivery.indexOf(
                "offerFailure[0] = publishFatalPreserving(failure)");
        int offerFailure = delivery.indexOf("if (offerFailure[0] != null)");
        int rethrow = delivery.indexOf("rethrow(\n                    "
                + "publishFatalPreserving(offerFailure[0]))", offerFailure);
        int cancelledMetric = delivery.indexOf("ps.cancelled.incrementAndGet()", rethrow);
        int preservingHelper = delivery.indexOf(
                "private static Throwable publishFatalPreserving", rethrow);
        assertTrue(caughtOffer >= 0 && offerFailure > caughtOffer && rethrow > offerFailure
                        && cancelledMetric > rethrow && preservingHelper > cancelledMetric,
                "Forget must select W before key cleanup and rethrow it after retirement");
        assertTrue(delivery.substring(preservingHelper).contains(
                        "RegionScheduler.publishFatalAndSelectWinner(primary)"),
                "packet transport must rethrow the generation's exact first-wins failure");
    }


    

    @Test
    void incrementalSaveAndDuplicateEntityFailuresRemainFatal() throws Exception {
        String chunkMap = source("common/mixin/server/ChunkMapMixin.java");
        assertFalse(chunkMap.contains("skipThrowLoadEntity"));
        assertFalse(chunkMap.contains("pauseInIde"),
                "Fabrolia must not cancel vanilla's duplicate tracked-entity failure");
        assertTrue(chunkMap.contains("FailStops.mergeFailure(failure, cleanup)"));
        assertTrue(chunkMap.contains(".publishAndPropagate(failure)"));

        String entities = source("common/mixin/server/EntitySectionSaveMixin.java");
        assertTrue(count(entities, "FailStops.mergeFailure(failure, cleanup)") == 2);
        assertTrue(entities.contains("IslandCommands.publishAndPropagate(failure)"));

        String playerList = source("common/mixin/server/PlayerListMixin.java");
        assertFalse(playerList.contains("this.server.execute(() -> ((PlayerList)"));
        assertTrue(playerList.contains("PlayerList.remove entered outside physical Main"));
    }

    @Test
    void shutdownDrainsBothRuntimesAndRethrowsTheExactFirstFailure() throws Exception {
        String processor = source("common/ParallelProcessor.java");
        int boundary = processor.indexOf("public static void stopServerRuntime()");
        int regionStop = processor.indexOf("RegionScheduler.stop()", boundary);
        int primaryCatch = processor.indexOf("catch (Throwable primary)", regionStop);
        int flatStop = processor.indexOf("stop();", primaryCatch);
        int cleanupCatch = processor.indexOf("catch (Throwable cleanup)", flatStop);
        int exactRethrow = processor.indexOf("FailStops.<RuntimeException>rethrow(failure)",
                cleanupCatch);

        assertTrue(boundary >= 0 && regionStop > boundary && primaryCatch > regionStop
                        && flatStop > primaryCatch && cleanupCatch > flatStop
                        && exactRethrow > cleanupCatch,
                "a region-stop failure must not skip the accepted flat/player work drain");
        assertFalse(processor.contains("shutdownNow()"),
                "shutdown may wait and diagnose accepted work, never cancel it");
        assertFalse(processor.contains("Interrupted while waiting for thread pool shutdown"),
                "an interrupted cleanup must be preserved, not logged as a successful stop");

        int flatUncaught = processor.indexOf(
                "private static void fabrolia$failStopUncaught(");
        int flatFatal = processor.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(thrown)", flatUncaught);
        int flatLog = processor.indexOf(
                "LOGGER.error(\"Uncaught exception in thread {}\"", flatFatal);
        assertTrue(flatUncaught >= 0 && flatFatal > flatUncaught && flatLog > flatFatal,
                "a flat-pool envelope escape must close the generation before logging");
        assertTrue(count(processor, "ParallelProcessor::fabrolia$failStopUncaught") == 2,
                "both ForkJoinPool and worker-thread uncaught hooks must share fail-stop");
    }



    private static int count(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }

    private static void assertClaimedGlobalTickPostReleaseCommit(
            String relative, String method, String label) throws Exception {
        String mixin = source(relative);
        String body = methodSlice(mixin, method, "\n    }\n");
        int claim = body.indexOf("IslandCommands.mainClaim(owner)");
        int admission = body.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", claim);
        int bodyFailure = body.indexOf("catch (Throwable bodyFailure)", admission);
        int winner = body.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure)",
                bodyFailure);
        int release = body.indexOf("IslandCommands.mainRelease(owner)", winner);
        int commit = body.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", release);
        int peerFailure = body.indexOf("catch (Throwable peerFailure)", commit);
        int peerWinner = body.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure)",
                peerFailure);
        int rethrow = body.indexOf("IslandCommands.publishAndPropagate(failure)",
                peerWinner);

        assertTrue(claim >= 0 && admission > claim && bodyFailure > admission
                        && winner > bodyFailure && release > winner && commit > release
                        && peerFailure > commit && peerWinner > peerFailure
                        && rethrow > peerWinner,
                label + " tick must select body W before mandatory claim release, then "
                        + "atomically commit normal return or exact-rethrow");
        assertFalse(body.contains("throwWorkerFailureIfAny"),
                label + " tick may not use a health read followed by normal return");
    }

    private static String methodSlice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        if (start < 0 || end < 0) {
            throw new AssertionError("cannot locate method slice: " + startNeedle);
        }
        return source.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }

    private static String resource(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/resources").resolve(relative);
            Path common = root.resolve("common/src/main/resources").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate resource: " + relative);
    }
}
