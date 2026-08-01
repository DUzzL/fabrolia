package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38, second half — pins the structure that let {@code SectionStorage}
 * leave the blanket ACC_SYNCHRONIZED set without shipping a corruption
 * regression. Source pins in the style of {@link PoiTrackerDeferralTest}: the
 * defects are the ABSENCE of structure (a lock held one frame too long, a
 * boolean discarded, an initialiser that silently lost to another mod's
 * injector), and that structure lives in mixin annotations that only resolve
 * at class-load. The behavioural halves are covered by
 * {@link PoiChunkLoadFenceTest}, {@link LockedLong2ObjectMapTest} and
 * {@link PoiTakeTicketAtomicityTest} against the extracted production helpers.
 */
class PoiBlanketSyncRemovalTest {

    private static Path root() {
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
        Path root = root();
        Assumptions.assumeTrue(root != null, "source tree not available");
        Path f = root.resolve(rel);
        Assumptions.assumeTrue(Files.isRegularFile(f), rel + " not found");
        return Files.readString(f);
    }

    private static String readResource(String rel) throws Exception {
        Path root = root();
        Assumptions.assumeTrue(root != null, "source tree not available");
        // root = .../src/main/java/com/axalotl/fabrolia → up 4 to .../src/main, then resources/.
        Path f = root.resolve("../../../../resources/" + rel).normalize();
        Assumptions.assumeTrue(Files.isRegularFile(f), rel + " not found");
        return Files.readString(f);
    }

    @Test
    void sectionStorageIsNotInTheBlanketSyncSet() throws Exception {
        String text = read("common/mixin/utils/SyncAllMixin.java");
        assertFalse(text.contains("SectionStorage.class"),
                "SectionStorage must not carry the blanket per-instance monitor: it was a corner "
                        + "of the DT/ST/PS deadlock triangle and serialised every Lithium POI query. "
                        + "Its four real coverages each have a structural replacement (see SectionStorageMixin).");
    }

    /**
     * The 2026-07-29 01:51 root cause. A {@code @Shadow} field initialiser is
     * transplanted inline into the ctor and can NEVER run after another mod's
     * {@code <init>} RETURN inject — Lithium overwrote the concurrent map and
     * nobody noticed. The only ordering lever that works is injector
     * {@code order} at the same RETURN, and the map must be WRAPPED, not
     * replaced (replacing kills Lithium's columns sidecar).
     */
    @Test
    void storageIsWrappedAtInitReturnWithMaxOrder() throws Exception {
        String text = read("common/mixin/world/SectionStorageMixin.java");
        assertFalse(text.contains("storage = new Long2ObjectConcurrentHashMap"),
                "the dead @Shadow storage initialiser must stay deleted — it never survived Lithium");
        assertTrue(text.contains("order = Integer.MAX_VALUE"),
                "the storage wrap must use injector order (mixin priority cannot beat another mod's RETURN inject)");
        assertTrue(text.contains("new LockedLong2ObjectMap<>(this.storage)"),
                "storage must be WRAPPED in place — replacing it defeats Lithium's listeners");
        assertTrue(text.contains("instanceof LockedLong2ObjectMap"),
                "the wrap must be idempotent under double application");
        assertTrue(text.contains("fabrolia$assertConcurrentStructuresOnce"),
                "the one-shot runtime class assert is what turns the next silent injector defeat loud");
    }

    /** D4: the handshake replaces the old blanket/synchronized unpack wrap. */
    @Test
    void unpackUsesTheLoadFenceNotAMonitor() throws Exception {
        String text = read("common/mixin/world/SectionStorageMixin.java");
        assertTrue(text.contains("fabrolia$loadFence.runExclusively"),
                "unpackChunk must go through PoiChunkLoadFence");
        assertFalse(text.contains("private synchronized void"),
                "no handler here may carry a method monitor — that was the ST monitor by another name");
        assertTrue(text.contains("Long2ObjectMap$Entry;getValue()Ljava/lang/Object;"),
                "unpackPendingLoads must skip in-flight winners or main double-fans-out "
                        + "and overwrites live sections with stale disk copies");
        assertTrue(text.contains("getOrLoad"),
                "the bounded getOrLoad retry for the unload-after-complete window is missing");
    }

    /** D8: drain-loop semantics for both dirty-set consumers. */
    @Test
    void flushAllAndTickDrainInsteadOfForEachClear() throws Exception {
        String text = read("common/mixin/world/SectionStorageMixin.java");
        int flushAll = text.indexOf("fabrolia$drainFlushAll");
        int tick = text.indexOf("fabrolia$drainTick");
        assertTrue(flushAll > 0 && tick > 0, "both drain wraps must exist");
        int count = text.split("removeFirstLong", -1).length - 1;
        assertTrue(count >= 2,
                "both flushAll and tick must poll with removeFirstLong — forEach+clear "
                        + "silently drops a concurrent setDirty (unsaved POI mutations)");
    }

    /**
     * D1 (the review's calibration flaw): the column snapshot must clone under
     * the read lock, and the whole-method wrap must hold NOTHING across the
     * unpack — a read-hold surviving into unpackChunk self-deadlocks
     * (StampedLock cannot upgrade) and AB-BAs against main's
     * loadLock→storageRW. The design's outer wraps on the two column
     * consumers are deliberately absent.
     */
    @Test
    void columnSnapshotClonesUnderReadLockAndNeverLocksAcrossUnpack() throws Exception {
        String text = read("common/mixin/lithium/FabroliaLithiumPoiStorageMixin.java");
        int snapshot = text.indexOf("fabrolia$snapshotColumn");
        int retry = text.indexOf("fabrolia$retryColumnAfterUnload");
        assertTrue(snapshot > 0 && retry > 0, "mixin shape not recognised");
        String snapshotBody = text.substring(snapshot, retry > snapshot ? retry : text.length());
        assertTrue(snapshotBody.contains(".clone()"),
                "the live BitSet must never escape — callers iterate it with nextSetBit while "
                        + "writers mutate it in place (Sepals-proven defensive clone)");
        assertTrue(snapshotBody.contains("readLock()") && snapshotBody.contains("unlockRead"),
                "the clone must happen under the storage read lock");
        String retryBody = text.substring(retry);
        int retryEnd = retryBody.indexOf("fabrolia$emptyWhenEvicted");
        if (retryEnd > 0) retryBody = retryBody.substring(0, retryEnd);
        assertFalse(retryBody.contains("readLock") || retryBody.contains("writeLock"),
                "the whole-method wrap must hold no lock — the miss path re-enters "
                        + "unpackChunk -> storage.put -> write lock");
        assertTrue(text.contains("require = 2"),
                "both columns.get probes must be pinned; a Lithium reshape must fail loudly");
        assertTrue(text.contains("lithium$getElementAt") && text.contains("lithium$getFirstInRangeInChunkColumn"),
                "the evicted-section null-guards on both column consumers are missing");
    }

    /** The third consumer (the anonymous iterator) gets the same null-guard. */
    @Test
    void columnIteratorIsNullGuarded() throws Exception {
        String text = read("common/mixin/lithium/FabroliaLithiumPoiColumnIteratorMixin.java");
        assertTrue(text.contains("SectionStorageMixin$1") && text.contains("computeNext"),
                "the lithium$getInChunkColumn iterator must be null-guarded against unload eviction");
    }

    /** D3: C2ME's unload must be loadLock-scoped and skip in-flight loads. */
    @Test
    void c2meUnloadIsGuarded() throws Exception {
        String text = read("common/mixin/c2me/PoiUnloadGuardMixin.java");
        assertTrue(text.contains("c2me$unloadPoi"), "the unload wrap is missing");
        int sync = text.indexOf("synchronized (this.loadLock)");
        int inFlight = text.indexOf("fabrolia$poiLoadInFlight");
        int call = text.indexOf("original.call(pos)");
        assertTrue(sync > 0 && inFlight > sync && call > inFlight,
                "the unload must run entirely under loadLock and bail while a load is in flight — "
                        + "the unload/unpack interleave leaves sections-present/loadedChunks-absent, and the "
                        + "NEXT unpack then overwrites live sections with stale disk state (lost tickets)");
    }

    /**
     * D5: fast_portals must be cancelled, not monitor-wrapped — its body calls
     * getChunk(EMPTY), and a monitor there recreates the task-#32 chunk-bridge
     * deadlock shape. The restored vanilla body then needs the concurrent
     * loadedChunks.
     */
    @Test
    void fastPortalsCancelledAndLoadedChunksConcurrent() throws Exception {
        String canceller = read("common/mixin/utils/MixinCanceller.java");
        assertTrue(canceller.contains("net.caffeinemc.mods.lithium.mixin.ai.poi.fast_portals.PoiManagerMixin"),
                "fast_portals PoiManagerMixin must be cancelled: its @Unique preloadedCenterChunks is an "
                        + "unguarded plain LongOpenHashSet mutated by workers — a live rehash-AIOOBE vector");
        String pm = read("common/mixin/entity/movement/PoiManagerMixin.java");
        assertTrue(pm.contains("new ConcurrentLongOpenHashSet()"),
                "PoiManager.loadedChunks (its own field, distinct from SectionStorage's) must be "
                        + "replaced via @Inject(<init> RETURN) — NOT via the config-global plugin rewrite table");
        assertFalse(read("common/mixin/utils/SynchronisePlugin.java").contains("LongOpenHashSet\","),
                "LongOpenHashSet must never enter CONCURRENT_REPLACEMENTS — it is instantiated in dozens "
                        + "of hot targeted classes and the table is config-global");
    }

    /**
     * D6: both acquisition paths must honour acquireTicket()'s boolean under
     * the record-position stripe, and take must NOT hold an outer center
     * stripe while acquiring (nested stripes are the AB-BA class the query
     * striping history already died of).
     */
    @Test
    void bothTakePathsFunnelThroughTheAtomicTicketHelper() throws Exception {
        String stripes = read("common/utils/PoiStripes.java");
        int helper = stripes.indexOf("takeTicketAtomically");
        assertTrue(helper > 0, "PoiStripes.takeTicketAtomically missing");
        String helperBody = stripes.substring(helper);
        assertTrue(helperBody.contains("synchronized (stripe(record.getPos()))")
                        && helperBody.contains("record.acquireTicket() ? Optional.of"),
                "the acquire must be atomic under stripe(record.pos) AND honour the boolean — "
                        + "acquireTicket returns false on a full record and both callers used to discard it");

        String vanilla = read("common/mixin/entity/movement/PoiManagerMixin.java");
        int take = vanilla.indexOf("fabrolia$atomicTake");
        assertTrue(take > 0, "vanilla take wrap missing");
        String takeBody = vanilla.substring(take, vanilla.indexOf("fabrolia$syncRelease"));
        assertTrue(takeBody.contains("PoiStripes::takeTicketAtomically"),
                "vanilla take must funnel through the helper");
        assertFalse(takeBody.contains("synchronized"),
                "take must hold NO outer stripe across the stream — stripe(center) was the wrong key "
                        + "(cross-chunk records) and nesting stripe(record.pos) inside it is a take-vs-take AB-BA");

        String lithium = read("common/mixin/lithium/FabroliaLithiumPoiTakeAtMixin.java");
        assertTrue(lithium.contains("lithium$takeAt") && lithium.contains("PoiStripes.takeTicketAtomically"),
                "Lithium's takeAt must be reimplemented through the same helper");
        assertTrue(lithium.contains("biPredicate.test"),
                "takeAt must honour the biPredicate vanilla take applies");
    }

    /** All four new mixins must be registered. */
    @Test
    void newMixinsAreRegistered() throws Exception {
        String text = readResource("fabrolia.common.mixins.json");
        assertTrue(text.contains("\"c2me.PoiUnloadGuardMixin\""), "c2me guard unregistered");
        assertTrue(text.contains("\"lithium.FabroliaLithiumPoiStorageMixin\""), "columns guard unregistered");
        assertTrue(text.contains("\"lithium.FabroliaLithiumPoiColumnIteratorMixin\""), "iterator guard unregistered");
        assertTrue(text.contains("\"lithium.FabroliaLithiumPoiTakeAtMixin\""), "takeAt fix unregistered");
    }

    /**
     * The misnamed plugin constant: 0x1548 decodes to
     * SYNTHETIC|ABSTRACT|NATIVE|BRIDGE|STATIC — private and final are NOT
     * excluded, so merged fabrolia$ methods on blanket-set classes silently
     * inherit ACC_SYNCHRONIZED. The name must say what the mask does.
     */
    @Test
    void synchronisePluginMaskIsHonestlyNamed() throws Exception {
        String text = read("common/mixin/utils/SynchronisePlugin.java");
        // Pins the DECLARATION, not any mention: the javadoc deliberately names the
        // old identifier when explaining what it lied about, and that history is
        // worth keeping.
        assertFalse(text.contains("int FINAL_STATIC_PRIVATE_ABSTRACT"),
                "the constant name was a lie — 0x1548 excludes neither private nor final");
        assertTrue(text.contains("SYNTHETIC_ABSTRACT_NATIVE_BRIDGE_STATIC = 0x1548"),
                "renamed constant missing");
        assertTrue(text.contains("0x1548") && text.contains("SYNTHETIC"),
                "the decode must stay documented at the declaration");
    }

    /** PoiRecord.acquireTicket must stay widened on BOTH loader paths. */
    @Test
    void acquireTicketIsWidenedForBothLoaders() throws Exception {
        assertTrue(readResource("fabrolia.accesswidener")
                        .contains("PoiRecord acquireTicket ()Z"),
                "accesswidener entry for acquireTicket missing");
        assertTrue(readResource("META-INF/accesstransformer.cfg")
                        .contains("PoiRecord acquireTicket()Z"),
                "accesstransformer entry for acquireTicket missing (neoforge would fail at runtime)");
    }
}
