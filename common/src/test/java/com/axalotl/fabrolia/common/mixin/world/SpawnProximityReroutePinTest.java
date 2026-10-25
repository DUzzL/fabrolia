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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * map was removed 2026-08-02 (a full map copy per changed wave); these pins
 * keep it out. A live read is not automatically a vanilla answer, though: a
 * mid-wave or queued tracker reads a near chunk as definite FALSE, and the
 * local-cap seed caches that for a whole spawn pass (2026-10-24 tunnel-bore
 * review). {@code DistanceManagerMixin} therefore answers DEFAULT unless its
 * proximity epoch proves the tracker settled; the pins below fix the seams
 * that epoch depends on.
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
        assertTrue(callsTrackerMethod(method(dm, "hasPlayersNearby"), "getLevel", "(J)I"),
                "hasPlayersNearby must still read the level through the INVOKE the "
                        + "settled-proximity wrap binds to");
    }

    /** The proximity epoch is only sound if it sees EVERY write: the update()
     *  INVOKEs in addPlayer/removePlayer and the wave in runAllUpdates(ChunkMap).
     *  Any other tracker mutation site in a future DistanceManager must be
     *  wrapped too before the settled answer can be trusted. */
    @Test
    void everyNaturalSpawnCounterWriteGoesThroughAWrappedSeam() throws IOException {
        ClassNode dm = load("/net/minecraft/server/level/DistanceManager.class");
        java.util.Set<String> writers = new java.util.TreeSet<>();
        for (MethodNode m : dm.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && mi.owner.equals(TRACKER)
                        && (mi.name.equals("update") || mi.name.equals("runAllUpdates")
                        || mi.name.equals("runUpdates") || mi.name.equals("setLevel")
                        || mi.name.equals("removeFromQueue") || mi.name.equals("checkEdge"))) {
                    writers.add(m.name + m.desc + " -> " + mi.name + mi.desc);
                }
            }
        }
        assertEquals(new java.util.TreeSet<>(java.util.List.of(
                        "addPlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V -> update(JIZ)V",
                        "getNaturalSpawnChunkCount()I -> runAllUpdates()V",
                        "getSpawnCandidateChunks()Lit/unimi/dsi/fastutil/longs/LongIterator; -> runAllUpdates()V",
                        "hasPlayersNearby(J)Lnet/minecraft/util/TriState; -> runAllUpdates()V",
                        "removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V -> update(JIZ)V",
                        "runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z -> runAllUpdates()V")),
                writers,
                "naturalSpawnChunkCounter write sites changed — re-audit the proximity epoch");
    }

    /** The wrap answers "level 8" for an unsettled tracker. Vanilla must map
     *  that to DEFAULT (ask every player): above the inscribed-square ring,
     *  not above the FALSE bound 8. */
    @Test
    void levelEightIsVanillasUndecidedRing() throws IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertTrue(net.minecraft.world.level.NaturalSpawner.INSCRIBED_SQUARE_SPAWN_DISTANCE_CHUNK < 8,
                "level 8 would read as definite TRUE");
        MethodNode m = method(load("/net/minecraft/server/level/DistanceManager.class"), "hasPlayersNearby");
        boolean falseBoundIsEight = false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.IntInsnNode in
                    && in.getOpcode() == org.objectweb.asm.Opcodes.BIPUSH && in.operand == 8
                    && insn.getNext() != null
                    && insn.getNext().getOpcode() == org.objectweb.asm.Opcodes.IF_ICMPLE) {
                falseBoundIsEight = true;
            }
        }
        assertTrue(falseBoundIsEight, "hasPlayersNearby no longer answers FALSE only above level 8");
    }

    /** The hazard itself, on the real private vanilla tracker: one chunk-step
     *  of a player, one work item of the wave, and the old chunk — one chunk
     *  from the player — is gone from the map (level 10 = definite FALSE).
     *  If a future algorithm stops doing this, the settled-answer wrap can be
     *  reconsidered; until then every unsettled read must be DEFAULT. */
    @Test
    void vanillaWaveTransientlyDropsAChunkNextToThePlayer() throws Exception {
        java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        Object dm = unsafe.allocateInstance(
                Class.forName("net.minecraft.server.level.ChunkMap$DistanceManager"));
        var players = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Object>();
        java.lang.reflect.Field pf = net.minecraft.server.level.DistanceManager.class
                .getDeclaredField("playersPerChunk");
        pf.setAccessible(true);
        pf.set(dm, players);
        Class<?> trackerClass = Class.forName(TRACKER.replace('/', '.'));
        var ctor = trackerClass.getDeclaredConstructor(net.minecraft.server.level.DistanceManager.class, int.class);
        ctor.setAccessible(true);
        Object tracker = ctor.newInstance(dm, 8);
        var update = net.minecraft.server.level.ChunkTracker.class
                .getDeclaredMethod("update", long.class, int.class, boolean.class);
        update.setAccessible(true);
        var runUpdates = net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint.class
                .getDeclaredMethod("runUpdates", int.class);
        runUpdates.setAccessible(true);
        var getLevel = trackerClass.getDeclaredMethod("getLevel", long.class);
        getLevel.setAccessible(true);

        long old = net.minecraft.world.level.ChunkPos.pack(0, 0);
        long next = net.minecraft.world.level.ChunkPos.pack(1, 0);
        var source = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<Object>();
        source.add(new Object());
        players.put(old, source);
        update.invoke(tracker, old, 0, true);
        runUpdates.invoke(tracker, Integer.MAX_VALUE);
        assertEquals(0, getLevel.invoke(tracker, old));

        // DistanceManager.removePlayer then addPlayer for a one-chunk move.
        players.remove(old);
        update.invoke(tracker, old, Integer.MAX_VALUE, false);
        players.put(next, source);
        update.invoke(tracker, next, 0, true);
        runUpdates.invoke(tracker, 1);
        assertEquals(10, getLevel.invoke(tracker, old),
                "mid-wave the adjacent chunk reads above 8 (definite FALSE)");

        runUpdates.invoke(tracker, Integer.MAX_VALUE);
        assertEquals(1, getLevel.invoke(tracker, old), "the settled answer is one chunk");
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
        for (String seam : new String[]{
                "fabrolia$queueProximityUpdate", "fabrolia$propagateProximity",
                "fabrolia$settledProximityLevel"}) {
            assertTrue(src.contains(seam), seam + " is gone — worker readers would take a "
                    + "mid-wave or queued tracker level as a definite vanilla answer again");
        }
        int settled = src.indexOf("private int fabrolia$settledProximityLevel");
        assertTrue(settled >= 0, "settled-proximity wrap must stay a method of DistanceManagerMixin");
        String settledBody = src.substring(settled, src.indexOf("\n    }", settled));
        assertTrue(settledBody.contains("fabrolia$proximityWriters.get() != 0")
                        && settledBody.contains("fabrolia$proximityQueued")
                        && settledBody.contains("acquireFence")
                        && settledBody.contains("== before ? level : 8"),
                "the settled answer needs: no writer, no queued update, fence, unchanged epoch");
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
