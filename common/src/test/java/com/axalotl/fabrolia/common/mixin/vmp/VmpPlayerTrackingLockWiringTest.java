package com.axalotl.fabrolia.common.mixin.vmp;

import com.axalotl.fabrolia.common.mixin.utils.MixinCanceller;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-artifact and compiled-bytecode pins for VMP's A5 movement boundary. */
class VmpPlayerTrackingLockWiringTest {

    private static final String AREA_MIXIN =
            "com/axalotl/fabrolia/common/mixin/vmp/"
                    + "FabroliaVmpAreaPlayerChunkWatchingManagerMixin";
    private static final String AREA_MAP_ACCESSOR =
            "com/axalotl/fabrolia/common/mixin/vmp/FabroliaVmpAreaMapAccessor";
    private static final String TRACKED_MIXIN =
            "com/axalotl/fabrolia/common/mixin/server/ChunkMapTrackedEntityMixin";
    private static final String AREA_MANAGER =
            "com/ishland/vmp/common/chunkwatching/AreaPlayerChunkWatchingManager";
    private static final String AREA_MAP = "com/ishland/vmp/common/maps/AreaMap";
    private static final String CLIENT_VD_TRACKING =
            "com/ishland/vmp/common/chunkwatching/PlayerClientVDTracking";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String OPERATION_CALL = "([Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String REENTRANT_LOCK =
            "java/util/concurrent/locks/ReentrantLock";
    private static final String PUBLISHED_MAP =
            "com/axalotl/fabrolia/common/vmp/PublishedLong2ObjectMap";
    private static final String INJECT =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT =
            "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String ACCESSOR =
            "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String REFERENCE_INT_MAP =
            "it/unimi/dsi/fastutil/objects/Reference2IntOpenHashMap";
    private static final String APPLIED_DISTANCES_DESC =
            "()L" + REFERENCE_INT_MAP + ";";
    private static final String APPLIED_DISTANCE_DESC =
            "(L" + AREA_MAP + ";Ljava/lang/Object;)I";
    private static final String AREA_ROOT_CTOR_DESC =
            "(Lcom/ishland/vmp/common/chunkwatching/"
                    + "AreaPlayerChunkWatchingManager$Listener;"
                    + "Lcom/ishland/vmp/common/chunkwatching/"
                    + "AreaPlayerChunkWatchingManager$Listener;"
                    + "Lnet/minecraft/server/level/ChunkMap;)V";
    private static final String AREA_ROOT_CONSTRUCTOR =
            "<init>" + AREA_ROOT_CTOR_DESC;
    private static final String VMP_JAR_SHA256 =
            "e1baeb5e1a0948c9fc49415b93ebaac4007355094b432ea151b3c8cce7ffe678";
    private static final String AREA_MANAGER_CLASS_SHA256 =
            "01e2e92f7ed0a8ccfa5746b8104dd551f22c011c5012e21897c928544a2aed60";
    private static final String AREA_MAP_CLASS_SHA256 =
            "50a35528f8fe4c4e7750113fdd44702592d6ea8f0094b5211e7908b3baf42f5a";

    @Test
    void mutationsAreSerializedAndHotReadersUsePublishedPrimitiveSnapshots()
            throws Exception {
        ClassNode mixin = readClass(AREA_MIXIN);
        assertTrue(hasAnnotation(mixin, "Lorg/spongepowered/asm/mixin/Pseudo;"));
        assertTrue(annotationStrings(mixin).contains(
                "com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager"));

        ClassNode areaMapAccessor = readClass(AREA_MAP_ACCESSOR);
        assertTrue((areaMapAccessor.access & Opcodes.ACC_PUBLIC) != 0,
                "the merged VMP manager must be able to invoke the accessor");
        assertTrue((areaMapAccessor.access & Opcodes.ACC_INTERFACE) != 0);
        assertTrue(hasAnnotation(
                areaMapAccessor, "Lorg/spongepowered/asm/mixin/Pseudo;"));
        assertTrue(annotationStrings(areaMapAccessor).contains(
                "com.ishland.vmp.common.maps.AreaMap"));
        MethodNode appliedDistances = method(areaMapAccessor,
                "fabrolia$getAppliedViewDistances", APPLIED_DISTANCES_DESC);
        AnnotationNode accessorAnnotation = annotation(appliedDistances, ACCESSOR);
        assertNotNull(accessorAnnotation);
        assertEquals("viewDistances",
                annotationValue(accessorAnnotation, "value"));

        List<FieldNode> lockFields = mixin.fields.stream()
                .filter(field -> field.name.equals("fabrolia$mutationLock")
                        && field.desc.equals("L" + REENTRANT_LOCK + ";"))
                .toList();
        assertEquals(1, lockFields.size(), "the target must receive one writer lock");
        assertTrue((lockFields.getFirst().access & Opcodes.ACC_VOLATILE) != 0,
                "off-thread mutators must observe constructor publication");
        for (String name : List.of(
                "fabrolia$watchingSnapshots", "fabrolia$generalSnapshots")) {
            List<FieldNode> snapshots = mixin.fields.stream()
                    .filter(field -> field.name.equals(name)
                            && field.desc.equals("L" + PUBLISHED_MAP + ";"))
                    .toList();
            assertEquals(1, snapshots.size(), name);
            assertTrue((snapshots.getFirst().access & Opcodes.ACC_VOLATILE) != 0,
                    name + " must be safely constructor-published");
        }

        MethodNode initialize = method(mixin, "fabrolia$initializePublishedAreaIndex");
        AnnotationNode inject = annotation(initialize, INJECT);
        assertNotNull(inject, "published-index initialization must remain an @Inject");
        assertEquals(List.of(AREA_ROOT_CONSTRUCTOR), annotationValue(inject, "method"));
        assertEquals(Integer.valueOf(1), annotationValue(inject, "require"));
        assertEquals(Integer.valueOf(1), annotationValue(inject, "allow"));
        AnnotationNode at = firstAnnotationValue(inject, "at");
        assertNotNull(at);
        assertEquals(AT, at.desc);
        assertEquals("RETURN", annotationValue(at, "value"));
        assertEquals(1, allocations(initialize, REENTRANT_LOCK));
        assertEquals(1, calls(initialize, REENTRANT_LOCK, "<init>", "()V"));
        assertEquals(2, allocations(initialize, PUBLISHED_MAP));
        assertEquals(1, allocations(initialize,
                "it/unimi/dsi/fastutil/objects/Reference2LongOpenHashMap"));
        assertEquals(1, allocations(initialize,
                "it/unimi/dsi/fastutil/objects/Reference2IntOpenHashMap"));
        assertEquals(1, fieldCount(initialize, Opcodes.PUTFIELD, AREA_MIXIN,
                "fabrolia$mutationLock", "L" + REENTRANT_LOCK + ";"));

        MethodNode accessor = method(mixin, "fabrolia$mutationLock",
                "()L" + REENTRANT_LOCK + ";");
        assertEquals(0, allocations(accessor, REENTRANT_LOCK),
                "the writer-lock accessor must fail closed instead of allocating lazily");
        assertEquals(1, allocations(accessor, "java/lang/IllegalStateException"));

        List<WrapperKey> writerWrappers = List.of(
                new WrapperKey("fabrolia$lockedWatchDistanceChange", "onWatchDistanceChange"),
                new WrapperKey("fabrolia$lockedAdd", "add"),
                new WrapperKey("fabrolia$lockedRemove", "remove"),
                new WrapperKey("fabrolia$lockedMovePlayer", "movePlayer"));
        for (WrapperKey key : writerWrappers) {
            MethodNode wrapper = method(mixin, key.wrapperName);
            assertMutationWrap(wrapper, key.targetName);
        }

        MethodNode appliedDistance = method(mixin,
                "fabrolia$appliedViewDistance", APPLIED_DISTANCE_DESC);
        assertEquals(1, calls(appliedDistance, AREA_MAP_ACCESSOR,
                "fabrolia$getAppliedViewDistances", APPLIED_DISTANCES_DESC));
        assertEquals(1, calls(appliedDistance, REFERENCE_INT_MAP,
                "containsKey", "(Ljava/lang/Object;)Z"));
        assertEquals(1, calls(appliedDistance, REFERENCE_INT_MAP,
                "getInt", "(Ljava/lang/Object;)I"));
        assertEquals(1, allocations(appliedDistance,
                "java/lang/IllegalStateException"));
        for (String wrapperName : List.of(
                "fabrolia$lockedWatchDistanceChange",
                "fabrolia$lockedAdd",
                "fabrolia$lockedMovePlayer")) {
            MethodNode wrapper = method(mixin, wrapperName);
            assertEquals(1, calls(wrapper, AREA_MIXIN,
                    "fabrolia$appliedViewDistance", APPLIED_DISTANCE_DESC),
                    wrapperName + " must use VMP's committed radius");
            assertEquals(0, calls(wrapper, AREA_MIXIN, "getViewDistance",
                    "(Lnet/minecraft/server/level/ServerPlayer;)I"),
                    wrapperName + " must not reread a concurrently mutable request");
            int originalCall = callIndex(
                    wrapper, OPERATION, "call", OPERATION_CALL);
            int appliedCall = callIndexAfter(wrapper, AREA_MIXIN,
                    "fabrolia$appliedViewDistance",
                    APPLIED_DISTANCE_DESC, originalCall);
            assertTrue(originalCall >= 0 && appliedCall > originalCall,
                    wrapperName + " must read the radius committed by the original");
        }

        MethodNode tick = method(mixin, "fabrolia$lockedTick");
        assertWrapBinding(tick, "tick");
        assertMonitorFree(tick);
        assertEquals(1, calls(tick, AREA_MIXIN, "fabrolia$mutationLock",
                "()L" + REENTRANT_LOCK + ";"));
        assertEquals(1, calls(tick, REENTRANT_LOCK, "lock", "()V"));
        assertTrue(calls(tick, REENTRANT_LOCK, "unlock", "()V") >= 2);
        assertEquals(1, fieldCount(tick, Opcodes.GETFIELD, AREA_MIXIN,
                "positions", "Lit/unimi/dsi/fastutil/objects/Object2LongOpenHashMap;"));
        assertEquals(1, calls(tick, CLIENT_VD_TRACKING,
                "isClientViewDistanceChanged", "()Z"));
        assertEquals(1, calls(tick, OPERATION, "call", OPERATION_CALL));
        int acquire = callIndex(tick, REENTRANT_LOCK, "lock", "()V");
        int predicate = callIndex(tick, CLIENT_VD_TRACKING,
                "isClientViewDistanceChanged", "()Z");
        int original = callIndex(tick, OPERATION, "call", OPERATION_CALL);
        int release = callIndexAfter(tick, REENTRANT_LOCK, "unlock", "()V", original);
        assertTrue(acquire >= 0 && predicate > acquire
                        && original > predicate && release > original,
                "tick must preflight and invoke the exact original under one writer lease");
        assertTrue(hasCleanupAroundCall(
                tick, original, REENTRANT_LOCK, "unlock", "()V"));

        MethodNode setReader = method(mixin, "fabrolia$snapshotPlayersWatchingChunk");
        assertMutationWrap(setReader, "getPlayersWatchingChunk");
        assertEquals(1, allocations(setReader,
                "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet"));
        assertEquals(1, calls(setReader,
                "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet", "addAll",
                "(Ljava/util/Collection;)Z"));

        for (PublishedReader reader : List.of(
                new PublishedReader("fabrolia$publishedPlayersWatchingChunk",
                        "getPlayersWatchingChunkArray", "fabrolia$watchingSnapshots"),
                new PublishedReader("fabrolia$publishedPlayersInGeneralArea",
                        "getPlayersInGeneralAreaMap", "fabrolia$generalSnapshots"))) {
            MethodNode wrapper = method(mixin, reader.wrapperName);
            assertWrapBinding(wrapper, reader.targetName);
            assertMonitorFree(wrapper);
            assertEquals(0, calls(wrapper, OPERATION, "call", OPERATION_CALL),
                    reader.wrapperName + " must never touch live VMP storage");
            assertEquals(0, calls(wrapper, REENTRANT_LOCK, "lock", "()V"),
                    reader.wrapperName + " must not join the writer queue");
            assertEquals(1, calls(wrapper, AREA_MIXIN, reader.accessorName,
                    "()L" + PUBLISHED_MAP + ";"));
            assertEquals(1, calls(wrapper, PUBLISHED_MAP, "get",
                    "(J)Ljava/lang/Object;"));
        }

        MethodNode refreshCell = method(mixin, "fabrolia$refreshCell");
        assertEquals(1, calls(refreshCell, AREA_MAP,
                "getObjectsInRangeArray", "(J)[Ljava/lang/Object;"));
        assertEquals(1, calls(refreshCell, "[Ljava/lang/Object;", "clone",
                "()Ljava/lang/Object;"));
        assertEquals(1, calls(refreshCell, PUBLISHED_MAP,
                "put", "(JLjava/lang/Object;)V"));
        assertEquals(1, calls(refreshCell, PUBLISHED_MAP, "remove", "(J)V"));

        long wraps = mixin.methods.stream()
                .filter(method -> annotationStrings(method).contains(
                        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"))
                .count();
        assertEquals(8, wraps,
                "five mutators, one exact set snapshot and two published readers");
        assertEquals(0, mixin.methods.stream().mapToInt(candidate ->
                        allocations(candidate,
                                "java/util/concurrent/locks/ReentrantReadWriteLock")).sum(),
                "the reader-participating dimension-wide gate must be gone");
    }

    @Test
    void trackerPairingTransitionsShareTheExistingIntrinsicMonitor() throws Exception {
        ClassNode mixin = readClass(TRACKED_MIXIN);
        for (TrackedWrapperKey key : List.of(
                new TrackedWrapperKey("updatePlayer",
                        "(Lnet/minecraft/server/level/ServerPlayer;L" + OPERATION + ";)V",
                        "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", 3, false),
                new TrackedWrapperKey("fabrolia$stageBulkUpdatePlayers",
                        "(Ljava/util/List;L" + OPERATION + ";)V",
                        "updatePlayers(Ljava/util/List;)V", 2, false),
                new TrackedWrapperKey("fabrolia$removePlayer",
                        "(Lnet/minecraft/server/level/ServerPlayer;L" + OPERATION + ";)V",
                        "removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", 1, false),
                new TrackedWrapperKey("fabrolia$broadcastRemoved",
                        "(L" + OPERATION + ";)V",
                        "broadcastRemoved()V", 1, true))) {
            MethodNode wrapper = method(mixin, key.wrapperName, key.wrapperDesc);
            assertWrapBinding(wrapper, key.targetName);
            if (key.methodSynchronized) {
                assertTrue((wrapper.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                        key.wrapperName + " must use the tracker intrinsic monitor");
            } else {
                assertTrue(opcodeCount(wrapper, Opcodes.MONITORENTER) >= 1,
                        key.wrapperName + " must enter the tracker intrinsic monitor");
                assertTrue(opcodeCount(wrapper, Opcodes.MONITOREXIT) >= 2,
                        key.wrapperName + " must release it normally and exceptionally");
            }
            assertEquals(key.originalCalls,
                    calls(wrapper, OPERATION, "call", OPERATION_CALL), key.wrapperName);
        }

        ClassNode vanilla = readClass("net/minecraft/server/level/ChunkMap$TrackedEntity");
        method(vanilla, "updatePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method(vanilla, "updatePlayers", "(Ljava/util/List;)V");
        method(vanilla, "removePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V");
        method(vanilla, "broadcastRemoved", "()V");
    }

    @Test
    void mixinsAreRegisteredAndCancelledWithoutVmp() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in, "compiled mixin config");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, occurrences(json,
                    "vmp.FabroliaVmpAreaMapAccessor"));
            assertEquals(1, occurrences(json,
                    "vmp.FabroliaVmpAreaPlayerChunkWatchingManagerMixin"));
        }

        String accessorMixin = AREA_MAP_ACCESSOR.replace('/', '.');
        String managerMixin = AREA_MIXIN.replace('/', '.');
        MixinCanceller withoutVmp = new MixinCanceller();
        assertTrue(withoutVmp.shouldCancel(List.of(), accessorMixin),
                "the optional accessor must not load when VMP is absent");
        assertTrue(withoutVmp.shouldCancel(List.of(), managerMixin),
                "the optional manager target must not load when VMP is absent");

        MixinCanceller withVmp = new MixinCanceller();
        assertFalse(withVmp.shouldCancel(List.of(),
                "com.ishland.vmp.mixins.playerwatching.MixinThreadedAnvilChunkStorage"));
        assertFalse(withVmp.shouldCancel(List.of(), accessorMixin),
                "the applied-radius accessor must load with VMP");
        assertFalse(withVmp.shouldCancel(List.of(), managerMixin),
                "all required seams must apply when VMP was discovered");
    }

    @Test
    void installedVmpArtifactStillHasTheAuditedUnsafeTopology() throws Exception {
        Path artifact = vmpArtifact();
        Assumptions.assumeTrue(artifact != null,
                "exact installed VMP artifact is optional outside the modpack workspace");
        assertEquals(VMP_JAR_SHA256, sha256(Files.readAllBytes(artifact)),
                "a new VMP artifact requires a fresh player-watching concurrency audit");

        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            byte[] managerBytes = entryBytes(zip, AREA_MANAGER + ".class");
            byte[] areaMapBytes = entryBytes(zip, AREA_MAP + ".class");
            assertEquals(AREA_MANAGER_CLASS_SHA256, sha256(managerBytes));
            assertEquals(AREA_MAP_CLASS_SHA256, sha256(areaMapBytes));

            ClassNode manager = read(managerBytes);
            List<MethodNode> constructors = manager.methods.stream()
                    .filter(method -> method.name.equals("<init>"))
                    .toList();
            assertEquals(List.of("()V", AREA_ROOT_CTOR_DESC).stream().sorted().toList(),
                    constructors.stream().map(method -> method.desc).sorted().toList(),
                    "the exact two-constructor VMP topology is part of the init proof");
            MethodNode delegatingConstructor = method(manager, "<init>", "()V");
            assertEquals(1, calls(delegatingConstructor, AREA_MANAGER,
                    "<init>", AREA_ROOT_CTOR_DESC),
                    "the no-argument constructor must delegate to the audited root");
            assertField(manager, "positions",
                    "Lit/unimi/dsi/fastutil/objects/Object2LongOpenHashMap;");
            assertField(manager, "playerAreaMap", "L" + AREA_MAP + ";");
            assertField(manager, "generalPlayerAreaMap", "L" + AREA_MAP + ";");

            for (MethodKey writer : List.of(
                    new MethodKey("tick", "()V"),
                    new MethodKey("onWatchDistanceChange", "()V"),
                    new MethodKey("add", "(Lnet/minecraft/server/level/ServerPlayer;J)V"),
                    new MethodKey("remove", "(Lnet/minecraft/server/level/ServerPlayer;)V"),
                    new MethodKey("movePlayer", "(JLnet/minecraft/server/level/ServerPlayer;)V"))) {
                MethodNode method = method(manager, writer.name, writer.desc);
                assertFalse((method.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                        writer.name + " unexpectedly changed upstream; re-audit the wrapper");
            }

            MethodNode liveSet = method(manager, "getPlayersWatchingChunk",
                    "(J)Ljava/util/Set;");
            assertEquals(1, calls(liveSet, AREA_MAP, "getObjectsInRange",
                    "(J)Ljava/util/Set;"));
            for (String reader : List.of("getPlayersWatchingChunkArray",
                    "getPlayersInGeneralAreaMap")) {
                MethodNode method = method(manager, reader, "(J)[Ljava/lang/Object;");
                assertEquals(1, calls(method, AREA_MAP, "getObjectsInRangeArray",
                        "(J)[Ljava/lang/Object;"));
            }

            ClassNode areaMap = read(areaMapBytes);
            assertField(areaMap, "viewDistances",
                    "L" + REFERENCE_INT_MAP + ";");
            MethodNode rawSet = method(areaMap, "getObjectsInRange", "(J)Ljava/util/Set;");
            assertEquals(0, allocations(rawSet, "java/util/HashSet"),
                    "installed VMP still returns its live identity set");
            MethodNode rawArray = method(areaMap, "getObjectsInRangeArray",
                    "(J)[Ljava/lang/Object;");
            assertEquals(1, calls(rawArray,
                    AREA_MAP + "$RawObjectLinkedOpenIdentityHashSet",
                    "getRawSet", "()[Ljava/lang/Object;"),
                    "installed VMP still exposes the pooled set backing array");

            ClassNode clientVdMixin = read(entryBytes(zip,
                    "com/ishland/vmp/mixins/playerwatching/MixinServerPlayerEntity.class"));
            MethodNode changed = method(clientVdMixin,
                    "isClientViewDistanceChanged", "()Z");
            assertEquals(1, fieldCount(changed, Opcodes.GETFIELD,
                    clientVdMixin.name, "vdChanged", "Z"));
            assertEquals(0, fieldCount(changed, Opcodes.PUTFIELD,
                    clientVdMixin.name, "vdChanged", "Z"));
            assertEquals(0, opcodeCount(changed, Opcodes.INVOKEVIRTUAL)
                            + opcodeCount(changed, Opcodes.INVOKEINTERFACE)
                            + opcodeCount(changed, Opcodes.INVOKESTATIC)
                            + opcodeCount(changed, Opcodes.INVOKESPECIAL),
                    "the no-writer tick preflight requires a pure changed predicate");
        }
    }

    private static void assertMutationWrap(MethodNode method, String targetName) {
        assertWrapBinding(method, targetName);
        assertMonitorFree(method);
        assertEquals(1, calls(method, AREA_MIXIN, "fabrolia$mutationLock",
                "()L" + REENTRANT_LOCK + ";"), method.name);
        assertEquals(1, calls(method, REENTRANT_LOCK, "lock", "()V"), method.name);
        assertTrue(calls(method, REENTRANT_LOCK, "unlock", "()V") >= 2,
                method.name + " must unlock normally and exceptionally");
        assertEquals(1, calls(method, OPERATION, "call", OPERATION_CALL), method.name);
        int acquire = callIndex(method, REENTRANT_LOCK, "lock", "()V");
        int original = callIndex(method, OPERATION, "call", OPERATION_CALL);
        int normalRelease = callIndexAfter(
                method, REENTRANT_LOCK, "unlock", "()V", original);
        assertTrue(acquire >= 0 && original > acquire && normalRelease > original,
                method.name + " must keep the complete original/publication under its lease");
        assertTrue(hasCleanupAroundCall(
                method, original, REENTRANT_LOCK, "unlock", "()V"),
                method.name + " must release its lease after every Throwable");
    }

    private static void assertMonitorFree(MethodNode method) {
        assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED, method.name);
        assertEquals(0, opcodeCount(method, Opcodes.MONITORENTER), method.name);
        assertEquals(0, opcodeCount(method, Opcodes.MONITOREXIT), method.name);
    }

    private static void assertWrapBinding(MethodNode method, String targetName) {
        List<String> annotations = annotationStrings(method);
        assertTrue(annotations.contains(
                        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"),
                method.name + " must be an exact required WrapMethod seam");
        assertTrue(annotations.contains(targetName),
                method.name + " must bind exact target " + targetName);
    }

    private static void assertField(ClassNode type, String name, String desc) {
        List<FieldNode> found = type.fields.stream()
                .filter(field -> name.equals(field.name) && desc.equals(field.desc)).toList();
        assertEquals(1, found.size(), "missing exact field " + type.name + "." + name + desc);
    }

    private static int calls(MethodNode method, String owner, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name, String desc) {
        return callIndexAfter(method, owner, name, desc, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name,
                                      String desc, int after) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && desc.equals(call.desc)) return index;
            index++;
        }
        return -1;
    }


    private static int fieldCount(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)
                    && desc.equals(field.desc)) count++;
        }
        return count;
    }


    private static boolean hasCleanupAroundCall(MethodNode method, int guardedCall,
                                                String owner, String name, String desc) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (start < 0 || start > guardedCall || end <= guardedCall || handler < 0) continue;
            boolean released = false;
            int index = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (index++ < handler) continue;
                if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                        && name.equals(call.name) && desc.equals(call.desc)) released = true;
                if (insn.getOpcode() == Opcodes.ATHROW && released) return true;
            }
        }
        return false;
    }

    private static int allocations(MethodNode method, String internalName) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && internalName.equals(type.desc)) count++;
        }
        return count;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static Path vmpArtifact() {
        String configured = System.getProperty("fabrolia.vmpArtifact");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        }
        return List.of(
                        Path.of("runtime-smoke.NTEH8H/server/mods/"
                                + "vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar"),
                        Path.of("../runtime-smoke.NTEH8H/server/mods/"
                                + "vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar"))
                .stream().map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static byte[] entryBytes(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, "missing exact VMP entry " + name);
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream in = VmpPlayerTrackingLockWiringTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "missing class " + internalName);
            return read(in.readAllBytes());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name) {
        List<MethodNode> found = type.methods.stream()
                .filter(method -> name.equals(method.name)).toList();
        assertEquals(1, found.size(), "expected one " + type.name + "." + name);
        return found.getFirst();
    }

    private static MethodNode method(ClassNode type, String name, String desc) {
        return type.methods.stream()
                .filter(method -> name.equals(method.name) && desc.equals(method.desc))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing exact method " + type.name + "." + name + desc));
    }

    private static boolean hasAnnotation(ClassNode type, String desc) {
        return annotations(type.visibleAnnotations, type.invisibleAnnotations).stream()
                .anyMatch(annotation -> desc.equals(annotation.desc));
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        return annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(candidate -> desc.equals(candidate.desc)).findFirst().orElse(null);
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }

    private static AnnotationNode firstAnnotationValue(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        if (value instanceof AnnotationNode nested) return nested;
        if (value instanceof List<?> list && !list.isEmpty()
                && list.getFirst() instanceof AnnotationNode nested) return nested;
        return null;
    }

    private static List<String> annotationStrings(ClassNode type) {
        ArrayList<String> out = new ArrayList<>();
        collectAnnotations(type.visibleAnnotations, out);
        collectAnnotations(type.invisibleAnnotations, out);
        return out;
    }

    private static List<String> annotationStrings(MethodNode method) {
        ArrayList<String> out = new ArrayList<>();
        collectAnnotations(method.visibleAnnotations, out);
        collectAnnotations(method.invisibleAnnotations, out);
        return out;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> first,
                                                     List<AnnotationNode> second) {
        ArrayList<AnnotationNode> out = new ArrayList<>();
        if (first != null) out.addAll(first);
        if (second != null) out.addAll(second);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectAnnotations(List<AnnotationNode> annotations,
                                           List<String> out) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            out.add(annotation.desc);
            collectValue(annotation.values, out);
        }
    }

    private static void collectValue(Object value, List<String> out) {
        if (value == null) return;
        if (value instanceof String string) {
            out.add(string);
        } else if (value instanceof AnnotationNode annotation) {
            out.add(annotation.desc);
            collectValue(annotation.values, out);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collectValue(nested, out);
        }
    }

    private record MethodKey(String name, String desc) {}

    private record WrapperKey(String wrapperName, String targetName) {}

    private record TrackedWrapperKey(String wrapperName, String wrapperDesc,
                                     String targetName, int originalCalls,
                                     boolean methodSynchronized) {}

    private record PublishedReader(String wrapperName, String targetName,
                                   String accessorName) {}
}
