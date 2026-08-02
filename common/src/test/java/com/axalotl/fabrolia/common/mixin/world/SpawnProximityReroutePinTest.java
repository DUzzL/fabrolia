package com.axalotl.fabrolia.common.mixin.world;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #40 (SyncAll audit finding #1) pins for the spawn-proximity wave drop:
 * vanilla's three spawn-proximity readers ({@code hasPlayersNearby},
 * {@code getNaturalSpawnChunkCount}, {@code getSpawnCandidateChunks}) used to
 * RUN the {@code naturalSpawnChunkCounter} propagation wave inline — under the
 * {@code DynamicGraphMinFixedPoint} blanket monitor, from spawn workers, while
 * holding the ChunkMap monitor (convoy; plus the unsynchronized
 * {@code anyPlayerCloseEnoughForSpawning} door racing {@code playersPerChunk}
 * rehashes). {@code DistanceManagerMixin} drops the wave INVOKE from the
 * readers (waves run only inside {@code DistanceManager.runAllUpdates},
 * main-only) and the readers read the LIVE concurrent tracker map installed by
 * {@code FixedPlayerDistanceChunkTrackerMixin}.
 *
 * <p>The completed-wave SNAPSHOT that used to sit between the readers and the
 * map was removed 2026-08-02: with the map concurrent, a mid-wave read's worst
 * case is one transient answer on one spawn check for one tick (self-healing),
 * while the snapshot paid a full map copy per changed wave and a wave of
 * staleness directly under the mob cap. These pins keep it out.
 *
 * <p>Two pin layers, because mixins do not apply in unit tests:
 * <ul>
 *   <li><b>Vanilla bytecode</b> — the exact wave-INVOKE seams the no-op
 *       {@code @WrapOperation} binds to must still exist in the shipped
 *       {@code DistanceManager}; if an MC update reshapes them, this fails at
 *       build time, not as a silently-unapplied wrap in production.</li>
 *   <li><b>Mixin source</b> — the wave drop must not be quietly reverted
 *       (reopens the convoy / AIOOBE class) and the snapshot must not quietly
 *       return (reintroduces the copy + the staleness under the cap).</li>
 * </ul>
 */
class SpawnProximityReroutePinTest {

    private static final String TRACKER =
            "net/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker";

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

    private static ClassNode load(String classResource) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = SpawnProximityReroutePinTest.class.getResourceAsStream(classResource)) {
            assertNotNull(in, classResource + " must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name + " on " + node.name));
    }

    private static boolean callsTrackerMethod(MethodNode m, String name, String desc) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && mi.owner.equals(TRACKER) && mi.name.equals(name) && mi.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void vanillaSeamsTheWrapsBindToStillExist() throws IOException {
        ClassNode dm = load("/net/minecraft/server/level/DistanceManager.class");

        for (String reader : new String[]{
                "hasPlayersNearby", "getNaturalSpawnChunkCount", "getSpawnCandidateChunks"}) {
            assertTrue(callsTrackerMethod(method(dm, reader), "runAllUpdates", "()V"),
                    reader + " must still contain the eager-wave INVOKE the no-op wrap "
                            + "removes — if vanilla reshaped it, re-audit the wave drop");
        }
        // The readers' data accesses (getLevel / chunks) are plain live-map
        // reads now — no wrap binds to them, so no seam pin is needed; only
        // the wave INVOKE above is load-bearing.
    }

    @Test
    void waveDropIsWiredAndSnapshotStaysRemoved() throws IOException {
        Path dmMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/world/DistanceManagerMixin.java");
        Assumptions.assumeTrue(dmMixin != null, "source tree not available");
        String src = Files.readString(dmMixin);

        assertTrue(src.contains("fabrolia$noEagerWaveOnReads"),
                "the eager-wave no-op wrap is gone — spawn workers are running the "
                        + "propagator again (convoy + ChunkMap-monitor pileup, audit #1)");
        for (String reader : new String[]{
                "\"hasPlayersNearby\"", "\"getNaturalSpawnChunkCount\"", "\"getSpawnCandidateChunks\""}) {
            assertTrue(src.contains(reader),
                    reader + " must be covered by the wave drop — each uncovered reader "
                            + "is an open door back into the wave");
        }
        assertFalse(src.matches("(?s).*fabrolia\\$noEagerWaveOnReads.{0,900}?original\\.call.*"),
                "the no-op wrap must NOT call original (not even behind a flag) — readers "
                        + "must never drive the propagator; the wave drop is unconditional");
        assertFalse(src.contains("CompletedPlayerProximityView")
                        && src.contains("fabrolia$completedProximityLevel"),
                "the proximity snapshot reroute is back — it pays a map copy per changed "
                        + "wave and adds a wave of staleness directly under the mob cap; the "
                        + "live map is concurrent, reads are already safe");

        Path trackerMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/server/FixedPlayerDistanceChunkTrackerMixin.java");
        Assumptions.assumeTrue(trackerMixin != null, "source tree not available");
        String tsrc = Files.readString(trackerMixin);
        assertTrue(tsrc.contains("Long2ByteConcurrentHashMap"),
                "the tracker's chunks map must stay concurrent — it is the whole basis for "
                        + "reading it live from spawn workers");
        assertFalse(tsrc.contains("fabrolia$completedChunks"),
                "the completed-wave snapshot publication is back on the tracker — with the "
                        + "concurrent live map it is a pure copy-per-wave cost plus staleness "
                        + "under the mob cap; keep it removed");
    }

    /** BinaryHeap left the SyncAll set on 2026-08-01 (audit #5: pure leaf,
     *  javap-verified zero outward calls, triple-guarded already by the nav
     *  monitor + PathFinderMixin's synchronized findPath — the bit only taxed
     *  the A* inner loop). DynamicGraphMinFixedPoint must STAY until its POI
     *  DistanceTracker monitor (load-bearing for 7e769ee's deferral drain) has
     *  an explicit replacement. */
    @Test
    void syncAllSetMembershipPinned() throws IOException {
        Path syncAll = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/utils/SyncAllMixin.java");
        Assumptions.assumeTrue(syncAll != null, "source tree not available");
        String src = Files.readString(syncAll);
        assertFalse(src.contains("BinaryHeap.class"),
                "BinaryHeap must stay OUT of the blanket set (pure uncontended leaf; "
                        + "re-adding it re-taxes the A* inner loop for nothing)");
        assertTrue(src.contains("DynamicGraphMinFixedPoint.class"),
                "DynamicGraphMinFixedPoint must stay IN the blanket set — the POI "
                        + "DistanceTracker drains its deferral queue under this monitor "
                        + "(7e769ee); removing it needs an explicit replacement guard");
    }
}
