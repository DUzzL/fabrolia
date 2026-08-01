package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38 — pins the structure that ended the POI deadlock campaign.
 *
 * <p>Three production watchdog crashes on 2026-07-29 (07:21:32, 10:08:03,
 * 10:26:57) were one AB-BA between the village-distance graph and a POI section.
 * Commit 7d58bbb had put {@code SectionStorage} and {@code PoiSection} into the
 * blanket ACC_SYNCHRONIZED set, asserting "both classes are leaves… no path back
 * into each other in reverse order". That was wrong in both directions, and the
 * monitors it added built a COMPLETE bidirectional triangle
 * {@code DistanceTracker ⇄ SectionStorage ⇄ PoiSection} — four elementary cycles.
 *
 * <p>These are source pins, not behaviour tests, because the defect is the
 * absence of structure and the structure lives in mixin annotations that only
 * resolve at class-load. Runtime application was verified separately against the
 * merged bytecode in {@code .mixin.out} (getRecords lost its monitor, all four
 * tracker call sites route through the wrap handlers, the four targeted section
 * mutators kept theirs).
 */
class PoiTrackerDeferralTest {

    private static Path src() {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia");
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia");
            if (Files.isDirectory(direct)) return direct;
            if (Files.isDirectory(viaCommon)) return viaCommon;
        }
        return null;
    }

    private static String read(String rel) throws Exception {
        Path root = src();
        Assumptions.assumeTrue(root != null, "source tree not available");
        Path f = root.resolve(rel);
        Assumptions.assumeTrue(Files.isRegularFile(f), rel + " not found");
        return Files.readString(f);
    }

    /**
     * The direct cause of the three crashes: the blanket bit made
     * {@code PoiSection.getRecords} synchronized, so a worker holding the tracker
     * monitor inside {@code getLevelFromSource → isVillageCenter} blocked on a
     * section another worker held inside {@code release → setDirty}.
     */
    @Test
    void poiSectionIsNotInTheBlanketSyncSet() throws Exception {
        String text = read("common/mixin/utils/SyncAllMixin.java");
        assertFalse(text.contains("PoiSection.class"),
                "PoiSection must not carry a blanket per-instance monitor: it made "
                        + "getRecords synchronized and closed the DistanceTracker AB-BA. "
                        + "Writes are serialised by PoiSectionMixin's targeted monitors.");
    }

    /**
     * Deferral is what deletes every {@code * → tracker} edge, and therefore what
     * makes it safe for PoiSection to be unlocked. Both notification sites must
     * be covered — {@code setDirty} alone leaves the section-load edge live.
     */
    @Test
    void bothTrackerNotificationSitesAreDeferred() throws Exception {
        String text = read("common/mixin/entity/movement/PoiManagerDistanceMixin.java");
        assertTrue(text.contains("method = \"setDirty(J)V\""),
                "setDirty's tracker notification must be deferred");
        assertTrue(text.contains("method = \"onSectionLoad(J)V\""),
                "onSectionLoad's tracker notification must be deferred");
        int enqueues = text.split("fabrolia\\$enqueueTrackerUpdate", -1).length - 1;
        assertTrue(enqueues >= 2,
                "both notification sites must enqueue rather than call update(); found " + enqueues);
    }

    /**
     * A deferred update is only safe if it is drained where nothing can be
     * waiting on it. Query-time drain also preserves vanilla's same-tick
     * freshness — vanilla runs runAllUpdates() inside sectionsToVillage.
     */
    @Test
    void drainRunsAtTickAndAtQuery() throws Exception {
        String text = read("common/mixin/entity/movement/PoiManagerDistanceMixin.java");
        assertTrue(text.contains("method = \"tick(Ljava/util/function/BooleanSupplier;)V\""),
                "the tick drain site is missing");
        assertTrue(text.contains("method = \"sectionsToVillage\""),
                "the query-time drain site is missing — village queries would go a tick stale");
        assertTrue(text.contains("runAllUpdates()V"),
                "drains must wrap the runAllUpdates INVOKE, not the method HEAD: "
                        + "super.tick's unpackPendingLoads enqueues must be applied by the "
                        + "very runAllUpdates that follows them");
    }

    /**
     * The load-bearing correctness property. {@code update(pos, level, false)} →
     * {@code checkEdge(SOURCE, …)} → {@code SectionTracker.getComputedLevel}
     * remaps the self-neighbour to {@code Long.MAX_VALUE}, which EQUALS
     * {@code knownParent} and is skipped — so the passed level is authoritative
     * and {@code getLevelFromSource} is never re-consulted. A drainer preempted
     * between its truth-read and its update therefore overwrites a newer
     * converged value with a stale one, and with no pending entry left the
     * village stays erased (or a phantom one planted) until an unrelated mutation
     * touches that section. Not a one-tick lag — unbounded divergence.
     */
    @Test
    void drainSnapshotsAndRecomputesUnderTheTrackerMonitor() throws Exception {
        String text = read("common/mixin/entity/movement/PoiDistanceTrackerMixin.java");
        int monitor = text.indexOf("synchronized (this) {");
        int snapshot = text.indexOf("toLongArray()");
        int clear = text.indexOf("fabrolia$pending.clear()");
        int recompute = text.indexOf("getLevelFromSource(sectionPos)");
        assertTrue(monitor > 0, "the drain must hold the tracker monitor");
        assertTrue(snapshot > monitor && clear > monitor && recompute > monitor,
                "snapshot, clear AND recompute must all be INSIDE synchronized (this) — "
                        + "recomputing outside it is a lost-update race that corrupts the "
                        + "village graph permanently.");
        int end = text.indexOf('}', recompute);
        assertTrue(end > recompute, "drain body not recognised");
    }

    /**
     * The enqueue side must stay lock-free: it is called from inside the section
     * monitor ({@code release → setDirty}). Taking any lock there would rebuild
     * the very edge the deferral deletes.
     */
    @Test
    void enqueueTakesNoLock() throws Exception {
        String text = read("common/mixin/entity/movement/PoiDistanceTrackerMixin.java");
        int enqueue = text.indexOf("public void fabrolia$enqueueTrackerUpdate");
        int drain = text.indexOf("public void fabrolia$drainTrackerUpdates");
        assertTrue(enqueue > 0 && drain > enqueue, "mixin shape not recognised");
        String body = text.substring(enqueue, drain);
        assertFalse(body.contains("synchronized"),
                "enqueue is called while holding the PoiSection monitor; it must not lock.");
    }
}
