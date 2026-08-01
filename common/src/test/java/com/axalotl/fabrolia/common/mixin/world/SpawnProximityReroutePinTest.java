package com.axalotl.fabrolia.common.mixin.world;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
 * Task #40 (SyncAll audit finding #1) pins for the spawn-proximity reroute:
 * vanilla's three spawn-proximity readers ({@code hasPlayersNearby},
 * {@code getNaturalSpawnChunkCount}, {@code getSpawnCandidateChunks}) used to
 * RUN the {@code naturalSpawnChunkCounter} propagation wave inline — under the
 * {@code DynamicGraphMinFixedPoint} blanket monitor, from spawn workers, while
 * holding the ChunkMap monitor (convoy; plus the unsynchronized
 * {@code anyPlayerCloseEnoughForSpawning} door racing {@code playersPerChunk}
 * rehashes). {@code DistanceManagerMixin} reroutes them onto the completed-wave
 * snapshot published by {@code FixedPlayerDistanceChunkTrackerMixin}.
 *
 * <p>Two pin layers, because mixins do not apply in unit tests:
 * <ul>
 *   <li><b>Vanilla bytecode</b> — the exact seams the {@code @WrapOperation}s
 *       bind to must still exist in the shipped {@code DistanceManager};
 *       if an MC update reshapes them, this fails at build time, not as a
 *       silently-unapplied wrap in production.</li>
 *   <li><b>Mixin source</b> — the reroute itself must not be quietly reverted
 *       (each wrap re-opens a door of the convoy / AIOOBE class).</li>
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

    private static boolean readsTrackerChunksField(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi
                    && fi.getOpcode() == Opcodes.GETFIELD
                    && fi.owner.equals(TRACKER) && fi.name.equals("chunks")
                    && fi.desc.equals("Lit/unimi/dsi/fastutil/longs/Long2ByteMap;")) {
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
                            + "removes — if vanilla reshaped it, re-audit the reroute");
        }
        assertTrue(callsTrackerMethod(method(dm, "hasPlayersNearby"), "getLevel", "(J)I"),
                "hasPlayersNearby must still read the tracker level via getLevel(J)I "
                        + "(snapshot-read wrap target)");
        assertTrue(readsTrackerChunksField(method(dm, "getNaturalSpawnChunkCount")),
                "getNaturalSpawnChunkCount must still GETFIELD tracker.chunks "
                        + "(snapshot-map wrap target)");
        assertTrue(readsTrackerChunksField(method(dm, "getSpawnCandidateChunks")),
                "getSpawnCandidateChunks must still GETFIELD tracker.chunks "
                        + "(snapshot-map wrap target)");
    }

    @Test
    void rerouteAndSnapshotPublicationAreWired() throws IOException {
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
                    reader + " must be covered by the reroute — each uncovered reader "
                            + "is an open door back into the wave");
        }
        assertFalse(src.matches("(?s).*fabrolia\\$noEagerWaveOnReads.{0,600}?original\\.call.*"),
                "the no-op wrap must NOT call original — readers must never drive "
                        + "the propagator");
        assertTrue(src.contains("fabrolia$completedProximityLevel")
                        && src.contains("fabrolia$completedProximityChunks"),
                "level/size/keySet reads must come from the completed-wave snapshot, "
                        + "not the propagator's live working map (mid-wave entries are "
                        + "transient and setLevel remove()s past-maxDistance chunks)");

        Path trackerMixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/server/FixedPlayerDistanceChunkTrackerMixin.java");
        Assumptions.assumeTrue(trackerMixin != null, "source tree not available");
        String tsrc = Files.readString(trackerMixin);
        assertTrue(tsrc.contains("volatile Long2ByteOpenHashMap fabrolia$completedChunks"),
                "snapshot must be published through a volatile (release fence for "
                        + "worker readers)");
        assertTrue(tsrc.matches("(?s).*method\\s*=\\s*\"runAllUpdates\",\\s*at\\s*=\\s*@At\\(\"RETURN\"\\).*"),
                "snapshot must republish at runAllUpdates RETURN — completed waves only");
        assertTrue(tsrc.contains("setLevel(JI)V"),
                "the dirty mark must ride setLevel, the single mutation point");
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
