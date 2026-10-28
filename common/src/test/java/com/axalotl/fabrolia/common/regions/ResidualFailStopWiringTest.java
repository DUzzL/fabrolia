package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source pins for residual no-suppressor seams outside the core region pool. */
final class ResidualFailStopWiringTest {



    @Test
    void regionGroupSuccessorPublicationHasAFreshExactWCut() throws Exception {
        String server = source("common/regions/RegionizedServer.java");
        String group = section(server,
                "private void processGroup(",
                "private Runnable cancellableGroupRoot(");
        int release = group.indexOf("r.release(me)");
        int healthy = group.indexOf("if (regionFailure == null)", release);
        int admission = group.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> {", healthy);
        int ready = group.indexOf("RegionScheduler.enqueueReady(r)", admission);
        int done = group.indexOf("ColorGate.tryMarkDone(r, this)", ready);
        int catchFailure = group.indexOf(
                "catch (Throwable completionFailure)", done);
        int winner = group.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(", catchFailure);
        assertTrue(release >= 0 && healthy > release && admission > healthy
                        && ready > admission && done > ready
                        && catchFailure > done && winner > catchFailure,
                "post-release ready/done successors must share a fresh atomic W cut");
    }

    @Test
    void workerClaimReleaseSuccessorsHaveFreshPoolCuts() throws Exception {
        String pool = source("common/regions/RegionWorkerPool.java");
        String steal = section(pool,
                "boolean stealOne(boolean physicalMain)",
                "private boolean rerouteIfMainSafe(");
        int stealRelease = steal.indexOf("r.release(Thread.currentThread())");
        int stealAdmission = steal.indexOf(
                "runSuccessBodyIfHealthy(() -> {", stealRelease);
        int stealReady = steal.indexOf("publishReady(r)", stealAdmission);
        int stealDone = steal.indexOf(".tryMarkDone(r, rsSteal)", stealReady);
        assertTrue(stealRelease >= 0 && stealAdmission > stealRelease
                        && stealReady > stealAdmission && stealDone > stealReady,
                "Main help-pump claim release must precede a fresh pool cut for re-arm/done");

        String work = section(pool,
                "boolean workOnce(RegionWorkerThread me, boolean allowUnits)",
                "private void drainOrphanHelp(");
        int workRelease = work.indexOf("claimed.release(me)");
        int workAdmission = work.indexOf(
                "runSuccessBodyIfHealthy(() -> {", workRelease);
        int workReady = work.indexOf("publishReady(releasedRegion)", workAdmission);
        int workDone = work.indexOf(
                ".tryMarkDone(releasedRegion, rs)", workReady);
        int barrier = work.indexOf(
                "rs.contributeEntityBarrier(releasedRegion)", workDone);
        assertTrue(workRelease >= 0 && workAdmission > workRelease
                        && workReady > workAdmission && workDone > workReady
                        && barrier > workDone,
                "worker claim release must precede one fresh pool cut for every success tail");
    }

    @Test
    void frozenArrivalClaimSelectsWBeforeExhaustiveRelease() throws Exception {
        String server = source("common/regions/RegionizedServer.java");
        String drive = section(server,
                "private CompletableFuture<Void> dispatchOwnedEntityTicks(boolean tickEntities)",
                "/**\n     * A2 core: dispatch one entity phase");
        int frozen = drive.indexOf("tickRateManager().runsNormally()");
        int claim = drive.indexOf("p.tryClaimAutonomousWork()", frozen);
        int owned = drive.indexOf("arrivalClaimed = true", claim);
        int ledger = drive.indexOf("p.noteClaim(\"frozen-arrival\")", owned);
        int body = drive.indexOf("CrossDimArrivals.publishIsland(p)", ledger);
        int bodyCatch = drive.indexOf("catch (Throwable primary)", body);
        int winner = drive.indexOf("publishFatalAndSelectWinner(primary)", bodyCatch);
        int clearLedger = drive.indexOf("p.clearClaim()", winner);
        int ledgerCatch = drive.indexOf(
                "catch (Throwable cleanupFailure)", clearLedger);
        int clearFlag = drive.indexOf("p.unitClaim.set(false)", ledgerCatch);
        int flagCatch = drive.indexOf(
                "catch (Throwable cleanupFailure)", clearFlag);
        int exact = drive.indexOf("rethrow(arrivalFailure)", flagCatch);
        assertTrue(frozen >= 0 && claim > frozen && owned > claim && ledger > owned
                        && body > ledger && bodyCatch > body && winner > bodyCatch
                        && clearLedger > winner && ledgerCatch > clearLedger
                        && clearFlag > ledgerCatch && flagCatch > clearFlag
                        && exact > flagCatch,
                "frozen arrival publication must select its exact body W before "
                        + "independent ledger/permit cleanup");
        assertFalse(drive.substring(frozen, exact).contains("finally"));
    }

    

    @Test
    void passengerAndDimensionTransitionSelectWBeforeOwnershipCleanup() throws Exception {
        String level = source("common/mixin/world/ServerLevelMixin.java");
        String passenger = section(level, "private void fabrolia$guardTickPassenger",
                "tickPassenger has removal/vehicle gates");
        int passengerBody = passenger.indexOf("original.call(vehicle, entity)");
        int passengerCatch = passenger.indexOf("catch (Throwable thrown)", passengerBody);
        int passengerWinner = passenger.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(thrown)", passengerCatch);
        int bodyRelease = passenger.indexOf("finishPlayerEntityBody(entity)", passengerWinner);
        int guardRelease = passenger.indexOf("guard.fabrolia$endTick()", bodyRelease);
        int exactRethrow = passenger.indexOf("fabrolia$throwPlayerTickFailure(failure)", guardRelease);
        assertTrue(passengerBody >= 0 && passengerCatch > passengerBody
                        && passengerWinner > passengerCatch && bodyRelease > passengerWinner
                        && guardRelease > bodyRelease && exactRethrow > guardRelease,
                "tickPassenger must select exact W before releasing body/guard ownership");
        assertTrue(count(passenger,
                        "failure = RegionScheduler.publishFatalAndSelectWinner(candidate)") == 2,
                "each passenger cleanup failure must merge behind and reselect exact W");

        String player = source("common/mixin/server/ServerPlayerMixin.java");
        String teleport = section(player,
                "private ServerPlayer fabrolia$markPlayerDimensionTransition",
                "Respawn/shadow copy mutates both exact session objects");
        int teleportBody = teleport.indexOf("result = original.call(transition)");
        int teleportCatch = teleport.indexOf("catch (Throwable thrown)", teleportBody);
        int teleportWinner = teleport.indexOf(".publishFatalAndSelectWinner(thrown)", teleportCatch);
        int markerRelease = teleport.indexOf("PlayerDimensionTransition.exit(self)", teleportWinner);
        int cleanupMerge = teleport.indexOf(
                "fabrolia$mergePlayerTickFailure(failure, cleanup)", markerRelease);
        int cleanupWinner = teleport.indexOf(
                ".publishFatalAndSelectWinner(candidate)", cleanupMerge);
        int teleportRethrow = teleport.indexOf(
                "fabrolia$throwPlayerTickFailure(failure)", cleanupWinner);
        int successReturn = teleport.indexOf("return result", teleportRethrow);
        assertTrue(teleportBody >= 0 && teleportCatch > teleportBody
                        && teleportWinner > teleportCatch && markerRelease > teleportWinner
                        && cleanupMerge > markerRelease && cleanupWinner > cleanupMerge
                        && teleportRethrow > cleanupWinner && successReturn > teleportRethrow,
                "cross-dimension teleport must select W while its marker/lease are closed, "
                        + "merge marker cleanup behind W, and rethrow before success");
    }

    @Test
    void passengerAndPlayerCleanupMergeRetainExactPrimaryIdentity() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        for (String owner : java.util.List.of(
                "com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin",
                "com.axalotl.fabrolia.common.mixin.server.ServerPlayerMixin")) {
            Throwable primary = new IllegalStateException(owner + "-primary");
            Throwable cleanup = new IllegalArgumentException(owner + "-cleanup");
            Class<?> type = Class.forName(owner);
            java.lang.reflect.Method merge = type.getDeclaredMethod(
                    "fabrolia$mergePlayerTickFailure", Throwable.class, Throwable.class);
            merge.setAccessible(true);
            Throwable merged = (Throwable) merge.invoke(null, primary, cleanup);
            assertSame(primary, merged,
                    "cleanup must never replace the exact body Throwable for " + owner);
            assertTrue(java.util.Arrays.asList(primary.getSuppressed()).contains(cleanup),
                    "cleanup must remain diagnostic suppressed context for " + owner);
        }
    }

    

    @Test
    void chunkBridgePropagatesExceptionalBodiesAndExposesNoRetryHook() throws Exception {
        String wait = source("common/regions/ChunkBridgeWait.java");
        String stalled = section(wait, "if (loadOrGenerate &&", "LockSupport.parkNanos");
        assertFalse(stalled.contains("onStall.run()")
                        || stalled.contains("submit.get()")
                        || stalled.contains("applyToEither"),
                "a live original upgrade must never acquire a successor ticket/schedule body");

        String cache = source("common/mixin/server/ServerChunkCacheMixin.java");
        String deferredArms = section(cache,
                "public void fabrolia$armDeferredChunkWork()",
                "private void fabrolia$pumpUntilDone");
        assertTrue(count(deferredArms,
                        "RegionScheduler.runSuccessBodyIfHealthy(() -> {") == 2,
                "spawn and block-tick arms must be admitted as two exact bodies");
        assertFalse(deferredArms.contains("if (s != null) s.run()")
                        || deferredArms.contains("if (b != null) b.run()"),
                "a peer W after spawn-arm may not enter the block-tick arm");
        String await = section(cache, "fabrolia$awaitMainThreadResult", "fabrolia$extractCompleted");
        assertFalse(await.contains("resubmit") || await.contains("LOAD_TRUE_MAX_RESUBMITS"));

        String serverCore = section(cache,
                "fabrolia$servercoreRandomTickDistanceAffectsRandomTicks",
                "private CompletableFuture<Void> fabrolia$groupAndSubmit");
        assertTrue(serverCore.contains("InvocationTargetException targetFailure")
                        && serverCore.contains("targetFailure.getCause()")
                        && serverCore.contains("fabrolia$rethrow("),
                "an entered ServerCore config getter must propagate its exact target failure");
        assertFalse(serverCore.contains("catch (Throwable"),
                "optional ServerCore resolution must not swallow VM errors");

        String publication = section(cache, "private boolean fabrolia$publishChangedHolder",
                "Ticket-consolidation read-side lock");
        assertTrue(publication.contains("holder.broadcastChanges(chunk)"));
        assertFalse(publication.contains("catch (")
                        || publication.contains("failed drain"),
                "a partially-entered holder broadcast must not be re-enqueued for retry");

        String heal = source("common/regions/C2meStrandHeal.java");
        String liveProbe = section(heal, "public static Probe inspectAndMaybeHeal",
                "private static synchronized boolean bootstrap");
        assertTrue(liveProbe.contains("targetFailure.getCause()")
                        && liveProbe.contains("publishFatalAndRethrow"));
        assertFalse(liveProbe.contains("failed (disabling)")
                        || liveProbe.contains("clearFlag.invoke")
                        || liveProbe.contains("markDirty.invoke")
                        || liveProbe.contains("BROKEN_HEALED"),
                "recognized c2me upgrade failure must fail-stop, never heal/retry");
    }

    @Test
    void enteredSimulationBodiesHaveNoRecoverySuppressors() throws Exception {
        String entity = source("common/mixin/entity/EntityInBlockStateMixin.java");
        String inBlockState = section(entity,
                "public BlockState getInBlockState()",
                "@Redirect(");
        assertTrue(inBlockState.contains("level.getBlockState(position)")
                        && inBlockState.contains("Objects.requireNonNull(")
                        && inBlockState.contains(
                                "revision = this.fabrolia$inBlockStateRevision;")
                        && inBlockState.contains(
                                "this.fabrolia$inBlockStateRevision != revision")
                        && inBlockState.contains("level = this.level;")
                        && inBlockState.contains("this.level != level")
                        && inBlockState.contains("this.inBlockState = computed;")
                        && inBlockState.contains("return state;"),
                "the atomic cache must validate a world lookup before conditional publication");
        assertFalse(inBlockState.contains("Blocks.AIR")
                        || inBlockState.contains("return null")
                        || inBlockState.contains("original.call")
                        || inBlockState.contains("CallbackInfoReturnable")
                        || inBlockState.contains("catch ("),
                "chunk lookup/null failure must not become AIR, null, suppression, or fallback");
        assertTrue(entity.contains("@Mixin(value = Entity.class, priority = 900)")
                        && entity.contains("fabrolia$publishLevelAndInvalidate")
                        && entity.contains("this.level = newLevel;")
                        && entity.contains("fabrolia$publishBlockPositionAndInvalidate")
                        && entity.contains("fabrolia$consumeRelocatedSetPosInvalidation")
                        && entity.contains("fabrolia$serializeBaseTickInBlockStateInvalidation")
                        && entity.contains("this.fabrolia$inBlockStateRevision++;"),
                "every vanilla position, level and invalidation writer must advance the cache revision");
        assertFalse(entity.contains("InBlockStateCachePublication"),
                "Lithium's higher-priority injector must bind directly; no duplicate hook");

        String lithiumCache = source(
                "common/mixin/lithium/FabroliaLithiumInBlockStateMixin.java");
        assertTrue(lithiumCache.contains("mixin = \"net.caffeinemc.mods.lithium.mixin.entity"
                        + ".collisions.unpushable_cramming.EntityMixin\"")
                        && lithiumCache.contains("name = \"onBlockCached\"")
                        && lithiumCache.contains("method = \"@MixinSquared:Handler\"")
                        && lithiumCache.contains(
                                "private BlockState fabrolia$conservativeLithiumCacheState")
                        && lithiumCache.contains("lithium$getCachedFeetBlockState()")
                        && lithiumCache.contains(
                                "private BlockState fabrolia$conservativeLithiumFeetBlockState")
                        && lithiumCache.contains("return null;"),
                "every Lithium cache observer must receive a conservative non-excluding state");

        String mixins = resource("fabrolia.common.mixins.json");
        assertTrue(mixins.contains("\"entity.EntityInBlockStateMixin\"")
                        && mixins.contains("\"lithium.FabroliaLithiumInBlockStateMixin\""),
                "the cache protocol and its exact Lithium observer must be registered");
        assertFalse(mixins.contains("FabroliaLithiumRandomTickSectionMixin"),
                "a removed random-tick recovery wrapper must not survive as an empty mixin dependency");

        String ticks = source("common/mixin/world/LevelChunkTicksMixin.java");
        assertFalse(ticks.contains("WrapMethod(method = \"poll\")")
                        || ticks.contains("recoverFromBucketInvariantViolation"),
                "a failed scheduled-tick poll must never compact/retry/return null");

        String sectionStorage = source("common/mixin/world/SectionStorageMixin.java");
        String lithiumPoi = source(
                "common/mixin/lithium/FabroliaLithiumPoiStorageMixin.java");
        assertFalse(sectionStorage.contains("fabrolia$retryUnloadRace"));
        assertFalse(lithiumPoi.contains("fabrolia$retryColumnAfterUnload"));

        String sort = source("common/parallelised/utils/DistanceSortBridge.java");
        String invocation = section(sort, "public static boolean sort", "@SuppressWarnings");
        assertTrue(invocation.contains("rethrow(failure)"));
        assertFalse(invocation.contains("catch (Throwable t) {\n            return false;"),
                "a native sort failure after entry may not fall back over partial output");

        String domains = source("common/regions/TickDomains.java");
        String resolve = section(domains,
                "public static long resolveNow(ServerLevel level, LevelChunkTicks<?> c, long chunkKey",
                "public static long resolveNow(ServerLevel level, long island)");
        assertTrue(resolve.contains("throw new IllegalStateException(report)"));
        assertFalse(resolve.contains("return Long.MIN_VALUE"),
                "operational clock resolution must fail before any semantic fallback");
        String rebase = section(domains, "public static int rebaseTo(ServerLevel level",
                "private static int shiftAllErased");
        assertFalse(rebase.contains("tagNow == Long.MIN_VALUE")
                        || rebase.contains("adopt the target tag WITHOUT shifting"),
                "an unresolved source domain must not be retagged without a shift");
        String unpack = section(domains, "public static long unpackBasis", "\n}");
        assertFalse(unpack.contains("return callerNow")
                        || unpack.contains("now == Long.MIN_VALUE"),
                "unpack must not invent the caller clock as an unresolved tag basis");
    }

    @Test
    void conservativeColliderProbeFallsBackToVanillaButNeverSwallowsErrors() throws Exception {
        String collider = source("common/parallelised/utils/HardColliderIndex.java");
        String probe = section(collider, "private static boolean isHard", "public static void onAdd");
        assertTrue(probe.contains("catch (RuntimeException probeFailure)")
                        && probe.contains("return true;"),
                "an unclassifiable null probe must route to vanilla's exact collider walk");
        assertFalse(probe.contains("catch (Throwable"), "VM errors remain fatal");
        assertFalse(collider.contains("try { CELLS_BY_LEVEL.remove")
                        || collider.contains("try { RECENT_ADDS.remove"),
                "level teardown must not silently retain derived indexes");
    }

    @Test
    void chunkPacketCleanupCannotReplaceTheConstructionPrimary() throws Exception {
        String packet = source(
                "common/mixin/network/ClientboundLevelChunkWithLightPacketMixin.java");
        String body = section(packet, "fabrolia$stableChunkData",
                "private static int fabrolia$readLockSections");
        assertTrue(body.contains(
                            "failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure)")
                        && body.contains("fabrolia$publishMergedFailure(failure, cleanupFailure)")
                        && body.contains("fabrolia$rethrow(failure)")
                        && body.contains("RegionScheduler.runSuccessBodyIfHealthy(")
                        && body.contains("RegionScheduler.commitSuccessIfHealthy("),
                "packet construction must preserve its exact primary across every release");
        assertFalse(body.contains("RegionScheduler.throwWorkerFailureIfAny()"),
                "packet construction may not use a check-then-original W window");
        assertFalse(body.contains("finally"),
                "a throwing finally release would replace the packet-construction primary");
        int envelope = body.indexOf("try {");
        int poolCas = body.indexOf(
                "while (!(poolClaimed = owner.tryClaimAutonomousWork()))", envelope);
        int ledger = body.indexOf("owner.noteClaim(\"pktPool\")", poolCas);
        int holderAllocation = body.indexOf(
                "new ClientboundLevelChunkPacketData[1]", ledger);
        int setupCatch = body.indexOf("catch (Throwable setupFailure)", holderAllocation);
        int clearLedger = body.indexOf("owner.clearClaim()", setupCatch);
        int clearFlag = body.indexOf("owner.unitClaim.set(false)", clearLedger);
        assertTrue(envelope >= 0 && poolCas > envelope && ledger > poolCas
                        && holderAllocation > ledger && setupCatch > holderAllocation
                        && clearLedger > setupCatch && clearFlag > clearLedger,
                "the pool-claim marker must arm inside the failure envelope before "
                        + "ledger/allocation, with both release steps after W selection");

        String acquire = section(packet, "private static int fabrolia$readLockSections",
                "private static void fabrolia$readUnlockSections");
        assertTrue(acquire.contains("RegionScheduler.publishFatalAndSelectWinner(t)")
                        && acquire.contains("fabrolia$publishMergedFailure(t, cleanupFailure)")
                        && acquire.contains("fabrolia$rethrow(t)"),
                "partial section-lock cleanup must preserve the acquisition primary");

        String delivery = source("common/regions/ChunkDelivery.java");
        String send = section(delivery, "public static int sendNextChunks",
                "private static boolean adoptSeal");
        assertTrue(send.contains("new BatchPublication")
                        && send.contains("publication.publish(p)")
                        && send.contains("return 0;"),
                "Main may only offer a receipt-gated batch, never report async success");
        int candidateCut = send.indexOf("RegionScheduler.runSuccessBodyIfHealthy(");
        int selectCandidate = send.indexOf("selectCandidate(level, rs, ps, r, reservations)",
                candidateCut);
        int construction = send.indexOf("new BatchPublication", selectCandidate);
        int constructionCut = send.lastIndexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", construction);
        assertTrue(candidateCut >= 0 && selectCandidate > candidateCut
                        && constructionCut > selectCandidate
                        && construction > constructionCut,
                "each delivery candidate and the batch construction stage need an atomic W cut");
        String publication = section(delivery,
                "private static final class BatchPublication",
                "// ------------------------------------------------------------------\n    // global state");
        int bodyCatch = publication.indexOf("catch (Throwable bodyFailure)",
                publication.indexOf("public void runPublished"));
        int select = publication.indexOf(
                "terminalFailure = publishFatalPreserving(bodyFailure)", bodyCatch);
        int terminalize = publication.indexOf(
                "terminalizeReservations(reservations, terminalFailure)", select);
        int rethrow = publication.indexOf("rethrow(failure)", terminalize);
        assertTrue(bodyCatch >= 0 && select > bodyCatch
                        && terminalize > select && rethrow > terminalize,
                "Start/full/Finished failure must publish W, exhaust cleanup, then rethrow W");
        assertFalse(publication.substring(bodyCatch, rethrow).contains("continue;"));
        assertTrue(publication.contains("state = SUCCEEDED")
                        && publication.indexOf("owner.outstanding.addLast(prepared)")
                        > publication.indexOf("state = SUCCEEDED"),
                "ACK/success accounting must follow the real EventLoop body");
        int startCut = publication.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() ->",
                publication.indexOf("public void runPublished"));
        int startSend = publication.indexOf(
                "sender.send(ClientboundChunkBatchStartPacket.INSTANCE)", startCut);
        int fullCut = publication.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() ->",
                startSend);
        int fullSend = publication.indexOf("sender.send(admitted[entry].entry.packet)",
                fullCut);
        int finishedCut = publication.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() ->",
                fullSend);
        int finishedSend = publication.indexOf(
                "sender.send(new ClientboundChunkBatchFinishedPacket", finishedCut);
        assertTrue(startCut >= 0 && startSend > startCut
                        && fullCut > startSend && fullSend > fullCut
                        && finishedCut > fullSend && finishedSend > finishedCut,
                "Start, every full packet, and Finished must be separately admitted");
        String lease = section(delivery, "private static void lease(",
                "public static void sealPending");
        int leaseFatal = lease.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure)");
        int leaseRelease = lease.indexOf("IslandCommands.mainRelease(island)", leaseFatal);
        assertTrue(leaseFatal >= 0 && leaseRelease > leaseFatal
                        && lease.contains("mergeFailure(failure, cleanupFailure)")
                        && lease.contains("rethrow(failure)"));
        assertFalse(lease.contains("finally {\n                if (took)"),
                "claim release must annotate rather than replace snapshot-build failure");
        String seal = section(delivery, "public static void sealPending",
                "/** Publication commits");
        int sealCut = seal.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() ->");
        int sealPoll = seal.indexOf("island.pendingChunkSnapshots.poll()", sealCut);
        int sealPacket = seal.indexOf("new ClientboundLevelChunkWithLightPacket", sealPoll);
        int requeueCut = seal.indexOf("RegionScheduler.runSuccessBodyIfHealthy(() ->",
                sealPacket);
        assertTrue(sealCut >= 0 && sealPoll > sealCut && sealPacket > sealPoll
                        && requeueCut > sealPacket,
                "snapshot detach + seal body share one W admission; each delayed-key "
                        + "publication owns its later successor admission");
    }

    @Test
    void acceptedCarpetMutationPreservesItsExactBodyFailure() throws Exception {
        String router = source("common/regions/CarpetPlayerMutationRouter.java");
        String body = section(router,
                "private void run(RegionizedServer runtime", "private static boolean supportedExactClass");
        int mutation = body.indexOf("mutation.run()");
        int bodyCatch = body.indexOf("catch (Throwable thrown)", mutation);
        int select = body.indexOf("publishFatalAndSelectWinner(thrown)", bodyCatch);
        int exact = body.indexOf("rethrow(winner)", select);
        assertTrue(mutation >= 0 && bodyCatch > mutation && select > bodyCatch && exact > select,
                "an accepted mutation body must select and rethrow its exact global winner");
    }


    @Test
    void routedCommandAndClaimCleanupPreserveThePrimary() throws Exception {
        String commands = source("common/regions/IslandCommands.java");
        String drain = section(commands, "public static void drainRoutedBatch",
                "private static void execute");
        assertTrue(drain.contains("fencedFailure = publishFatalPreserving(bodyFailure)")
                        && drain.contains("mergeFailure(fencedFailure, cleanupFailure)")
                        && drain.contains("rethrow(fencedFailure)"),
                "fence release must annotate, not replace, a routed command body failure");

        String claims = section(commands, "public static void mainClaim",
                "public static boolean mainClaimScoped");
        int claimCatch = claims.indexOf("catch (Throwable t)");
        int claimFatal = claims.indexOf("failure = publishFatalPreserving(t)", claimCatch);
        int claimRelease = claims.indexOf("failure = releaseMainClaim(owner, failure)", claimFatal);
        int claimRethrow = claims.indexOf("publishFatalPreserving(failure)", claimRelease);
        assertTrue(claimCatch >= 0 && claimFatal > claimCatch
                        && claimRelease > claimFatal && claimRethrow > claimRelease,
                "claim-publication cleanup must retain and exact-rethrow the acquisition primary");
    }

    @Test
    void failedEntityUnloadPassDoesNotPublishRetrySuccessors() throws Exception {
        String unload = source(
                "common/mixin/server/PersistentEntityUnloadRetryMixin.java");
        String process = section(unload, "fabrolia$restoreFailedUnloadRequests",
                "private static Throwable fabrolia$mergeFailure");
        int nestedBody = process.indexOf("original.call();");
        int originalAdmission = process.indexOf(
                "runSuccessBodyIfHealthy(original::call)", nestedBody);
        int restore = process.indexOf("for (long chunkPos : retries)", originalAdmission);
        int retryAdmission = process.lastIndexOf(
                "runSuccessBodyIfHealthy(() -> {", restore);
        int retryBody = process.indexOf("this.chunksToUnload.add(chunkPos)", retryAdmission);
        int bodyCatch = process.indexOf("catch (Throwable bodyFailure)", restore);
        int cleanup = process.indexOf("fabrolia$insideProcessUnloads.remove()", bodyCatch);
        int terminal = process.indexOf("commitSuccessIfHealthy(() -> {})", cleanup);
        int exact = process.indexOf("fabrolia$rethrow(failure)", terminal);
        assertTrue(nestedBody >= 0 && originalAdmission > nestedBody
                        && retryAdmission > originalAdmission && restore > retryAdmission
                        && retryBody > retryAdmission && bodyCatch > retryBody
                        && cleanup > bodyCatch && terminal > cleanup && exact > terminal,
                "false-result retries belong only to a normally completed processUnloads body");
        assertTrue(process.contains(
                ".publishFatalAndSelectWinner(bodyFailure)"));
        assertTrue(process.contains(".runSuccessBodyIfHealthy("),
                "retry restoration and its diagnostics must run outside lifecycleLock "
                        + "behind a fresh W-vs-body admission");
        assertEquals(0, count(process, "callSuccessBooleanIfHealthy"),
                "a retry set must not open a lifecycle permit for each chunk key");
        assertEquals(3, count(process, "runSuccessBodyIfHealthy"),
                "the pass has O(1) cuts: vanilla body, one retry-set transaction, diagnostics");
        assertTrue(process.contains(".commitSuccessIfHealthy("),
                "mandatory ThreadLocal cleanup needs its own normal-return cut");
        assertFalse(process.contains("throwWorkerFailureIfAny"),
                "unload roots may not use check-then-body admission");
        assertFalse(process.contains("catch (Throwable ignored)"),
                "diagnostic failure may not be swallowed into a normal tick return");
        assertFalse(process.contains("finally"),
                "a post-entry finally must not requeue work or replace the unload primary");
    }

    

    @Test
    void existingWinnerLeavesHaloHelpObligationOwnedByItsQueue() throws Exception {
        Field pool = RegionScheduler.class.getDeclaredField("pool");
        Field orphan = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        pool.setAccessible(true);
        orphan.setAccessible(true);
        Object previousPool = pool.get(null);
        Object previousOrphan = orphan.get(null);
        pool.set(null, null);
        orphan.set(null, null);

        Method runOne = IslandTickUnit.class.getDeclaredMethod(
                "runOneHaloTask", Queue.class);
        runOne.setAccessible(true);
        AtomicInteger bodies = new AtomicInteger();
        Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        queue.add(bodies::incrementAndGet);
        AssertionError winner = new AssertionError("halo peer W");

        try {
            orphan.set(null, winner);
            InvocationTargetException escaped = assertThrows(
                    InvocationTargetException.class,
                    () -> runOne.invoke(null, queue));
            assertSame(winner, escaped.getCause());
            assertEquals(1, queue.size(),
                    "W-before-cut must leave the exact task in its owning queue");
            assertEquals(0, bodies.get());

            orphan.set(null, null);
            assertSame(Boolean.TRUE, runOne.invoke(null, queue));
            assertTrue(queue.isEmpty());
            assertEquals(1, bodies.get());
        } finally {
            pool.set(null, previousPool);
            orphan.set(null, previousOrphan);
        }
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
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
