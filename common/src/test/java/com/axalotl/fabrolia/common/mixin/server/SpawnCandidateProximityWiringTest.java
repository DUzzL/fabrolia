package com.axalotl.fabrolia.common.mixin.server;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the O(candidate chunks x players) vanilla spawning fallback reroute. */
final class SpawnCandidateProximityWiringTest {

    private static final String INTERNAL =
            "anyPlayerCloseEnoughForSpawningInternal";
    private static final String DESC =
            "(Lnet/minecraft/world/level/ChunkPos;)Z";

    @Test
    void exactVanillaCollectorSeamStillExistsOnce() throws Exception {
        ClassNode chunkMap = new ClassNode();
        try (InputStream in = getClass().getResourceAsStream(
                "/net/minecraft/server/level/ChunkMap.class")) {
            assertNotNull(in, "mapped vanilla ChunkMap must be on the test classpath");
            new ClassReader(in).accept(chunkMap, 0);
        }
        MethodNode collector = chunkMap.methods.stream()
                .filter(method -> method.name.equals("collectSpawningChunks")
                        && method.desc.equals("(Ljava/util/List;)V"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "vanilla collectSpawningChunks seam is missing"));
        int calls = 0;
        for (AbstractInsnNode instruction = collector.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ChunkMap")
                    && call.name.equals(INTERNAL)
                    && call.desc.equals(DESC)) {
                calls++;
            }
        }
        assertEquals(1, calls,
                "re-audit the reroute if vanilla changes the private fallback seam");
    }

    @Test
    void vmpCollectorUsesLocalCandidatesAndTheExactModdedPredicate() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/mixin/vmp/"
                        + "FabroliaVmpSpawnCandidateMixin.java"));
        int start = source.indexOf("private boolean fabrolia$useVmpSpawnCandidates(");
        int end = source.indexOf("\n    }", start);
        assertTrue(start >= 0 && end > start, "VMP spawn-proximity wrapper missing");
        String body = source.substring(start, end);

        assertTrue(source.substring(Math.max(0, start - 700), start)
                        .contains("method = \"collectSpawningChunks\""));
        assertTrue(source.substring(Math.max(0, start - 700), start)
                        .contains(INTERNAL));
        assertTrue(body.contains("getPlayersInGeneralAreaMap(pos.pack())"),
                "the collector must query VMP's detached local candidate array");
        assertTrue(body.contains("playerIsCloseEnoughForSpawning(player, pos)"),
                "every local candidate must still pass through the exact target "
                        + "predicate wrapped by Vanish");
        assertFalse(body.contains("instance.anyPlayerCloseEnoughForSpawning(pos)"),
                "TriState.TRUE bypasses Vanish's per-player mob-spawning predicate");
        assertFalse(body.contains("original.call("),
                "calling the private fallback here restores the full player scan");

        String mixins = Files.readString(Path.of(
                "src/main/resources/fabrolia.common.mixins.json"));
        String canceller = Files.readString(Path.of(
                "src/main/java/com/axalotl/fabrolia/common/mixin/utils/MixinCanceller.java"));
        assertTrue(mixins.contains("vmp.FabroliaVmpSpawnCandidateMixin"));
        assertTrue(canceller.contains("FabroliaVmpSpawnCandidateMixin"),
                "the hard-linked VMP bridge must disappear when VMP is absent");
    }
}
