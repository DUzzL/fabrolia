package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins lifecycle cuts to fixed receipt/state writes, never future callbacks. */
final class RegionizedSuccessReceiptWiringTest {

    @Test
    void entityBarrierCompletesOutsideTheLifecycleCut() throws Exception {
        String all = source();
        String contribution = section(all, "void contributeEntityBarrier(Region r)",
                "/** Heal/watchdog view:");
        int cut = contribution.indexOf("RegionScheduler.commitSuccessIfHealthy(commit)");
        int admitted = contribution.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", cut);
        int complete = contribution.indexOf("barrier.complete(null)", admitted);
        assertTrue(cut >= 0 && admitted > cut && complete > admitted,
                "barrier accounting must win the fixed cut before callback-free completion "
                        + "runs outside lifecycleLock");
        assertFalse(contribution.substring(0, admitted).contains(".complete(null)"));
    }

    @Test
    void dispatchPreparesVariableStateBeforeAcceptingOneFixedReceipt() throws Exception {
        String all = source();
        String dispatch = section(all,
                "private CompletableFuture<Void> dispatchEntityPhase(",
                "/**\n     * Gated group processing");
        int prepare = dispatch.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int prepareCall = dispatch.indexOf("prepareEntityDispatchPlan(", prepare);
        int publicationAdmission = dispatch.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", prepareCall);
        int publicationStore = dispatch.indexOf(
                "d.entityDispatchPublication = publication", publicationAdmission);
        int eventSeed = dispatch.indexOf("tryEnqueueEntity(active.get(i).region)",
                publicationStore);
        int epochAdmission = dispatch.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", eventSeed);
        int epochLoop = dispatch.indexOf(
                "active.get(i).region.groupedEpoch = epoch", epochAdmission);
        int cut = dispatch.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(publication::accept)", epochLoop);
        int emptyCompletionAdmission = dispatch.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", cut);
        int emptyComplete = dispatch.indexOf("eventBarrier.complete(null)",
                emptyCompletionAdmission);
        assertTrue(prepare >= 0 && prepareCall > prepare
                        && publicationAdmission > prepareCall
                        && publicationStore > publicationAdmission
                        && eventSeed > publicationStore
                        && epochAdmission > eventSeed && epochLoop > epochAdmission
                        && cut > epochLoop
                        && emptyCompletionAdmission > cut && emptyComplete > emptyCompletionAdmission,
                "plan, publication ownership, event seeds, epoch state and empty barrier "
                        + "must retain their separate W-vs-entry cuts");
        String cutTail = dispatch.substring(cut, emptyCompletionAdmission);
        assertFalse(cutTail.contains("System.nanoTime()"));
        assertFalse(cutTail.contains("ColorGate."));
        assertFalse(cutTail.contains(".complete("));
    }

    @Test
    void everyEntityDispatchUsesAnExactRegionCreationCut() throws Exception {
        String server = source();
        String drive = section(server,
                "private CompletableFuture<Void> dispatchOwnedEntityTicks(",
                "/**\n     * A2 core:");
        assertEquals(3, occurrences(
                drive, "snapshotAtEntityDispatchCut(globalDomain)"),
                "sprint, async-global and sync-global dispatches each need one map cut");

        String dispatch = section(server,
                "private CompletableFuture<Void> dispatchEntityPhase(",
                "/**\n     * Gated group processing");
        assertTrue(dispatch.contains(
                        "d.entityDispatchCutEpoch != d.epoch"),
                "dispatch must fail closed when a caller omits its membership cut");
    }

    @Test
    void driveHeadSubsystemsAndPerOwnerMutationsHaveFreshSuccessCuts() throws Exception {
        String all = source();
        String drive = section(all,
                "private CompletableFuture<Void> dispatchOwnedEntityTicks(",
                "/**\n     * A2 core:");

        int batchGate = drive.indexOf("RegionScheduler.runSuccessBodyIfHealthy");
        int beginLevel = drive.indexOf(
                "RegionalPlayerEntityTicksV2.beginLevel(this)", batchGate);
        int batchStore = drive.indexOf("playerTickV2Batch =", batchGate);
        int arrivals = drive.indexOf("CrossDimArrivals.publishGlobal(this)", batchStore);
        assertTrue(batchGate >= 0 && beginLevel > batchGate
                        && batchStore >= batchGate && batchStore < beginLevel
                        && arrivals > beginLevel,
                "player batch construction/publication must win before global arrivals");

        for (String admittedCall : java.util.List.of(
                "() -> TpsHud.tick(level.getServer(), level)",
                "TemporalControl::tickAcks",
                "CrossDimLinks::mainTick",
                "ChunkDelivery::stressTick",
                "ChunkDelivery::liveness",
                "() -> IslandBlockEntityTicks.preUnitMaintenance(level, this)")) {
            int call = drive.indexOf(admittedCall);
            int gate = drive.lastIndexOf(
                    "RegionScheduler.runSuccessBodyIfHealthy", call);
            assertTrue(call >= 0 && gate >= 0 && call - gate < 180,
                    admittedCall + " lost its dedicated successor admission");
        }

        int dueGate = drive.indexOf("RegionScheduler.runSuccessBodyIfHealthy",
                drive.indexOf("AtomicReference<ArrayList<IslandRegistry.PIsland>> dueRef"));
        int dueSnapshot = drive.indexOf(
                "new ArrayList<>(islandRegistry.islands())", dueGate);
        int dueFreeze = drive.indexOf("p.driveSortCost = p.costNanos", dueSnapshot);
        int dueSort = drive.indexOf("prepared.sort", dueFreeze);
        int duePublish = drive.indexOf("dueRef.set(prepared)", dueSort);
        assertTrue(dueGate >= 0 && dueSnapshot > dueGate && dueFreeze > dueSnapshot
                        && dueSort > dueFreeze && duePublish > dueSort,
                "pacer snapshot/key freeze/sort must be one admitted preparation body");

        int ensure = drive.indexOf("TemporalControl.ensurePaced(this, p)");
        int perIslandGate = drive.lastIndexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", ensure);
        int perIslandLoop = drive.lastIndexOf("for (int i = 0, n = due.size()", ensure);
        assertTrue(perIslandLoop >= 0 && perIslandGate > perIslandLoop
                        && ensure > perIslandGate,
                "each ordinary island drive must win a fresh body permit");

        int adoption = drive.indexOf("FlightRecorder.record(\"GAP-DOMAIN-ADOPT ");
        int adoptionGate = drive.lastIndexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", adoption);
        int migration = drive.indexOf("assignDomain(r, globalDomain)", adoption);
        int membership = drive.indexOf("globals.add(r)", migration);
        assertTrue(adoptionGate >= 0 && adoption > adoptionGate
                        && migration > adoption && membership > migration,
                "each stale-domain adoption must preserve record -> migration -> membership "
                        + "inside one admitted body");

        String eventSeed = section(all, "boolean tryEnqueueEntity(Region r)",
                "/** Does {@code r} still owe its entity phase");
        int seedGate = eventSeed.indexOf(
                "RegionScheduler.callSuccessBooleanIfHealthy");
        int seedCas = eventSeed.indexOf("entityEnqueuedEpoch.compareAndSet", seedGate);
        int ready = eventSeed.indexOf("RegionScheduler.enqueueEntityReady(r)", seedCas);
        assertTrue(seedGate >= 0 && seedCas > seedGate && ready > seedCas,
                "event seed CAS and ready publication must share one admitted body");
    }

    @Test
    void groupFutureNormalTerminalIsAnOutsideMonitorSuccessBody() throws Exception {
        String all = source();
        String group = section(all, "private void processGroup(",
                "private Runnable cancellableGroupRoot(");
        int terminalTail = group.indexOf("The group-local body may have returned normally");
        int normalBranch = group.indexOf("if (deferred == null)", terminalTail);
        int admission = group.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy", normalBranch);
        int complete = group.indexOf("cf.complete(null)", admission);
        assertTrue(terminalTail >= 0 && normalBranch > terminalTail
                && admission > normalBranch && complete > admission);
        assertFalse(group.substring(normalBranch, complete)
                .contains("commitSuccessIfHealthy"),
                "CompletableFuture completion may not execute while lifecycleLock is held");
    }

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from,
                () -> "source section not found: " + start + " .. " + end);
        return source.substring(from, to);
    }
}
