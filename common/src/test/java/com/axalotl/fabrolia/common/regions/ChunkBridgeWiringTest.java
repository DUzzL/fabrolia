package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #34 structural pins for shadow-bound chunk-bridge mixins. Source checks
 * retain the branch/order contract; ASM checks bind it to the exact Minecraft
 * callsites and compiled MixinExtras annotation.
 */
class ChunkBridgeWiringTest {

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

    @Test
    void tierThreeUsesOneExactBridgeRequestWithNoRetryHook() throws IOException {
        Path mixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/server/ServerChunkCacheMixin.java");
        Assumptions.assumeTrue(mixin != null, "source tree not available");
        String src = Files.readString(mixin);

        assertTrue(src.contains("ChunkBridgeWait.await"),
                "Tier-3 must wait through ChunkBridgeWait (bounded load=false / "
                        + "one exact load=true obligation) — a raw unbounded park is the "
                        + "2026-07-28 06:56 watchdog");
        assertTrue(src.contains("fabrolia$softNullUntil.put"),
                "a load=false budget expiry must feed the soft-null memo — without "
                        + "it a persistent strand costs a full budget PER LOOKUP "
                        + "(N mobs × collisions/tick × 200ms inside the entity barrier)");
        assertTrue(src.contains("fabrolia$softNullUntil.get"),
                "Tier-3 must consult the soft-null memo before bridging");
        assertTrue(!src.contains("LOAD_TRUE_MAX_RESUBMITS")
                        && !src.contains("future = resubmit.get()"),
                "completed-empty load=true must fail; no fresh ticket/schedule retry exists");
        // The memo must be consulted only AFTER the ready-probes so a chunk
        // that became available is never masked by a stale negative entry.
        int probe = src.indexOf("fabrolia$extractReady(holder, targetStatus)");
        int memo = src.indexOf("fabrolia$softNullUntil.get");
        assertTrue(probe > 0 && memo > probe,
                "soft-null memo consult must come after the Tier-1 ready probe");
    }

    @Test
    void comparatorOutputSignalsNeverLoadChunksFromARegionWorker() throws IOException {
        Path mixin = findSource(
                "src/main/java/com/axalotl/fabrolia/common/mixin/world/LevelMixin.java");
        Assumptions.assumeTrue(mixin != null, "source tree not available");
        String src = Files.readString(mixin);

        int annotation = src.indexOf("method = \"updateNeighbourForOutputSignal\"");
        int requireTwo = src.indexOf("require = 2", annotation);
        int allowTwo = src.indexOf("allow = 2", requireTwo);
        int handler = src.indexOf(
                "private BlockState fabrolia$readLoadedComparatorNeighbor", allowTwo);
        int worker = src.indexOf("instanceof RegionWorkerThread", handler);
        int serverLevel = src.indexOf("instance instanceof ServerLevel", worker);
        int readyLookup = src.indexOf(".getChunkNow(", serverLevel);
        int absentAir = src.indexOf("Blocks.AIR.defaultBlockState()", readyLookup);
        int directRead = src.indexOf("chunk.getBlockState(pos)", absentAir);
        int nonWorkerOriginal = src.indexOf("original.call(instance, pos)", directRead);

        assertTrue(annotation >= 0 && requireTwo > annotation && allowTwo > requireTwo,
                "both vanilla comparator probes must be wrapped; wrapping only the "
                        + "observed second ordinal leaves the first hasChunkAt/read TOCTOU");
        assertTrue(handler > allowTwo && worker > handler && serverLevel > worker
                        && readyLookup > serverLevel && absentAir > readyLookup
                        && directRead > absentAir && nonWorkerOriginal > directRead,
                "region workers need a nonblocking ready-chunk read with AIR for no "
                        + "recipient; only non-region venues may call the loading original");
    }

    @Test
    void comparatorWrapperPinsBothMinecraftProbesAndItsCompiledContract()
            throws IOException {
        String blockPos = "Lnet/minecraft/core/BlockPos;";
        String blockState = "Lnet/minecraft/world/level/block/state/BlockState;";
        String getBlockState = "(" + blockPos + ")" + blockState;

        ClassNode level = readClass("net/minecraft/world/level/Level");
        MethodNode vanilla = method(level, "updateNeighbourForOutputSignal",
                "(" + blockPos + "Lnet/minecraft/world/level/block/Block;)V");
        assertEquals(2, callCount(vanilla, level.name, "getBlockState", getBlockState),
                "MC 26.2 comparator traversal must retain exactly two wrapped probes");

        ClassNode mixin = readClass(
                "com/axalotl/fabrolia/common/mixin/world/LevelMixin");
        MethodNode handler = method(mixin,
                "fabrolia$readLoadedComparatorNeighbor",
                "(Lnet/minecraft/world/level/Level;" + blockPos
                        + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
                        + blockState);
        AnnotationNode wrap = annotation(handler,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("updateNeighbourForOutputSignal"),
                annotationValue(wrap, "method"));
        assertEquals(2, annotationValue(wrap, "require"));
        assertEquals(2, annotationValue(wrap, "allow"));

        List<?> atValues = (List<?>) annotationValue(wrap, "at");
        assertEquals(1, atValues.size());
        AnnotationNode at = (AnnotationNode) atValues.getFirst();
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Lnet/minecraft/world/level/Level;getBlockState("
                        + blockPos + ")" + blockState,
                annotationValue(at, "target"));

        assertEquals(1, callCount(handler,
                "net/minecraft/server/level/ServerChunkCache", "getChunkNow",
                "(II)Lnet/minecraft/world/level/chunk/LevelChunk;"));
        assertEquals(1, callCount(handler,
                "net/minecraft/world/level/chunk/LevelChunk", "getBlockState",
                getBlockState));
        assertEquals(1, callCount(handler,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
    }

    private static ClassNode readClass(String internalName) throws IOException {
        String resource = "/" + internalName + ".class";
        try (InputStream in = Objects.requireNonNull(
                ChunkBridgeWiringTest.class.getResourceAsStream(resource), resource)) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream()
                .filter(candidate -> candidate.name.equals(name)
                        && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        owner.name + "." + name + desc + " not found"));
    }

    private static int callCount(MethodNode method, String owner, String name,
                                 String desc) {
        int count = 0;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        return java.util.stream.Stream.concat(
                        method.visibleAnnotations == null
                                ? java.util.stream.Stream.empty()
                                : method.visibleAnnotations.stream(),
                        method.invisibleAnnotations == null
                                ? java.util.stream.Stream.empty()
                                : method.invisibleAnnotations.stream())
                .filter(candidate -> candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        method.name + " lacks annotation " + desc));
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        List<Object> values = annotation.values;
        for (int i = 0; i < values.size(); i += 2) {
            if (values.get(i).equals(key)) {
                return values.get(i + 1);
            }
        }
        throw new AssertionError(annotation.desc + " lacks member " + key);
    }
}
