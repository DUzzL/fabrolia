package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the final, post-composition bytecode exported by Mixin in the real
 * server modpack. This is deliberately separate from source-mixin wiring tests:
 * it proves that Fabrolia, Lithium, C2ME, Carpet, FSit (when installed) and the
 * other runtime transformers actually produced the one final machine shape we rely on.
 *
 * <p>The ordinary {@code test} task skips this external-artifact test. Use the
 * dedicated Gradle task with {@code -PfabroliaMixinExportRoot=/.../.mixin.out}.
 * The selected export must contain a {@code class/} directory and must be newer
 * than the deployed Fabrolia jar (auto-discovered in the sibling {@code mods/}
 * directory, or supplied with {@code -PfabroliaMixinExportArtifact=...}).</p>
 */
class FullModpackMixinExportVerifierTest {

    private static final String EXPORT_ROOT_PROPERTY = "fabrolia.mixinExportRoot";
    private static final String EXPORT_ARTIFACT_PROPERTY = "fabrolia.mixinExportArtifact";

    private static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
    private static final String SERVER_FUNCTION_MANAGER =
            "net/minecraft/server/ServerFunctionManager";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String PLAYER_LIST = "net/minecraft/server/players/PlayerList";
    private static final String SERVER_CHUNK_CACHE =
            "net/minecraft/server/level/ServerChunkCache";
    private static final String SERVER_ENTITY =
            "net/minecraft/server/level/ServerEntity";
    private static final String CHUNK_MAP =
            "net/minecraft/server/level/ChunkMap";
    private static final String PLAYER_MAP =
            "net/minecraft/server/level/PlayerMap";
    private static final String PLAYER_MOVE_READ_SNAPSHOT =
            "com/axalotl/fabrolia/common/regions/PlayerMoveReadSnapshot";
    private static final String GENERATION_CHUNK_HOLDER =
            "net/minecraft/server/level/GenerationChunkHolder";
    private static final String IDENTITY_SET =
            "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet";
    private static final String PLAYER_CHUNK_SENDER =
            "net/minecraft/server/network/PlayerChunkSender";
    private static final String ITEM_ENTITY =
            "net/minecraft/world/entity/item/ItemEntity";
    private static final String MAIN_THREAD_EXECUTOR =
            "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor";
    private static final String PACKET_PROCESSOR = "net/minecraft/network/PacketProcessor";
    private static final String SERVER_COMMON_LISTENER =
            "net/minecraft/server/network/ServerCommonPacketListenerImpl";
    private static final String SERVER_GAME_LISTENER =
            "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String CONNECTION = "net/minecraft/network/Connection";
    private static final String COMMANDS = "net/minecraft/commands/Commands";
    private static final String DEDICATED_SERVER =
            "net/minecraft/server/dedicated/DedicatedServer";
    private static final String RCON_CLIENT =
            "net/minecraft/server/rcon/thread/RconClient";
    private static final String REENTRANT_BLOCKABLE_EVENT_LOOP =
            "net/minecraft/util/thread/ReentrantBlockableEventLoop";
    private static final String BLOCKABLE_EVENT_LOOP =
            "net/minecraft/util/thread/BlockableEventLoop";
    private static final String TICK_TASK = "net/minecraft/server/TickTask";
    private static final String REENTRANT_EVENT_LOOP_ACCESSOR =
            "com/axalotl/fabrolia/common/mixin/server/"
                    + "ReentrantBlockableEventLoopAccessor";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String LEVEL_TICKS = "net/minecraft/world/ticks/LevelTicks";
    private static final String POI_SECTION =
            "net/minecraft/world/entity/ai/village/poi/PoiSection";
    private static final String POI_READ_SNAPSHOT =
            "com/axalotl/fabrolia/common/utils/PoiSectionReadSnapshot";
    private static final String POI_READ_ACCESS =
            "com/axalotl/fabrolia/common/utils/FabroliaPoiSectionReadAccess";
    private static final String GROUPED_SNAPSHOT =
            "com/axalotl/fabrolia/common/utils/GroupedSnapshot";
    private static final String LEVEL_CHUNK =
            "net/minecraft/world/level/chunk/LevelChunk";
    private static final String LEVEL_CHUNK_SIMPLE_CTOR =
            "(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/ChunkPos;)V";
    private static final String LEVEL_CHUNK_CANONICAL_CTOR =
            "(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/ChunkPos;"
                    + "Lnet/minecraft/world/level/chunk/UpgradeData;"
                    + "Lnet/minecraft/world/ticks/LevelChunkTicks;"
                    + "Lnet/minecraft/world/ticks/LevelChunkTicks;J"
                    + "[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"
                    + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
    private static final String LEVEL_CHUNK_PROTO_CTOR =
            "(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/ProtoChunk;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V";
    private static final String CHUNK_STATUS_TRACKER =
            "net/caffeinemc/mods/lithium/common/world/chunk/ChunkStatusTracker";
    private static final String CHUNK_MAP_TRACKED_ENTITY =
            "net/minecraft/server/level/ChunkMap$TrackedEntity";
    private static final String VMP_AREA_WATCHING_MANAGER =
            "com/ishland/vmp/common/chunkwatching/AreaPlayerChunkWatchingManager";
    private static final String VMP_AREA_MAP =
            "com/ishland/vmp/common/maps/AreaMap";
    private static final String VMP_CLIENT_VD_TRACKING =
            "com/ishland/vmp/common/chunkwatching/PlayerClientVDTracking";
    private static final String VMP_AREA_ROOT_CTOR =
            "(Lcom/ishland/vmp/common/chunkwatching/"
                    + "AreaPlayerChunkWatchingManager$Listener;"
                    + "Lcom/ishland/vmp/common/chunkwatching/"
                    + "AreaPlayerChunkWatchingManager$Listener;"
                    + "Lnet/minecraft/server/level/ChunkMap;)V";
    private static final String REENTRANT_LOCK =
            "java/util/concurrent/locks/ReentrantLock";
    private static final String REJECTED_READ_WRITE_LOCK =
            "java/util/concurrent/locks/ReentrantReadWriteLock";
    private static final String TRACKING_TOPOLOGY_ADMISSION_GATE =
            "com/axalotl/fabrolia/common/regions/TrackingTopologyAdmissionGate";
    private static final String PUBLISHED_LONG_OBJECT_MAP =
            "com/axalotl/fabrolia/common/vmp/PublishedLong2ObjectMap";
    private static final String VMP_AREA_MAP_ACCESSOR =
            "com/axalotl/fabrolia/common/mixin/vmp/FabroliaVmpAreaMapAccessor";
    private static final String REFERENCE_2_INT_OPEN_HASH_MAP =
            "it/unimi/dsi/fastutil/objects/Reference2IntOpenHashMap";
    private static final String VMP_NEARBY_ENTITY_TRACKING =
            "com/ishland/vmp/common/playerwatching/NearbyEntityTracking";

    private static final List<String> REQUIRED_CLASSES = List.of(
            MINECRAFT_SERVER,
            SERVER_FUNCTION_MANAGER,
            SERVER_LEVEL,
            SERVER_PLAYER,
            ENTITY,
            PLAYER_LIST,
            SERVER_CHUNK_CACHE,
            SERVER_ENTITY,
            CHUNK_MAP,
            PLAYER_MAP,
            PLAYER_CHUNK_SENDER,
            ITEM_ENTITY,
            MAIN_THREAD_EXECUTOR,
            PACKET_PROCESSOR,
            CONNECTION,
            COMMANDS,
            DEDICATED_SERVER,
            RCON_CLIENT,
            REENTRANT_BLOCKABLE_EVENT_LOOP,
            BLOCKABLE_EVENT_LOOP,
            SERVER_COMMON_LISTENER,
            SERVER_GAME_LISTENER,
            LEVEL,
            LEVEL_TICKS,
            POI_SECTION,
            LEVEL_CHUNK,
            CHUNK_STATUS_TRACKER,
            CHUNK_MAP_TRACKED_ENTITY,
            VMP_AREA_MAP,
            VMP_AREA_WATCHING_MANAGER,
            VMP_NEARBY_ENTITY_TRACKING);

    private static final String CARPET_FAKE = "carpet/patches/EntityPlayerMPFake";
    private static final String CARPET_PLAYER_COMMAND = "carpet/commands/PlayerCommand";
    private static final String CARPET_THREAD_VALUE = "carpet/script/value/ThreadValue";
    private static final List<String> OPTIONAL_CARPET_CLASSES = List.of(
            CARPET_FAKE,
            CARPET_PLAYER_COMMAND,
            CARPET_THREAD_VALUE);

    private static final String PLAYER_TICKS =
            "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2";
    private static final String PLAYER_LIFECYCLE_SCOPE =
            PLAYER_TICKS + "$LifecycleLeaseScope";
    private static final String A5_PLAYER_TICKS =
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks";
    private static final String A5_PLAYER_STATE = A5_PLAYER_TICKS + "$PlayerState";
    private static final String CHUNK_BATCH_ACK_PACKET =
            "net/minecraft/network/protocol/game/ServerboundChunkBatchReceivedPacket";
    private static final String ISLAND_COMMANDS =
            "com/axalotl/fabrolia/common/regions/IslandCommands";
    private static final String ADVANCEMENT_BATCH_SCOPE =
            "com/axalotl/fabrolia/common/parallelised/advancement/AdvancementBatchScope";
    private static final String REGION_SCHEDULER =
            "com/axalotl/fabrolia/common/regions/RegionScheduler";
    private static final String REGION_MAIN_BLOCKING_WAIT_GUARD =
            "com/axalotl/fabrolia/common/regions/RegionMainBlockingWaitGuard";
    private static final String CARPET_MUTATION_ROUTER =
            "com/axalotl/fabrolia/common/regions/CarpetPlayerMutationRouter";
    private static final String FABROLIA_MIXIN_PACKAGE =
            "com/axalotl/fabrolia/common/mixin/";
    private static final String CARPET_MUTATION_ROUTE_DESC =
            "(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Runnable;"
                    + "Ljava/lang/String;)Z";
    private static final String A5_LISTENER_STATE_ACCESS =
            "com/axalotl/fabrolia/common/regions/AsyncIslandListenerAccess";
    private static final String ISLAND_ENTITY_TRACKING =
            "com/axalotl/fabrolia/common/regions/IslandEntityTracking";
    private static final String ISLAND_TRACKED_ENTITY_ACCESS =
            "com/axalotl/fabrolia/common/regions/IslandTrackedEntityAccess";
    /** Identity-keyed, insertion-ordered staging queue of ChunkMapTrackedEntityMixin. */
    private static final String PENDING_PAIRING_QUEUE =
            "it/unimi/dsi/fastutil/objects/Reference2ReferenceLinkedOpenHashMap";
    private static final String REGIONIZED_SERVER =
            "com/axalotl/fabrolia/common/regions/RegionizedServer";
    private static final String P_ISLAND =
            "com/axalotl/fabrolia/common/regions/IslandRegistry$PIsland";
    private static final String CHUNK_DELIVERY =
            "com/axalotl/fabrolia/common/regions/ChunkDelivery";
    private static final String CHUNK_DELIVERY_BATCH = CHUNK_DELIVERY + "$Batch";
    private static final String CHUNK_DELIVERY_REC = CHUNK_DELIVERY + "$Rec";
    private static final String CHUNK_DELIVERY_ENTRY = CHUNK_DELIVERY + "$Entry";
    private static final String CHUNK_DELIVERY_RESERVATION =
            CHUNK_DELIVERY + "$Reservation";
    private static final String CHUNK_DELIVERY_BATCH_PUBLICATION =
            CHUNK_DELIVERY + "$BatchPublication";
    private static final String CHUNK_DELIVERY_ENQUEUE_RESULT =
            CHUNK_DELIVERY + "$EnqueueResult";
    private static final String CHUNK_WRITE_ENQUEUE_FENCE =
            "com/axalotl/fabrolia/common/regions/ChunkWriteEnqueueFence";
    private static final String FABROLIA_CONNECTION_EVENT_LOOP =
            "com/axalotl/fabrolia/common/regions/FabroliaConnectionEventLoop";
    private static final String FABROLIA_FULL_CHUNK_PUBLISHER =
            "com/axalotl/fabrolia/common/regions/FabroliaFullChunkPublisher";
    private static final String FABROLIA_CHUNK_FORGET_PUBLISHER =
            "com/axalotl/fabrolia/common/regions/FabroliaChunkForgetPublisher";
    private static final String FABROLIA_WRITE_GEN =
            "com/axalotl/fabrolia/common/regions/FabroliaWriteGen";
    private static final String CHUNK_BATCH_ACK_REPLAY =
            "com/axalotl/fabrolia/common/regions/ChunkBatchAckReplay";
    private static final String OPERATION =
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String OPERATION_CALL_DESC =
            "([Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CALLBACK_RETURNABLE =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String THREAD = "java/lang/Thread";
    private static final String THREAD_DESC = "()Ljava/lang/Thread;";

    /* Most files are emitted seconds apart, but lazily initialized compatibility
       classes can first load after datapack compilation. The artifact freshness
       floor is the primary stale-file guard; this secondary window catches old
       leftovers while allowing a deliberately slow cold bootstrap. */
    private static final Duration MAX_CRITICAL_EXPORT_SPAN = Duration.ofMinutes(10);
    private static final Duration FILESYSTEM_MTIME_TOLERANCE = Duration.ofSeconds(2);

    @Test
    void verifiesFreshFinalFullModpackComposition() throws Exception {
        String configured = System.getProperty(EXPORT_ROOT_PROPERTY);
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "use :common:verifyFullModpackMixinExport with "
                        + "-PfabroliaMixinExportRoot=/path/to/.mixin.out");

        Export export = loadFreshExport(Path.of(configured));
        ClassNode a5PlayerTicks = readArtifactType(export.artifact(), A5_PLAYER_TICKS);
        verifyMinecraftServer(export.type(MINECRAFT_SERVER));
        verifyServerFunctionManager(export.type(SERVER_FUNCTION_MANAGER));
        verifyServerLevel(export.type(SERVER_LEVEL), export.type(LEVEL));
        verifyServerPlayer(export.type(SERVER_PLAYER));
        verifyServerChunkCache(export.type(SERVER_CHUNK_CACHE),
                export.type(MAIN_THREAD_EXECUTOR));
        verifyPacketProcessor(export.type(PACKET_PROCESSOR));
        verifyConnectionA5(export.type(CONNECTION));
        verifyServerCommonFlush(export.type(SERVER_COMMON_LISTENER));
        verifyServerGameListenerA5(export.type(SERVER_GAME_LISTENER));
        verifyServerGameDeferredContinuations(export.type(SERVER_GAME_LISTENER));
        verifyLevelTicksConstructorQueues(export.type(LEVEL_TICKS));
        verifyCommandsFence(export.type(MINECRAFT_SERVER), export.type(COMMANDS),
                export.type(SERVER_GAME_LISTENER), export.type(DEDICATED_SERVER),
                export.type(RCON_CLIENT),
                export.type(REENTRANT_BLOCKABLE_EVENT_LOOP),
                export.type(BLOCKABLE_EVENT_LOOP));
        verifyLithium(export.type(LEVEL), export.type(CHUNK_STATUS_TRACKER));
        verifyPoiSectionSnapshotComposition(export.type(POI_SECTION));
        verifyVmpPlayerTrackingLocks(
                export.type(VMP_AREA_WATCHING_MANAGER),
                export.type(VMP_AREA_MAP),
                export.type(CHUNK_MAP_TRACKED_ENTITY));
        verifyEntityTrackingComposition(
                export.type(SERVER_ENTITY), export.type(CHUNK_MAP_TRACKED_ENTITY),
                export.type(VMP_NEARBY_ENTITY_TRACKING),
                readArtifactType(export.artifact(), ISLAND_ENTITY_TRACKING),
                readArtifactType(export.artifact(), REGIONIZED_SERVER));
        verifyBlockPublicationComposition(
                export.type(SERVER_CHUNK_CACHE), export.type(CHUNK_MAP),
                export.type(PLAYER_MAP),
                export.type(PLAYER_CHUNK_SENDER),
                a5PlayerTicks,
                readArtifactType(export.artifact(), PLAYER_MOVE_READ_SNAPSHOT));
        verifyChunkBatchAckComposition(
                export.type(PLAYER_CHUNK_SENDER), export.type(SERVER_GAME_LISTENER),
                readArtifactType(export.artifact(), CHUNK_BATCH_ACK_REPLAY),
                readArtifactType(export.artifact(), CHUNK_DELIVERY),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_BATCH),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_REC));
        verifyChunkFullSnapshotEnqueueFence(
                export.type(LEVEL_CHUNK), export.type(PLAYER_CHUNK_SENDER),
                export.type(CONNECTION), export.type(SERVER_COMMON_LISTENER),
                readArtifactType(export.artifact(), CHUNK_WRITE_ENQUEUE_FENCE),
                readArtifactType(export.artifact(), CHUNK_DELIVERY),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_REC),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_RESERVATION),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_ENTRY),
                readArtifactType(export.artifact(), CHUNK_DELIVERY_BATCH_PUBLICATION),
                readArtifactType(export.artifact(), FABROLIA_CONNECTION_EVENT_LOOP),
                readArtifactType(export.artifact(), FABROLIA_FULL_CHUNK_PUBLISHER),
                readArtifactType(export.artifact(), FABROLIA_CHUNK_FORGET_PUBLISHER));
        verifyInBlockStateCacheComposition(export.type(ENTITY));
        verifyA5LifecycleLeaseComposition(
                export.type(SERVER_PLAYER), export.type(PLAYER_LIST));
        verifyCarpetMutationRouter(
                readArtifactType(export.artifact(), CARPET_MUTATION_ROUTER));
        ClassNode carpetFake = export.optionalType(CARPET_FAKE);
        if (carpetFake != null) verifyCarpetFakeA5(carpetFake);
        ClassNode carpetCommand = export.optionalType(CARPET_PLAYER_COMMAND);
        if (carpetCommand != null) verifyCarpetCommandA5(carpetCommand);
        ClassNode carpetThreadValue = export.optionalType(CARPET_THREAD_VALUE);
        if (carpetThreadValue != null) verifyCarpetThreadValueBlockingWaitGuards(
                carpetThreadValue);
    }

    private static void verifyLevelTicksConstructorQueues(ClassNode levelTicks) {
        MethodNode constructor = method(levelTicks, "<init>",
                "(Ljava/util/function/LongPredicate;)V");
        assertEquals(1, fieldCount(constructor, Opcodes.PUTFIELD, LEVEL_TICKS,
                        "toRunThisTick", "Ljava/util/Queue;"),
                "Mixin composition must not overwrite vanilla's live toRunThisTick queue");
        assertEquals(1, fieldCount(constructor, Opcodes.PUTFIELD, LEVEL_TICKS,
                        "alreadyRunThisTick", "Ljava/util/List;"),
                "Mixin composition must not overwrite vanilla's live alreadyRunThisTick list");
    }

    private static Export loadFreshExport(Path configuredRoot) throws Exception {
        Path root = configuredRoot.toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), "missing Mixin export root: " + root);
        Path classRoot = root.resolve("class");
        assertTrue(Files.isDirectory(classRoot),
                "Mixin export root must contain class/: " + root);

        Path artifact = resolveDeployedArtifact(root);
        assertTrue(Files.isRegularFile(artifact),
                "missing deployed Fabrolia artifact: " + artifact);
        FileTime artifactTimestamp = Files.getLastModifiedTime(artifact);
        Instant freshnessFloor = artifactTimestamp.toInstant()
                .minus(FILESYSTEM_MTIME_TOLERANCE);

        Map<String, ClassNode> classes = new LinkedHashMap<>();
        List<FileTime> timestamps = new ArrayList<>();
        for (String internalName : REQUIRED_CLASSES) {
            Path classFile = classRoot.resolve(internalName + ".class");
            assertTrue(Files.isRegularFile(classFile),
                    "missing critical transformed class: " + classFile);
            FileTime timestamp = Files.getLastModifiedTime(classFile);
            timestamps.add(timestamp);
            assertFalse(timestamp.toInstant().isBefore(freshnessFloor),
                    () -> "stale transformed class " + classFile + " (" + timestamp
                            + ") predates deployed artifact " + artifact + " ("
                            + artifactTimestamp + ")");

            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(classFile)).accept(node, 0);
            assertEquals(internalName, node.name,
                    "export path/class identity mismatch for " + classFile);
            classes.put(internalName, node);
        }
        for (String internalName : OPTIONAL_CARPET_CLASSES) {
            Path classFile = classRoot.resolve(internalName + ".class");
            if (!Files.isRegularFile(classFile)) continue;
            FileTime timestamp = Files.getLastModifiedTime(classFile);
            timestamps.add(timestamp);
            assertFalse(timestamp.toInstant().isBefore(freshnessFloor),
                    () -> "stale optional transformed class " + classFile + " (" + timestamp
                            + ") predates deployed artifact " + artifact + " ("
                            + artifactTimestamp + ")");
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(classFile)).accept(node, 0);
            assertEquals(internalName, node.name,
                    "export path/class identity mismatch for " + classFile);
            classes.put(internalName, node);
        }

        Instant oldest = timestamps.stream().min(Comparator.naturalOrder())
                .orElseThrow().toInstant();
        Instant newest = timestamps.stream().max(Comparator.naturalOrder())
                .orElseThrow().toInstant();
        Duration span = Duration.between(oldest, newest);
        assertTrue(span.compareTo(MAX_CRITICAL_EXPORT_SPAN) <= 0,
                "critical transformed classes span " + span
                        + "; .mixin.out contains stale leftovers from another bootstrap");
        return new Export(root, artifact, classes);
    }

    private static Path resolveDeployedArtifact(Path exportRoot) throws IOException {
        String configured = System.getProperty(EXPORT_ARTIFACT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path serverRoot = exportRoot.getParent();
        assertNotNull(serverRoot,
                "cannot infer server root from Mixin export: " + exportRoot);
        Path mods = serverRoot.resolve("mods");
        assertTrue(Files.isDirectory(mods),
                "cannot auto-discover deployed artifact; missing " + mods
                        + " (supply -PfabroliaMixinExportArtifact=...)");
        List<Path> candidates;
        try (Stream<Path> files = Files.list(mods)) {
            candidates = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return (name.startsWith("fabrolia-fabric-")
                                || name.startsWith("fabrolia-alpha_")) && name.endsWith(".jar");
                    })
                    .sorted()
                    .toList();
        }
        assertEquals(1, candidates.size(),
                "expected exactly one deployed Fabrolia Fabric jar in " + mods
                        + ", found " + candidates);
        return candidates.getFirst().toAbsolutePath().normalize();
    }

    private static ClassNode readArtifactType(Path artifact, String internalName)
            throws IOException {
        String entryName = internalName + ".class";
        try (JarFile jar = new JarFile(artifact.toFile())) {
            var entry = jar.getJarEntry(entryName);
            if (entry != null) {
                assertFalse(entry.isDirectory(),
                        "deployed artifact entry is a directory: " + entryName);
                ClassNode node = new ClassNode();
                try (var input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(node, 0);
                }
                assertEquals(internalName, node.name,
                        "artifact path/class identity mismatch for " + entryName);
                return node;
            }

            // Fabric include() dependencies are nested under META-INF/jars.
            // Runtime helpers such as SyncItemPickup therefore belong to the
            // exact deployed artifact even though they are not top-level ZIP
            // entries. Inspect those bytes directly rather than a loose API
            // build directory which could diverge from the deployed JAR.
            List<ClassNode> nestedMatches = new ArrayList<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var nested = entries.nextElement();
                if (nested.isDirectory() || !nested.getName().startsWith("META-INF/jars/")
                        || !nested.getName().endsWith(".jar")) continue;
                byte[] bytes;
                try (var input = jar.getInputStream(nested)) {
                    bytes = input.readAllBytes();
                }
                try (JarInputStream nestedJar = new JarInputStream(
                        new ByteArrayInputStream(bytes))) {
                    for (var nestedEntry = nestedJar.getNextJarEntry(); nestedEntry != null;
                         nestedEntry = nestedJar.getNextJarEntry()) {
                        if (!nestedEntry.isDirectory()
                                && entryName.equals(nestedEntry.getName())) {
                            ClassNode node = new ClassNode();
                            new ClassReader(nestedJar).accept(node, 0);
                            nestedMatches.add(node);
                        }
                    }
                }
            }
            assertEquals(1, nestedMatches.size(),
                    "deployed artifact " + artifact + " must contain exactly one direct or "
                            + "nested " + entryName);
            ClassNode node = nestedMatches.getFirst();
            assertEquals(internalName, node.name,
                    "nested artifact path/class identity mismatch for " + entryName);
            return node;
        }
    }

    private static void verifyMinecraftServer(ClassNode server) {
        MethodNode getRunningThread = method(server, "getRunningThread", THREAD_DESC);
        int ownerHook = generatedCallIndex(getRunningThread, server.name,
                "$fabrolia$logicalPlayerActorThread");
        int physicalThread = fieldIndex(getRunningThread, Opcodes.GETFIELD,
                server.name, "serverThread", "Ljava/lang/Thread;");
        assertEquals(1, generatedCallCount(getRunningThread, server.name,
                "$fabrolia$logicalPlayerActorThread"));
        assertTrue(ownerHook >= 0 && physicalThread > ownerHook,
                "MinecraftServer logical-owner HEAD hook must precede serverThread fallback");

        MethodNode ownerHandler = generatedMethod(server,
                "$fabrolia$logicalPlayerActorThread");
        assertEquals(1, callCount(ownerHandler, PLAYER_TICKS, "logicalServerThread",
                "(Lnet/minecraft/server/MinecraftServer;)Ljava/lang/Thread;"));
        assertEquals(1, callCount(ownerHandler, CALLBACK_RETURNABLE,
                "setReturnValue", "(Ljava/lang/Object;)V"));

        MethodNode outer = method(server, "tickChildren",
                "(Ljava/util/function/BooleanSupplier;)V");
        assertEquals(1, generatedCallCount(outer, server.name,
                "$fabrolia$regionalPlayerServerPhase"),
                "tickChildren must retain exactly one Fabrolia server-turn wrapper");
        MethodNode turn = generatedMethod(server, "$fabrolia$regionalPlayerServerPhase");
        assertEquals(1, classCallCount(server, PLAYER_TICKS, "beginServerTick"),
                "MinecraftServer must retain exactly one beginServerTick admission");
        MethodNode beginAdmission = uniqueMethod(server,
                candidate -> callCount(candidate, PLAYER_TICKS, "beginServerTick",
                        "(Lnet/minecraft/server/MinecraftServer;)V") == 1,
                "beginServerTick admission body");
        assertEquals(1, invokedynamicTargetCount(turn, server.name,
                        beginAdmission.name, beginAdmission.desc),
                "server-turn wrapper must capture its beginServerTick admission body");
        assertEquals(1, callCount(turn, PLAYER_TICKS, "finishServerTick",
                "()Ljava/lang/Throwable;"));
        assertEquals(1, callCount(turn, PLAYER_TICKS, "abortServerTick",
                "()Ljava/lang/Throwable;"));
        assertTrue(callCount(turn, OPERATION, "call", OPERATION_CALL_DESC) >= 1);

        MethodNode body = uniqueMethod(server,
                candidate -> candidate.name.startsWith("tickChildren$mixinextras$wrapped$")
                        && candidate.desc.equals("(Ljava/util/function/BooleanSupplier;)V"),
                "MixinExtras tickChildren body");
        int connectionGuard = callListIndex(calls(body), call -> call.owner.equals(server.name)
                && call.name.endsWith("$fabrolia$assertPlayerBarrierBeforeConnections"));
        assertEquals(1, generatedCallCount(body, server.name,
                "$fabrolia$assertPlayerBarrierBeforeConnections"));
        assertTrue(connectionGuard >= 0);
        assertEquals(0, callCount(body, server.name, "tickConnection", "()V"),
                "tickChildren must not retain a raw connection-body entry beside its wrapper");

        MethodNode connectionBridge = uniqueMethod(server,
                candidate -> candidate.name.startsWith("mixinextras$bridge$tickConnection$")
                        && callCount(candidate, server.name, "tickConnection", "()V") == 1,
                "MinecraftServer.tickConnection operation bridge");
        assertEquals(1, callCount(connectionBridge, server.name, "tickConnection", "()V"));
        MethodNode barrierHandler = generatedMethod(server,
                "$fabrolia$assertPlayerBarrierBeforeConnections");
        int failureRead = callIndex(barrierHandler, REGION_SCHEDULER,
                "throwWorkerFailureIfAny", "()V");
        int barrier = callIndex(barrierHandler, PLAYER_TICKS, "beforeConnections",
                "(Lnet/minecraft/server/MinecraftServer;)V");
        assertEquals(1, callCount(barrierHandler, PLAYER_TICKS, "beforeConnections",
                "(Lnet/minecraft/server/MinecraftServer;)V"));
        assertTrue(failureRead >= 0 && barrier > failureRead,
                "the transformed connection barrier must observe worker failure first");
        assertEquals(0, callCount(barrierHandler, server.name, "tickConnection", "()V"));

        assertSuspendFlushingComposition(server, body);
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitClockTick",
                "net/minecraft/world/clock/ServerClockManager", "tick", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitGameTimeSynchronization",
                server.name, "forceGameTimeSynchronization", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitRespawnDataUpdate",
                server.name, "updateEffectiveRespawnData", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitPlayerListTick",
                PLAYER_LIST, "tick", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitDebugSubscribersTick",
                "net/minecraft/util/debug/ServerDebugSubscribers", "tick", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitGameTestTick",
                "net/minecraft/gametest/framework/GameTestTicker", "tick", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitTickable",
                "java/lang/Runnable", "run", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitSendNextChunks",
                PLAYER_CHUNK_SENDER, "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitResumeFlushing",
                SERVER_GAME_LISTENER, "resumeFlushing", "()V");
        assertTickChildrenStageComposition(server, body,
                "$fabrolia$admitActivityMonitorTick",
                "net/minecraft/server/notifications/ServerActivityMonitor", "tick", "()V");
        assertEquals(1, callCount(body, SERVER_FUNCTION_MANAGER, "tick", "()V"),
                "the self-admitted function tick must retain its one canonical call site");
        assertEquals(1, callCount(body, SERVER_LEVEL, "tick",
                        "(Ljava/util/function/BooleanSupplier;)V"),
                "ServerLevel owns segmented admissions; a caller permit would self-deadlock");

        assertC2meSaveThreadSubstitution(server,
                "preventAsyncSave", "$fabrolia$logicalC2meSaveAllChunksThread");
        assertC2meSaveThreadSubstitution(server,
                "preventAsyncSaveAll", "$fabrolia$logicalC2meSaveEverythingThread");
    }

    private static void assertTickChildrenStageComposition(
            ClassNode server, MethodNode body, String handlerSuffix,
            String targetOwner, String targetName, String targetDescriptor) {
        assertEquals(1, generatedCallCount(body, server.name, handlerSuffix),
                handlerSuffix + " must replace its one top-level tickChildren call");
        assertEquals(0, callCount(body, targetOwner, targetName, targetDescriptor),
                handlerSuffix + " must leave no raw body entry in tickChildren");
        generatedMethod(server, handlerSuffix);
    }

    private static void assertSuspendFlushingComposition(
            ClassNode server, MethodNode body) {
        String handlerSuffix = "$fabrolia$admitSuspendFlushing";
        assertEquals(1, generatedCallCount(body, server.name, handlerSuffix));
        assertEquals(0, callCount(body, "java/util/List", "forEach",
                "(Ljava/util/function/Consumer;)V"));
        generatedMethod(server, handlerSuffix);
    }

    private static void assertServerLevelStageComposition(
            ClassNode level, MethodNode body, String handlerSuffix,
            String targetOwner, String targetName, String targetDescriptor,
            int matches) {
        assertEquals(matches, generatedCallCount(body, level.name, handlerSuffix),
                handlerSuffix + " must replace every 26.3 ServerLevel.tick call");
        assertEquals(0, callCount(body, targetOwner, targetName, targetDescriptor),
                handlerSuffix + " must leave no raw stage entry in the transformed tick");
        generatedMethod(level, handlerSuffix);
    }

    private static void verifyServerFunctionManager(ClassNode functions) {
        MethodNode outer = method(functions, "tick", "()V");
        assertEquals(1, generatedCallCount(outer, functions.name,
                "$fabrolia$markTickTag"));
        MethodNode handler = generatedMethod(functions, "$fabrolia$markTickTag");
        int markerSet = fieldIndex(handler, Opcodes.PUTFIELD,
                functions.name, "fabrolia$inTickTag", "Z");
        int markerReset = fieldIndexAfter(handler, Opcodes.PUTFIELD,
                functions.name, "fabrolia$inTickTag", "Z", markerSet);
        int cleanupCommit = callIndex(handler, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V");
        int exactRethrow = callIndexByName(handler, functions.name,
                "fabrolia$rethrow");
        assertEquals(1, callCount(handler, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V"));
        assertTrue(markerReset >= 0 && cleanupCommit > markerReset
                        && exactRethrow > cleanupCommit,
                "function tick must cut normal return after restoring its marker");
        String advancementBuffer =
                "com/axalotl/fabrolia/common/parallelised/advancement/AdvancementBuffer";
        List<MethodNode> tickTagFamily = functions.methods.stream()
                .filter(candidate -> candidate.name.contains("fabrolia$markTickTag"))
                .toList();
        assertEquals(0, tickTagFamily.stream()
                        .mapToInt(candidate -> callCountByName(candidate,
                                advancementBuffer, "drainMainFrameless"))
                        .sum(),
                "the raw advancement drain may not bypass the tick-head window");
        assertEquals(1, tickTagFamily.stream()
                        .mapToInt(candidate -> callCount(candidate,
                                advancementBuffer, "beginTickHeadWindow", "(Z)Z"))
                        .sum(),
                "the tick-head advancement window must open exactly once before "
                        + "function-body admission");
        assertEquals(1, callCount(handler, advancementBuffer, "endTickHeadWindow",
                        "(Ljava/lang/Throwable;)Ljava/lang/Throwable;"),
                "the tick-head advancement window must be closed exactly once");
        int windowClose = callIndex(handler, advancementBuffer, "endTickHeadWindow",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        assertTrue(windowClose > markerReset && cleanupCommit > windowClose,
                "the advancement window must close after the marker reset and before "
                        + "the success commit");

        String executeDesc = "(Lnet/minecraft/commands/functions/CommandFunction;"
                + "Lnet/minecraft/commands/CommandSourceStack;)V";
        MethodNode execute = method(functions, "execute", executeDesc);
        assertEquals(1, generatedCallCount(execute, functions.name,
                "$fabrolia$fenceFunction"),
                "Fabrolia must remain the one outer function-execution fence");

        MethodNode fence = generatedMethod(functions, "$fabrolia$fenceFunction");
        String unboundedDesc = "(L" + P_ISLAND + ";)Z";
        String boundedDesc = "(L" + P_ISLAND + ";J)Z";
        int batchCapability = callIndex(fence, ADVANCEMENT_BATCH_SCOPE,
                "cleanMainFenceRoot", "()Z");
        int unbounded = callIndex(fence, ISLAND_COMMANDS,
                "quiesceAllExcept", unboundedDesc);
        int bounded = callIndex(fence, ISLAND_COMMANDS,
                "quiesceAllExcept", boundedDesc);
        // The worker and owner-local-reward branches ahead of the fence publish
        // their own fail-stops; the pre-body fail-stop is the one after the
        // bounded quiesce. (Taking the first call made this check fail on every
        // export, the production jar included.)
        int preBodyFailStop = callIndexAfter(fence, ISLAND_COMMANDS,
                "publishAndPropagate", "(Ljava/lang/Throwable;)V", bounded);
        int stickyRead = lastCallIndexByName(fence,
                ADVANCEMENT_BATCH_SCOPE, "rewardFunctionActive");
        int release = callIndex(fence, ISLAND_COMMANDS,
                "releaseAllExceptAndPropagate", "(Ljava/lang/Throwable;)V");
        assertEquals(1, callCount(fence, ADVANCEMENT_BATCH_SCOPE,
                "cleanMainFenceRoot", "()Z"));
        assertEquals(2, callCount(fence, ADVANCEMENT_BATCH_SCOPE,
                "rewardFunctionActive", "()Z"),
                "only the exact serial reward-body phase may classify or meter a "
                        + "function as an advancement reward");
        assertEquals(0, callCount(fence, ADVANCEMENT_BATCH_SCOPE,
                "active", "()Z"),
                "the broad batch interval includes predicate/chunk pumping and is not a "
                        + "clean function-fence capability");
        assertEquals(1, callCount(fence, ISLAND_COMMANDS,
                "quiesceAllExcept", unboundedDesc));
        assertEquals(1, callCount(fence, ISLAND_COMMANDS,
                "quiesceAllExcept", boundedDesc));
        assertTrue(stickyRead >= 0 && batchCapability > stickyRead
                        && unbounded > batchCapability && bounded > unbounded
                        && preBodyFailStop > bounded
                        && release > preBodyFailStop,
                "the sticky cleanup receipt must precede fence acquisition; deferred rewards "
                        + "select the clean unbounded branch before the bounded unknown-caller "
                        + "fail-stop and fence release");
        assertFalse(hasBackwardJump(fence),
                "the transformed function fence may not retry or defer in-place");

        List<MethodNode> fenceFamily = functions.methods.stream()
                .filter(candidate -> candidate.name.contains("fabrolia$fenceFunction"))
                .toList();
        assertEquals(0, fenceFamily.stream()
                        .mapToInt(candidate -> callCount(candidate,
                                functions.name, "execute", executeDesc)).sum(),
                "the fence may not recursively replay ServerFunctionManager.execute");
        List<MethodNode> stickyArm = fenceFamily.stream()
                .filter(candidate -> callCount(candidate, ADVANCEMENT_BATCH_SCOPE,
                        "noteStickyFence", "()V") == 1)
                .toList();
        assertEquals(1, stickyArm.size(),
                "one generated receipt body must arm the sticky advancement fence");
        int note = callIndex(stickyArm.getFirst(), ADVANCEMENT_BATCH_SCOPE,
                "noteStickyFence", "()V");
        int armed = opcodeIndexAfter(stickyArm.getFirst(), Opcodes.BASTORE, note);
        assertTrue(note >= 0 && armed > note
                        && previousRealOpcode(stickyArm.getFirst(), armed) == Opcodes.ICONST_1,
                "the outer advancement cleanup receipt must become visible only after "
                        + "the exact sticky fence is armed");
        assertFalse(functions.methods.stream().anyMatch(candidate ->
                        candidate.name.contains("fabrolia$deferFenced")),
                "the final transformed class may own no later-tick function retry");
        assertFalse(functions.fields.stream().anyMatch(field ->
                        field.name.equals("fabrolia$deferredTickFns")
                                || field.name.equals("fabrolia$fenceStarve")),
                "the final transformed class may retain no retry/coalescing state");
    }

    private static void assertC2meSaveThreadSubstitution(ClassNode server,
                                                          String upstreamHandler,
                                                          String hookSuffix) {
        String callbackDesc =
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
        MethodNode provider = uniqueMethod(server,
                candidate -> candidate.name.endsWith("$" + upstreamHandler)
                        && candidate.desc.equals(callbackDesc),
                "C2ME save catcher " + upstreamHandler);
        int current = callIndex(provider, THREAD, "currentThread", THREAD_DESC);
        int hook = generatedCallIndex(provider, server.name, hookSuffix);
        int physical = fieldIndexAfter(provider, Opcodes.GETFIELD, server.name,
                "serverThread", "Ljava/lang/Thread;", hook);
        assertEquals(1, callCount(provider, THREAD, "currentThread", THREAD_DESC));
        assertEquals(1, generatedCallCount(provider, server.name, hookSuffix));
        assertEquals(1, fieldCount(provider, Opcodes.GETFIELD, server.name,
                "serverThread", "Ljava/lang/Thread;"));
        assertEquals(1, callCount(provider, "java/util/ConcurrentModificationException",
                "<init>", "(Ljava/lang/String;)V"),
                "C2ME catcher body must remain active after TargetHandler composition");
        assertTrue(current >= 0 && hook > current && physical > hook,
                upstreamHandler + " must compare the actor-substituted thread with serverThread");

        MethodNode handler = generatedMethod(server, hookSuffix);
        assertEquals(1, callCount(handler, PLAYER_TICKS, "inExactPlayerActor", "()Z"));
        assertEquals(1, fieldCount(handler, Opcodes.GETFIELD, server.name,
                "serverThread", "Ljava/lang/Thread;"));
    }

    private static void verifyServerLevel(ClassNode level, ClassNode baseLevel) {
        String tickDesc = "(Ljava/util/function/BooleanSupplier;)V";
        MethodNode outerTick = method(level, "tick", tickDesc);
        assertEquals(1, generatedCallCount(outerTick, level.name,
                "$fabrolia$exactPlayerLevelTurn"));
        MethodNode levelTurn = generatedMethod(level,
                "$fabrolia$exactPlayerLevelTurn");
        int entryCut = callIndex(levelTurn, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V");
        int levelBody = callIndex(levelTurn, OPERATION, "call", OPERATION_CALL_DESC);
        int finishTurn = callIndex(levelTurn, PLAYER_TICKS, "finishLevelTurn",
                "(Lnet/minecraft/server/level/ServerLevel;Ljava/lang/Throwable;)"
                        + "Ljava/lang/Throwable;");
        int finalCut = callIndexAfter(levelTurn, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V", finishTurn);
        assertTrue(entryCut >= 0 && levelBody > entryCut && finishTurn > levelBody
                        && finalCut > finishTurn,
                "ServerLevel needs short entry and post-release terminal cuts");

        MethodNode tickBody = uniqueMethod(level,
                candidate -> candidate.name.startsWith("tick$mixinextras$wrapped$")
                        && candidate.desc.equals(tickDesc),
                "MixinExtras ServerLevel.tick body");
        int entityListWrapper = generatedCallIndex(tickBody, level.name,
                "$fabrolia$overwriteEntityTicking");
        int blockEntityTail = generatedCallIndex(tickBody, level.name,
                "$fabrolia$admitBlockEntityTick");
        assertEquals(1, generatedCallCount(tickBody, level.name,
                "$fabrolia$overwriteEntityTicking"));
        assertEquals(0, callCount(tickBody,
                "net/minecraft/world/level/entity/EntityTickList", "forEach",
                "(Ljava/util/function/Consumer;)V"),
                "the canonical EntityTickList call must be wrapped, not duplicated");
        assertTrue(entityListWrapper >= 0 && blockEntityTail > entityListWrapper,
                "canonical entity-list ordinal must remain before the block-entity tail");

        MethodNode entityListBridge = uniqueMethod(level,
                candidate -> candidate.name.startsWith("mixinextras$bridge$forEach$")
                        && callCount(candidate,
                        "net/minecraft/world/level/entity/EntityTickList", "forEach",
                        "(Ljava/util/function/Consumer;)V") == 1,
                "EntityTickList.forEach operation bridge");
        assertEquals(1, callCount(entityListBridge,
                "net/minecraft/world/level/entity/EntityTickList", "forEach",
                "(Ljava/util/function/Consumer;)V"));
        MethodNode entityListHandler = generatedMethod(level,
                "$fabrolia$overwriteEntityTicking");
        assertEquals(1, callCountByName(entityListHandler, PLAYER_TICKS,
                "beginExactLevelIteration"));
        assertTrue(callCountByName(entityListHandler, PLAYER_TICKS,
                        "endExactLevelIteration") >= 1,
                "compiled normal/exceptional exits must all retain exact-iteration cleanup");
        assertEquals(1, level.methods.stream()
                        .filter(candidate -> candidate.name.contains(
                                "overwriteEntityTicking"))
                        .mapToInt(candidate -> callCountByName(candidate,
                                PLAYER_TICKS, "finishLevel"))
                        .sum(),
                "the canonical entity-list wrapper must retain one finishLevel call");
        int pause = callIndexByName(entityListHandler, PLAYER_TICKS,
                "beginExactLevelIteration");
        int end = callIndexByName(entityListHandler, PLAYER_TICKS,
                "endExactLevelIteration");
        int postThreadLocalCut = callIndexAfter(entityListHandler, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V", end);
        assertTrue(pause >= 0 && end > pause && postThreadLocalCut > end,
                "the entity-list wrapper must clean up exact iteration before its terminal cut");

        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitEnvironmentTickCacheInvalidation",
                "net/minecraft/world/attribute/EnvironmentAttributeSystem",
                "invalidateTickCache", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitWorldBorderTick",
                "net/minecraft/world/level/border/WorldBorder", "tick", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitWeatherTick", level.name,
                "advanceWeatherCycle", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitSleepTimeAdvance",
                "net/minecraft/world/clock/ServerClockManager", "moveToTimeMarker",
                "(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;)"
                        + "Lnet/minecraft/world/clock/ServerClockManager$MoveResult;", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitWakePlayers", level.name,
                "wakeUpAllPlayers", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitWeatherReset", level.name,
                "resetWeatherCycle", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitSkyBrightnessUpdate", level.name,
                "updateSkyBrightness", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitLevelTimeTick", level.name,
                "tickTime", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$islandScheduledTicks", "net/minecraft/world/ticks/LevelTicks",
                "tick", "(JILjava/util/function/BiConsumer;)V", 2);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitRaidCollectionTick",
                "net/minecraft/world/entity/raid/Raids", "tick",
                "(Lnet/minecraft/server/level/ServerLevel;)V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitChunkSourceTick", SERVER_CHUNK_CACHE, "tick",
                "(Ljava/util/function/BooleanSupplier;Z)V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitEmptyTimeReset", level.name,
                "resetEmptyTime", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitEnderDragonFightTick",
                "net/minecraft/world/level/dimension/end/EnderDragonFight",
                "tick", "()V", 1);
        assertEquals(1, generatedCallCount(tickBody, level.name,
                "$fabrolia$admitEmptyTimeIncrement"),
                "the sole emptyTime increment must be replaced by its field seam");
        assertEquals(0, fieldCount(tickBody, Opcodes.PUTFIELD,
                        level.name, "emptyTime", "I"),
                "ServerLevel.tick may not retain a raw emptyTime mutation");
        generatedMethod(level, "$fabrolia$admitEmptyTimeIncrement");
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitBlockEntityTick", level.name,
                "tickBlockEntities", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitEntityManagerTick",
                "net/minecraft/world/level/entity/PersistentEntitySectionManager",
                "tick", "()V", 1);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitNeighborDebugListener",
                "net/minecraft/world/level/redstone/CollectingNeighborUpdater",
                "setDebugListener", "(Ljava/util/function/Consumer;)V", 2);
        assertServerLevelStageComposition(level, tickBody,
                "$fabrolia$admitLevelDebugSynchronizersTick",
                "net/minecraft/util/debug/LevelDebugSynchronizers", "tick",
                "(Lnet/minecraft/util/debug/ServerDebugSubscribers;)V", 1);

        String lambdaDesc = "(Lnet/minecraft/world/TickRateManager;"
                + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                + "Lnet/minecraft/world/entity/Entity;)V";
        MethodNode lambda = method(level, "lambda$tick$0", lambdaDesc);
        int despawn = callIndex(lambda, "net/minecraft/world/entity/Entity",
                "checkDespawn", "()V");
        int actorWrapper = generatedCallIndex(lambda, level.name,
                "$fabrolia$exactOrdinalPlayerBody");
        assertEquals(1, generatedCallCount(lambda, level.name,
                "$fabrolia$exactOrdinalPlayerBody"));
        assertEquals(0, callCount(lambda, level.name, "guardEntityTick",
                "(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V"));
        assertTrue(despawn >= 0 && actorWrapper > despawn,
                "actor wrapper must stay at the post-checkDespawn canonical guard ordinal");

        MethodNode guardBridge = uniqueMethod(level,
                candidate -> candidate.name.startsWith("mixinextras$bridge$guardEntityTick$")
                        && callCount(candidate, level.name, "guardEntityTick",
                        "(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V") == 1,
                "ServerLevel.guardEntityTick operation bridge");
        assertEquals(1, callCount(guardBridge, level.name, "guardEntityTick",
                "(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V"));
        MethodNode actorHandler = generatedMethod(level,
                "$fabrolia$exactOrdinalPlayerBody");
        assertEquals(1, callCountByName(actorHandler, PLAYER_TICKS,
                "executeExactOrdinal"));

        verifyC2meCollisionReplacement(level, baseLevel);
    }

    private static void verifyServerPlayer(ClassNode player) {
        MethodNode tick = method(player, "tick", "()V");
        assertEquals(1, generatedCallCount(tick, player.name,
                "$fabrolia$accountPlayerTick"));

        MethodNode body = uniqueMethod(player,
                candidate -> candidate.name.startsWith("tick$mixinextras$wrapped$")
                        && candidate.desc.equals("()V"),
                "MixinExtras ServerPlayer.tick body");
        List<MethodInsnNode> bodyCalls = calls(body);
        assertFalse(bodyCalls.isEmpty());
        assertTrue(bodyCalls.getFirst().owner.equals(player.name)
                        && bodyCalls.getFirst().name.contains("$carpet$onTick"),
                "Carpet ServerPlayer tick HEAD must remain the first call in the wrapped body");

        // FSit has no build for every Minecraft version the pack targets. When it
        // is installed, its TAIL handlers must stay inside the wrapped body, last.
        boolean fsitComposed = player.methods.stream()
                .anyMatch(candidate -> candidate.name.contains("$fsit$"));
        if (!fsitComposed) return;
        List<String> fsitTail = List.of(
                "$fsit$sneakWindow",
                "$fsit$tickAttached",
                "$fsit$tickLayingVisuals");
        assertTrue(bodyCalls.size() >= fsitTail.size());
        int tailStart = bodyCalls.size() - fsitTail.size();
        for (int i = 0; i < fsitTail.size(); i++) {
            MethodInsnNode call = bodyCalls.get(tailStart + i);
            assertTrue(call.owner.equals(player.name) && call.name.endsWith(fsitTail.get(i)),
                    "FSit tick TAIL order changed at " + fsitTail.get(i)
                            + "; actual final calls=" + methodNames(bodyCalls));
        }
    }

    private static void verifyServerChunkCache(ClassNode cache, ClassNode executor) {

        MethodNode getRunningThread = method(executor, "getRunningThread", THREAD_DESC);
        int hook = generatedCallIndex(getRunningThread, executor.name,
                "$fabrolia$logicalPlayerActorThread");
        int physical = fieldIndex(getRunningThread, Opcodes.GETFIELD,
                cache.name, "mainThread", "Ljava/lang/Thread;");
        assertEquals(1, generatedCallCount(getRunningThread, executor.name,
                "$fabrolia$logicalPlayerActorThread"));
        assertTrue(hook >= 0 && physical > hook,
                "MainThreadExecutor actor hook must precede physical-main fallback");
        MethodNode handler = generatedMethod(executor,
                "$fabrolia$logicalPlayerActorThread");
        assertEquals(1, callCount(handler, PLAYER_TICKS, "inExactPlayerActor", "()Z"));
        assertTrue(callCount(handler, THREAD, "currentThread", THREAD_DESC) >= 1,
                "logical ownership must return the executing thread, not a stored worker");
        assertEquals(1, callCount(handler, CALLBACK_RETURNABLE,
                "setReturnValue", "(Ljava/lang/Object;)V"));

        // Portal read/generation scopes must route a chunk read before any other
        // HEAD callback answers it (Fabrolia's off-Main bridge, c2me's shortcut).
        MethodNode getChunk = method(cache, "getChunk",
                "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;");
        assertEquals(1, generatedCallCount(getChunk, cache.name, "$fabrolia$ownedPortalRead"));
        int portal = generatedCallIndex(getChunk, cache.name, "$fabrolia$ownedPortalRead");
        int firstCallback = -1;
        int index = 0;
        for (AbstractInsnNode instruction : getChunk.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(cache.name)
                    && call.name.startsWith("handler$")) {
                firstCallback = index;
                break;
            }
            index++;
        }
        assertEquals(portal, firstCallback,
                "the portal scope callback must be the first getChunk HEAD callback");
        assertTrue(generatedCallIndex(getChunk, cache.name, "$fabrolia$getChunk") > portal,
                "Fabrolia's off-Main getChunk bridge must run after the portal scope");

        // The worker FULL-chunk cache answers in place of C2ME's off-thread
        // shortcut, so it must sit after the portal scope and immediately before
        // that shortcut: no other HEAD callback may be skipped by a cache hit.
        assertEquals(1, generatedCallCount(getChunk, cache.name,
                "$fabrolia$workerChunkCacheLookup"));
        int lookup = generatedCallIndex(getChunk, cache.name, "$fabrolia$workerChunkCacheLookup");
        assertTrue(lookup > portal, "the worker chunk cache must run after the portal scope");
        // Any generated (mixin-merged) callee counts, not only handler$ ones.
        String afterLookup = null;
        index = 0;
        for (AbstractInsnNode instruction : getChunk.instructions) {
            if (index++ > lookup && instruction instanceof MethodInsnNode call
                    && call.owner.equals(cache.name) && call.name.contains("$")) {
                afterLookup = call.name;
                break;
            }
        }
        assertTrue(afterLookup != null && afterLookup.endsWith("$shortcutGetChunk"),
                "C2ME's shortcut must be the callback right after the worker chunk cache, found "
                        + afterLookup);
        // Every shortcut exit (answer or decline) must capture-or-disarm.
        MethodNode shortcut = generatedMethod(cache, "$shortcutGetChunk");
        int returns = 0;
        for (AbstractInsnNode instruction : shortcut.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) returns++;
        }
        assertTrue(returns > 0);
        assertEquals(returns, generatedCallCount(shortcut, cache.name,
                "$fabrolia$workerChunkCacheCapture"),
                "the worker chunk cache must capture or disarm at every C2ME shortcut return");
    }


    private static void verifyPacketProcessor(ClassNode processor) {
        MethodNode sameThread = method(processor, "isSameThread", "()Z");
        int current = callIndex(sameThread, THREAD, "currentThread", THREAD_DESC);
        int physical = fieldIndex(sameThread, Opcodes.GETFIELD, processor.name,
                "runningThread", "Ljava/lang/Thread;");
        int hook = generatedCallIndex(sameThread, processor.name,
                "$fabrolia$logicalPlayerActorThread");
        assertEquals(1, generatedCallCount(sameThread, processor.name,
                "$fabrolia$logicalPlayerActorThread"));
        assertTrue(current >= 0 && physical > current && hook > physical,
                "PacketProcessor must actor-extend the completed physical comparison");
        MethodNode handler = generatedMethod(processor,
                "$fabrolia$logicalPlayerActorThread");
        assertEquals(1, callCount(handler, PLAYER_TICKS, "inExactPlayerActor", "()Z"));
        assertEquals(1, callCount(handler, A5_PLAYER_TICKS, "inPacketReplay", "()Z"));
    }

    private static void verifyConnectionA5(ClassNode connection) {
        String tickableListener = "net/minecraft/network/TickablePacketListener";
        MethodNode connectionTick = method(connection, "tick", "()V");
        assertEquals(1, generatedCallCount(connectionTick, connection.name,
                "$fabrolia$islandOwnPlayListenerTick"),
                "Connection transport shell must contain one complete-listener A5 decision");
        assertEquals(0, callCount(connectionTick, tickableListener, "tick", "()V"),
                "wrapped play-listener state-machine call must not remain duplicated");
        MethodNode listenerHandler = generatedMethod(connection,
                "$fabrolia$islandOwnPlayListenerTick");
        assertEquals(1, typeCount(listenerHandler, Opcodes.INSTANCEOF, SERVER_GAME_LISTENER),
                "Connection may lease only an exact play-listener session, never login/config");
        MethodNode listenerBridge = uniqueMethod(connection,
                candidate -> candidate.name.startsWith("mixinextras$bridge$tick$")
                        && callCount(candidate, tickableListener, "tick", "()V") == 1,
                "Connection TickablePacketListener.tick Operation bridge");
        assertEquals(1, callCount(listenerBridge, tickableListener, "tick", "()V"));

        String ingressDesc = "(Lio/netty/channel/ChannelHandlerContext;"
                + "Lnet/minecraft/network/protocol/Packet;)V";
        String genericsDesc = "(Lnet/minecraft/network/protocol/Packet;"
                + "Lnet/minecraft/network/PacketListener;)V";
        MethodNode ingress = method(connection, "channelRead0", ingressDesc);
        assertEquals(1, generatedCallCount(ingress, connection.name,
                "$fabrolia$enqueueIslandPlayerPacket"),
                "Connection must contain one exact A5 decoded-packet interception");
        assertEquals(0, callCount(ingress, connection.name, "genericsFtw", genericsDesc),
                "wrapped genericsFtw callsite must not remain duplicated in channelRead0");

        MethodNode handler = generatedMethod(connection,
                "$fabrolia$enqueueIslandPlayerPacket");
        assertEquals(1, callCount(handler, A5_PLAYER_TICKS, "enqueuePacket",
                "(Lnet/minecraft/network/protocol/Packet;"
                        + "Lnet/minecraft/network/PacketListener;)Z"));

        MethodNode bridge = uniqueMethod(connection,
                candidate -> candidate.name.startsWith("mixinextras$bridge$genericsFtw$")
                        && callCount(candidate, connection.name, "genericsFtw", genericsDesc) == 1,
                "Connection.genericsFtw Operation bridge");
        assertEquals(1, callCount(bridge, connection.name, "genericsFtw", genericsDesc),
                "the A5 Operation bridge must retain exactly one vanilla packet apply");
    }

    private static void verifyServerGameListenerA5(ClassNode listener) {
        assertTrue(listener.interfaces.contains(A5_LISTENER_STATE_ACCESS),
                "transformed listener must expose the exact immutable A5 session gate");
        assertTrue(listener.fields.stream().anyMatch(field ->
                        field.desc.equals("Ljava/util/concurrent/atomic/AtomicReference;")),
                "transformed listener must retain its atomic A5 PlayerState binding");
        MethodNode stateRead = method(listener, "fabrolia$asyncIslandPlayerState",
                "()L" + A5_PLAYER_TICKS + "$PlayerState;");
        MethodNode stateBind = method(listener, "fabrolia$bindAsyncIslandPlayerState",
                "(L" + A5_PLAYER_TICKS + "$PlayerState;)Z");
        MethodNode stateUnbind = method(listener, "fabrolia$unbindAsyncIslandPlayerState",
                "(L" + A5_PLAYER_TICKS + "$PlayerState;)Z");
        assertEquals(1, callCount(stateRead, "java/util/concurrent/atomic/AtomicReference",
                "get", "()Ljava/lang/Object;"));
        assertEquals(1, callCount(stateBind, "java/util/concurrent/atomic/AtomicReference",
                "compareAndSet", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(stateUnbind, "java/util/concurrent/atomic/AtomicReference",
                "compareAndSet", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));

        String onDisconnectDesc =
                "(Lnet/minecraft/network/DisconnectionDetails;)V";
        MethodNode disconnect = method(listener, "onDisconnect", onDisconnectDesc);
        assertEquals(1, generatedCallCount(disconnect, listener.name,
                "$fabrolia$scopePhysicalMainDisconnect"),
                "the complete remote/direct disconnect must enter one A5 terminal scope");
        assertEquals(0, callCount(disconnect, SERVER_PLAYER, "disconnect", "()V"),
                "ServerPlayer.disconnected may not change in the unfenced entry method");
        MethodNode disconnectHandler = generatedMethod(listener,
                "$fabrolia$scopePhysicalMainDisconnect");
        String runPhysicalDesc =
                "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                        + "Lnet/minecraft/server/MinecraftServer;Ljava/lang/Runnable;)V";
        int bodyFactory = opcodeIndex(disconnectHandler, Opcodes.INVOKEDYNAMIC);
        int scope = callIndex(disconnectHandler, A5_PLAYER_TICKS,
                "runPhysicalMainDisconnect", runPhysicalDesc);
        assertEquals(1, callCount(disconnectHandler, A5_PLAYER_TICKS,
                "runPhysicalMainDisconnect", runPhysicalDesc));
        assertEquals(0, callCount(disconnectHandler, OPERATION, "call",
                OPERATION_CALL_DESC),
                "the raw disconnect body may run only after the scope owns its fence");
        assertTrue(bodyFactory >= 0 && scope > bodyFactory,
                "the exact vanilla body must be captured before the fence is acquired");
        MethodNode scopedDisconnectBody = uniqueMethod(listener,
                candidate -> callCount(candidate, OPERATION, "call",
                        OPERATION_CALL_DESC) == 1
                        && invokedynamicTargetCount(disconnectHandler, listener.name,
                        candidate.name, candidate.desc) == 1,
                "physical-Main scoped onDisconnect body");
        assertTrue(scopedDisconnectBody.tryCatchBlocks.isEmpty(),
                "the scoped body must preserve every vanilla failure");
        MethodNode vanillaDisconnect = uniqueMethod(listener,
                candidate -> candidate.name.startsWith(
                        "onDisconnect$mixinextras$wrapped$")
                        && callCount(candidate, listener.name,
                        "removePlayerFromWorld", "()V") == 1,
                "wrapped vanilla onDisconnect body");
        assertEquals(1, callCount(vanillaDisconnect,
                listener.name, "removePlayerFromWorld", "()V"),
                "the wrapped vanilla body must preserve its one player-removal call");
        MethodNode playerRemoval = method(listener, "removePlayerFromWorld", "()V");
        assertEquals(1, callCount(playerRemoval, SERVER_PLAYER, "disconnect", "()V"),
                "the player detach write must survive exactly once inside the scoped call chain");

        MethodNode tick = method(listener, "tick", "()V");
        assertEquals(1, callCount(tick, listener.name, "tickPlayer", "()Z"),
                "the complete listener state machine must retain its one private tickPlayer "
                        + "call; ownership is decided outside it in Connection.tick");
        assertEquals(0, generatedCallCount(tick, listener.name,
                "$fabrolia$islandOwnTickPlayer"),
                "A5 must not split tickPlayer from the listener's ack/keepalive/throttler tail");
        assertTrue(listener.methods.stream().noneMatch(candidate ->
                        candidate.name.endsWith("$fabrolia$invokeIslandTickPlayer")),
                "obsolete private tickPlayer invoker must not survive final composition");
    }

    private static void verifyServerGameDeferredContinuations(ClassNode listener) {
        String future = "java/util/concurrent/CompletableFuture";
        String acceptAsync = "(Ljava/util/function/Consumer;"
                + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
        verifyDeferredContinuationRoute(listener, "handleEditBook",
                "(Lnet/minecraft/network/protocol/game/ServerboundEditBookPacket;)V",
                "$fabrolia$routeBookCompletion", future, "thenAcceptAsync", acceptAsync);
        verifyDeferredContinuationRoute(listener, "handleSignUpdate",
                "(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
                "$fabrolia$routeSignCompletion", future, "thenAcceptAsync", acceptAsync);
        verifyDeferredContinuationRoute(listener, "tryHandleChat",
                "(Ljava/lang/String;ZLjava/lang/Runnable;)V",
                "$fabrolia$publishChatStageOneBeforeMainEmpty", MINECRAFT_SERVER,
                "execute", "(Ljava/lang/Runnable;)V");
        verifyDeferredContinuationRoute(listener, "lambda$handleChat$0",
                "(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;"
                        + "Ljava/util/Optional;)V",
                "$fabrolia$routeFilteredChatCompletion", "net/minecraft/util/FutureChain",
                "append", "(Ljava/util/concurrent/CompletableFuture;"
                        + "Ljava/util/function/Consumer;)V");
        verifyDeferredContinuationRoute(listener, "resetPlayerChatState",
                "(Lnet/minecraft/network/chat/RemoteChatSession;)V",
                "$fabrolia$routeChatSessionPublication", "net/minecraft/util/FutureChain",
                "append", "(Ljava/lang/Runnable;)V");
        verifyDeferredContinuationRoute(listener, "lambda$performSignedChatCommand$0",
                "(Lnet/minecraft/commands/CommandSigningContext;"
                        + "Lnet/minecraft/commands/CommandSourceStack;)"
                        + "Lnet/minecraft/commands/CommandSourceStack;",
                "$fabrolia$routeSignedArgumentContinuations",
                "net/minecraft/commands/CommandSourceStack", "withSigningContext",
                "(Lnet/minecraft/commands/CommandSigningContext;"
                        + "Lnet/minecraft/util/TaskChainer;)"
                        + "Lnet/minecraft/commands/CommandSourceStack;");

        List<String> continuationRoutes = List.of(
                "$fabrolia$routeBookCompletion",
                "$fabrolia$routeSignCompletion",
                "$fabrolia$publishChatStageOneBeforeMainEmpty",
                "$fabrolia$routeFilteredChatCompletion",
                "$fabrolia$routeChatSessionPublication",
                "$fabrolia$routeSignedArgumentContinuations");
        List<MethodNode> continuationHandlers = continuationRoutes.stream()
                .map(route -> generatedMethod(listener, route))
                .toList();
        assertEquals(6, continuationHandlers.size(),
                "final listener must contain exactly six mutating continuation handlers");
        assertTrue(listener.methods.stream().noneMatch(method ->
                        method.name.contains("$fabrolia$routeTextFilterPlayerRead")),
                "read-only filter terminalization must remain vanilla and unwrapped");

        MethodNode filterTerminal = method(listener, "lambda$filterTextPacket$0",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        assertEquals(1, callCount(filterTerminal, listener.name,
                "isAcceptingMessages", "()Z"));
        assertEquals(0, fieldWriteCount(filterTerminal),
                "the intentionally unwrapped async filter terminal must remain read-only");
        MethodNode accepting = method(listener, "isAcceptingMessages", "()Z");
        assertEquals(1, callCount(accepting, "net/minecraft/network/Connection",
                "isConnected", "()Z"));
        assertEquals(1, fieldCount(accepting, Opcodes.GETFIELD, listener.name,
                "waitingForSwitchToConfig", "Z"));
        assertEquals(0, fieldWriteCount(accepting),
                "filter completion's connectivity predicate must remain read-only");
    }

    private static void verifyDeferredContinuationRoute(
            ClassNode listener, String targetName, String targetDesc, String handlerSuffix,
            String wrappedOwner, String wrappedName, String wrappedDesc) {
        MethodNode target = method(listener, targetName, targetDesc);
        assertEquals(1, generatedCallCount(target, listener.name, handlerSuffix),
                targetName + " must route through one exact deferred-continuation handler");
        assertEquals(0, callCount(target, wrappedOwner, wrappedName, wrappedDesc),
                "wrapped async publication must not remain duplicated in " + targetName);
        MethodNode handler = generatedMethod(listener, handlerSuffix);
        int originalCalls = listener.methods.stream()
                .filter(method -> isDeferredContinuationRouteBody(
                        method.name, handlerSuffix))
                .mapToInt(method -> callCount(method, OPERATION,
                        "call", OPERATION_CALL_DESC))
                .sum();
        assertTrue(originalCalls >= 1,
                handler.name + " must preserve the vanilla fallback Operation");
    }

    private static boolean isDeferredContinuationRouteBody(
            String methodName, String handlerSuffix) {
        return methodName.endsWith(handlerSuffix)
                || methodName.contains("$fabrolia$lambda" + handlerSuffix + "$");
    }

    private static void verifyCommandsFence(ClassNode minecraftServer,
                                            ClassNode commands, ClassNode listener,
                                            ClassNode dedicatedServer,
                                            ClassNode rconClient,
                                            ClassNode reentrantEventLoop,
                                            ClassNode blockableEventLoop) {
        String prefixedDesc = "(Lnet/minecraft/commands/CommandSourceStack;"
                + "Ljava/lang/String;)V";
        String parsedDesc = "(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V";
        MethodNode prefixed = method(commands, "performPrefixedCommand", prefixedDesc);
        MethodNode parsed = method(commands, "performCommand", parsedDesc);
        assertEquals(1, generatedCallCount(prefixed, commands.name,
                "$fabrolia$fenceMainCommands"));
        assertEquals(1, generatedCallCount(parsed, commands.name,
                "$fabrolia$fenceParsedMainCommands"));

        MethodNode tickChildrenBody = uniqueMethod(minecraftServer,
                candidate -> candidate.name.startsWith(
                                "tickChildren$mixinextras$wrapped$")
                        && candidate.desc.equals(
                                "(Ljava/util/function/BooleanSupplier;)V"),
                "MixinExtras tickChildren body for the console root");
        assertEquals(1, generatedCallCount(tickChildrenBody,
                minecraftServer.name,
                "$fabrolia$assertPlayerBarrierBeforeConnections"));
        assertEquals(0, callCount(tickChildrenBody, minecraftServer.name,
                        "tickConnection", "()V"),
                "the wrapped tickChildren body must expose no raw connection entry");
        assertEquals(1, minecraftServer.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "assertPlayerBarrierBeforeConnections"))
                .mapToInt(candidate -> callCount(candidate,
                        "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                        "runDedicatedTickConnection",
                        "(Ljava/lang/Object;Ljava/lang/Runnable;)V"))
                .sum(), "the final exact tickConnection edge must install one root marker");

        MethodNode tickConnection = method(dedicatedServer,
                "tickConnection", "()V");
        assertEquals(1, generatedCallCount(tickConnection, dedicatedServer.name,
                "$fabrolia$frameExactTickConnectionEntry"));
        MethodNode tickEntryHandler = generatedMethod(dedicatedServer,
                "$fabrolia$frameExactTickConnectionEntry");
        assertEquals(1, callCount(tickEntryHandler,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "runDedicatedTickConnectionEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        List<MethodNode> tickBodies = dedicatedServer.methods.stream()
                .filter(candidate -> callCount(candidate, minecraftServer.name,
                        "tickConnection", "()V") == 1).toList();
        assertEquals(1, tickBodies.size());
        MethodNode tickBody = tickBodies.getFirst();
        int superConnection = callIndex(tickBody, minecraftServer.name,
                "tickConnection", "()V");
        int exactDrain = generatedCallIndex(tickBody, dedicatedServer.name,
                "$fabrolia$markExactConsoleInputDrain");
        assertEquals(1, generatedCallCount(tickBody, dedicatedServer.name,
                "$fabrolia$markExactConsoleInputDrain"));
        assertTrue(superConnection >= 0 && exactDrain > superConnection,
                "all connection callbacks/monitors must return before the clean console edge");
        MethodNode drainHandler = generatedMethod(dedicatedServer,
                "$fabrolia$markExactConsoleInputDrain");
        assertEquals(1, callCount(drainHandler,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "runDedicatedConsoleInputs",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));

        MethodNode console = method(dedicatedServer, "handleConsoleInputs", "()V");
        assertEquals(1, generatedCallCount(console, dedicatedServer.name,
                "$fabrolia$frameExactConsoleInputEntry"));
        MethodNode consoleEntryHandler = generatedMethod(dedicatedServer,
                "$fabrolia$frameExactConsoleInputEntry");
        assertEquals(1, callCount(consoleEntryHandler,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "runDedicatedConsoleInputsEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        List<MethodNode> consoleBodies = dedicatedServer.methods.stream()
                .filter(candidate -> generatedCallCount(candidate, dedicatedServer.name,
                        "$fabrolia$markExactConsoleCommand") == 1).toList();
        assertEquals(1, consoleBodies.size());
        MethodNode consoleBody = consoleBodies.getFirst();
        assertEquals(1, generatedCallCount(consoleBody, dedicatedServer.name,
                "$fabrolia$markExactConsoleCommand"),
                "the final console loop must wrap exactly its one command invoke");
        assertEquals(0, callCount(consoleBody, commands.name,
                "performPrefixedCommand", prefixedDesc),
                "the exact console invoke must not remain beside the generated wrapper");
        for (MethodNode cleanCut : List.of(
                tickConnection, tickBody, console, consoleBody)) {
            assertEquals(0, cleanCut.access & Opcodes.ACC_SYNCHRONIZED,
                    cleanCut.name + cleanCut.desc + " must not hold a method monitor");
            assertEquals(0, opcodeCount(cleanCut, Opcodes.MONITORENTER),
                    cleanCut.name + cleanCut.desc + " must not enter a monitor");
            assertEquals(0, opcodeCount(cleanCut, Opcodes.MONITOREXIT),
                    cleanCut.name + cleanCut.desc + " must not exit a monitor");
        }
        MethodNode consoleHandler = generatedMethod(dedicatedServer,
                "$fabrolia$markExactConsoleCommand");
        assertEquals(1, callCount(consoleHandler,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "runDedicatedConsolePrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;"
                        + "Ljava/lang/Runnable;)V"));
        verifyExactRconCommandFence(minecraftServer, dedicatedServer,
                rconClient, reentrantEventLoop, blockableEventLoop,
                commands, prefixedDesc);

        MethodNode prefixedFence = generatedMethod(commands,
                "$fabrolia$fenceMainCommands");
        int consoleClaim = callIndex(prefixedFence,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "claimDedicatedConsolePrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
        int rconClaim = callIndex(prefixedFence,
                "com/axalotl/fabrolia/common/regions/RconCommandFenceScope",
                "claimDedicatedRconPrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
        int firstFastPath = callIndex(prefixedFence,
                "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;");
        int prefixedUnbounded = callIndex(prefixedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;)Z");
        int prefixedBounded = callIndex(prefixedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;J)Z");
        assertEquals(1, callCount(prefixedFence,
                "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope",
                "claimDedicatedConsolePrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(prefixedFence,
                "com/axalotl/fabrolia/common/regions/RconCommandFenceScope",
                "claimDedicatedRconPrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(prefixedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;)Z"));
        assertEquals(1, callCount(prefixedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;J)Z"));
        assertTrue(consoleClaim >= 0 && rconClaim > consoleClaim
                        && firstFastPath > rconClaim
                        && prefixedUnbounded > firstFastPath
                        && prefixedBounded > prefixedUnbounded,
                "both exact Main-root tokens must be consumed independently before every "
                        + "fast path, then select unbounded ownership while unknown "
                        + "prefixed callers stay bounded");

        MethodNode parsedFence = generatedMethod(commands,
                "$fabrolia$fenceParsedMainCommands");
        String playerScope =
                "com/axalotl/fabrolia/common/regions/PlayerCommandFenceScope";
        int playerClaim = callIndex(parsedFence, playerScope,
                "claimParsedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
        int nested = callIndex(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "fenceWindowHeld", "()Z");
        int unbounded = callIndex(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;)Z");
        int bounded = callIndex(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;J)Z");
        int failStop = callIndex(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "publishAndPropagate", "(Ljava/lang/Throwable;)V");
        int release = callIndex(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "releaseAllExceptAndPropagate", "(Ljava/lang/Throwable;)V");
        assertEquals(1, callCount(parsedFence, playerScope,
                "claimParsedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "quiesceAllExcept",
                "(Lcom/axalotl/fabrolia/common/regions/IslandRegistry$PIsland;)Z"));
        assertTrue(playerClaim >= 0 && nested > playerClaim
                        && unbounded > nested && bounded > unbounded
                        && failStop > bounded
                        && release > failStop,
                "parsed command must consume the exact player receipt first, select its "
                        + "unbounded branch only for that receipt, keep unknown callers "
                        + "bounded, then release first-wins");
        assertEquals(1, callCount(parsedFence,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "releaseAllExceptAndPropagate", "(Ljava/lang/Throwable;)V"));
        assertFalse(commands.methods.stream().anyMatch(methodNode ->
                        methodNode.name.contains("fabrolia$deferParsedFenced")),
                "the transformed command path must own no later-tick retry helper");
        MethodNode unsigned = method(listener, "performUnsignedChatCommand",
                "(Ljava/lang/String;)V");
        MethodNode signed = method(listener, "performSignedChatCommand",
                "(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;"
                        + "Lnet/minecraft/network/chat/LastSeenMessages;)V");
        verifyExactPlayerCommandFence(listener, unsigned,
                "$fabrolia$frameUnsignedPlayerCommand",
                "$fabrolia$frameUnsignedParsedCommand", playerScope);
        verifyExactPlayerCommandFence(listener, signed,
                "$fabrolia$frameSignedPlayerCommand",
                "$fabrolia$frameSignedParsedCommand", playerScope);
    }

    private static void verifyExactPlayerCommandFence(
            ClassNode listener, MethodNode entry, String entryHandlerSuffix,
            String parsedHandlerSuffix, String playerScope) {
        assertEquals(1, generatedCallCount(entry, listener.name, entryHandlerSuffix),
                entry.name + " must enter one exact private-command provenance frame");
        MethodNode entryHandler = generatedMethod(listener, entryHandlerSuffix);
        assertEquals(1, callCount(entryHandler, playerScope,
                "runPlayerCommandEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));

        int parsedEdges = listener.methods.stream().mapToInt(candidate ->
                generatedCallCount(candidate, listener.name, parsedHandlerSuffix)).sum();
        assertEquals(1, parsedEdges,
                entry.name + " must frame exactly one parsed Commands invocation");
        MethodNode parsedHandler = generatedMethod(listener, parsedHandlerSuffix);
        assertEquals(1, callCount(parsedHandler, playerScope,
                "runParsedCommandEdge",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;Ljava/lang/Runnable;)V"));
    }

    private static void verifyExactRconCommandFence(
            ClassNode minecraftServer, ClassNode dedicatedServer,
            ClassNode rconClient, ClassNode reentrantEventLoop,
            ClassNode blockableEventLoop,
            ClassNode commands, String prefixedDesc) {
        String rconScope =
                "com/axalotl/fabrolia/common/regions/RconCommandFenceScope";
        String serverInterface = "net/minecraft/server/ServerInterface";
        String commandDesc = "(Ljava/lang/String;)Ljava/lang/String;";

        MethodNode clientRun = method(rconClient, "run", "()V");
        assertEquals(1, generatedCallCount(clientRun, rconClient.name,
                "$fabrolia$markExactRconClientCommand"));
        assertEquals(0, callCount(clientRun, serverInterface,
                        "runCommand", commandDesc),
                "the authenticated RCON callsite must not remain beside its wrapper");
        MethodNode clientHandler = generatedMethod(rconClient,
                "$fabrolia$markExactRconClientCommand");
        assertEquals(1, callCount(clientHandler, rconScope,
                "runRconClientCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/Supplier;)"
                        + "Ljava/lang/Object;"));
        assertEquals(1, rconClient.methods.stream().mapToInt(candidate ->
                        callCount(candidate, serverInterface,
                                "runCommand", commandDesc)).sum(),
                "MixinExtras must retain exactly one raw authenticated RCON bridge");

        MethodNode runCommand = method(dedicatedServer,
                "runCommand", commandDesc);
        assertEquals(1, generatedCallCount(runCommand, dedicatedServer.name,
                "$fabrolia$frameExactRconRunCommand"));
        MethodNode runCommandEntry = generatedMethod(dedicatedServer,
                "$fabrolia$frameExactRconRunCommand");
        assertEquals(1, callCount(runCommandEntry, rconScope,
                "runDedicatedRconCommandEntry",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/Supplier;)"
                        + "Ljava/lang/Object;"));
        MethodNode runCommandBody = uniqueMethod(dedicatedServer,
                candidate -> candidate.name.startsWith(
                                "runCommand$mixinextras$wrapped$")
                        && candidate.desc.equals(commandDesc),
                "MixinExtras exact DedicatedServer.runCommand body");
        String rconConsoleSource =
                "net/minecraft/server/rcon/RconConsoleSource";
        int prepareForCommand = callIndex(runCommandBody, rconConsoleSource,
                "prepareForCommand", "()V");
        int wrapMainTask = generatedCallIndex(runCommandBody,
                dedicatedServer.name, "$fabrolia$wrapExactRconMainTask");
        int getCommandResponse = callIndex(runCommandBody, rconConsoleSource,
                "getCommandResponse", "()Ljava/lang/String;");
        assertEquals(1, generatedCallCount(runCommandBody, dedicatedServer.name,
                "$fabrolia$wrapExactRconMainTask"));
        assertEquals(1, callCount(runCommandBody, rconConsoleSource,
                "prepareForCommand", "()V"));
        assertEquals(1, callCount(runCommandBody, rconConsoleSource,
                "getCommandResponse", "()Ljava/lang/String;"));
        assertTrue(prepareForCommand >= 0 && wrapMainTask > prepareForCommand
                        && getCommandResponse > wrapMainTask,
                "the task receipt must wrap only vanilla executeBlocking between "
                        + "RCON response reset and response readback");
        assertEquals(0, callCount(runCommandBody, dedicatedServer.name,
                        "executeBlocking", "(Ljava/lang/Runnable;)V"),
                "the tagged executeBlocking task must not coexist with a raw invoke");
        MethodNode executeTaskHandler = generatedMethod(dedicatedServer,
                "$fabrolia$wrapExactRconMainTask");
        assertEquals(1, callCount(executeTaskHandler, rconScope,
                "wrapDedicatedRconExecuteTask",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/Runnable;"));
        assertEquals(1, dedicatedServer.methods.stream().mapToInt(candidate ->
                        callCount(candidate, dedicatedServer.name,
                                "executeBlocking", "(Ljava/lang/Runnable;)V")).sum(),
                "the transformed class must retain one raw executeBlocking bridge");

        String lambdaDesc = "(Ljava/lang/String;)V";
        MethodNode rconLambda = method(dedicatedServer,
                "lambda$runCommand$0", lambdaDesc);
        assertEquals(1, generatedCallCount(rconLambda, dedicatedServer.name,
                "$fabrolia$frameExactRconLambda"));
        assertEquals(0, callCount(rconLambda, commands.name,
                        "performPrefixedCommand", prefixedDesc),
                "the RCON entry wrapper must expose no raw command invoke");
        MethodNode lambdaEntry = generatedMethod(dedicatedServer,
                "$fabrolia$frameExactRconLambda");
        assertEquals(1, callCount(lambdaEntry, rconScope,
                "runDedicatedRconLambdaEntry",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        MethodNode rconLambdaBody = uniqueMethod(dedicatedServer,
                candidate -> candidate.name.startsWith(
                                "lambda$runCommand$0$mixinextras$wrapped$")
                        && candidate.desc.equals(lambdaDesc),
                "MixinExtras exact DedicatedServer RCON lambda body");
        int sourceStack = callIndex(rconLambdaBody, rconConsoleSource,
                "createCommandSourceStack",
                "()Lnet/minecraft/commands/CommandSourceStack;");
        int exactCommand = generatedCallIndex(rconLambdaBody,
                dedicatedServer.name, "$fabrolia$markExactRconCommand");
        assertEquals(1, generatedCallCount(rconLambdaBody, dedicatedServer.name,
                "$fabrolia$markExactRconCommand"));
        assertEquals(1, callCount(rconLambdaBody, rconConsoleSource,
                "createCommandSourceStack",
                "()Lnet/minecraft/commands/CommandSourceStack;"));
        assertTrue(sourceStack >= 0 && exactCommand > sourceStack,
                "the unbounded fence capability must begin at the exact command body, "
                        + "after vanilla creates its RCON source stack");
        assertEquals(0, callCount(rconLambdaBody, commands.name,
                        "performPrefixedCommand", prefixedDesc),
                "the exact RCON command edge must not remain beside its wrapper");
        MethodNode commandHandler = generatedMethod(dedicatedServer,
                "$fabrolia$markExactRconCommand");
        assertEquals(1, callCount(commandHandler, rconScope,
                "runDedicatedRconPrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        assertEquals(2, dedicatedServer.methods.stream().mapToInt(candidate ->
                        callCount(candidate, commands.name,
                                "performPrefixedCommand", prefixedDesc)).sum(),
                "console and RCON must each retain exactly one vanilla operation bridge");

        String spinDesc =
                "(Ljava/util/concurrent/atomic/AtomicReference;)V";
        MethodNode spinRoot = method(minecraftServer,
                "lambda$spin$0", spinDesc);
        assertEquals(1, generatedCallCount(spinRoot, minecraftServer.name,
                "$fabrolia$markExactServerLoopRoot"));
        assertEquals(0, callCount(spinRoot, minecraftServer.name,
                        "runServer", "()V"),
                "the exact spin root must not retain a second raw runServer entry");
        MethodNode spinHandler = generatedMethod(minecraftServer,
                "$fabrolia$markExactServerLoopRoot");
        assertEquals(1, callCount(spinHandler, rconScope,
                "runServerLoopRoot",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        assertEquals(1, minecraftServer.methods.stream().mapToInt(candidate ->
                        callCount(candidate, minecraftServer.name,
                                "runServer", "()V")).sum(),
                "the exact spin wrapper must retain one vanilla runServer bridge");

        MethodNode runServer = method(minecraftServer, "runServer", "()V");
        assertEquals(1, generatedCallCount(runServer, minecraftServer.name,
                "$fabrolia$frameExactServerLoopEntry"));
        MethodNode runServerEntry = generatedMethod(minecraftServer,
                "$fabrolia$frameExactServerLoopEntry");
        assertEquals(1, callCount(runServerEntry, rconScope,
                "runServerEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        MethodNode runServerBody = uniqueMethod(minecraftServer,
                candidate -> candidate.name.startsWith(
                                "runServer$mixinextras$wrapped$")
                        && candidate.desc.equals("()V"),
                "MixinExtras exact MinecraftServer.runServer body");
        assertEquals(1, generatedCallCount(runServerBody, minecraftServer.name,
                "$fabrolia$markExactTopLevelWait"));
        assertEquals(0, callCount(runServerBody, minecraftServer.name,
                        "waitUntilNextTick", "()V"),
                "the exact server loop must offer only its wrapped top-level wait");
        MethodNode waitRootHandler = generatedMethod(minecraftServer,
                "$fabrolia$markExactTopLevelWait");
        assertEquals(1, callCount(waitRootHandler, rconScope,
                "runServerWaitUntilNextTick",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));

        MethodNode wait = method(minecraftServer,
                "waitUntilNextTick", "()V");
        assertEquals(1, generatedCallCount(wait, minecraftServer.name,
                "$fabrolia$frameExactTopLevelWaitEntry"));
        MethodNode waitEntry = generatedMethod(minecraftServer,
                "$fabrolia$frameExactTopLevelWaitEntry");
        assertEquals(1, callCount(waitEntry, rconScope,
                "runWaitUntilNextTickEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        MethodNode waitBody = uniqueMethod(minecraftServer,
                candidate -> candidate.name.startsWith(
                                "waitUntilNextTick$mixinextras$wrapped$")
                        && candidate.desc.equals("()V"),
                "MixinExtras exact MinecraftServer.waitUntilNextTick body");
        int runAllTasks = generatedCallIndex(waitBody, minecraftServer.name,
                "$fabrolia$markExactTopLevelRunAllTasks");
        int managedBlock = generatedCallIndex(waitBody, minecraftServer.name,
                "$fabrolia$markExactTopLevelManagedBlock");
        assertEquals(1, generatedCallCount(waitBody, minecraftServer.name,
                "$fabrolia$markExactTopLevelRunAllTasks"));
        assertEquals(1, generatedCallCount(waitBody, minecraftServer.name,
                "$fabrolia$markExactTopLevelManagedBlock"));
        assertTrue(runAllTasks >= 0 && managedBlock > runAllTasks,
                "the clean Main proof must preserve vanilla runAllTasks-before-managedBlock");
        assertEquals(0, callCount(waitBody, minecraftServer.name,
                "runAllTasks", "()V"));
        assertEquals(0, callCount(waitBody, minecraftServer.name,
                "managedBlock", "(Ljava/util/function/BooleanSupplier;)V"));
        MethodNode runAllTasksBridge = uniqueMethod(minecraftServer,
                candidate -> candidate.name.startsWith(
                                "mixinextras$bridge$runAllTasks$")
                        && callCount(candidate, minecraftServer.name,
                                "runAllTasks", "()V") == 1,
                "one final vanilla runAllTasks operation bridge");
        MethodNode managedBlockBridge = uniqueMethod(minecraftServer,
                candidate -> candidate.name.startsWith(
                                "mixinextras$bridge$managedBlock$")
                        && callCount(candidate, minecraftServer.name,
                                "managedBlock",
                                "(Ljava/util/function/BooleanSupplier;)V") == 1,
                "one final vanilla managedBlock operation bridge");
        assertEquals(1, callCount(runAllTasksBridge, minecraftServer.name,
                "runAllTasks", "()V"));
        assertEquals(1, callCount(managedBlockBridge, minecraftServer.name,
                "managedBlock", "(Ljava/util/function/BooleanSupplier;)V"));
        MethodNode runAllTasksHandler = generatedMethod(minecraftServer,
                "$fabrolia$markExactTopLevelRunAllTasks");
        assertEquals(1, callCount(runAllTasksHandler, rconScope,
                "runWaitUntilNextTickRunAllTasks",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        MethodNode managedBlockHandler = generatedMethod(minecraftServer,
                "$fabrolia$markExactTopLevelManagedBlock");
        assertEquals(1, callCount(managedBlockHandler, rconScope,
                "runWaitUntilNextTickManagedBlock",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));

        assertTrue(reentrantEventLoop.interfaces.contains(
                        REENTRANT_EVENT_LOOP_ACCESSOR),
                "the final event loop must implement Fabrolia's exact depth accessor");
        MethodNode depthAccessor = method(reentrantEventLoop,
                "fabrolia$getReentrantCount", "()I");
        assertEquals(1, fieldCount(depthAccessor, Opcodes.GETFIELD,
                reentrantEventLoop.name, "reentrantCount", "I"));
        assertEquals(1, opcodeCount(depthAccessor, Opcodes.IRETURN));
        MethodNode doRunTask = method(reentrantEventLoop,
                "doRunTask", "(Ljava/lang/Runnable;)V");
        String blockableEventLoopName =
                "net/minecraft/util/thread/BlockableEventLoop";
        int enterDepth = fieldIndex(doRunTask, Opcodes.PUTFIELD,
                reentrantEventLoop.name, "reentrantCount", "I");
        int body = callIndex(doRunTask, blockableEventLoopName,
                "doRunTask", "(Ljava/lang/Runnable;)V");
        int normalExitDepth = fieldIndexAfter(doRunTask, Opcodes.PUTFIELD,
                reentrantEventLoop.name, "reentrantCount", "I", enterDepth);
        int exceptionalExitDepth = fieldIndexAfter(doRunTask, Opcodes.PUTFIELD,
                reentrantEventLoop.name, "reentrantCount", "I", normalExitDepth);
        assertEquals(3, fieldCount(doRunTask, Opcodes.GETFIELD,
                reentrantEventLoop.name, "reentrantCount", "I"));
        assertEquals(3, fieldCount(doRunTask, Opcodes.PUTFIELD,
                reentrantEventLoop.name, "reentrantCount", "I"));
        assertEquals(1, opcodeCount(doRunTask, Opcodes.IADD));
        assertEquals(2, opcodeCount(doRunTask, Opcodes.ISUB));
        assertEquals(1, callCount(doRunTask, blockableEventLoopName,
                "doRunTask", "(Ljava/lang/Runnable;)V"));
        assertTrue(enterDepth >= 0 && body > enterDepth
                        && normalExitDepth > body
                        && exceptionalExitDepth > normalExitDepth,
                "reentrant depth must be one only around the exact vanilla task body "
                        + "and must retire on both exits");
        verifyFinalRconMainQueueChain(
                minecraftServer, blockableEventLoop, reentrantEventLoop);

        for (MethodNode cleanCut : List.of(
                clientRun, runCommand, runCommandBody, rconLambda,
                rconLambdaBody, spinRoot, runServer, runServerBody,
                wait, waitBody, runAllTasksBridge, managedBlockBridge,
                doRunTask)) {
            assertEquals(0, cleanCut.access & Opcodes.ACC_SYNCHRONIZED,
                    cleanCut.name + cleanCut.desc + " must not hold a method monitor");
            assertEquals(0, opcodeCount(cleanCut, Opcodes.MONITORENTER),
                    cleanCut.name + cleanCut.desc + " must not enter a monitor");
            assertEquals(0, opcodeCount(cleanCut, Opcodes.MONITOREXIT),
                    cleanCut.name + cleanCut.desc + " must not exit a monitor");
        }
    }

    private static void verifyFinalRconMainQueueChain(
            ClassNode minecraftServer, ClassNode blockableEventLoop,
            ClassNode reentrantEventLoop) {
        String runnableDesc = "(Ljava/lang/Runnable;)V";
        String futureDesc = "(Ljava/lang/Runnable;)"
                + "Ljava/util/concurrent/CompletableFuture;";
        String completableFuture = "java/util/concurrent/CompletableFuture";

        MethodNode executeBlocking = method(blockableEventLoop,
                "executeBlocking", runnableDesc);
        MethodInsnNode waitGuardDispatch = generatedHandlerCall(
                executeBlocking, blockableEventLoop,
                "$failBeforeCausalServerMainWait");
        int waitGuard = callIndex(executeBlocking, blockableEventLoop.name,
                waitGuardDispatch.name, waitGuardDispatch.desc);
        int sameThread = callIndex(executeBlocking, blockableEventLoop.name,
                "isSameThread", "()Z");
        int submitAsync = callIndex(executeBlocking, blockableEventLoop.name,
                "submitAsync", futureDesc);
        int join = callIndex(executeBlocking, completableFuture,
                "join", "()Ljava/lang/Object;");
        assertEquals(1, callCount(executeBlocking, blockableEventLoop.name,
                "isSameThread", "()Z"));
        assertEquals(1, callCount(executeBlocking, blockableEventLoop.name,
                "submitAsync", futureDesc));
        assertEquals(1, callCount(executeBlocking, completableFuture,
                "join", "()Ljava/lang/Object;"));
        assertTrue(waitGuard >= 0 && sameThread > waitGuard
                        && submitAsync > sameThread
                        && join > submitAsync,
                "executeBlocking must check causal Main-wait safety before its "
                        + "native isSameThread -> submitAsync/join branch");

        MethodNode waitGuardHandler = method(blockableEventLoop,
                waitGuardDispatch.name, waitGuardDispatch.desc);
        int serverFilter = typeIndex(waitGuardHandler,
                Opcodes.INSTANCEOF, MINECRAFT_SERVER);
        int logicalMain = callIndex(waitGuardHandler, MINECRAFT_SERVER,
                "isSameThread", "()Z");
        int causalFailStop = callIndex(waitGuardHandler,
                REGION_MAIN_BLOCKING_WAIT_GUARD,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V");
        assertEquals(1, typeCount(waitGuardHandler,
                Opcodes.INSTANCEOF, MINECRAFT_SERVER));
        assertEquals(1, typeCount(waitGuardHandler,
                Opcodes.CHECKCAST, MINECRAFT_SERVER));
        assertEquals(0, callCount(waitGuardHandler, REGION_SCHEDULER,
                "isActive", "()Z"),
                "only the shared guard may make the authoritative scheduler-state check");
        assertEquals(1, callCount(waitGuardHandler, MINECRAFT_SERVER,
                "isSameThread", "()Z"));
        assertEquals(1, callCount(waitGuardHandler,
                REGION_MAIN_BLOCKING_WAIT_GUARD,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V"));
        assertTrue(serverFilter >= 0 && logicalMain > serverFilter
                        && causalFailStop > logicalMain,
                "the HEAD handler must filter MinecraftServer, retain the logical-Main "
                        + "fast path, then fail-stop a causal region participant");
        assertTrue(waitGuardHandler.tryCatchBlocks == null
                        || waitGuardHandler.tryCatchBlocks.isEmpty(),
                "the server Main-wait guard must not suppress a fatal failure");

        MethodNode submit = method(blockableEventLoop,
                "submitAsync", futureDesc);
        String submitLambdaDesc =
                "(Ljava/lang/Runnable;)Ljava/lang/Void;";
        int capture = invokedynamicTargetIndex(submit, blockableEventLoop.name,
                "lambda$submitAsync$0", submitLambdaDesc);
        int supply = callIndex(submit, completableFuture,
                "supplyAsync",
                "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        assertEquals(1, invokedynamicTargetCount(submit, blockableEventLoop.name,
                "lambda$submitAsync$0", submitLambdaDesc));
        assertEquals(1, callCount(submit, completableFuture,
                "supplyAsync",
                "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;"));
        assertTrue(capture >= 0 && supply > capture,
                "submitAsync must capture the exact Runnable in one supplier before "
                        + "offering that supplier to this event loop");
        MethodNode submitLambda = method(blockableEventLoop,
                "lambda$submitAsync$0", submitLambdaDesc);
        assertEquals(1, callCount(submitLambda,
                "java/lang/Runnable", "run", "()V"));

        MethodNode execute = method(blockableEventLoop,
                "execute", runnableDesc);
        int wrap = callIndex(execute, blockableEventLoop.name,
                "wrapRunnable",
                "(Ljava/lang/Runnable;)Ljava/lang/Runnable;");
        int scheduleCheck = callIndex(execute, blockableEventLoop.name,
                "scheduleExecutables", "()Z");
        int schedule = callIndex(execute, blockableEventLoop.name,
                "schedule", runnableDesc);
        int inlineTask = callIndex(execute, blockableEventLoop.name,
                "doRunTask", runnableDesc);
        assertEquals(1, callCount(execute, blockableEventLoop.name,
                "wrapRunnable",
                "(Ljava/lang/Runnable;)Ljava/lang/Runnable;"));
        assertEquals(1, callCount(execute, blockableEventLoop.name,
                "scheduleExecutables", "()Z"));
        assertEquals(1, callCount(execute, blockableEventLoop.name,
                "schedule", runnableDesc));
        assertEquals(1, callCount(execute, blockableEventLoop.name,
                "doRunTask", runnableDesc));
        assertTrue(wrap >= 0 && scheduleCheck > wrap && schedule > scheduleCheck
                        && inlineTask > schedule,
                "Executor.execute must wrap exactly once, then choose FIFO schedule or "
                        + "same-thread execution without replacing the task");

        MethodNode enqueue = method(blockableEventLoop,
                "schedule", runnableDesc);
        int queue = fieldIndex(enqueue, Opcodes.GETFIELD,
                blockableEventLoop.name, "pendingRunnables", "Ljava/util/Queue;");
        int add = callIndex(enqueue, "java/util/Queue",
                "add", "(Ljava/lang/Object;)Z");
        int runningThread = callIndex(enqueue, blockableEventLoop.name,
                "getRunningThread", "()Ljava/lang/Thread;");
        int unpark = callIndex(enqueue,
                "java/util/concurrent/locks/LockSupport",
                "unpark", "(Ljava/lang/Thread;)V");
        assertEquals(1, fieldCount(enqueue, Opcodes.GETFIELD,
                blockableEventLoop.name, "pendingRunnables", "Ljava/util/Queue;"));
        assertEquals(1, callCount(enqueue, "java/util/Queue",
                "add", "(Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(enqueue,
                "java/util/concurrent/locks/LockSupport",
                "unpark", "(Ljava/lang/Thread;)V"));
        assertEquals(1, callCount(enqueue, blockableEventLoop.name,
                "getRunningThread", "()Ljava/lang/Thread;"));
        assertEquals(0, callCount(enqueue, blockableEventLoop.name,
                "doRunTask", runnableDesc));
        assertTrue(queue >= 0 && add > queue && runningThread > add
                        && unpark > runningThread,
                "the wrapped task must enter the one vanilla FIFO before the exact "
                        + "running thread is resolved and unparked");

        MethodNode runAllTasks = method(blockableEventLoop,
                "runAllTasks", "()V");
        // The generic drain is not framed per poll: its one native poll result
        // alone decides whether the drain repeats. The RCON edge is claimed by the
        // waitUntilNextTick runAllTasks wrapper, not inside this loop.
        assertEquals(1, callCount(runAllTasks, blockableEventLoop.name, "pollTask", "()Z"),
                "runAllTasks must retain exactly one native poll");
        assertEquals(1, calls(runAllTasks).size(),
                "runAllTasks may not gain a second call beside its native poll");
        MethodInsnNode runAllPoll = calls(runAllTasks).getFirst();
        JumpInsnNode nativeEmptyExit = assertInstanceOf(JumpInsnNode.class,
                nextRealInstruction(runAllPoll.getNext()));
        assertEquals(Opcodes.IFEQ, nativeEmptyExit.getOpcode(),
                "the exact poll's returned boolean must decide whether the drain ends");
        JumpInsnNode nextPoll = assertInstanceOf(JumpInsnNode.class,
                nextRealInstruction(nativeEmptyExit.getNext()));
        assertEquals(Opcodes.GOTO, nextPoll.getOpcode());
        assertTrue(runAllTasks.instructions.indexOf(nextPoll.label)
                        < runAllTasks.instructions.indexOf(runAllPoll),
                "only a true poll result may repeat the native drain");
        assertEquals(Opcodes.RETURN, nextRealInstruction(nativeEmptyExit.label).getOpcode(),
                "a false poll result must leave the original drain without another body");
        assertEquals(0, callCount(runAllTasks, blockableEventLoop.name,
                "doRunTask", runnableDesc));
        assertEquals(0, callCount(runAllTasks,
                "java/lang/Runnable", "run", "()V"));
        MethodNode pollTask = method(blockableEventLoop,
                "pollTask", "()Z");
        int peek = callIndex(pollTask, "java/util/Queue",
                "peek", "()Ljava/lang/Object;");
        int remove = callIndex(pollTask, "java/util/Queue",
                "remove", "()Ljava/lang/Object;");
        int taskBody = callIndex(pollTask, blockableEventLoop.name,
                "doRunTask", runnableDesc);
        assertEquals(1, callCount(pollTask, "java/util/Queue",
                "peek", "()Ljava/lang/Object;"));
        assertEquals(1, callCount(pollTask, "java/util/Queue",
                "remove", "()Ljava/lang/Object;"));
        assertEquals(1, callCount(pollTask, blockableEventLoop.name,
                "doRunTask", runnableDesc));
        assertTrue(peek >= 0 && remove > peek && taskBody > remove,
                "Main must remove the FIFO head only when it is about to enter that "
                        + "same task through doRunTask");

        MethodNode managedBlock = method(blockableEventLoop,
                "managedBlock", "(Ljava/util/function/BooleanSupplier;)V");
        int done = callIndex(managedBlock,
                "java/util/function/BooleanSupplier", "getAsBoolean", "()Z");
        int managedPoll = callIndex(managedBlock, blockableEventLoop.name,
                "pollTask", "()Z");
        int waitForTasks = callIndex(managedBlock, blockableEventLoop.name,
                "waitForTasks", "()V");
        assertEquals(1, callCount(managedBlock,
                "java/util/function/BooleanSupplier", "getAsBoolean", "()Z"));
        assertEquals(1, callCount(managedBlock, blockableEventLoop.name,
                "pollTask", "()Z"));
        assertEquals(1, callCount(managedBlock, blockableEventLoop.name,
                "waitForTasks", "()V"));
        assertEquals(0, callCount(managedBlock, blockableEventLoop.name,
                "doRunTask", runnableDesc));
        assertEquals(0, callCount(managedBlock,
                "java/lang/Runnable", "run", "()V"));
        assertTrue(done >= 0 && managedPoll > done && waitForTasks > managedPoll,
                "the top-level managedBlock may pump only through the same FIFO poll "
                        + "path, then wait only when that poll found no runnable task");

        MethodNode baseDoRunTask = method(blockableEventLoop,
                "doRunTask", runnableDesc);

        MethodNode wrapRunnable = method(minecraftServer,
                "wrapRunnable",
                "(Ljava/lang/Runnable;)Lnet/minecraft/server/TickTask;");
        assertEquals(1, typeCount(wrapRunnable, Opcodes.NEW, TICK_TASK));
        assertEquals(1, callCount(wrapRunnable, TICK_TASK,
                "<init>", "(ILjava/lang/Runnable;)V"));
        MethodNode genericWrapRunnable = method(minecraftServer,
                "wrapRunnable", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;");
        assertEquals(1, callCount(genericWrapRunnable, minecraftServer.name,
                "wrapRunnable",
                "(Ljava/lang/Runnable;)Lnet/minecraft/server/TickTask;"));

        MethodNode minecraftPollTask = method(minecraftServer, "pollTask", "()Z");
        assertFalse(minecraftServer.methods.stream().anyMatch(candidate ->
                        candidate.name.startsWith("pollTask$mixinextras$wrapped$")),
                "the Main poll must stay one native body, not a framed receipt");
        MethodInsnNode failStopDispatch = generatedHandlerCall(
                minecraftPollTask, minecraftServer,
                "$failStopBeforeNextMainTask");
        int failStop = callIndex(minecraftPollTask, minecraftServer.name,
                failStopDispatch.name, failStopDispatch.desc);
        int pollInternal = callIndex(minecraftPollTask, minecraftServer.name,
                "pollTaskInternal", "()Z");
        assertEquals(0, callCount(minecraftPollTask, REGION_SCHEDULER,
                "throwWorkerFailureIfAny", "()V"));
        assertEquals(1, callCount(minecraftPollTask, minecraftServer.name,
                "pollTaskInternal", "()Z"));
        assertEquals(2, calls(minecraftPollTask).size(),
                "the Main poll may hold only its HEAD W gate and the one native poll");
        assertTrue(failStop >= 0 && pollInternal > failStop,
                "Main must observe Fabrolia's exact W before it asks the vanilla queue "
                        + "path to remove or run another task");
        MethodNode failStopHandler = method(minecraftServer,
                failStopDispatch.name, failStopDispatch.desc);
        assertEquals(1, callCount(failStopHandler, REGION_SCHEDULER,
                "throwWorkerFailureIfAny", "()V"));
        assertTrue(failStopHandler.tryCatchBlocks == null
                        || failStopHandler.tryCatchBlocks.isEmpty(),
                "the pre-poll W gate must not suppress the selected failure");
        MethodNode minecraftPollInternal = method(minecraftServer,
                "pollTaskInternal", "()Z");
        assertEquals(1, callCount(minecraftPollInternal,
                reentrantEventLoop.name, "pollTask", "()Z"));

        MethodNode minecraftDoRunTask = method(minecraftServer,
                "doRunTask", "(Lnet/minecraft/server/TickTask;)V");
        assertEquals(1, callCount(minecraftDoRunTask,
                reentrantEventLoop.name, "doRunTask", runnableDesc));
        MethodNode genericMinecraftDoRunTask = method(minecraftServer,
                "doRunTask", runnableDesc);
        assertEquals(1, typeCount(genericMinecraftDoRunTask,
                Opcodes.CHECKCAST, TICK_TASK));
        assertEquals(1, callCount(genericMinecraftDoRunTask,
                minecraftServer.name, "doRunTask",
                "(Lnet/minecraft/server/TickTask;)V"));

        for (MethodNode monitorFree : List.of(
                executeBlocking, submit, submitLambda, execute, enqueue,
                runAllTasks, pollTask, managedBlock, baseDoRunTask,
                wrapRunnable, genericWrapRunnable, minecraftPollTask, failStopHandler,
                minecraftPollInternal, minecraftDoRunTask,
                genericMinecraftDoRunTask)) {
            assertEquals(0, monitorFree.access & Opcodes.ACC_SYNCHRONIZED,
                    monitorFree.name + monitorFree.desc
                            + " must remain monitor-free in the RCON task chain");
            assertEquals(0, opcodeCount(monitorFree, Opcodes.MONITORENTER));
            assertEquals(0, opcodeCount(monitorFree, Opcodes.MONITOREXIT));
        }
    }

    private static void verifyVmpPlayerTrackingLocks(
            ClassNode area, ClassNode areaMap, ClassNode tracker) {
        List<FieldNode> mutationLocks = area.fields.stream().filter(field ->
                field.name.equals("fabrolia$mutationLock")
                        && field.desc.equals("L" + REENTRANT_LOCK + ";"))
                .toList();
        assertEquals(1, mutationLocks.size(),
                "final VMP manager must own one exact mutator lease");
        assertTrue((mutationLocks.getFirst().access & Opcodes.ACC_VOLATILE) != 0,
                "the constructor-published VMP mutator lock must remain volatile");
        for (String fieldName : List.of(
                "fabrolia$watchingSnapshots", "fabrolia$generalSnapshots")) {
            List<FieldNode> fields = area.fields.stream().filter(field ->
                    field.name.equals(fieldName)
                            && field.desc.equals("L" + PUBLISHED_LONG_OBJECT_MAP + ";"))
                    .toList();
            assertEquals(1, fields.size(), fieldName);
            assertTrue((fields.getFirst().access & Opcodes.ACC_VOLATILE) != 0,
                    fieldName + " must be safely constructor-published");
        }

        assertTrue(areaMap.interfaces.contains(VMP_AREA_MAP_ACCESSOR),
                "final VMP AreaMap must implement the public applied-radius accessor");
        MethodNode appliedDistances = method(areaMap,
                "fabrolia$getAppliedViewDistances",
                "()L" + REFERENCE_2_INT_OPEN_HASH_MAP + ";");
        assertTrue((appliedDistances.access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(1, fieldCount(appliedDistances, Opcodes.GETFIELD,
                areaMap.name, "viewDistances",
                "L" + REFERENCE_2_INT_OPEN_HASH_MAP + ";"),
                "the accessor must expose VMP's exact committed-radius field");

        MethodNode initialize = generatedMethod(area,
                "$fabrolia$initializePublishedAreaIndex");
        assertEquals(1, typeCount(initialize, Opcodes.NEW, REENTRANT_LOCK));
        assertEquals(1, callCount(initialize, REENTRANT_LOCK, "<init>", "()V"));
        assertEquals(2, typeCount(initialize, Opcodes.NEW, PUBLISHED_LONG_OBJECT_MAP));
        assertEquals(1, typeCount(initialize, Opcodes.NEW,
                "it/unimi/dsi/fastutil/objects/Reference2LongOpenHashMap"));
        assertEquals(1, typeCount(initialize, Opcodes.NEW,
                "it/unimi/dsi/fastutil/objects/Reference2IntOpenHashMap"));
        assertEquals(1, fieldCount(initialize, Opcodes.PUTFIELD, area.name,
                "fabrolia$mutationLock", "L" + REENTRANT_LOCK + ";"));
        assertEquals(1, fieldCount(initialize, Opcodes.PUTFIELD, area.name,
                "fabrolia$watchingSnapshots",
                "L" + PUBLISHED_LONG_OBJECT_MAP + ";"));
        assertEquals(1, fieldCount(initialize, Opcodes.PUTFIELD, area.name,
                "fabrolia$generalSnapshots",
                "L" + PUBLISHED_LONG_OBJECT_MAP + ";"));

        List<MethodNode> constructors = area.methods.stream()
                .filter(candidate -> candidate.name.equals("<init>"))
                .toList();
        assertEquals(List.of("()V", VMP_AREA_ROOT_CTOR).stream().sorted().toList(),
                constructors.stream().map(candidate -> candidate.desc).sorted().toList(),
                "the audited VMP manager has exactly its delegating and root constructors");
        MethodNode rootConstructor = method(area, "<init>", VMP_AREA_ROOT_CTOR);
        MethodNode delegatingConstructor = method(area, "<init>", "()V");
        int objectConstructor = callIndex(rootConstructor,
                "java/lang/Object", "<init>", "()V");
        int initializeCall = generatedCallIndex(rootConstructor, area.name,
                "$fabrolia$initializePublishedAreaIndex");
        int rootReturn = lastOpcodeIndex(rootConstructor, Opcodes.RETURN);
        assertEquals(1, generatedCallCount(rootConstructor, area.name,
                "$fabrolia$initializePublishedAreaIndex"));
        assertTrue(objectConstructor >= 0 && initializeCall > objectConstructor
                        && rootReturn > initializeCall,
                "the exact root constructor must publish VMP snapshots before RETURN");
        int delegation = callIndex(delegatingConstructor, area.name,
                "<init>", VMP_AREA_ROOT_CTOR);
        int delegatingReturn = lastOpcodeIndex(delegatingConstructor, Opcodes.RETURN);
        assertEquals(1, callCount(delegatingConstructor, area.name,
                "<init>", VMP_AREA_ROOT_CTOR));
        assertEquals(0, generatedCallCount(delegatingConstructor, area.name,
                "$fabrolia$initializePublishedAreaIndex"));
        assertTrue(delegation >= 0 && delegatingReturn > delegation,
                "the no-argument VMP constructor must inherit root initialization");
        assertEquals(1, constructors.stream().mapToInt(candidate ->
                        generatedCallCount(candidate, area.name,
                                "$fabrolia$initializePublishedAreaIndex")).sum(),
                "only the three-argument VMP root may execute the initializer");
        for (MethodNode constructor : constructors) {
            int dominance = constructor.desc.equals(VMP_AREA_ROOT_CTOR)
                    ? initializeCall : delegation;
            int firstGuardedUse = firstCallIndexByNames(constructor, area.name,
                    List.of("fabrolia$mutationLock", "fabrolia$watchingSnapshots",
                            "fabrolia$generalSnapshots", "wrapMethod$"));
            assertTrue(dominance >= 0
                            && (firstGuardedUse < 0 || dominance < firstGuardedUse),
                    constructor.desc
                            + " must establish snapshots before any guarded use");
            assertEquals(0, typeCount(constructor, Opcodes.NEW, REENTRANT_LOCK),
                    "constructors must call, not inline, the generated initializer");
        }

        MethodNode lockAccessor = generatedMethod(area, "fabrolia$mutationLock");
        assertEquals("()L" + REENTRANT_LOCK + ";", lockAccessor.desc);
        assertEquals(0, typeCount(lockAccessor, Opcodes.NEW, REENTRANT_LOCK),
                "the final VMP accessor must fail closed instead of allocating lazily");
        assertEquals(1, typeCount(lockAccessor, Opcodes.NEW,
                "java/lang/IllegalStateException"));
        MethodNode watchingAccessor =
                generatedMethod(area, "fabrolia$watchingSnapshots");
        MethodNode generalAccessor =
                generatedMethod(area, "fabrolia$generalSnapshots");
        for (MethodNode accessor : List.of(watchingAccessor, generalAccessor)) {
            assertEquals("()L" + PUBLISHED_LONG_OBJECT_MAP + ";", accessor.desc);
            assertEquals(1, typeCount(accessor, Opcodes.NEW,
                    "java/lang/IllegalStateException"));
        }
        MethodNode appliedDistance =
                generatedMethod(area, "fabrolia$appliedViewDistance");
        assertEquals("(L" + VMP_AREA_MAP + ";Ljava/lang/Object;)I",
                appliedDistance.desc);
        assertEquals(1, typeCount(
                appliedDistance, Opcodes.CHECKCAST, VMP_AREA_MAP_ACCESSOR),
                "the merged helper must cast only to the public accessor");
        assertEquals(1, callCount(appliedDistance, VMP_AREA_MAP_ACCESSOR,
                "fabrolia$getAppliedViewDistances",
                "()L" + REFERENCE_2_INT_OPEN_HASH_MAP + ";"));
        assertEquals(1, callCount(appliedDistance, REFERENCE_2_INT_OPEN_HASH_MAP,
                "containsKey", "(Ljava/lang/Object;)Z"));
        assertEquals(1, callCount(appliedDistance, REFERENCE_2_INT_OPEN_HASH_MAP,
                "getInt", "(Ljava/lang/Object;)I"));
        assertEquals(1, typeCount(appliedDistance, Opcodes.NEW,
                "java/lang/IllegalStateException"),
                "missing applied radius must fail closed");
        assertEquals(1, area.methods.stream().mapToInt(candidate ->
                        typeCount(candidate, Opcodes.NEW, REENTRANT_LOCK)).sum(),
                "the root-constructor handler is the sole VMP lock allocation site");
        assertEquals(0, area.methods.stream().mapToInt(candidate ->
                        typeCount(candidate, Opcodes.NEW,
                                REJECTED_READ_WRITE_LOCK)).sum(),
                "hot readers must not participate in a dimension-wide RW gate");

        MethodNode tickTarget = method(area, "tick", "()V");
        MethodNode lockedTick = uniqueMethod(area, candidate ->
                        candidate.name.endsWith("fabrolia$lockedTick")
                                && candidate.desc.contains("L" + OPERATION + ";")
                                && callCount(candidate, OPERATION,
                                "call", OPERATION_CALL_DESC) == 1,
                area.name + " conditional VMP tick Operation handler");
        assertEquals(1, callCount(tickTarget, area.name,
                lockedTick.name, lockedTick.desc));
        assertMonitorFree(lockedTick);
        assertEquals(1, callCount(lockedTick, area.name,
                lockAccessor.name, lockAccessor.desc));
        assertEquals(1, fieldCount(lockedTick, Opcodes.GETFIELD, area.name,
                "positions", "Lit/unimi/dsi/fastutil/objects/Object2LongOpenHashMap;"));
        assertEquals(1, callCount(lockedTick, VMP_CLIENT_VD_TRACKING,
                "isClientViewDistanceChanged", "()Z"));
        assertEquals(1, callCount(lockedTick, REENTRANT_LOCK, "lock", "()V"));
        assertTrue(callCount(lockedTick, REENTRANT_LOCK, "unlock", "()V") >= 2,
                "VMP tick must release its mutation lease on Throwable");
        int tickAcquire = callIndex(lockedTick, REENTRANT_LOCK, "lock", "()V");
        int changedFlag = callIndex(lockedTick, VMP_CLIENT_VD_TRACKING,
                "isClientViewDistanceChanged", "()Z");
        int exactOriginal = callIndex(lockedTick,
                OPERATION, "call", OPERATION_CALL_DESC);
        int tickRelease = callIndexAfter(lockedTick,
                REENTRANT_LOCK, "unlock", "()V", exactOriginal);
        assertTrue(tickAcquire >= 0 && changedFlag > tickAcquire
                        && exactOriginal > changedFlag && tickRelease > exactOriginal,
                "VMP tick must preflight and run original under one mutation lease");
        assertTrue(hasCatchAllCleanupAroundCall(lockedTick, exactOriginal,
                REENTRANT_LOCK, "unlock", "()V"));

        MethodNode lockedDistance = assertReentrantOperationRoute(area,
                "onWatchDistanceChange", "()V", "fabrolia$lockedWatchDistanceChange",
                lockAccessor);
        MethodNode lockedAdd = assertReentrantOperationRoute(area, "add",
                "(Lnet/minecraft/server/level/ServerPlayer;J)V", "fabrolia$lockedAdd",
                lockAccessor);
        MethodNode lockedRemove = assertReentrantOperationRoute(area, "remove",
                "(Lnet/minecraft/server/level/ServerPlayer;)V", "fabrolia$lockedRemove",
                lockAccessor);
        MethodNode lockedMove = assertReentrantOperationRoute(area, "movePlayer",
                "(JLnet/minecraft/server/level/ServerPlayer;)V",
                "fabrolia$lockedMovePlayer", lockAccessor);
        for (MethodNode writer : List.of(
                lockedDistance, lockedAdd, lockedRemove, lockedMove)) {
            assertEquals(1, callCount(writer, OPERATION, "call", OPERATION_CALL_DESC),
                    "each VMP mutator must retain its exact original body");
        }
        for (MethodNode radiusWriter : List.of(
                lockedDistance, lockedAdd, lockedMove)) {
            assertEquals(1, callCount(radiusWriter, area.name,
                    appliedDistance.name, appliedDistance.desc),
                    "snapshot metadata must use VMP's committed radius");
            assertEquals(0, callCount(radiusWriter, area.name,
                    "getViewDistance",
                    "(Lnet/minecraft/server/level/ServerPlayer;)I"),
                    "post-mutation code must not reread a newer distance request");
            int originalCall = callIndex(radiusWriter,
                    OPERATION, "call", OPERATION_CALL_DESC);
            int appliedCall = callIndexAfter(radiusWriter, area.name,
                    appliedDistance.name, appliedDistance.desc, originalCall);
            assertTrue(originalCall >= 0 && appliedCall > originalCall,
                    "committed radius must be captured after exact VMP mutation");
        }

        MethodNode setSnapshot = assertReentrantOperationRoute(area,
                "getPlayersWatchingChunk", "(J)Ljava/util/Set;",
                "fabrolia$snapshotPlayersWatchingChunk", lockAccessor);
        assertEquals(1, typeCount(setSnapshot, Opcodes.NEW,
                "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet"),
                "the compatibility set must remain detached from live VMP storage");
        assertEquals(1, callCount(setSnapshot,
                "it/unimi/dsi/fastutil/objects/ReferenceLinkedOpenHashSet", "addAll",
                "(Ljava/util/Collection;)Z"));

        assertPublishedArrayRoute(area, "getPlayersWatchingChunkArray",
                "(J)[Ljava/lang/Object;", "fabrolia$publishedPlayersWatchingChunk",
                watchingAccessor);
        assertPublishedArrayRoute(area, "getPlayersInGeneralAreaMap",
                "(J)[Ljava/lang/Object;", "fabrolia$publishedPlayersInGeneralArea",
                generalAccessor);

        MethodNode refreshCell = generatedMethod(area, "fabrolia$refreshCell");
        assertEquals(1, callCount(refreshCell, "com/ishland/vmp/common/maps/AreaMap",
                "getObjectsInRangeArray", "(J)[Ljava/lang/Object;"));
        assertEquals(1, callCount(refreshCell, "[Ljava/lang/Object;", "clone",
                "()Ljava/lang/Object;"),
                "writers must detach VMP's pooled raw backing array before publication");
        assertEquals(1, callCount(refreshCell, PUBLISHED_LONG_OBJECT_MAP,
                "put", "(JLjava/lang/Object;)V"));
        assertEquals(1, callCount(refreshCell, PUBLISHED_LONG_OBJECT_MAP,
                "remove", "(J)V"));

        assertMonitorOperationRoute(tracker, "updatePlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)V",
                "fabrolia$updatePlayer");
        assertMonitorOperationRoute(tracker, "removePlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)V",
                "fabrolia$removePlayer");
        assertSynchronizedOperationRoute(tracker, "broadcastRemoved", "()V",
                "fabrolia$broadcastRemoved");
    }

    private static MethodNode assertReentrantOperationRoute(
            ClassNode owner, String targetName, String targetDesc, String handlerSuffix,
            MethodNode lockAccessor) {
        MethodNode target = method(owner, targetName, targetDesc);
        assertMonitorFree(target);
        MethodNode handler = uniqueMethod(owner, candidate ->
                        candidate.name.endsWith(handlerSuffix)
                                && candidate.desc.contains("L" + OPERATION + ";")
                                && callCount(candidate, OPERATION,
                                "call", OPERATION_CALL_DESC) == 1,
                owner.name + " serialized " + handlerSuffix + " Operation handler");
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route once through " + handler.name);
        assertMonitorFree(handler);
        assertEquals(1, callCount(handler, owner.name,
                lockAccessor.name, lockAccessor.desc));
        assertEquals(1, callCount(handler, REENTRANT_LOCK, "lock", "()V"));
        assertTrue(callCount(handler, REENTRANT_LOCK, "unlock", "()V") >= 2,
                handler.name + " must unlock normally and exceptionally");
        int acquire = callIndex(handler, REENTRANT_LOCK, "lock", "()V");
        int body = callIndex(handler, OPERATION, "call", OPERATION_CALL_DESC);
        int release = callIndexAfter(
                handler, REENTRANT_LOCK, "unlock", "()V", body);
        assertTrue(acquire >= 0 && body > acquire && release > body,
                handler.name + " must retain original/publication under its lease");
        assertTrue(hasCatchAllCleanupAroundCall(handler, body,
                        REENTRANT_LOCK, "unlock", "()V"),
                handler.name + " must release its lease after every Throwable");
        return handler;
    }

    private static MethodNode assertPublishedArrayRoute(
            ClassNode owner, String targetName, String targetDesc, String handlerSuffix,
            MethodNode snapshotAccessor) {
        MethodNode target = method(owner, targetName, targetDesc);
        assertMonitorFree(target);
        MethodNode handler = uniqueMethod(owner, candidate ->
                        candidate.name.endsWith(handlerSuffix)
                                && candidate.desc.contains("L" + OPERATION + ";"),
                owner.name + " published " + handlerSuffix + " Operation handler");
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route once through " + handler.name);
        assertMonitorFree(handler);
        assertEquals(0, callCount(handler, OPERATION,
                "call", OPERATION_CALL_DESC),
                handler.name + " must never read live VMP storage");
        assertEquals(0, callCount(handler, REENTRANT_LOCK, "lock", "()V"),
                handler.name + " must never join the writer queue");
        assertEquals(1, callCount(handler, owner.name,
                snapshotAccessor.name, snapshotAccessor.desc));
        assertEquals(1, callCount(handler, PUBLISHED_LONG_OBJECT_MAP,
                "get", "(J)Ljava/lang/Object;"));
        return handler;
    }

    private static void assertMonitorFree(MethodNode method) {
        assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED,
                method.name + method.desc + " must not hold a method monitor");
        assertEquals(0, opcodeCount(method, Opcodes.MONITORENTER),
                method.name + method.desc + " must not enter a monitor");
        assertEquals(0, opcodeCount(method, Opcodes.MONITOREXIT),
                method.name + method.desc + " must not exit a monitor");
    }

    /** Final merged proof for VMP membership staging and the dual publication clock. */
    private static void verifyEntityTrackingComposition(
            ClassNode serverEntity, ClassNode tracker, ClassNode nearby,
            ClassNode tracking, ClassNode regionizedServer) {
        MethodNode send = method(serverEntity, "sendChanges", "()V");
        MethodNode route = generatedMethod(serverEntity,
                "$fabrolia$routeAutonomousEntityPublication");
        assertEquals(1, callCount(send, serverEntity.name, route.name, route.desc),
                "ServerEntity.sendChanges must enter the one composed routing handler");
        int monitor = opcodeIndex(route, Opcodes.MONITORENTER);
        int decision = callIndex(route, ISLAND_ENTITY_TRACKING, "route",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)L"
                        + ISLAND_ENTITY_TRACKING + "$Route;");
        int begin = callIndex(route, ISLAND_ENTITY_TRACKING, "beginMainPublication",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)Z");
        int defer = callIndex(route, ISLAND_ENTITY_TRACKING,
                "deferDuplicateMainPublication", "(Ljava/lang/Object;)V");
        int original = callIndex(route, OPERATION, "call", OPERATION_CALL_DESC);
        int mark = callIndex(route, ISLAND_TRACKED_ENTITY_ACCESS,
                "fabrolia$markIslandTrackingPublished", "()V");
        int commit = callIndex(route, ISLAND_ENTITY_TRACKING,
                "commitPendingPairingsAfterMain", "(Ljava/lang/Object;)V");
        int finish = callIndex(route, ISLAND_ENTITY_TRACKING,
                "finishMainPublication", "(Ljava/lang/Object;)V");
        assertTrue(monitor >= 0 && decision > monitor && begin > decision
                        && defer > begin && original > defer && mark > original
                        && commit > mark && finish > commit,
                "the exact tracker monitor must serialize route/stamp/original/generation/"
                        + "membership/cadence in that order");
        assertEquals(1, callCount(route, OPERATION, "call", OPERATION_CALL_DESC));

        MethodNode beginPublication = method(tracking, "beginMainPublication",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)Z");
        int beginIsland = fieldIndex(beginPublication, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING, "POST_PHYSICS_CROSSING_PUBLISHING",
                "Ljava/lang/ThreadLocal;");
        int beginGet = callIndex(beginPublication, "java/lang/ThreadLocal", "get",
                "()Ljava/lang/Object;");
        int beginBranch = opcodeIndexAfter(beginPublication, Opcodes.IF_ACMPNE, beginGet);
        int beginReturn = opcodeIndexAfter(beginPublication, Opcodes.IRETURN, beginBranch);
        int beginRuntime = callIndex(beginPublication, ISLAND_ENTITY_TRACKING,
                "runtime", "(Lnet/minecraft/world/entity/Entity;)Lcom/axalotl/fabrolia/"
                        + "common/regions/RegionizedServer;");
        int beginStamp = callIndex(beginPublication, ISLAND_TRACKED_ENTITY_ACCESS,
                "fabrolia$tryBeginMainTrackingPublication", "(I)Z");
        assertTrue(beginIsland >= 0 && beginGet > beginIsland && beginBranch > beginGet
                        && beginReturn > beginBranch && beginRuntime > beginReturn
                        && beginStamp > beginRuntime
                        && previousRealOpcode(beginPublication, beginReturn)
                        == Opcodes.ICONST_1,
                "only an authenticated post-physics crossing may return before hard-Main "
                        + "same-tick dedupe");
        assertEquals(0, fieldCount(beginPublication, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING, "PUBLISHING", "Ljava/lang/ThreadLocal;"),
                "ordinary island/fallback publication must retain Main stamp semantics");
        MethodNode finishPublication = method(tracking, "finishMainPublication",
                "(Ljava/lang/Object;)V");
        int finishIsland = fieldIndex(finishPublication, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING, "POST_PHYSICS_CROSSING_PUBLISHING",
                "Ljava/lang/ThreadLocal;");
        int finishGet = callIndex(finishPublication, "java/lang/ThreadLocal", "get",
                "()Ljava/lang/Object;");
        int finishBranch = opcodeIndexAfter(finishPublication, Opcodes.IF_ACMPNE, finishGet);
        int finishReturn = opcodeIndexAfter(finishPublication, Opcodes.RETURN, finishBranch);
        MethodNode finishEntered = method(tracking, "finishMainPublicationEntered",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        MethodNode finishBody = uniqueMethod(tracking,
                candidate -> callCount(candidate, tracking.name,
                        "finishMainPublicationEntered",
                        "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V") == 1,
                "finishMainPublication admitted body");
        int finishBodyTarget = invokedynamicTargetIndex(finishPublication, tracking.name,
                finishBody.name, finishBody.desc);
        int finishMonitor = opcodeIndex(finishEntered, Opcodes.MONITORENTER);
        assertTrue(finishIsland >= 0 && finishGet > finishIsland
                        && finishBranch > finishGet && finishReturn > finishBranch
                        && finishBodyTarget > finishReturn && finishMonitor >= 0,
                "only an authenticated post-physics crossing may return before Main cadence "
                        + "maintenance");
        assertEquals(1, invokedynamicTargetCount(finishPublication, tracking.name,
                        finishBody.name, finishBody.desc),
                "Main cadence maintenance must retain its success-admitted body");
        assertEquals(0, fieldCount(finishPublication, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING, "PUBLISHING", "Ljava/lang/ThreadLocal;"),
                "ordinary island/fallback publication must retain Main cadence semantics");

        assertTrue(tracker.interfaces.contains(ISLAND_TRACKED_ENTITY_ACCESS),
                "the transformed TrackedEntity must expose its exact synchronizer duck");
        for (String field : List.of("fabrolia$islandTrackingState",
                "fabrolia$islandTrackingOwner", "fabrolia$mainTrackingScheduled",
                "fabrolia$pendingBulkPairingRefresh",
                "fabrolia$lastMainTrackingServerTick",
                "fabrolia$islandTrackingPublicationGeneration",
                "fabrolia$pendingIslandPairings")) {
            assertEquals(1, tracker.fields.stream()
                            .filter(candidate -> candidate.name.equals(field)).count(),
                    "transformed tracker authority field " + field);
        }
        // 26.3 passes the tracker cadence as an UpdateInterval instead of an int.
        MethodNode constructor = method(tracker, "<init>",
                "(Lnet/minecraft/server/level/ChunkMap;"
                        + "Lnet/minecraft/world/entity/Entity;I"
                        + "Lnet/minecraft/world/entity/UpdateInterval;Z)V");
        MethodNode initialize = generatedMethod(tracker,
                "$fabrolia$initializeIslandTrackingState");
        int initializeCall = callIndex(constructor, tracker.name,
                initialize.name, initialize.desc);
        int constructorReturn = opcodeIndexAfter(constructor, Opcodes.RETURN,
                initializeCall);
        assertEquals(1, callCount(constructor, tracker.name,
                        initialize.name, initialize.desc),
                "the exact merged constructor must invoke one generated initializer");
        assertTrue(initializeCall >= 0 && constructorReturn > initializeCall,
                "the generated initializer must execute on the constructor RETURN path");
        assertTrue(hasImmediateIntFieldWrite(initialize, tracker.name,
                        "fabrolia$islandTrackingState", Opcodes.ICONST_0, null),
                "the merged constructor route must explicitly publish TRACKING_ACTIVE");
        assertTrue(hasImmediateIntFieldWrite(initialize, tracker.name,
                        "fabrolia$lastMainTrackingServerTick", Opcodes.LDC,
                        Integer.MIN_VALUE),
                "the merged constructor route must explicitly publish the uninitialised Main clock");
        assertTrue(hasCapacityConstructedFieldWrite(initialize, tracker.name,
                        "fabrolia$pendingIslandPairings", PENDING_PAIRING_QUEUE, 2),
                "the executable merged constructor route, not merely the source mixin, must install "
                        + "the bounded identity-keyed pairing queue before first VMP pairing");
        int activeInit = fieldIndex(initialize, Opcodes.PUTFIELD, tracker.name,
                "fabrolia$islandTrackingState", "I");
        int pendingInit = fieldIndex(initialize, Opcodes.PUTFIELD, tracker.name,
                "fabrolia$pendingIslandPairings", "L" + PENDING_PAIRING_QUEUE + ";");
        int registerSelf = callIndex(initialize, ISLAND_ENTITY_TRACKING,
                "registerPlayerTracker", "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        assertEquals(1, callCountByName(initialize, ISLAND_ENTITY_TRACKING,
                        "registerPlayerTracker"),
                "the completed tracker constructor must register one exact player self publisher");
        assertTrue(activeInit >= 0 && pendingInit > activeInit && registerSelf > pendingInit,
                "self registration must observe explicit ACTIVE state and initialized uniques");
        for (String suffix : List.of("fabrolia$hasPendingIslandPairings",
                "fabrolia$queueIslandBulkPairingRefresh",
                "fabrolia$commitIslandPairings",
                "fabrolia$tryBeginMainTrackingPublication",
                "fabrolia$islandTrackingPublicationGeneration",
                "fabrolia$markIslandTrackingPublished", "fabrolia$sendIslandChanges")) {
            MethodNode method = generatedMethod(tracker, suffix);
            assertTrue((method.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                    suffix + " must retain the exact tracker intrinsic monitor");
        }
        MethodNode islandSend = generatedMethod(tracker,
                "fabrolia$sendIslandChanges");
        assertEquals(1, callCount(islandSend, SERVER_ENTITY, "sendChanges", "()V"));
        assertTrue(fieldCount(islandSend, Opcodes.GETFIELD, tracker.name,
                        "fabrolia$islandTrackingPublicationGeneration", "J") >= 2,
                "island send must compare the publication generation around sendChanges");

        MethodNode update = generatedMethod(tracker, "$fabrolia$updatePlayer");
        MethodNode bulk = generatedMethod(tracker,
                "$fabrolia$stageBulkUpdatePlayers");
        MethodNode remove = generatedMethod(tracker, "$fabrolia$removePlayer");
        MethodNode teardown = generatedMethod(tracker,
                "$fabrolia$broadcastRemoved");
        assertEquals(1, opcodeCount(update, Opcodes.MONITORENTER));
        assertEquals(1, callCountByName(update, ISLAND_ENTITY_TRACKING,
                "stagePairingIfOwned"));
        assertEquals(0, callCountByName(update, tracker.name,
                "fabrolia$removeCapturedPairingDirect"),
                "a failed transformed ADD must propagate without compensating publication");
        assertFalse(update.tryCatchBlocks.stream().anyMatch(block ->
                        "java/lang/RuntimeException".equals(block.type)
                                || "java/lang/Error".equals(block.type)),
                "unchecked pairing failures must not enter rollback/retry bodies");
        assertEquals(1, callCountByName(update, ISLAND_ENTITY_TRACKING,
                "registerFromPairing"));
        assertEquals(1, opcodeCount(bulk, Opcodes.MONITORENTER));
        assertEquals(1, callCountByName(bulk, ISLAND_ENTITY_TRACKING,
                "stageBulkPairingRefreshIfOwned"));
        assertEquals(2, callCount(bulk, OPERATION, "call", OPERATION_CALL_DESC),
                "bulk commit and non-island fallback are the only immediate list bodies");
        assertEquals(1, opcodeCount(remove, Opcodes.MONITORENTER));
        assertEquals(1, callCountByName(remove, ISLAND_ENTITY_TRACKING,
                "stageRemovalIfOwned"));
        assertEquals(0, callCountByName(remove, tracker.name,
                "fabrolia$finishFailedRemoval"),
                "a failed vanilla removal must not run recovery/destroy successors");
        assertEquals(2, callCountByName(remove, ISLAND_ENTITY_TRACKING,
                "unregisterIfIdle"));
        assertEquals(1, callCount(remove, OPERATION, "call", OPERATION_CALL_DESC),
                "the exact vanilla removal body must have one entry and no retry");
        assertTrue((teardown.access & Opcodes.ACC_SYNCHRONIZED) != 0);
        int retire = callIndex(teardown, tracker.name,
                generatedMethod(tracker, "fabrolia$deactivateIslandTracking").name, "()V");
        int detach = callIndex(teardown, ISLAND_ENTITY_TRACKING, "retire",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        int teardownBody = callIndex(teardown, OPERATION, "call", OPERATION_CALL_DESC);
        assertTrue(retire >= 0 && detach > retire && teardownBody > detach,
                "the exact tracker generation must retire before vanilla remove packets");

        MethodNode stage = method(tracking, "stageBulkPairingRefreshIfOwned",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z");
        int bulkStageMonitor = opcodeIndex(stage, Opcodes.MONITORENTER);
        int bulkStageState = callIndexAfter(stage, ISLAND_TRACKED_ENTITY_ACCESS,
                "fabrolia$islandTrackingState", "()I", bulkStageMonitor);
        int bulkStageQueue = callIndexAfter(stage, ISLAND_TRACKED_ENTITY_ACCESS,
                "fabrolia$queueIslandBulkPairingRefresh", "()Z", bulkStageState);
        int bulkStageRoute = callIndexAfter(stage, ISLAND_ENTITY_TRACKING,
                "routeMembershipObligation", "(L" + ISLAND_TRACKED_ENTITY_ACCESS
                        + ";Lnet/minecraft/world/entity/Entity;L"
                        + REGIONIZED_SERVER + ";)V", bulkStageQueue);
        assertTrue(bulkStageMonitor >= 0 && bulkStageState > bulkStageMonitor
                        && bulkStageQueue > bulkStageState
                        && bulkStageRoute > bulkStageQueue,
                "the directly callable VMP bulk seam must keep ACTIVE/entity/runtime "
                        + "revalidation, token publication, and reentrant routing in one tracker "
                        + "transaction");
        assertEquals(1, opcodeCount(stage, Opcodes.MONITORENTER));
        assertEquals(1, callCount(stage, ISLAND_TRACKED_ENTITY_ACCESS,
                "fabrolia$queueIslandBulkPairingRefresh", "()Z"));
        MethodNode bulkScope = method(tracking, "commitBulkPairingRefresh",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";Ljava/lang/Runnable;)V");
        int lockProof = callIndex(bulkScope, "java/lang/Thread", "holdsLock",
                "(Ljava/lang/Object;)Z");
        int set = callIndex(bulkScope, "java/lang/ThreadLocal", "set",
                "(Ljava/lang/Object;)V");
        int clear = callIndex(bulkScope, "java/lang/ThreadLocal", "remove", "()V");
        assertTrue(lockProof >= 0 && set > lockProof && clear > set,
                "bulk commit must authenticate the exact tracker before publishing its "
                        + "capability and retain thread-local cleanup");
        MethodNode hardTypes = method(tracking, "mainOwnedEntityType",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        for (String type : List.of(
                "net/minecraft/world/entity/boss/wither/WitherBoss",
                "net/minecraft/world/entity/boss/enderdragon/EnderDragon")) {
            assertEquals(1, typeCount(hardTypes, Opcodes.INSTANCEOF, type),
                    "persistent physical-Main tracker type " + type);
        }
        MethodNode routeState = method(tracking, "route",
                "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;)L"
                        + ISLAND_ENTITY_TRACKING + "$Route;");
        String topologyReceipt = REGIONIZED_SERVER
                + "$TrackingTopologyTransitionReceipt";
        String topologyReceiptReadDesc = "()L" + topologyReceipt + ";";
        int routeAdmission = callIndex(routeState, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V");
        int routeHandoff = callIndex(routeState, ISLAND_ENTITY_TRACKING,
                "handoffMainPublicationToActiveOwnerLocked",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS
                        + ";Lnet/minecraft/world/entity/Entity;)Z");
        int routeTopologyReceipt = callIndex(routeState, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc);
        // Branch layout and duplicated finally epilogues are not routing contracts.
        // Ownership observation still requires admission and failure-path release.
        assertTrue(routeAdmission >= 0 && routeHandoff > routeAdmission
                        && routeTopologyReceipt > routeAdmission,
                "active-owner and transition-receipt reads must follow topology admission");
        assertEquals(1, callCount(routeState, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V"));
        assertTrue(hasCatchAllCleanupAroundCall(routeState, routeHandoff,
                        REGIONIZED_SERVER, "exitTrackingTopologyRouting", "()V")
                        && hasCatchAllCleanupAroundCall(routeState, routeTopologyReceipt,
                        REGIONIZED_SERVER, "exitTrackingTopologyRouting", "()V"),
                "route must release topology admission when handoff/receipt routing throws");

        MethodNode pairingRegister = method(tracking, "registerFromPairing",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        int registerMonitor = opcodeIndex(pairingRegister, Opcodes.MONITORENTER);
        int registerAdmission = callIndexAfter(pairingRegister, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V", registerMonitor);
        int registerRevalidation = callIndexByNameAfter(pairingRegister,
                ISLAND_TRACKED_ENTITY_ACCESS, "fabrolia$islandTrackingState",
                registerAdmission);
        int registerHandoff = callIndexAfter(pairingRegister, ISLAND_ENTITY_TRACKING,
                "handoffMainPublicationToActiveOwnerLocked",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS
                        + ";Lnet/minecraft/world/entity/Entity;)Z", registerRevalidation);
        int registerReceipt = callIndexAfter(pairingRegister, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc,
                registerHandoff);
        int registerDetach = callIndexAfter(pairingRegister, ISLAND_ENTITY_TRACKING,
                "detach", "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V",
                registerReceipt);
        int registerSchedule = callIndexAfter(pairingRegister, ISLAND_ENTITY_TRACKING,
                "scheduleMainPublication",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V", registerDetach);
        int registerRelease = callIndexAfter(pairingRegister, REGIONIZED_SERVER,
                "exitTrackingTopologyRouting", "()V", registerSchedule);
        assertTrue(registerMonitor >= 0 && registerAdmission > registerMonitor
                        && registerRevalidation > registerAdmission
                        && registerHandoff > registerRevalidation
                        && registerReceipt > registerHandoff
                        && registerDetach > registerReceipt
                        && registerSchedule > registerDetach
                        && registerRelease > registerSchedule,
                "pairing registration must revalidate under tracker -> topology-read authority "
                        + "and divert a transition receipt through detach -> Main publication");
        assertEquals(1, callCount(pairingRegister, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V"));
        assertEquals(1, callCount(pairingRegister, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc));
        assertTrue(hasCatchAllCleanupAroundCall(pairingRegister, registerReceipt,
                        REGIONIZED_SERVER, "exitTrackingTopologyRouting", "()V"),
                "pairing registration must release topology admission on every failure");

        MethodNode idleUnregister = method(tracking, "unregisterIfIdle",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        int unregisterMonitor = opcodeIndex(idleUnregister, Opcodes.MONITORENTER);
        int unregisterAdmission = callIndexAfter(idleUnregister, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V", unregisterMonitor);
        int unregisterHandoff = callIndexAfter(idleUnregister, ISLAND_ENTITY_TRACKING,
                "handoffMainPublicationToActiveOwnerLocked",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS
                        + ";Lnet/minecraft/world/entity/Entity;)Z", unregisterAdmission);
        int unregisterReceipt = callIndexAfter(idleUnregister, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc,
                unregisterHandoff);
        int unregisterDetach = callIndexAfter(idleUnregister, ISLAND_ENTITY_TRACKING,
                "detach", "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V",
                unregisterReceipt);
        int unregisterRelease = callIndexAfter(idleUnregister, REGIONIZED_SERVER,
                "exitTrackingTopologyRouting", "()V", unregisterDetach);
        assertTrue(unregisterMonitor >= 0 && unregisterAdmission > unregisterMonitor
                        && unregisterHandoff > unregisterAdmission
                        && unregisterReceipt > unregisterHandoff
                        && unregisterDetach > unregisterReceipt
                        && unregisterRelease > unregisterDetach,
                "idle unregistration must keep its exact receipt detach inside the same "
                        + "tracker -> topology-read admission");
        assertEquals(1, callCount(idleUnregister, REGIONIZED_SERVER,
                "enterTrackingTopologyRouting", "()V"));
        assertEquals(1, callCount(idleUnregister, REGIONIZED_SERVER,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc));
        assertEquals(0, callCountByName(idleUnregister,
                ISLAND_ENTITY_TRACKING, "scheduleMainPublication"));
        assertTrue(hasCatchAllCleanupAroundCall(idleUnregister, unregisterReceipt,
                        REGIONIZED_SERVER, "exitTrackingTopologyRouting", "()V"),
                "idle unregistration must release topology admission on every failure");

        MethodNode persistentType = method(tracking,
                "requiresPersistentMainPublisher",
                "(Lnet/minecraft/world/entity/Entity;)Z");
        assertEquals(1, callCountByName(persistentType, A5_PLAYER_TICKS,
                "hasAttachedOwner"));
        assertEquals(2, callCountByName(persistentType, A5_PLAYER_TICKS,
                "supportsIslandPlayerType"));

        List<FieldNode> topologyAdmissionFields = regionizedServer.fields.stream()
                .filter(field -> field.name.equals("trackingTopologyAdmission")
                        && field.desc.equals("L" + TRACKING_TOPOLOGY_ADMISSION_GATE + ";"))
                .toList();
        assertEquals(1, topologyAdmissionFields.size(),
                "the packaged runtime must own one exact tracker-topology admission lock");
        assertTrue((topologyAdmissionFields.getFirst().access & Opcodes.ACC_FINAL) != 0,
                "the topology admission lock identity must be final");
        MethodNode topologyAdmissionConstructor = uniqueMethod(regionizedServer,
                candidate -> candidate.name.equals("<init>")
                        && fieldCount(candidate, Opcodes.PUTFIELD, REGIONIZED_SERVER,
                        "trackingTopologyAdmission",
                        "L" + TRACKING_TOPOLOGY_ADMISSION_GATE + ";") == 1,
                "RegionizedServer topology-admission constructor");
        assertEquals(1, typeCount(topologyAdmissionConstructor, Opcodes.NEW,
                TRACKING_TOPOLOGY_ADMISSION_GATE));
        assertEquals(1, callCount(topologyAdmissionConstructor,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "<init>", "()V"));

        MethodNode enterTopologyRouting = method(regionizedServer,
                "enterTrackingTopologyRouting", "()V");
        int enterReadAcquire = callIndex(enterTopologyRouting,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "readLock", "()V");
        assertTrue(enterReadAcquire >= 0,
                "routing admission must acquire one striped read hold");
        assertEquals(1, fieldCount(enterTopologyRouting, Opcodes.GETFIELD,
                REGIONIZED_SERVER, "trackingTopologyAdmission",
                "L" + TRACKING_TOPOLOGY_ADMISSION_GATE + ";"));

        MethodNode exitTopologyRouting = method(regionizedServer,
                "exitTrackingTopologyRouting", "()V");
        int exitReadRelease = callIndex(exitTopologyRouting,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "readUnlock", "()V");
        assertTrue(exitReadRelease >= 0,
                "routing admission must release the same striped read hold");
        assertEquals(1, fieldCount(exitTopologyRouting, Opcodes.GETFIELD,
                REGIONIZED_SERVER, "trackingTopologyAdmission",
                "L" + TRACKING_TOPOLOGY_ADMISSION_GATE + ";"));

        MethodNode observeTopologyReceipt = method(regionizedServer,
                "trackingTopologyTransitionReceipt", topologyReceiptReadDesc);
        int observeReceipt = fieldIndex(observeTopologyReceipt, Opcodes.GETFIELD,
                REGIONIZED_SERVER, "trackingTopologyTransition",
                "L" + topologyReceipt + ";");
        int observeReadAuthority = callIndexAfter(observeTopologyReceipt,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "getReadHoldCount", "()I",
                observeReceipt);
        int observeOwner = fieldIndexAfter(observeTopologyReceipt, Opcodes.GETFIELD,
                topologyReceipt, "owner", "Ljava/lang/Thread;", observeReadAuthority);
        int observeThread = callIndexAfter(observeTopologyReceipt, "java/lang/Thread",
                "currentThread", "()Ljava/lang/Thread;", observeOwner);
        int observeMainAuthority = callIndexAfter(observeTopologyReceipt,
                REGIONIZED_SERVER, "currentThreadHoldsAllClaims", "()Z", observeThread);
        assertTrue(observeReceipt >= 0 && observeReadAuthority > observeReceipt
                        && observeOwner > observeReadAuthority
                        && observeThread > observeOwner
                        && observeMainAuthority > observeThread,
                "the exact receipt may be observed only under this thread's read admission, "
                        + "or by its physical-Main all-claims owner");

        MethodNode beginTopologyTransition = method(regionizedServer,
                "beginTrackingTopologyTransition", topologyReceiptReadDesc);
        int beginWriteAcquire = callIndex(beginTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeLock", "()V");
        int beginReceiptPublish = fieldIndexAfter(beginTopologyTransition,
                Opcodes.PUTFIELD, REGIONIZED_SERVER, "trackingTopologyTransition",
                "L" + topologyReceipt + ";", beginWriteAcquire);
        int beginWriteRelease = callIndexAfter(beginTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeUnlock", "()V",
                beginReceiptPublish);
        assertTrue(beginWriteAcquire >= 0 && beginReceiptPublish > beginWriteAcquire
                        && beginWriteRelease > beginReceiptPublish,
                "transition begin must drain old-map readers before publishing its exact receipt");
        assertEquals(2, callCount(beginTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeUnlock", "()V"),
                "transition begin must release W on success and failure");
        assertTrue(hasCatchAllCleanupAroundCall(beginTopologyTransition,
                        callIndexByName(beginTopologyTransition, topologyReceipt, "<init>"),
                        TRACKING_TOPOLOGY_ADMISSION_GATE, "writeUnlock", "()V"),
                "transition begin must release W if receipt construction/publication fails");

        String topologyReceiptArgDesc = "(L" + topologyReceipt + ";)V";
        MethodNode completeTopologyTransition = method(regionizedServer,
                "completeTrackingTopologyTransition", topologyReceiptArgDesc);
        int completeWriteAcquire = callIndex(completeTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeLock", "()V");
        int completeReceiptClear = fieldIndexAfter(completeTopologyTransition,
                Opcodes.PUTFIELD, REGIONIZED_SERVER, "trackingTopologyTransition",
                "L" + topologyReceipt + ";", completeWriteAcquire);
        int completeWriteRelease = callIndexAfter(completeTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeUnlock", "()V",
                completeReceiptClear);
        assertTrue(completeWriteAcquire >= 0
                        && completeReceiptClear > completeWriteAcquire
                        && completeWriteRelease > completeReceiptClear
                        && previousRealOpcode(completeTopologyTransition,
                        completeReceiptClear) == Opcodes.ACONST_NULL,
                "transition completion must compare identities, then clear under W before reopen");
        assertEquals(2, callCount(completeTopologyTransition,
                TRACKING_TOPOLOGY_ADMISSION_GATE, "writeUnlock", "()V"),
                "transition completion must release W on success and failure");
        assertEquals(0, callCountByName(beginTopologyTransition,
                REGIONIZED_SERVER, "mainHoldsAllClaims"));
        assertEquals(0, callCountByName(completeTopologyTransition,
                REGIONIZED_SERVER, "mainHoldsAllClaims"));

        MethodNode rebuildEntry = method(regionizedServer,
                "rebuildIslandsIfDirty", "()V");
        MethodNode rebuildTransaction = uniqueMethod(regionizedServer,
                candidate -> callCount(candidate, REGIONIZED_SERVER,
                        "beginTrackingTopologyTransition", topologyReceiptReadDesc) == 1
                        && callCount(candidate, REGIONIZED_SERVER,
                        "rebuildIslandsBody", "()V") == 1
                        && callCount(candidate, REGIONIZED_SERVER,
                        "completeTrackingTopologyTransition", topologyReceiptArgDesc) == 1,
                "exact admitted topology rebuild transaction");
        int transactionBegin = callIndex(rebuildTransaction, REGIONIZED_SERVER,
                "beginTrackingTopologyTransition", topologyReceiptReadDesc);
        int transactionDirtyClear = fieldIndexAfter(rebuildTransaction, Opcodes.PUTFIELD,
                REGIONIZED_SERVER, "islandsDirty", "Z", transactionBegin);
        int transactionRebuild = callIndexAfter(rebuildTransaction, REGIONIZED_SERVER,
                "rebuildIslandsBody", "()V", transactionDirtyClear);
        int transactionComplete = callIndexAfter(rebuildTransaction, REGIONIZED_SERVER,
                "completeTrackingTopologyTransition", topologyReceiptArgDesc,
                transactionRebuild);
        assertTrue(transactionBegin >= 0 && transactionDirtyClear > transactionBegin
                        && transactionRebuild > transactionDirtyClear
                        && transactionComplete > transactionRebuild
                        && previousRealOpcode(rebuildTransaction, transactionDirtyClear)
                        == Opcodes.ICONST_0,
                "one admitted lambda must publish the transition before dirty clear/snapshot, "
                        + "then compare-clear only after rebuildBody retired and rerouted all work");
        assertEquals(1, invokedynamicTargetCount(rebuildEntry, REGIONIZED_SERVER,
                rebuildTransaction.name, rebuildTransaction.desc),
                "rebuildIslandsIfDirty must submit the exact begin -> body -> complete lambda");
        int transactionFactory = invokedynamicTargetIndex(rebuildEntry,
                REGIONIZED_SERVER, rebuildTransaction.name, rebuildTransaction.desc);

        String temporalControl =
                "com/axalotl/fabrolia/common/regions/TemporalControl";
        MethodNode topologyCommittedTail = uniqueMethod(regionizedServer,
                candidate -> callCount(candidate, temporalControl,
                        "onTopologyCommitted", "(L" + REGIONIZED_SERVER + ";)V") == 1,
                "post-release topology pacing successor");
        assertEquals(1, invokedynamicTargetCount(rebuildEntry, REGIONIZED_SERVER,
                topologyCommittedTail.name, topologyCommittedTail.desc),
                "rebuildIslandsIfDirty must retain one distinct post-release pacing successor");
        int pacingFactory = invokedynamicTargetIndex(rebuildEntry,
                REGIONIZED_SERVER, topologyCommittedTail.name, topologyCommittedTail.desc);
        assertTrue(transactionFactory >= 0 && pacingFactory > transactionFactory,
                "topology pacing must follow the distinct begin -> body -> complete transaction");
        assertEquals(1, regionizedServer.methods.stream().mapToInt(candidate ->
                        callCount(candidate, REGIONIZED_SERVER,
                                "beginTrackingTopologyTransition",
                                topologyReceiptReadDesc)).sum(),
                "the packaged runtime must contain only one topology-transition begin caller");
        assertEquals(1, regionizedServer.methods.stream().mapToInt(candidate ->
                        callCount(candidate, REGIONIZED_SERVER,
                                "completeTrackingTopologyTransition",
                                topologyReceiptArgDesc)).sum(),
                "the packaged runtime must contain only one topology-transition completion caller");

        MethodNode pendingFallback = method(tracking,
                "fallbackPendingPairingsOnMain",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)V");
        int fallbackMonitor = opcodeIndex(pendingFallback, Opcodes.MONITORENTER);
        int fallbackHandoff = callIndexAfter(pendingFallback,
                ISLAND_ENTITY_TRACKING, "handoffMainPublicationToActiveOwnerLocked",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS
                        + ";Lnet/minecraft/world/entity/Entity;)Z", fallbackMonitor);
        int fallbackTerminal = callIndexAfter(pendingFallback,
                ISLAND_ENTITY_TRACKING, "publishAndPairUnderTrackerLock",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z",
                fallbackHandoff);
        assertTrue(fallbackMonitor >= 0 && fallbackHandoff > fallbackMonitor
                        && fallbackTerminal > fallbackHandoff,
                "packaged synchronous fallback must hand off active A5 before the fail-stop "
                        + "shared publish/pending-commit/cadence terminal helper");
        assertEquals(1, callCountByName(pendingFallback,
                ISLAND_ENTITY_TRACKING, "publishAndPairUnderTrackerLock"));
        assertEquals(0, callCountByName(pendingFallback,
                ISLAND_ENTITY_TRACKING, "publishUnderTrackerLock"));
        assertEquals(0, callCountByName(pendingFallback,
                ISLAND_ENTITY_TRACKING, "finishMainPublication"));
        assertEquals(0, callCountByName(pendingFallback,
                ISLAND_ENTITY_TRACKING, "activeIslandPlayerOwner"));
        assertEquals(0, callCountByName(pendingFallback,
                ISLAND_ENTITY_TRACKING, "scheduleMainPublication"));
        assertEquals(0, callCountByName(pendingFallback, SERVER_ENTITY, "sendChanges"),
                "fallback delegates one attempt and cannot replay sendChanges itself");

        String gapReceipt = A5_PLAYER_TICKS + "$TrackingTopologyGap";
        MethodNode gapSuccessor = method(tracking,
                "finishTopologyGapPublicationOnMain", "(L" + gapReceipt + ";)V");
        int successorPhysicalMain = callIndex(gapSuccessor, ISLAND_ENTITY_TRACKING,
                "physicalMain", "(Lnet/minecraft/world/entity/Entity;)Z");
        int successorClaim = callIndexAfter(gapSuccessor, REGION_SCHEDULER,
                "runWithMainEntityClaim",
                "(Lnet/minecraft/world/entity/Entity;Ljava/lang/Runnable;)V",
                successorPhysicalMain);
        assertTrue(successorPhysicalMain >= 0 && successorClaim > successorPhysicalMain,
                "the packaged receipt successor needs one physical-Main entity claim");
        MethodNode gapSuccessorBody = uniqueMethod(tracking,
                candidate -> callCountByName(candidate, A5_PLAYER_TICKS,
                        "trackingTopologyGapPublicationIfReady") == 1
                        && callCountByName(candidate, A5_PLAYER_TICKS,
                        "completeTrackingTopologyGap") == 1,
                "exact topology-gap Main successor body");
        int successorMonitor = opcodeIndex(gapSuccessorBody, Opcodes.MONITORENTER);
        int successorReady = callIndexAfter(gapSuccessorBody, A5_PLAYER_TICKS,
                "trackingTopologyGapPublicationIfReady",
                "(L" + gapReceipt + ";)Ljava/lang/Boolean;", successorMonitor);
        int successorLive = callIndexByNameAfter(gapSuccessorBody,
                ISLAND_ENTITY_TRACKING, "currentLivePlayerTrackerLocked", successorReady);
        int successorPublish = callIndexAfter(gapSuccessorBody, ISLAND_ENTITY_TRACKING,
                "publishUnderTrackerLock",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z", successorLive);
        int successorPending = callIndexAfter(gapSuccessorBody,
                ISLAND_TRACKED_ENTITY_ACCESS, "fabrolia$hasPendingIslandPairings",
                "()Z", successorPublish);
        int successorCommit = invokedynamicTargetIndex(gapSuccessorBody,
                ISLAND_TRACKED_ENTITY_ACCESS, "fabrolia$commitIslandPairings", "()V");
        int successorComplete = callIndexAfter(gapSuccessorBody, A5_PLAYER_TICKS,
                "completeTrackingTopologyGap", "(L" + gapReceipt + ";Z)V",
                successorCommit);
        assertTrue(successorMonitor >= 0 && successorReady > successorMonitor
                        && successorLive > successorReady
                        && successorPublish > successorLive
                        && successorPending > successorPublish
                        && successorCommit > successorPending
                        && successorComplete > successorCommit,
                "readiness precedes one optional send; its hard-clock duplicate may still "
                        + "commit staged membership, then the exact receipt completes");
        assertEquals(1, callCountByName(gapSuccessorBody,
                ISLAND_ENTITY_TRACKING, "publishUnderTrackerLock"));
        assertEquals(1, invokedynamicTargetCount(gapSuccessorBody,
                ISLAND_TRACKED_ENTITY_ACCESS, "fabrolia$commitIslandPairings", "()V"));
        assertEquals(1, callCountByName(gapSuccessorBody,
                A5_PLAYER_TICKS, "completeTrackingTopologyGap"));
        for (MethodNode gapBody : List.of(gapSuccessor, gapSuccessorBody)) {
            assertEquals(0, callCountByName(gapBody, ISLAND_ENTITY_TRACKING, "attach"));
            assertEquals(0, callCountByName(gapBody, ISLAND_ENTITY_TRACKING, "detach"));
            assertEquals(0, callCountByName(gapBody,
                    ISLAND_ENTITY_TRACKING, "scheduleMainPublication"));
            assertEquals(0, callCountByName(gapBody, A5_PLAYER_TICKS, "markMainFallback"));
            assertEquals(0, callCountByName(gapBody, A5_PLAYER_TICKS, "clearIsland"));
            assertEquals(0, callCountByName(gapBody,
                    "net/minecraft/server/level/ServerPlayer", "doTick"));
            assertEquals(0, callCountByName(gapBody,
                    "net/minecraft/server/network/ServerGamePacketListenerImpl", "tick"));
            assertEquals(0, callCountByName(gapBody,
                    "net/minecraft/server/MinecraftServer", "schedule"));
            assertEquals(0, typeCount(gapBody, Opcodes.NEW,
                    "net/minecraft/server/TickTask"));
        }


        assertFalse(tracking.methods.stream().anyMatch(candidate ->
                        candidate.name.equals("activeOwnerRecoveryNeedsRetryLocked")
                                || candidate.name.equals("retryMainClockLocked")
                                || candidate.name.equals("mainWorkOrClockLocked")
                                || candidate.name.equals("scheduleMainPublication")
                                && candidate.desc.endsWith(";Z)V")),
                "packaged tracking owns no later-tick retry decision or overload");

        MethodNode postPhysics = method(tracking,
                "publishPendingRealPlayerAfterPhysics", "(L" + A5_PLAYER_TICKS
                        + "$PendingLate;Lcom/axalotl/fabrolia/common/regions/"
                        + "IslandRegistry$PIsland;)L" + ISLAND_ENTITY_TRACKING
                        + "$PostPhysicsResult;");
        assertEquals(1, callCountByName(postPhysics, ISLAND_ENTITY_TRACKING,
                        "publishAndPairUnderTrackerLock"),
                "an exact pending real-player token emits one post-physics generation");
        assertEquals(2, callCountByName(postPhysics, ISLAND_ENTITY_TRACKING,
                        "publishCrossOwnerStateUnderTrackerLock"),
                "soft handoff and spatial crossing each publish post-friction state once");
        assertEquals(0, callCountByName(postPhysics, ISLAND_TRACKED_ENTITY_ACCESS,
                        "fabrolia$commitIslandPairings"),
                "a source permit may not commit cross-owner staged viewer membership");
        MethodNode crossingScope = method(tracking,
                "publishCrossOwnerStateUnderTrackerLock",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z");
        assertEquals(1, callCount(crossingScope, "java/lang/Thread", "holdsLock",
                "(Ljava/lang/Object;)Z"));
        assertEquals(1, callCountByName(crossingScope, "java/lang/ThreadLocal", "get"));
        assertEquals(1, callCountByName(crossingScope, "java/lang/ThreadLocal", "set"));
        assertEquals(1, callCountByName(crossingScope,
                "java/lang/ThreadLocal", "remove"),
                "the consolidated cleanup path clears the crossing capability exactly once");
        assertEquals(1, callCountByName(crossingScope, ISLAND_ENTITY_TRACKING,
                "publishUnderTrackerLock"));
        assertEquals(1, callCountByName(postPhysics,
                        "com/axalotl/fabrolia/common/regions/RegionizedServer",
                        "islandOwnerOfChunk"),
                "post-physics publication must fail closed after an owner crossing");
        assertEquals(0, callCountByName(postPhysics, ISLAND_ENTITY_TRACKING, "detach"));
        assertEquals(0, callCountByName(postPhysics, ISLAND_ENTITY_TRACKING, "attach"));
        int immutableProof = callIndexByName(postPhysics, ISLAND_ENTITY_TRACKING,
                "pendingRealPlayerAfterPhysicsInvariantLocked");
        int lifecycleProof = callIndexByName(postPhysics, ISLAND_ENTITY_TRACKING,
                "explicitPostPhysicsLifecycleCutLocked");
        int softMutableProof = callIndexByName(postPhysics, ISLAND_ENTITY_TRACKING,
                "pendingRealPlayerAfterPhysicsPublicationCurrentLocked");
        int mutableProof = callIndexByName(postPhysics, ISLAND_ENTITY_TRACKING,
                "pendingRealPlayerAfterPhysicsCurrentLocked");
        int softPublish = callIndexAfter(postPhysics, ISLAND_ENTITY_TRACKING,
                "publishCrossOwnerStateUnderTrackerLock",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z", mutableProof);
        int softHandoff = fieldIndexAfter(postPhysics, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING + "$PostPhysicsResult", "HANDOFF",
                "L" + ISLAND_ENTITY_TRACKING + "$PostPhysicsResult;", softPublish);
        int ownerLookup = callIndexByName(postPhysics,
                "com/axalotl/fabrolia/common/regions/RegionizedServer",
                "islandOwnerOfChunk");
        int crossingPublish = callIndexAfter(postPhysics, ISLAND_ENTITY_TRACKING,
                "publishCrossOwnerStateUnderTrackerLock",
                "(L" + ISLAND_TRACKED_ENTITY_ACCESS + ";)Z", ownerLookup);
        int ownerlessHandoff = fieldIndexAfter(postPhysics, Opcodes.GETSTATIC,
                ISLAND_ENTITY_TRACKING + "$PostPhysicsResult", "HANDOFF",
                "L" + ISLAND_ENTITY_TRACKING + "$PostPhysicsResult;",
                crossingPublish);
        assertTrue(immutableProof >= 0 && lifecycleProof > immutableProof
                        && softMutableProof > lifecycleProof
                        && mutableProof > softMutableProof
                        && softPublish > mutableProof && softHandoff > softPublish
                        && ownerLookup > softHandoff && crossingPublish > ownerLookup
                        && ownerlessHandoff > crossingPublish,
                "both soft and ownerless handoffs must publish post-friction state after "
                        + "authority validation and before selecting HANDOFF");

        MethodNode invariant = method(tracking,
                "pendingRealPlayerAfterPhysicsInvariantLocked", "(L"
                        + A5_PLAYER_TICKS + "$PendingLate;Lcom/axalotl/fabrolia/common/"
                        + "regions/IslandRegistry$PIsland;)Z");
        assertEquals(1, callCountByName(invariant,
                        "com/axalotl/fabrolia/common/regions/IslandPlayerTurnSession",
                        "isRunningRealLate"),
                "HANDOFF must retain the exact active late-token proof");
        String ownerStamp = "com/axalotl/fabrolia/common/regions/"
                + "IslandPlayerTurnSession$OwnerStamp";
        for (String ownerField : List.of("playerIdentity", "listenerIdentity",
                "islandIdentity", "islandId", "islandGeneration", "domainIdentity", "kind")) {
            assertEquals(1, callCountByName(invariant, ownerStamp, ownerField),
                    "immutable late authority must pin OwnerStamp." + ownerField);
        }
        assertEquals(1, callCountByName(invariant, ownerStamp, "matchesExactly"));
        assertEquals(1, callCountByName(invariant, A5_LISTENER_STATE_ACCESS,
                "fabrolia$asyncIslandPlayerState"));
        assertEquals(0, callCountByName(invariant,
                "com/axalotl/fabrolia/common/regions/PlayerTickPhaseStamp",
                "fabrolia$playerLifecycleEpoch"));
        assertEquals(0, callCountByName(invariant, ownerStamp, "lifecycleEpoch"));
        assertTrue(calls(invariant).stream().noneMatch(call -> List.of(
                        "level", "isRemoved", "isChangingDimension", "hasDisconnected",
                        "hasClientLoaded").contains(call.name)),
                "immutable proof must precede every mutable death/listener observation");
        assertEquals(0, callCountByName(invariant, A5_PLAYER_TICKS,
                "supportsIslandPlayerType"));
        assertEquals(0, callCountByName(invariant, A5_PLAYER_TICKS, "isCarpetFake"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                SERVER_PLAYER, "connection",
                "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "eligible", "Z"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "lifecycleInvalidated", "Z"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "mainDisconnectPending", "Z"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "auditedLifecycleMutationPending", "Z"));
        assertEquals(0, fieldCount(invariant, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "hardLifecycleExitPending", "Z"));
        MethodNode lifecycleCut = method(tracking,
                "explicitPostPhysicsLifecycleCutLocked", "(L"
                        + A5_PLAYER_TICKS + "$PendingLate;)Z");
        assertEquals(1, fieldCount(lifecycleCut, Opcodes.GETFIELD,
                A5_PLAYER_TICKS + "$PlayerState", "hardLifecycleExitPending", "Z"));
        assertEquals(1, fieldCount(lifecycleCut, Opcodes.GETFIELD,
                A5_PLAYER_TICKS + "$PlayerState", "mainDisconnectPending", "Z"));
        assertEquals(1, calls(lifecycleCut).stream()
                .filter(call -> call.name.equals("getRemovalReason")).count());
        assertEquals(0, calls(lifecycleCut).stream()
                .filter(call -> call.name.equals("isRemoved")).count(),
                "portalPending removal spoof is not terminal lifecycle authority");
        assertEquals(0, callCountByName(lifecycleCut,
                        "com/axalotl/fabrolia/common/regions/RegionizedServer",
                        "islandOwnerOfChunk"),
                "an unexplained tracker/owner mismatch must remain INVALID");
        MethodNode authorityModel = method(tracking,
                "classifyPostPhysicsAuthority", "(ZZZZ)L" + ISLAND_ENTITY_TRACKING
                        + "$PostPhysicsAuthority;");
        assertEquals(0, calls(authorityModel).size(),
                "the lifecycle-priority authority model must stay Minecraft-free");
        MethodNode beforeToken = method(tracking,
                "pendingRealPlayerBeforeLateCurrentLocked", "(L" + A5_PLAYER_TICKS
                        + "$PendingLate;Lcom/axalotl/fabrolia/common/regions/"
                        + "IslandRegistry$PIsland;)Z");
        assertEquals(1, callCountByName(beforeToken, A5_PLAYER_TICKS,
                        "pendingRealPlayerBeforeLateInvariantLocked"),
                "general flush must exclude every immutable queued PendingLate, including hard "
                        + "death/removal which may not publish mutable state");
        MethodNode publicationIdentity = method(tracking,
                "pendingRealPlayerAfterPhysicsPublicationIdentityCurrentLocked", "(L"
                        + A5_PLAYER_TICKS + "$PendingLate;Lcom/axalotl/fabrolia/common/"
                        + "regions/IslandRegistry$PIsland;)Z");
        assertEquals(1, fieldCount(publicationIdentity, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "auditedLifecycleMutationPending", "Z"));
        assertEquals(1, fieldCount(publicationIdentity, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "hardLifecycleExitPending", "Z"));
        MethodNode mutableIdentity = method(tracking,
                "pendingRealPlayerIdentityCurrentLocked", "(L" + A5_PLAYER_TICKS
                        + "$PendingLate;Lcom/axalotl/fabrolia/common/regions/"
                        + "IslandRegistry$PIsland;ZZ)Z");
        assertEquals(1, callCountByName(mutableIdentity, A5_PLAYER_TICKS,
                "lifecycleEpochMatchesPendingAuthority"));
        assertEquals(1, calls(mutableIdentity).stream()
                .filter(call -> call.name.equals("getRemovalReason")).count());
        MethodNode afterToken = method(tracking,
                "pendingRealPlayerAfterPhysicsCurrentLocked", "(L" + A5_PLAYER_TICKS
                        + "$PendingLate;Lcom/axalotl/fabrolia/common/regions/"
                        + "IslandRegistry$PIsland;)Z");
        assertEquals(1, callCountByName(afterToken,
                        "com/axalotl/fabrolia/common/regions/IslandPlayerTurnSession",
                        "isRunningRealLate"),
                "post-physics publication must retain the exact LATE_RUNNING permit proof");
        MethodNode flush = method(tracking, "flush",
                "(Lcom/axalotl/fabrolia/common/regions/RegionizedServer;Lcom/axalotl/"
                        + "fabrolia/common/regions/IslandRegistry$PIsland;)V");
        assertEquals(1, callCountByName(flush, ISLAND_ENTITY_TRACKING,
                        "publishCurrent"),
                "general flush must route each non-deferred tracker through exact validation");
        assertEquals(0, callCountByName(flush, ISLAND_ENTITY_TRACKING,
                        "publishContained"),
                "publication failures must remain fatal rather than quarantine-and-continue");

        MethodNode staged = generatedMethod(nearby,
                "$fabrolia$captureStagedUpdate");
        MethodNode initial = generatedMethod(nearby,
                "$fabrolia$captureInitialTrackerPlayers");
        for (MethodNode directVmpBulkCaller : List.of(staged, initial)) {
            assertEquals(0, directVmpBulkCaller.access & Opcodes.ACC_SYNCHRONIZED,
                    "installed VMP bulk capture is not itself a tracker-monitor owner");
            assertEquals(0, opcodeCount(directVmpBulkCaller, Opcodes.MONITORENTER),
                    "the direct VMP caller must remain reachable without a tracker monitor; "
                            + "the bulk seam/router owns that authority");
            assertEquals(1, typeCount(directVmpBulkCaller, Opcodes.INSTANCEOF,
                    ISLAND_TRACKED_ENTITY_ACCESS),
                    "installed VMP must prove the tracker duck before calling the bulk seam");
            assertEquals(1, callCountByName(directVmpBulkCaller,
                    ISLAND_ENTITY_TRACKING, "stageBulkPairingRefreshIfOwned"));
        }
        MethodNode stagedBody = uniqueMethod(nearby,
                candidate -> invokedynamicTargetCount(staged, nearby.name,
                        candidate.name, candidate.desc) > 0
                        && callCount(candidate, OPERATION, "call",
                        OPERATION_CALL_DESC) == 1,
                "VMP staged update exact original success body");
        assertEquals(1, callCount(stagedBody, OPERATION, "call", OPERATION_CALL_DESC));
        MethodNode initialBody = uniqueMethod(nearby,
                candidate -> invokedynamicTargetCount(initial, nearby.name,
                        candidate.name, candidate.desc) > 0
                        && callCount(candidate, OPERATION, "call",
                        OPERATION_CALL_DESC) == 1,
                "VMP initial tracker exact original success body");
        assertEquals(1, callCount(initialBody, OPERATION, "call", OPERATION_CALL_DESC));
        assertEquals(2, callCountByName(method(nearby, "tickStaging", "()V"),
                        nearby.name, staged.name),
                "both installed VMP tickStaging updatePlayers sites must be intercepted");
        String addDesc = "(Lnet/minecraft/server/level/ChunkMap$TrackedEntity;)V";
        MethodNode add = method(nearby, "addEntityTracker", addDesc);
        MethodNode addLock = generatedMethod(nearby, "$fabrolia$lockAdd");
        assertEquals(1, callCount(add, nearby.name, addLock.name, addLock.desc),
                "VMP addEntityTracker must retain its outer mutation lock");
        MethodNode addLockBody = uniqueMethod(nearby,
                candidate -> invokedynamicTargetCount(addLock, nearby.name,
                        candidate.name, candidate.desc) > 0
                        && callCount(candidate, OPERATION, "call",
                        OPERATION_CALL_DESC) == 1,
                "VMP outer mutation-lock exact original success body");
        assertEquals(1, callCount(addLockBody, OPERATION, "call", OPERATION_CALL_DESC),
                "the outer mutation lock must retain the generated original body");
        assertTrue(opcodeIndex(addLockBody, Opcodes.MONITORENTER)
                        < callIndex(addLockBody, OPERATION, "call", OPERATION_CALL_DESC)
                        && opcodeIndex(addLockBody, Opcodes.MONITORENTER) >= 0,
                "the composed VMP add body must enter its mutation monitor before vanilla");
        MethodNode addBody = uniqueMethod(nearby, candidate ->
                        candidate.name.startsWith("addEntityTracker$mixinextras$wrapped$")
                                && candidate.desc.equals(addDesc)
                                && callCount(candidate, nearby.name,
                                initial.name, initial.desc) == 1,
                "VMP addEntityTracker MixinExtras wrapped body owning the initial fan-out");
        assertEquals(1, callCount(addBody, nearby.name, initial.name, initial.desc),
                "VMP's initial all-player fan-out must collapse before entity reads");
    }


    /** Final merged ghost-block/prediction ordering proof. */
    private static void verifyBlockPublicationComposition(
            ClassNode cache, ClassNode chunkMap, ClassNode playerMap, ClassNode sender,
            ClassNode a5,
            ClassNode moveSnapshot) {
        MethodNode blockChanged = method(cache, "blockChanged",
                "(Lnet/minecraft/core/BlockPos;)V");
        MethodNode queue = generatedMethod(cache,
                "$fabrolia$queueIslandBlockUpdate");
        assertEquals(1, callCount(blockChanged, cache.name, queue.name, queue.desc));
        int correction = callIndex(queue, A5_PLAYER_TICKS,
                "recordPredictedBlockChange", "(Lnet/minecraft/core/BlockPos;)V");
        int runtime = callIndex(queue,
                "com/axalotl/fabrolia/common/regions/RegionScheduler", "forLevel",
                "(Lnet/minecraft/server/level/ServerLevel;)"
                        + "Lcom/axalotl/fabrolia/common/regions/RegionizedServer;");
        assertTrue(correction >= 0 && runtime > correction,
                "successful blockChanged must journal the exact actor correction first");

        MethodNode global = generatedMethod(cache,
                "$fabrolia$losslessGlobalBlockUpdateDrain");
        assertEquals(1, callCount(method(cache, "broadcastChangedChunks",
                        "(Lnet/minecraft/util/profiling/ProfilerFiller;)V"),
                cache.name, global.name, global.desc));
        assertEquals(0, callCountByName(global, "java/util/Set", "clear"));
        assertEquals(0, callCount(global, OPERATION, "call", OPERATION_CALL_DESC));
        assertEquals(1, typeCount(global, Opcodes.NEW, "java/util/ArrayList"));
        MethodNode publish = generatedMethod(cache, "fabrolia$publishChangedHolder");
        int enter = opcodeIndex(publish, Opcodes.MONITORENTER);
        int claim = callIndex(publish, "java/util/Set", "remove",
                "(Ljava/lang/Object;)Z");
        int dirty = callIndex(publish, "net/minecraft/server/level/ChunkHolder",
                "hasChangesToBroadcast", "()Z");
        int broadcast = callIndex(publish, "net/minecraft/server/level/ChunkHolder",
                "broadcastChanges",
                "(Lnet/minecraft/world/level/chunk/LevelChunk;)V");
        assertTrue(enter >= 0 && claim > enter && dirty > claim && broadcast > dirty,
                "remove-to-claim and holder broadcast must share the exact holder monitor");
        assertEquals(1, callCountByName(publish, "java/util/Set", "add"),
                "only a new dirty generation observed after a successful broadcast may be queued; "
                        + "a failed entered broadcast has no retry token");
        MethodNode flush = generatedMethod(cache,
                "fabrolia$flushIslandBlockUpdates");
        MethodNode flushOne = generatedMethod(cache,
                "fabrolia$flushOneIslandBlockUpdate");
        MethodNode flushBody = uniqueMethod(cache,
                candidate -> invokedynamicTargetCount(flush, cache.name,
                        candidate.name, candidate.desc) > 0
                        && callCountByName(candidate, cache.name, flushOne.name) == 1,
                "island block-update exact per-holder success body");
        assertEquals(1, callCountByName(flushBody, cache.name, flushOne.name));
        assertEquals(1, callCountByName(flushOne, cache.name, publish.name));
        assertEquals(1, typeCount(flush, Opcodes.NEW, "java/util/ArrayList"),
                "one island tail must inspect a bounded holder-hint snapshot");
        assertEquals(0, typeCount(flushOne, Opcodes.NEW, "java/lang/IllegalStateException"),
                "a demoted holder must not abort the island or defer its direct actor ACK");
        assertEquals(2, callCount(flushOne, "java/util/Set", "add",
                "(Ljava/lang/Object;)Z"),
                "one add transfers a topology-moved holder and one retains a genuine residual "
                        + "dirty generation for the next bounded island tail");

        MethodNode release = method(chunkMap, "releaseGeneration",
                "(L" + GENERATION_CHUNK_HOLDER + ";)V");
        assertTrue((release.access & Opcodes.ACC_PUBLIC) != 0,
                "the exact public generation-release seam changed");
        assertEquals(0, release.access & Opcodes.ACC_SYNCHRONIZED,
                "atomic generation-reference release must not acquire ChunkMap");
        assertEquals(0, opcodeCount(release, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(release, Opcodes.MONITOREXIT));
        assertEquals(1, callCount(release, GENERATION_CHUNK_HOLDER,
                "decreaseGenerationRefCount", "()V"),
                "releaseGeneration must remain the exact atomic holder decrement");
        assertEquals(1, calls(release).size(),
                "releaseGeneration gained work requiring a fresh lock-order audit");
        assertEquals(0, chunkMap.methods.stream()
                        .filter(candidate -> candidate != release)
                        .filter(candidate -> candidate.name.contains("releaseGeneration"))
                        .filter(candidate -> candidate.desc.contains("L" + OPERATION + ";"))
                        .count(),
                "the removed releaseGeneration Operation wrapper was regenerated");

        MethodNode move = assertConditionalPlayerMoveOperationRoute(chunkMap,
                "move", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                "fabrolia$syncPlayerMove");
        verifyMoveDecisionSnapshotComposition(chunkMap, move, moveSnapshot);
        MethodNode status = assertSynchronizedOperationRoute(chunkMap,
                "updatePlayerStatus", "(Lnet/minecraft/server/level/ServerPlayer;Z)V",
                "fabrolia$syncPlayerMapMembership");
        MethodNode viewers = assertOperationRoute(chunkMap, "getPlayers",
                "(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;",
                "fabrolia$snapshotBroadcastPlayers");
        MethodNode spawning = assertOperationRoute(chunkMap,
                "getPlayersCloseForSpawning",
                "(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;",
                "fabrolia$snapshotPlayersCloseForSpawning");
        for (MethodNode reader : List.of(viewers, spawning)) {
            assertEquals(0, reader.access & Opcodes.ACC_SYNCHRONIZED,
                    "holder-side viewer reads must not own ChunkMap's method monitor");
            assertEquals(0, opcodeCount(reader, Opcodes.MONITORENTER),
                    "holder-side viewer reads must not enter a replacement monitor");
            assertEquals(0, opcodeCount(reader, Opcodes.MONITOREXIT),
                    "holder-side viewer reads must not exit a replacement monitor");
            assertEquals(1, callCount(reader, "java/util/List", "copyOf",
                    "(Ljava/util/Collection;)Ljava/util/List;"),
                    "every compatibility viewer list must remain detached");
        }
        assertEquals(2, callCount(move, OPERATION, "call", OPERATION_CALL_DESC),
                "same-section and transition branches must both retain vanilla move");
        assertEquals(1, callCount(status, OPERATION, "call", OPERATION_CALL_DESC));

        MethodNode allPlayers = assertSynchronizedOperationRoute(playerMap,
                "getAllPlayers", "()Ljava/util/Set;",
                "fabrolia$snapshotAllPlayers");
        assertEquals(1, typeCount(allPlayers, Opcodes.NEW, IDENTITY_SET),
                "vanilla PlayerMap must allocate one detached identity snapshot");
        assertEquals(1, callCount(allPlayers, IDENTITY_SET, "<init>", "(I)V"),
                "the PlayerMap snapshot must be pre-sized from its live view");
        assertEquals(1, callCount(allPlayers, IDENTITY_SET, "addAll",
                "(Ljava/util/Collection;)Z"),
                "the PlayerMap snapshot must preserve identity and iteration order");
        assertEquals(0, callCount(allPlayers, "java/util/Set", "copyOf",
                "(Ljava/util/Collection;)Ljava/util/Set;"),
                "Set.copyOf must not replace the identity-preserving snapshot");
        for (List<String> route : List.of(
                List.of("addPlayer", "(Lnet/minecraft/server/level/ServerPlayer;Z)V",
                        "fabrolia$lockedAddPlayer"),
                List.of("removePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                        "fabrolia$lockedRemovePlayer"),
                List.of("ignorePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                        "fabrolia$lockedIgnorePlayer"),
                List.of("unIgnorePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                        "fabrolia$lockedUnIgnorePlayer"),
                List.of("ignoredOrUnknown", "(Lnet/minecraft/server/level/ServerPlayer;)Z",
                        "fabrolia$lockedIgnoredOrUnknown"),
                List.of("ignored", "(Lnet/minecraft/server/level/ServerPlayer;)Z",
                        "fabrolia$lockedIgnored"))) {
            MethodNode guarded = assertSynchronizedOperationRoute(playerMap,
                    route.get(0), route.get(1), route.get(2));
            assertEquals(1, callCount(guarded, OPERATION, "call", OPERATION_CALL_DESC),
                    route.get(0) + " must retain one pure PlayerMap operation");
        }

        MethodNode pending = generatedMethod(sender,
                "$fabrolia$isPendingConcurrent");
        assertEquals(1, callCount(method(sender, "isPending", "(J)Z"),
                sender.name, pending.name, pending.desc));
        assertEquals(1, callCount(pending, CHUNK_DELIVERY, "isPending",
                "(Lnet/minecraft/server/level/ServerPlayer;J)Z"));
        assertEquals(0, fieldCount(pending, Opcodes.GETFIELD, sender.name,
                "pendingChunks", "Lit/unimi/dsi/fastutil/longs/LongSet;"),
                "off-main pending checks must never read vanilla's plain LongSet");


        MethodNode record = method(a5, "recordPredictedBlockChange",
                "(Lnet/minecraft/core/BlockPos;)V");
        assertEquals(2, callCount(record, "java/lang/ThreadLocal", "get",
                "()Ljava/lang/Object;"));
        assertEquals(1, callCountByName(record,
                "java/util/concurrent/ConcurrentLinkedQueue", "add"));

        MethodNode topologyHandoffBody = method(a5,
                "schedulePostPhysicsMainHandoff", "(L" + A5_PLAYER_TICKS
                        + "$PendingLate;)V");
        int orderedClose = callIndexByName(topologyHandoffBody, A5_PLAYER_TICKS,
                "scheduleMainHandoffLocked");
        assertEquals(1, fieldCount(topologyHandoffBody, Opcodes.PUTFIELD,
                A5_PLAYER_STATE, "eligible", "Z"));
        assertEquals(1, fieldCount(topologyHandoffBody, Opcodes.PUTFIELD,
                A5_PLAYER_STATE, "lifecycleInvalidated", "Z"));
        assertEquals(1, fieldCount(topologyHandoffBody, Opcodes.GETFIELD,
                A5_PLAYER_STATE, "mainDisconnectPending", "Z"));
        assertTrue(orderedClose >= 0
                        && previousRealOpcode(topologyHandoffBody, orderedClose)
                        == Opcodes.ICONST_0,
                "packaged contained late turn must hand off with scheduleEntityTick=false");
        assertEquals(0, callCountByName(topologyHandoffBody,
                "com/axalotl/fabrolia/common/ParallelProcessor",
                "tickEntityVanillaGuarded"));
        assertEquals(0, callCountByName(topologyHandoffBody,
                "net/minecraft/server/network/ServerGamePacketListenerImpl", "tick"));
        assertEquals(0, fieldCount(topologyHandoffBody, Opcodes.GETSTATIC,
                A5_PLAYER_TICKS, "FAILURES", "Ljava/util/concurrent/atomic/LongAdder;"),
                "packaged contained topology/lifecycle loss must not increment failures");
    }

    /** Final Mixin composition of the bounded chunk-batch ACK ownership transfer. */
    private static void verifyChunkBatchAckComposition(ClassNode sender, ClassNode listener,
                                                       ClassNode replay, ClassNode delivery,
                                                       ClassNode batchNode, ClassNode recNode) {
        MethodNode target = method(sender, "onChunkBatchReceivedByClient", "(F)V");
        MethodNode publicSend = method(sender, "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        for (MethodNode publicSurface : List.of(target, publicSend)) {
            assertEquals(0, publicSurface.access & Opcodes.ACC_SYNCHRONIZED,
                    publicSurface.name + " must not hold sender across ACK/world/network work");
            assertEquals(0, opcodeCount(publicSurface, Opcodes.MONITORENTER),
                    publicSurface.name + " must remain monitor-free after full composition");
            assertEquals(0, opcodeCount(publicSurface, Opcodes.MONITOREXIT),
                    publicSurface.name + " must remain monitor-free after full composition");
        }
        MethodNode route = generatedMethod(sender, "$fabrolia$routeChunkBatchAck");
        assertEquals(1, callCount(target, sender.name, route.name, route.desc),
                "public ACK target must enter exactly one Fabrolia venue wrapper");
        int permit = callIndexByName(route, CHUNK_BATCH_ACK_REPLAY, "consume");
        int original = callIndex(route, OPERATION, "call", OPERATION_CALL_DESC);
        int enqueue = callIndexByName(route, sender.name,
                "fabrolia$enqueueChunkBatchAckBits");
        assertTrue(permit >= 0 && permit < original && original < enqueue,
                "the internal replay permit must precede transformed vanilla, and external "
                        + "ACKs must retain their mailbox route");
        assertEquals(1, callCountByName(route, CHUNK_BATCH_ACK_REPLAY, "consume"));
        assertEquals(1, callCount(route, OPERATION, "call", OPERATION_CALL_DESC));
        assertEquals(1, sender.methods.stream()
                        .mapToInt(candidate -> callCountByName(candidate,
                                CHUNK_DELIVERY, "onAck")).sum(),
                "the admitted ACK tail must retire the delivery ledger exactly once");

        MethodNode c2meTail = uniqueMethod(sender,
                candidate -> candidate.name.contains("c2me-notickvd$modifyBatchSize"),
                "C2ME chunk ACK desired-rate TAIL");
        MethodNode scalarBody = uniqueMethod(sender,
                candidate -> callCount(candidate, sender.name, c2meTail.name, c2meTail.desc) == 1,
                "wrapped vanilla ACK scalar body containing C2ME TAIL");
        assertEquals(2, fieldCount(scalarBody, Opcodes.PUTFIELD, sender.name,
                "unacknowledgedBatches", "I"));
        assertEquals(1, fieldCount(scalarBody, Opcodes.PUTFIELD, sender.name,
                "desiredChunksPerTick", "F"));
        assertEquals(1, fieldCount(scalarBody, Opcodes.PUTFIELD, sender.name,
                "batchQuota", "F"));
        assertEquals(1, fieldCount(scalarBody, Opcodes.PUTFIELD, sender.name,
                "maxUnacknowledgedBatches", "I"));
        assertEquals(1, fieldCount(c2meTail, Opcodes.PUTFIELD, sender.name,
                "desiredChunksPerTick", "F"),
                "C2ME scaling must remain inside Operation.call's transformed original");
        assertEquals(0, opcodeCount(scalarBody, Opcodes.ATHROW));
        assertEquals(0, opcodeCount(c2meTail, Opcodes.ATHROW));

        for (String transaction : List.of("fabrolia$enqueueChunkBatchAckBits",
                "fabrolia$takeChunkBatchAcks", "fabrolia$discardChunkBatchAcks")) {
            MethodNode primitive = generatedMethod(sender, transaction);
            assertTrue((primitive.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                    transaction + " must use the exact sender monitor");
            assertEquals(0, calls(primitive).size(),
                    transaction + " must contain primitive operations only");
        }
        MethodNode discard = generatedMethod(sender, "fabrolia$discardChunkBatchAcks");
        assertEquals(1, fieldCount(discard, Opcodes.PUTFIELD, sender.name,
                "fabrolia$ackMailboxClosed", "Z"),
                "disconnect must permanently reject stale post-terminal ACKs");

        MethodNode drain = generatedMethod(sender, "fabrolia$replayChunkBatchAcksOnMain");
        assertEquals(0, callCountByName(drain, CHUNK_BATCH_ACK_REPLAY, "replay"));
        assertEquals(0, callCountByName(drain, CHUNK_DELIVERY, "onAck"));
        MethodNode send = generatedMethod(sender, "$fabrolia$send");
        int fatalGate = callIndexByName(send, REGION_SCHEDULER,
                "throwWorkerFailureIfAny");
        int snapshot = callIndexByName(send, sender.name,
                "fabrolia$takeChunkBatchAcks");
        int completion = callIndexByName(send, CHUNK_DELIVERY,
                "applyCompletedChunkBatch");
        int successSequence = callIndexByName(send, sender.name,
                "fabrolia$applyCompletedAndReplay");
        int gate = fieldIndex(send, Opcodes.GETFIELD, sender.name,
                "unacknowledgedBatches", "I");
        int batch = callIndexByName(send, CHUNK_DELIVERY, "sendNextChunks");
        assertTrue(fatalGate >= 0 && snapshot > fatalGate && completion > snapshot
                        && successSequence > completion
                        && successSequence < gate && gate < batch,
                "Main must fail-stop before ACK mutation, then cut intent, commit real "
                        + "EventLoop completion, and replay that snapshot before the next offer");

        MethodNode sequence = generatedMethod(sender, "fabrolia$applyCompletedAndReplay");
        int completionCut = callIndexByName(sequence, REGION_SCHEDULER,
                "commitSuccessIfHealthy");
        int drainIndex = callIndexByName(sequence, sender.name, drain.name);
        int refillCut = callIndexAfter(sequence, REGION_SCHEDULER,
                "commitSuccessIfHealthy", "(Ljava/lang/Runnable;)V", drainIndex);
        assertTrue(completionCut >= 0 && drainIndex > completionCut
                        && refillCut > drainIndex,
                "fixed pacing writes and transformed ACK bodies must use separate cuts");

        MethodNode replayMethod = method(replay, "replay",
                "(L" + PLAYER_CHUNK_SENDER + ";F)V");
        int physical = callIndexByName(replayMethod, PLAYER_TICKS,
                "isPhysicalServerThread");
        int arm = callIndexByName(replayMethod, "java/lang/ThreadLocal", "set");
        int reenter = callIndexByName(replayMethod, PLAYER_CHUNK_SENDER,
                "onChunkBatchReceivedByClient");
        int cleanup = callIndexByName(replayMethod, "java/lang/ThreadLocal", "remove");
        assertTrue(physical >= 0 && physical < arm && arm < reenter && reenter < cleanup,
                "replay helper must assert physical Main and finally clear exact-sender permit");
        assertEquals(2, callCountByName(replayMethod, "java/lang/ThreadLocal", "set"));
        MethodNode consume = method(replay, "consume", "(L" + PLAYER_CHUNK_SENDER + ";)Z");
        assertTrue(callIndexByName(consume, "java/lang/ThreadLocal", "get")
                        < callIndexByName(consume, "java/lang/ThreadLocal", "remove"),
                "one-shot permit must be removed before transformed original.call");

        MethodNode listenerHandler = method(listener, "handleChunkBatchReceived",
                "(L" + CHUNK_BATCH_ACK_PACKET + ";)V");
        assertEquals(1, callCount(listenerHandler, PLAYER_CHUNK_SENDER,
                "onChunkBatchReceivedByClient", "(F)V"));
        assertEquals(0, callCountByName(listenerHandler, CHUNK_DELIVERY, "onAck"));
        assertEquals(0, listener.methods.stream()
                        .filter(candidate -> candidate.name.contains("fabrolia$ack"))
                        .count(),
                "listener-level ACK hook would bypass or double-retire the sender wrapper");

        verifyChunkPacingWriterCensus(sender, scalarBody, c2meTail, publicSend, send);
        verifyChunkDeliveryAckIdentity(delivery, batchNode, recNode);
    }

    /** No compatibility transform may introduce a second pacing-field owner. */
    private static void verifyChunkPacingWriterCensus(ClassNode sender,
                                                       MethodNode scalarBody,
                                                       MethodNode c2meTail,
                                                       MethodNode publicSend,
                                                       MethodNode fabroliaSend) {
        MethodNode constructor = method(sender, "<init>", "(Z)V");
        MethodNode completedWrites = uniqueMethod(sender,
                candidate -> candidate.name.contains(
                        "lambda$fabrolia$applyCompletedAndReplay$")
                        && fieldCount(candidate, Opcodes.PUTFIELD, sender.name,
                        "unacknowledgedBatches", "I") == 1,
                "completed-batch pacing success body");
        MethodNode refillWrites = uniqueMethod(sender,
                candidate -> candidate.name.contains(
                        "lambda$fabrolia$applyCompletedAndReplay$")
                        && fieldCount(candidate, Opcodes.PUTFIELD, sender.name,
                        "batchQuota", "F") == 1
                        && fieldCount(candidate, Opcodes.PUTFIELD, sender.name,
                        "unacknowledgedBatches", "I") == 0,
                "post-ACK quota-refill success body");
        List<MethodNode> allowed = List.of(
                constructor, scalarBody, c2meTail, publicSend,
                completedWrites, refillWrites);

        assertEquals(1, fieldCount(constructor, Opcodes.PUTFIELD, sender.name,
                "desiredChunksPerTick", "F"));
        assertEquals(1, fieldCount(constructor, Opcodes.PUTFIELD, sender.name,
                "maxUnacknowledgedBatches", "I"));
        assertEquals(1, fieldCount(publicSend, Opcodes.PUTFIELD, sender.name,
                "unacknowledgedBatches", "I"));
        assertEquals(2, fieldCount(publicSend, Opcodes.PUTFIELD, sender.name,
                "batchQuota", "F"));
        assertEquals(0, fieldCount(fabroliaSend, Opcodes.PUTFIELD, sender.name,
                "unacknowledgedBatches", "I"),
                "send admission must not write pacing before its success cuts");
        assertEquals(0, fieldCount(fabroliaSend, Opcodes.PUTFIELD, sender.name,
                "batchQuota", "F"),
                "send admission must not write pacing before its success cuts");
        assertEquals(1, fieldCount(completedWrites, Opcodes.PUTFIELD, sender.name,
                "unacknowledgedBatches", "I"));
        assertEquals(1, fieldCount(completedWrites, Opcodes.PUTFIELD, sender.name,
                "batchQuota", "F"));
        assertEquals(1, fieldCount(refillWrites, Opcodes.PUTFIELD, sender.name,
                "batchQuota", "F"));

        assertPacingWriterCensus(sender, allowed, "unacknowledgedBatches", "I", 4);
        assertPacingWriterCensus(sender, allowed, "desiredChunksPerTick", "F", 3);
        assertPacingWriterCensus(sender, allowed, "batchQuota", "F", 5);
        assertPacingWriterCensus(sender, allowed, "maxUnacknowledgedBatches", "I", 2);
    }

    private static void assertPacingWriterCensus(ClassNode sender,
                                                  List<MethodNode> allowed,
                                                  String fieldName, String fieldDesc,
                                                  int expectedSites) {
        List<String> unexpected = sender.methods.stream()
                .filter(candidate -> fieldCount(candidate, Opcodes.PUTFIELD, sender.name,
                        fieldName, fieldDesc) != 0 && !allowed.contains(candidate))
                .map(candidate -> candidate.name + candidate.desc)
                .toList();
        assertTrue(unexpected.isEmpty(),
                "unexpected transformed writer(s) of " + fieldName + ": " + unexpected);
        int sites = sender.methods.stream()
                .mapToInt(candidate -> fieldCount(candidate, Opcodes.PUTFIELD, sender.name,
                        fieldName, fieldDesc))
                .sum();
        assertEquals(expectedSites, sites,
                "class-wide transformed writer census for " + fieldName);
    }

    /** Cross-dimension FIFO uses exact record identities, never reusable keys. */
    private static void verifyChunkDeliveryAckIdentity(ClassNode delivery,
                                                        ClassNode batchNode,
                                                        ClassNode recNode) {
        assertEquals(CHUNK_DELIVERY, delivery.name);
        assertEquals(CHUNK_DELIVERY_BATCH, batchNode.name);
        assertEquals(CHUNK_DELIVERY_REC, recNode.name);

        MethodNode track = method(delivery, "track",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V");
        int physicalMain = callIndexByName(track, PLAYER_TICKS,
                "isPhysicalServerThread");
        int oldRecordSweep = callIndexByName(track,
                "java/util/concurrent/ConcurrentHashMap", "clear");
        assertTrue(physicalMain >= 0 && physicalMain < oldRecordSweep,
                "level epoch cut must assert physical Main before retiring old records");
        assertEquals(1, callCountByName(track, PLAYER_TICKS,
                "isPhysicalServerThread"));
        assertEquals(0, callCountByName(track, "java/util/ArrayDeque", "clear"),
                "dimension change must retain old ACK skeletons in FIFO");

        MethodNode quit = method(delivery, "quit",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, callCountByName(quit, "java/util/ArrayDeque", "clear"),
                "only connection teardown may destroy outstanding ACK obligations");

        String recordsDesc = "[L" + CHUNK_DELIVERY_REC + ";";
        assertTrue(batchNode.fields.stream().anyMatch(field ->
                        field.name.equals("records") && field.desc.equals(recordsDesc)
                                && (field.access & Opcodes.ACC_FINAL) != 0),
                "Batch must pin a final array of exact Rec identities");
        assertFalse(batchNode.fields.stream().anyMatch(field ->
                        field.name.equals("keys") && field.desc.equals("[J")),
                "a reusable chunk key must not be an ACK capability");

        MethodNode onAck = method(delivery, "onAck",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, fieldCount(onAck, Opcodes.GETFIELD, CHUNK_DELIVERY_BATCH,
                "records", recordsDesc));
        assertEquals(1, callCountByName(onAck, CHUNK_DELIVERY_REC, "cas"));
        assertEquals(0, fieldCount(onAck, Opcodes.GETFIELD,
                CHUNK_DELIVERY + "$PlayerState", "records",
                "Ljava/util/concurrent/ConcurrentHashMap;"),
                "ACK must CAS the pinned Rec, never resolve a current same-key record");

        MethodNode deliverySend = method(delivery, "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/server/level/ServerLevel;I)I");
        assertEquals(0, callCountByName(deliverySend,
                "net/minecraft/network/protocol/game/ClientboundChunkBatchFinishedPacket",
                "<init>"),
                "Main may not report Finished before the real EventLoop sends");
        method(batchNode, "<init>", "(JJ" + recordsDesc + "I)V");
        MethodNode apply = method(delivery, "applyCompletedChunkBatch",
                "(Lnet/minecraft/server/level/ServerPlayer;)I");
        assertEquals(1, callCountByName(apply,
                REGION_SCHEDULER, "throwWorkerFailureIfAny"));
        assertEquals(1, callCountByName(apply,
                CHUNK_DELIVERY_BATCH_PUBLICATION, "applySuccessOnMain"));
    }

    /** Final deployed composition of the old-full-chunk/new-block-update ordering fence. */
    private static void verifyChunkFullSnapshotEnqueueFence(
            ClassNode levelChunk, ClassNode sender, ClassNode connection,
            ClassNode serverCommon, ClassNode fence, ClassNode delivery, ClassNode rec,
            ClassNode reservation, ClassNode entry, ClassNode publication,
            ClassNode connectionEventLoop, ClassNode fullChunkPublisher,
            ClassNode chunkForgetPublisher) {
        assertEquals(FABROLIA_CONNECTION_EVENT_LOOP, connectionEventLoop.name);
        assertEquals(FABROLIA_FULL_CHUNK_PUBLISHER, fullChunkPublisher.name);
        assertEquals(FABROLIA_CHUNK_FORGET_PUBLISHER, chunkForgetPublisher.name);
        assertTrue(connection.interfaces.contains(FABROLIA_CONNECTION_EVENT_LOOP),
                "final Connection must expose the bounded event-loop task surface");
        assertTrue(serverCommon.interfaces.contains(FABROLIA_FULL_CHUNK_PUBLISHER),
                "final play listener must expose physical-Main full-snapshot publication");
        assertTrue(serverCommon.interfaces.contains(FABROLIA_CHUNK_FORGET_PUBLISHER),
                "final play listener must expose ordered chunk-Forget publication");
        assertTrue(rec.fields.stream().anyMatch(field ->
                        field.name.equals("transportRetireGate")
                                && field.desc.equals("Ljava/lang/Object;")
                                && (field.access & Opcodes.ACC_FINAL) != 0),
                "deployed Rec must own one final publication/retirement gate");
        assertTrue(rec.fields.stream().anyMatch(field ->
                        field.name.equals("clientMayHaveChunk")
                                && field.desc.equals("Z")),
                "deployed Rec must retain exact client exposure");
        assertTrue(entry.fields.stream().anyMatch(field -> field.name.equals("chunk")
                        && field.desc.equals("Lnet/minecraft/world/level/chunk/LevelChunk;")
                        && (field.access & Opcodes.ACC_FINAL) != 0));
        assertTrue(entry.fields.stream().anyMatch(field -> field.name.equals("packet")
                        && field.desc.equals("Lnet/minecraft/network/protocol/game/"
                                + "ClientboundLevelChunkWithLightPacket;")
                        && (field.access & Opcodes.ACC_FINAL) != 0),
                "deployed Entry must retain one final immutable packet snapshot");

        assertTrue(levelChunk.interfaces.contains(FABROLIA_WRITE_GEN),
                "final LevelChunk must expose the write/enqueue generation capability");
        assertTrue(levelChunk.fields.stream().anyMatch(field ->
                        field.name.equals("fabrolia$writeFence")
                                && field.desc.equals("L" + CHUNK_WRITE_ENQUEUE_FENCE + ";")),
                "final LevelChunk must own one narrow publication fence");

        MethodNode init = generatedMethod(levelChunk,
                "$fabrolia$initializeWriteFence");
        assertEquals(1, typeCount(init, Opcodes.NEW, CHUNK_WRITE_ENQUEUE_FENCE));
        assertEquals(1, fieldCount(init, Opcodes.PUTFIELD, levelChunk.name,
                "fabrolia$writeFence", "L" + CHUNK_WRITE_ENQUEUE_FENCE + ";"));

        List<MethodNode> constructors = levelChunk.methods.stream()
                .filter(candidate -> candidate.name.equals("<init>"))
                .toList();
        List<String> expectedConstructors = List.of(LEVEL_CHUNK_SIMPLE_CTOR,
                        LEVEL_CHUNK_CANONICAL_CTOR, LEVEL_CHUNK_PROTO_CTOR)
                .stream().sorted().toList();
        assertEquals(expectedConstructors,
                constructors.stream().map(candidate -> candidate.desc)
                        .sorted().toList(),
                "26.3 constructor census is part of the write-fence dominance proof");

        MethodNode canonicalConstructor = method(levelChunk, "<init>",
                LEVEL_CHUNK_CANONICAL_CTOR);
        String chunkAccessConstructor =
                "(Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/chunk/UpgradeData;"
                        + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                        + "Lnet/minecraft/world/level/chunk/PalettedContainerFactory;J"
                        + "[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
                        + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V";
        int superConstructor = callIndex(canonicalConstructor,
                "net/minecraft/world/level/chunk/ChunkAccess", "<init>",
                chunkAccessConstructor);
        int initialize = generatedCallIndex(canonicalConstructor,
                levelChunk.name, "$fabrolia$initializeWriteFence");
        int constructorReturn = lastOpcodeIndex(canonicalConstructor, Opcodes.RETURN);
        assertEquals(1, generatedCallCount(canonicalConstructor, levelChunk.name,
                "$fabrolia$initializeWriteFence"));
        assertEquals(1, opcodeCount(canonicalConstructor, Opcodes.RETURN));
        assertTrue(superConstructor >= 0 && initialize > superConstructor
                        && constructorReturn > initialize,
                "the executable canonical constructor must initialize its fence before return");
        assertEquals(1, constructors.stream().mapToInt(candidate ->
                        generatedCallCount(candidate, levelChunk.name,
                                "$fabrolia$initializeWriteFence")).sum(),
                "only the canonical constructor may execute the initializer");

        List<String> writerEntrypoints = List.of("setBlockState", "setBlockEntity",
                "removeBlockEntity", "markUnsaved");
        for (MethodNode constructor : constructors) {
            int dominance;
            if (constructor.desc.equals(LEVEL_CHUNK_CANONICAL_CTOR)) {
                dominance = initialize;
            } else {
                dominance = callIndex(constructor, levelChunk.name, "<init>",
                        LEVEL_CHUNK_CANONICAL_CTOR);
                assertEquals(1, callCount(constructor, levelChunk.name, "<init>",
                                LEVEL_CHUNK_CANONICAL_CTOR),
                        constructor.desc + " must delegate once to the canonical constructor");
                assertEquals(0, generatedCallCount(constructor, levelChunk.name,
                                "$fabrolia$initializeWriteFence"),
                        constructor.desc + " must not own a late duplicate initializer");
            }
            int firstWriter = -1;
            int index = 0;
            for (AbstractInsnNode instruction : constructor.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(levelChunk.name)
                        && writerEntrypoints.contains(call.name)) {
                    firstWriter = index;
                    break;
                }
                index++;
            }
            assertTrue(dominance >= 0 && (firstWriter < 0 || dominance < firstWriter),
                    constructor.desc + " may not write before canonical fence initialization");
            assertEquals(0, typeCount(constructor, Opcodes.NEW,
                            CHUNK_WRITE_ENQUEUE_FENCE),
                    "constructors must execute, not inline or lazily duplicate, the handler");
        }

        MethodNode fenceAccessor = method(levelChunk, "fabrolia$writeFence",
                "()L" + CHUNK_WRITE_ENQUEUE_FENCE + ";");
        assertEquals(0, typeCount(fenceAccessor, Opcodes.NEW,
                        CHUNK_WRITE_ENQUEUE_FENCE),
                "the deployed accessor must fail closed instead of lazily allocating");
        assertEquals(1, levelChunk.methods.stream().mapToInt(candidate ->
                        typeCount(candidate, Opcodes.NEW,
                                CHUNK_WRITE_ENQUEUE_FENCE)).sum(),
                "the generated constructor handler must be the sole fence allocation site");

        for (List<String> route : List.of(
                List.of("setBlockState",
                        "(Lnet/minecraft/core/BlockPos;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                                + "Lnet/minecraft/world/level/block/state/BlockState;",
                        "$fabrolia$fenceSetBlockState"),
                List.of("setBlockEntity",
                        "(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
                        "$fabrolia$fenceSetBlockEntity"),
                List.of("removeBlockEntity",
                        "(Lnet/minecraft/core/BlockPos;)V",
                        "$fabrolia$fenceRemoveBlockEntity"),
                List.of("markUnsaved", "()V",
                        "$fabrolia$fenceMarkUnsaved"))) {
            MethodNode target = method(levelChunk, route.get(0), route.get(1));
            MethodNode wrapper = generatedMethod(levelChunk, route.get(2));
            assertEquals(1, generatedCallCount(target, levelChunk.name, route.get(2)),
                    route.get(0) + " must enter the writer-start mark once");
            int mark = callIndexByName(wrapper, CHUNK_WRITE_ENQUEUE_FENCE,
                    "markWriteStart");
            int original = callIndex(wrapper, OPERATION, "call", OPERATION_CALL_DESC);
            assertTrue(mark >= 0 && original > mark,
                    route.get(0) + " must invalidate before final transformed original");
            assertEquals(1, callCountByName(wrapper,
                    CHUNK_WRITE_ENQUEUE_FENCE, "markWriteStart"));
            assertEquals(0, callCountByName(wrapper,
                    CHUNK_WRITE_ENQUEUE_FENCE, "beginWrite"));
            assertEquals(0, callCountByName(wrapper,
                    CHUNK_WRITE_ENQUEUE_FENCE, "endWrite"));
            assertEquals(0, callCountByName(wrapper,
                    "java/util/concurrent/locks/ReentrantLock", "lock"));
            assertEquals(0, wrapper.access & Opcodes.ACC_SYNCHRONIZED);
            assertEquals(0, opcodeCount(wrapper, Opcodes.MONITORENTER));
            assertEquals(0, opcodeCount(wrapper, Opcodes.MONITOREXIT),
                    route.get(0) + " body/callbacks must own no chunk fence");
        }

        MethodNode reserveSurface = method(levelChunk,
                "fabrolia$tryReserveFullChunkEnqueue", "(J)Z");
        MethodNode releaseSurface = method(levelChunk,
                "fabrolia$releaseFullChunkEnqueue", "(J)V");
        assertEquals(1, callCountByName(reserveSurface,
                CHUNK_WRITE_ENQUEUE_FENCE, "tryReserveEnqueue"));
        assertEquals(1, callCountByName(releaseSurface,
                CHUNK_WRITE_ENQUEUE_FENCE, "releaseEnqueue"));

        MethodNode markWrite = method(fence, "markWriteStart", "()V");
        MethodNode tryReserve = method(fence, "tryReserveEnqueue", "(J)Z");
        int markLock = callIndexByName(markWrite,
                "java/util/concurrent/locks/ReentrantLock", "lock");
        int generation = fieldIndex(markWrite, Opcodes.PUTFIELD,
                CHUNK_WRITE_ENQUEUE_FENCE, "generation", "J");
        int markUnlock = lastCallIndex(markWrite,
                "java/util/concurrent/locks/ReentrantLock", "unlock", "()V");
        assertTrue(markLock >= 0 && generation > markLock && markUnlock > generation);
        assertTrue(markWrite.tryCatchBlocks.size() >= 1);
        assertEquals(1, callCountByName(markWrite,
                "java/util/concurrent/locks/ReentrantLock", "lock"));
        assertTrue(callCountByName(markWrite,
                "java/util/concurrent/locks/ReentrantLock", "unlock") >= 2);
        assertFalse(fence.methods.stream().anyMatch(candidate ->
                        candidate.name.equals("beginWrite") || candidate.name.equals("endWrite")),
                "deployed fence must expose no full-body writer lock API");
        assertEquals(1, callCountByName(tryReserve,
                "java/util/concurrent/locks/ReentrantLock", "tryLock"));
        assertEquals(0, callCountByName(tryReserve,
                "java/util/concurrent/locks/ReentrantLock", "lock"),
                "physical Main must never wait behind an island chunk writer");

        MethodNode enqueue = method(reservation, "admitAfterBatchOffer",
                "()L" + CHUNK_DELIVERY_ENQUEUE_RESULT + ";");
        int recGate = fieldIndex(enqueue, Opcodes.GETFIELD,
                CHUNK_DELIVERY_REC, "transportRetireGate", "Ljava/lang/Object;");
        int retireMonitor = opcodeIndexAfter(enqueue, Opcodes.MONITORENTER, recGate);
        int reserve = callIndexByName(enqueue, FABROLIA_WRITE_GEN,
                "fabrolia$tryReserveFullChunkEnqueue");
        int recCommit = callIndexAfter(enqueue, CHUNK_DELIVERY_REC, "cas",
                "(L" + CHUNK_DELIVERY + "$St;L" + CHUNK_DELIVERY + "$St;)Z",
                reserve);
        int exposed = fieldIndexAfter(enqueue, Opcodes.PUTFIELD,
                CHUNK_DELIVERY_REC, "clientMayHaveChunk", "Z", recCommit);
        int enqueueRelease = lastCallIndex(enqueue, FABROLIA_WRITE_GEN,
                "fabrolia$releaseFullChunkEnqueue", "(J)V");
        assertTrue(recGate >= 0 && retireMonitor > recGate && reserve > retireMonitor
                        && recCommit > reserve && exposed > recCommit
                        && enqueueRelease > exposed,
                "Rec gate -> exact generation reserve -> provisional ADMITTED/exposure -> release");
        assertEquals(1, opcodeCount(enqueue, Opcodes.MONITORENTER));
        assertTrue(opcodeCount(enqueue, Opcodes.MONITOREXIT) >= 2);
        assertTrue(callCountByName(enqueue, CHUNK_DELIVERY,
                        "publishFatalPreserving") >= 2,
                "admission/release failures must select and rethrow exact W");
        assertEquals(0, callCountByName(enqueue,
                "com/axalotl/fabrolia/common/regions/IslandCommands", "mainClaim"));
        assertEquals(0, callCountByName(enqueue,
                "com/axalotl/fabrolia/common/regions/IslandCommands", "mainClaimScoped"));
        MethodNode commitRefs = method(reservation,
                "releaseReservationReference", "()V");
        MethodNode rollbackRefs = method(reservation, "rollback", "()V");
        MethodNode abandonRefs = method(reservation, "abandonCancelled", "()V");
        assertEquals(1, callCountByName(commitRefs,
                "java/util/concurrent/atomic/AtomicInteger", "decrementAndGet"));
        assertEquals(1, callCountByName(rollbackRefs,
                CHUNK_DELIVERY_RESERVATION, "releaseReservationReference"));
        assertEquals(1, callCountByName(rollbackRefs, CHUNK_DELIVERY_REC, "cas"));
        assertEquals(1, callCountByName(abandonRefs,
                CHUNK_DELIVERY_RESERVATION, "releaseReservationReference"));
        MethodNode terminalize = method(reservation, "terminalizeFailure", "()V");
        assertEquals(1, callCountByName(terminalize,
                CHUNK_DELIVERY_RESERVATION, "releaseReservationReference"));

        method(publication, "runPublished",
                "(L" + FABROLIA_FULL_CHUNK_PUBLISHER + "$PacketSender;)V");
        MethodNode fullPacketStage = publication.methods.stream()
                .filter(candidate -> candidate.name.contains("lambda$runPublished"))
                .filter(candidate -> fieldCount(candidate, Opcodes.GETFIELD,
                        CHUNK_DELIVERY_ENTRY, "packet",
                        "Lnet/minecraft/network/protocol/game/"
                                + "ClientboundLevelChunkWithLightPacket;") == 1)
                .findFirst().orElseThrow();
        int packetRead = fieldIndex(fullPacketStage, Opcodes.GETFIELD,
                CHUNK_DELIVERY_ENTRY, "packet",
                "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;");
        int realSendCommit = callIndexAfter(fullPacketStage,
                CHUNK_DELIVERY_RESERVATION, "markRealSendSucceeded", "()V", packetRead);
        assertTrue(packetRead >= 0 && realSendCommit > packetRead,
                "deployed ENQUEUED edge must follow the real listener send");

        verifyChunkTransportRetirementComposition(sender, serverCommon, delivery, rec);

        MethodNode publish = method(serverCommon,
                "fabrolia$enqueueFullChunkBatch",
                "(L" + FABROLIA_FULL_CHUNK_PUBLISHER
                        + "$FullChunkBatchPublication;)V");
        int physicalMain = callIndexByName(publish, PLAYER_TICKS,
                "isPhysicalServerThread");
        int queueTask = callIndexByName(publish, FABROLIA_CONNECTION_EVENT_LOOP,
                "fabrolia$enqueueEventLoopTask");
        assertEquals(0, publish.access & Opcodes.ACC_SYNCHRONIZED,
                "publication may not hold the listener monitor");
        assertTrue(physicalMain >= 0 && queueTask > physicalMain,
                "deployed publication must assert physical Main before task admission");
        assertEquals(0, callCountByName(publish, SERVER_COMMON_LISTENER, "send"),
                "public listener send must never execute in the LevelChunk critical section");
        List<MethodNode> deferred = serverCommon.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueFullChunkBatch"))
                .filter(candidate -> callCountByName(candidate,
                        FABROLIA_FULL_CHUNK_PUBLISHER
                                + "$FullChunkBatchPublication", "runPublished") == 1)
                .toList();
        assertEquals(1, deferred.size(),
                "exactly one deferred task must enter the delivery receipt");

        MethodNode eventLoop = method(connection,
                "fabrolia$enqueueEventLoopTask", "(Ljava/lang/Runnable;)V");
        assertEquals(0, eventLoop.access & Opcodes.ACC_SYNCHRONIZED,
                "event-loop publication may not hold the Connection monitor");
        int selectLoop = callIndexByName(eventLoop,
                "io/netty/channel/Channel", "eventLoop");
        int tracked = callIndexByName(eventLoop,
                REGION_SCHEDULER, "executeTrackedExternal");
        assertTrue(selectLoop >= 0 && tracked > selectLoop,
                "deployed transport must reserve its generation before EventLoop visibility");
        assertEquals(0, callCountByName(eventLoop,
                "io/netty/channel/EventLoop", "execute"),
                "deployed transport may not reacquire a root permit after Netty delay");
        assertEquals(0, callCountByName(eventLoop, CONNECTION, "send"));
        assertEquals(0, callCountByName(eventLoop, CONNECTION, "flushQueue"));
        assertEquals(0, opcodeCount(eventLoop, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(eventLoop, Opcodes.MONITOREXIT));

        MethodNode eventLoopBody = connection.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueEventLoopTask"))
                .filter(candidate -> callCountByName(
                        candidate, "java/lang/Runnable", "run") == 1)
                .findFirst().orElseThrow();
        assertEquals(1, callCountByName(
                eventLoopBody, ISLAND_COMMANDS, "publishAndPropagate"));

        MethodNode deliverySend = method(delivery, "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/server/level/ServerLevel;I)I");
        assertEquals(1, callCountByName(deliverySend,
                CHUNK_DELIVERY_BATCH_PUBLICATION, "publish"));
        assertEquals(0, fieldCount(deliverySend, Opcodes.GETFIELD,
                CHUNK_DELIVERY + "$Entry", "packet",
                "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;"),
                "deployed batcher must not bypass the final generation commit helper");
    }

    /** Final target+artifact proof for full/Forget/successor lifecycle ordering. */
    private static void verifyChunkTransportRetirementComposition(
            ClassNode sender, ClassNode serverCommon, ClassNode delivery, ClassNode rec) {
        MethodNode retire = method(delivery, "retire",
                "(L" + CHUNK_DELIVERY_REC + ";L" + CHUNK_DELIVERY + "$St;)Z");
        int retireGate = fieldIndex(retire, Opcodes.GETFIELD,
                CHUNK_DELIVERY_REC, "transportRetireGate", "Ljava/lang/Object;");
        int retireMonitor = opcodeIndexAfter(retire, Opcodes.MONITORENTER, retireGate);
        int exposureRead = fieldIndexAfter(retire, Opcodes.GETFIELD,
                CHUNK_DELIVERY_REC, "clientMayHaveChunk", "Z", retireMonitor);
        assertTrue(retireGate >= 0 && retireMonitor > retireGate
                        && exposureRead > retireMonitor,
                "retirement must snapshot exact client exposure under one Rec gate");
        assertEquals(1, opcodeCount(retire, Opcodes.MONITORENTER));
        assertEquals(0, callCountByName(retire,
                FABROLIA_FULL_CHUNK_PUBLISHER, "fabrolia$enqueueFullChunkBatch"));
        assertEquals(0, callCountByName(retire,
                FABROLIA_CHUNK_FORGET_PUBLISHER, "fabrolia$enqueueChunkForget"));

        MethodNode track = method(delivery, "track",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V");
        assertEquals(1, callCountByName(track,
                "java/util/concurrent/ConcurrentHashMap", "compute"));
        assertEquals(0, callCountByName(track,
                "java/util/concurrent/ConcurrentHashMap", "put"));
        MethodNode trackTransaction = uniqueMethod(delivery,
                candidate -> candidate.name.contains("lambda$track")
                        && callCountByName(candidate, CHUNK_DELIVERY, "retire") == 1,
                "same-key old-Rec replacement transaction");
        int replaceRetire = callIndexByName(trackTransaction, CHUNK_DELIVERY, "retire");
        int exposureTransfer = fieldIndexAfter(trackTransaction, Opcodes.PUTFIELD,
                CHUNK_DELIVERY_REC, "clientMayHaveChunk", "Z", replaceRetire);
        assertTrue(exposureTransfer > replaceRetire,
                "successor publication must inherit exact old client exposure");

        MethodNode untrack = method(delivery, "untrack",
                "(Lnet/minecraft/server/level/ServerPlayer;J"
                        + "Lnet/minecraft/world/level/ChunkPos;)Z");
        assertEquals(1, callCountByName(untrack,
                "java/util/concurrent/ConcurrentHashMap", "compute"));
        assertEquals(0, callCountByName(untrack,
                "java/util/concurrent/ConcurrentHashMap", "remove"));
        MethodNode dropTransaction = uniqueMethod(delivery,
                candidate -> candidate.name.contains("lambda$untrack")
                        && callCountByName(candidate, CHUNK_DELIVERY, "retire") == 1,
                "same-key retirement/Forget transaction");
        int dropRetire = callIndexByName(dropTransaction, CHUNK_DELIVERY, "retire");
        int forgetOffer = callIndexAfter(dropTransaction,
                FABROLIA_CHUNK_FORGET_PUBLISHER, "fabrolia$enqueueChunkForget",
                "(Lnet/minecraft/network/protocol/game/"
                        + "ClientboundForgetLevelChunkPacket;)V", dropRetire);
        assertTrue(forgetOffer > dropRetire,
                "old Rec must retire and offer Forget before its CHM key is released");
        assertEquals(0, callCountByName(dropTransaction,
                SERVER_COMMON_LISTENER, "send"));
        assertEquals(0, callCountByName(dropTransaction, CONNECTION, "send"));

        MethodNode markHandler = generatedMethod(sender, "$fabrolia$track");
        MethodNode dropHandler = generatedMethod(sender, "$fabrolia$untrack");
        MethodNode sendHandler = generatedMethod(sender, "$fabrolia$send");
        assertEquals(1, callCountByName(markHandler, CHUNK_DELIVERY, "track"));
        assertEquals(1, callCountByName(dropHandler, CHUNK_DELIVERY, "untrack"));
        assertEquals(1, callCount(markHandler,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                "cancel", "()V"));
        assertEquals(1, callCount(dropHandler,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                "cancel", "()V"));
        assertEquals(1, callCount(sendHandler,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                "cancel", "()V"));
        assertCancelledBeforePendingMirror(sender,
                method(sender, "markChunkPendingToSend",
                        "(Lnet/minecraft/world/level/chunk/LevelChunk;)V"),
                markHandler);
        assertCancelledBeforePendingMirror(sender,
                method(sender, "dropChunk",
                        "(Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/world/level/ChunkPos;)V"),
                dropHandler);
        assertCancelledBeforePendingMirror(sender,
                method(sender, "sendNextChunks",
                        "(Lnet/minecraft/server/level/ServerPlayer;)V"),
                sendHandler);
        assertEquals(1, callCount(sendHandler, CHUNK_DELIVERY, "sendNextChunks",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/server/level/ServerLevel;I)I"));

        List<String> unexpectedPendingConsumers = sender.methods.stream()
                .filter(candidate -> fieldCountByName(candidate, sender.name,
                        "pendingChunks") != 0)
                .filter(candidate -> !candidate.name.equals("<init>"))
                .filter(candidate -> !candidate.name.equals("markChunkPendingToSend"))
                .filter(candidate -> !candidate.name.equals("dropChunk"))
                .filter(candidate -> !candidate.name.equals("sendNextChunks"))
                .filter(candidate -> !candidate.name.equals("collectChunksToSend"))
                .filter(candidate -> !candidate.name.contains(
                        "isPending$mixinextras$wrapped"))
                .map(candidate -> candidate.name + candidate.desc)
                .toList();
        assertTrue(unexpectedPendingConsumers.isEmpty(),
                "unexpected transformed pendingChunks consumer: "
                        + unexpectedPendingConsumers);
        MethodNode collect = method(sender, "collectChunksToSend",
                "(Lnet/minecraft/server/level/ChunkMap;"
                        + "Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;");
        assertEquals(1, sender.methods.stream().mapToInt(candidate ->
                        callCount(candidate, sender.name, collect.name, collect.desc)).sum(),
                "the dead vanilla collector may remain reachable only from cancelled sendNextChunks");

        MethodNode forgetPublisher = method(serverCommon,
                "fabrolia$enqueueChunkForget",
                "(Lnet/minecraft/network/protocol/game/"
                        + "ClientboundForgetLevelChunkPacket;)V");
        assertEquals(1, callCountByName(forgetPublisher,
                FABROLIA_FULL_CHUNK_PUBLISHER + "$PublicationReceipt", "publish"));
        assertEquals(0, callCountByName(forgetPublisher,
                SERVER_COMMON_LISTENER, "send"));
        List<MethodNode> deferredForgets = serverCommon.methods.stream()
                .filter(candidate -> candidate.name.contains(
                        "lambda$fabrolia$enqueueChunkForget"))
                .filter(candidate -> callCount(candidate, SERVER_COMMON_LISTENER,
                        "send", "(Lnet/minecraft/network/protocol/Packet;)V") == 1)
                .toList();
        assertEquals(1, deferredForgets.size(),
                "exactly one deferred Forget task must re-enter public listener send");
    }

    private static void assertCancelledBeforePendingMirror(
            ClassNode sender, MethodNode target, MethodNode handler) {
        int injected = callIndex(target, sender.name, handler.name, handler.desc);
        int cancelled = callIndexByName(target,
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                "isCancelled");
        int earlyReturn = opcodeIndexAfter(target, Opcodes.RETURN, cancelled);
        int pendingRead = fieldIndex(target, Opcodes.GETFIELD, sender.name,
                "pendingChunks", "Lit/unimi/dsi/fastutil/longs/LongSet;");
        assertTrue(injected >= 0 && cancelled > injected && earlyReturn > cancelled
                        && (pendingRead < 0 || earlyReturn < pendingRead),
                target.name + " must return on Fabrolia cancellation before pendingChunks");
    }

    /** Entity block-state cache never holds its publication monitor across a world lookup. */
    private static void verifyInBlockStateCacheComposition(ClassNode entity) {
        String blockState = "Lnet/minecraft/world/level/block/state/BlockState;";
        MethodNode getter = method(entity, "getInBlockState", "()" + blockState);
        int firstMonitor = opcodeIndex(getter, Opcodes.MONITORENTER);
        int hitRead = fieldIndex(getter, Opcodes.GETFIELD, entity.name,
                "inBlockState", blockState);
        int snapshotLevel = fieldIndex(getter, Opcodes.GETFIELD, entity.name,
                "level", "Lnet/minecraft/world/level/Level;");
        int lookup = callIndex(getter, "net/minecraft/world/level/Level",
                "getBlockState", "(Lnet/minecraft/core/BlockPos;)" + blockState);
        int secondMonitor = opcodeIndexAfter(getter, Opcodes.MONITORENTER, firstMonitor);
        int snapshotRevision = fieldIndex(getter, Opcodes.GETFIELD, entity.name,
                "fabrolia$inBlockStateRevision", "J");
        int snapshotUnlock = opcodeIndexAfter(
                getter, Opcodes.MONITOREXIT, snapshotRevision);
        int validationRevision = fieldIndexAfter(getter, Opcodes.GETFIELD, entity.name,
                "fabrolia$inBlockStateRevision", "J", lookup);
        int levelRecheck = fieldIndexAfter(getter, Opcodes.GETFIELD, entity.name,
                "level", "Lnet/minecraft/world/level/Level;", lookup);
        int publish = fieldIndexAfter(getter, Opcodes.PUTFIELD, entity.name,
                "inBlockState", blockState, secondMonitor);
        assertTrue(firstMonitor >= 0 && hitRead > firstMonitor
                        && snapshotLevel > hitRead && snapshotRevision > snapshotLevel
                        && snapshotUnlock > snapshotRevision && lookup > snapshotUnlock
                        && secondMonitor > lookup && validationRevision > secondMonitor
                        && levelRecheck > validationRevision && publish > levelRecheck,
                "cache miss must read under lock, unlock, look up, then revalidate before publication");
        assertEquals(2, opcodeCount(getter, Opcodes.MONITORENTER));
        assertEquals(1, callCount(getter, "net/minecraft/world/level/Level",
                "getBlockState", "(Lnet/minecraft/core/BlockPos;)" + blockState));
        assertEquals(2, fieldCount(getter, Opcodes.GETFIELD, entity.name,
                "fabrolia$inBlockStateRevision", "J"));
        assertEquals(2, fieldCount(getter, Opcodes.GETFIELD, entity.name,
                "inBlockState", blockState));
        assertEquals(2, fieldCount(getter, Opcodes.GETFIELD, entity.name,
                "level", "Lnet/minecraft/world/level/Level;"));
        assertEquals(0, callCount(getter, entity.name, "level",
                "()Lnet/minecraft/world/level/Level;"),
                "cache monitor must not invoke an overridable level getter");
        assertEquals(0, fieldCount(getter, Opcodes.PUTFIELD, entity.name,
                "fabrolia$inBlockStateRevision", "J"));
        assertEquals(1, fieldCount(getter, Opcodes.PUTFIELD, entity.name,
                "inBlockState", blockState));
        assertTrue(hasBackwardJump(getter), "a stale snapshot must retry rather than publish");

        MethodNode lithiumObserver = generatedMethod(entity, "$lithium$onBlockCached");
        int racingRead = fieldIndex(lithiumObserver, Opcodes.GETFIELD, entity.name,
                "inBlockState", blockState);
        int conservative = generatedCallIndex(
                lithiumObserver, entity.name, "$fabrolia$conservativeLithiumCacheState");
        int lithiumIndex = callIndexByName(
                lithiumObserver, entity.name, "lithium$OnFeetBlockCacheSet");
        assertTrue(racingRead >= 0 && conservative > racingRead && lithiumIndex > conservative,
                "Lithium's pre-publication field observation must be replaced before indexing");
        assertEquals(1, generatedCallCount(
                lithiumObserver, entity.name, "$fabrolia$conservativeLithiumCacheState"));
        MethodNode cachedGetter = method(entity, "lithium$getCachedFeetBlockState",
                "()" + blockState);
        int cachedRead = fieldIndex(cachedGetter, Opcodes.GETFIELD, entity.name,
                "inBlockState", blockState);
        int cachedConservative = generatedCallIndex(
                cachedGetter, entity.name, "$fabrolia$conservativeLithiumFeetBlockState");
        int cachedReturn = opcodeIndexAfter(cachedGetter, Opcodes.ARETURN, cachedConservative);
        assertTrue(cachedRead >= 0 && cachedConservative > cachedRead
                        && cachedReturn > cachedConservative,
                "Lithium's section-entry cache read must be discarded before indexing");
        assertEquals(1, generatedCallCount(
                cachedGetter, entity.name, "$fabrolia$conservativeLithiumFeetBlockState"));

        MethodNode publicationHook =
                generatedMethod(entity, "$fabrolia$conservativeLithiumCacheState");
        MethodNode feetHook =
                generatedMethod(entity, "$fabrolia$conservativeLithiumFeetBlockState");
        for (MethodNode hook : List.of(publicationHook, feetHook)) {
            assertEquals(1, opcodeCount(hook, Opcodes.ACONST_NULL));
            assertEquals(1, opcodeCount(hook, Opcodes.ARETURN));
            assertEquals(0, fieldCount(hook, Opcodes.GETFIELD, entity.name,
                    "inBlockState", blockState),
                    "compatibility hooks must not race by rereading the cache");
        }

        MethodNode movement =
                generatedMethod(entity, "$fabrolia$publishBlockPositionAndInvalidate");
        MethodNode levelChange =
                generatedMethod(entity, "$fabrolia$publishLevelAndInvalidate");
        MethodNode baseTick =
                generatedMethod(entity, "$fabrolia$serializeBaseTickInBlockStateInvalidation");
        assertEquals(1, fieldCount(levelChange, Opcodes.PUTFIELD, entity.name,
                "level", "Lnet/minecraft/world/level/Level;"));
        for (MethodNode writer : List.of(movement, levelChange, baseTick)) {
            int monitor = opcodeIndex(writer, Opcodes.MONITORENTER);
            int invalidate = fieldIndex(writer, Opcodes.PUTFIELD, entity.name,
                    "inBlockState", blockState);
            int revision = fieldIndex(writer, Opcodes.PUTFIELD, entity.name,
                    "fabrolia$inBlockStateRevision", "J");
            assertTrue(monitor >= 0 && invalidate > monitor && revision > invalidate,
                    "each cache invalidation must advance its revision under the same monitor");
            assertEquals(1, opcodeCount(writer, Opcodes.MONITORENTER));
            assertEquals(1, fieldCount(writer, Opcodes.PUTFIELD, entity.name,
                    "fabrolia$inBlockStateRevision", "J"));
        }
    }


    /** PlayerAction self/target split plus exact Attack/Interact target-owner composition. */
    

    private static MethodNode assertOperationRoute(ClassNode owner,
                                                   String targetName,
                                                   String targetDesc,
                                                   String handlerSuffix) {
        MethodNode target = method(owner, targetName, targetDesc);
        MethodNode handler = generatedMethod(owner, "$" + handlerSuffix);
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route through " + handler.name);
        assertEquals(1, callCount(handler, OPERATION, "call", OPERATION_CALL_DESC),
                handler.name + " must retain one original Operation");
        return handler;
    }

    /** Explicit monitor block whose post-lock continuation forbids ACC_SYNCHRONIZED. */
    private static MethodNode assertMonitorOperationRoute(ClassNode owner,
                                                          String targetName,
                                                          String targetDesc,
                                                          String handlerSuffix) {
        MethodNode target = method(owner, targetName, targetDesc);
        MethodNode handler = uniqueMethod(owner, candidate ->
                        candidate.name.endsWith(handlerSuffix)
                                && candidate.desc.contains("L" + OPERATION + ";"),
                owner.name + " explicit-monitor " + handlerSuffix + " Operation handler");
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route through " + handler.name);
        assertEquals(1, opcodeCount(handler, Opcodes.MONITORENTER),
                handler.name + " must own one exact tracker transaction");
        return handler;
    }

    /** The final target method must enter one synchronized instance handler around its body. */
    private static MethodNode assertSynchronizedOperationRoute(ClassNode owner,
                                                               String targetName,
                                                               String targetDesc,
                                                               String handlerSuffix) {
        MethodNode target = method(owner, targetName, targetDesc);
        MethodNode handler = uniqueMethod(owner, candidate ->
                        candidate.name.endsWith(handlerSuffix)
                                && candidate.desc.contains("L" + OPERATION + ";")
                                && callCount(candidate, OPERATION,
                                "call", OPERATION_CALL_DESC) == 1,
                owner.name + " synchronized " + handlerSuffix + " Operation handler");
        assertTrue((handler.access & Opcodes.ACC_SYNCHRONIZED) != 0
                        && (handler.access & Opcodes.ACC_STATIC) == 0,
                handler.name + " must use the transformed target's intrinsic monitor");
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route once through " + handler.name);
        return handler;
    }

    /** Same-section movement is lock-free; only a real membership transition owns ChunkMap. */
    private static MethodNode assertConditionalPlayerMoveOperationRoute(
            ClassNode owner, String targetName, String targetDesc,
            String handlerSuffix) {
        MethodNode target = method(owner, targetName, targetDesc);
        MethodNode handler = uniqueMethod(owner, candidate ->
                        candidate.name.endsWith(handlerSuffix)
                                && candidate.desc.contains("L" + OPERATION + ";")
                                && callCount(candidate, OPERATION,
                                "call", OPERATION_CALL_DESC) == 2,
                owner.name + " conditional " + handlerSuffix + " Operation handler");
        assertEquals(0, handler.access & Opcodes.ACC_SYNCHRONIZED,
                "same-section movement must not acquire ChunkMap at method entry");
        assertEquals(1, opcodeCount(handler, Opcodes.MONITORENTER),
                "only the membership-transition branch may enter ChunkMap");
        assertEquals(2, opcodeCount(handler, Opcodes.MONITOREXIT),
                "the explicit transaction must release on both exits");
        assertEquals(1, callCount(handler,
                "com/axalotl/fabrolia/common/regions/PlayerMoveLockPolicy",
                "requiresGlobalTransaction", "(JJZZ)Z"));
        assertEquals(1, callCount(handler, "net/minecraft/core/SectionPos",
                "of", "(Lnet/minecraft/world/level/entity/EntityAccess;)"
                        + "Lnet/minecraft/core/SectionPos;"));
        assertEquals(2, callCount(handler, "net/minecraft/core/SectionPos",
                "asLong", "()J"));
        assertEquals(1, callCount(handler, "net/minecraft/server/level/PlayerMap",
                "ignored", "(Lnet/minecraft/server/level/ServerPlayer;)Z"));
        assertEquals(1, callCount(handler, owner.name, "skipPlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z"));
        assertEquals(1, callCount(target, owner.name, handler.name, handler.desc),
                targetName + targetDesc + " must route once through " + handler.name);
        return handler;
    }

    /** VMP-composed proof that admission and vanilla's later writer branch use one tuple. */
    private static void verifyMoveDecisionSnapshotComposition(
            ClassNode owner, MethodNode wrapper, ClassNode snapshot) {
        String stableDesc = "(Lnet/minecraft/core/SectionPos;"
                + "Lnet/minecraft/core/SectionPos;ZZ)V";
        int stable = callIndex(wrapper, PLAYER_MOVE_READ_SNAPSHOT,
                "pushStable", stableDesc);
        int firstBody = callIndex(wrapper, OPERATION, "call", OPERATION_CALL_DESC);
        int live = callIndex(wrapper, PLAYER_MOVE_READ_SNAPSHOT, "pushLive", "()V");
        int secondBody = lastCallIndex(wrapper, OPERATION, "call", OPERATION_CALL_DESC);
        assertEquals(1, callCount(wrapper, PLAYER_MOVE_READ_SNAPSHOT,
                "pushStable", stableDesc));
        assertEquals(1, callCount(wrapper, PLAYER_MOVE_READ_SNAPSHOT,
                "pushLive", "()V"));
        assertEquals(4, callCount(wrapper, PLAYER_MOVE_READ_SNAPSHOT, "pop", "()V"));
        assertTrue(stable >= 0 && firstBody > stable && live > firstBody
                        && secondBody > live,
                "the fast branch must install its stable tuple and a nested transition must "
                        + "mask it before either vanilla body");
        for (int body : List.of(firstBody, secondBody)) {
            assertTrue(hasCatchAllCleanupAroundCall(wrapper, body,
                            PLAYER_MOVE_READ_SNAPSHOT, "pop", "()V")
                            || hasThrowableCleanupAroundCall(wrapper, body,
                            PLAYER_MOVE_READ_SNAPSHOT, "pop", "()V"),
                    "both transformed move branches must restore the snapshot after Throwable");
        }

        for (List<String> route : List.of(
                List.of("fabrolia$stableMoveLastSection", "lastSectionOr",
                        "(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/core/SectionPos;"),
                List.of("fabrolia$stableMoveCurrentSection", "currentSectionOr",
                        "(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/core/SectionPos;"),
                List.of("fabrolia$stableMoveIgnored", "ignoredOr", "(Z)Z"),
                List.of("fabrolia$stableMoveSkip", "skipOr", "(Z)Z"))) {
            MethodNode handler = generatedMethod(owner, "$" + route.get(0));
            assertEquals(1, callCount(handler, PLAYER_MOVE_READ_SNAPSHOT,
                    route.get(1), route.get(2)),
                    route.get(0) + " must replay its admitted decision component");
            int classRoutes = owner.methods.stream()
                    .mapToInt(method -> callCount(method, owner.name,
                            handler.name, handler.desc))
                    .sum();
            assertEquals(1, classRoutes,
                    route.get(0) + " must remain injected at exactly one VMP-composed read");
        }

        assertTrue((snapshot.access & Opcodes.ACC_PUBLIC) != 0,
                "transformed ChunkMap must be able to call the packaged snapshot helper");
        for (List<String> api : List.of(
                List.of("pushStable", stableDesc),
                List.of("pushLive", "()V"),
                List.of("pop", "()V"),
                List.of("lastSectionOr",
                        "(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/core/SectionPos;"),
                List.of("currentSectionOr",
                        "(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/core/SectionPos;"),
                List.of("ignoredOr", "(Z)Z"),
                List.of("skipOr", "(Z)Z"))) {
            MethodNode method = method(snapshot, api.get(0), api.get(1));
            assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0
                            && (method.access & Opcodes.ACC_STATIC) != 0,
                    api.get(0) + " must remain a public static cross-package seam");
            assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED);
            assertEquals(0, opcodeCount(method, Opcodes.MONITORENTER));
            assertEquals(0, opcodeCount(method, Opcodes.MONITOREXIT));
        }
    }

    private static void verifyServerCommonFlush(ClassNode listener) {
        MethodNode send = method(listener, "send",
                "(Lnet/minecraft/network/protocol/Packet;"
                        + "Lio/netty/channel/ChannelFutureListener;)V");
        int vanilla = callIndex(send, MINECRAFT_SERVER, "isSameThread", "()Z");
        int hook = generatedCallIndex(send, listener.name,
                "$fabrolia$preserveSuspendedFlush");
        int connectionSend = callIndex(send, "net/minecraft/network/Connection", "send",
                "(Lnet/minecraft/network/protocol/Packet;"
                        + "Lio/netty/channel/ChannelFutureListener;Z)V");
        assertEquals(1, callCount(send, MINECRAFT_SERVER, "isSameThread", "()Z"));
        assertEquals(1, generatedCallCount(send, listener.name,
                "$fabrolia$preserveSuspendedFlush"));
        assertTrue(vanilla >= 0 && hook > vanilla && connectionSend > hook,
                "suspended-flush hook must wrap the vanilla thread decision before send");
        MethodNode handler = generatedMethod(listener,
                "$fabrolia$preserveSuspendedFlush");
        assertEquals(1, callCount(handler, PLAYER_TICKS, "inWorkerPlayerTick", "()Z"));
        assertEquals(0, callCount(handler, A5_PLAYER_TICKS, "inOwnerTurn", "()Z"),
                "autonomous A5 sends must retain vanilla off-main flush=true");

        MethodNode disconnect = method(listener, "disconnect",
                "(Lnet/minecraft/network/DisconnectionDetails;)V");
        assertEquals(1, generatedCallCount(disconnect, listener.name,
                "$fabrolia$deferPlayerOwnerDisconnect"),
                "common-listener disconnect must contain one A5 nonblocking owner decision");
        assertEquals(0, callCount(disconnect, MINECRAFT_SERVER, "executeBlocking",
                "(Ljava/lang/Runnable;)V"),
                "the wrapped executeBlocking call must not remain duplicated");
        MethodNode disconnectHandler = generatedMethod(listener,
                "$fabrolia$deferPlayerOwnerDisconnect");
        String deferDesc =
                "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                        + "Lnet/minecraft/server/MinecraftServer;Ljava/lang/Runnable;)Z";
        int defer = callIndex(disconnectHandler, A5_PLAYER_TICKS,
                "deferBlockingDisconnect", deferDesc);
        int blockingOriginal = callIndex(
                disconnectHandler, OPERATION, "call", OPERATION_CALL_DESC);
        assertEquals(1, callCount(disconnectHandler, A5_PLAYER_TICKS,
                "deferBlockingDisconnect", deferDesc));
        assertEquals(0, callCountByName(disconnectHandler, A5_PLAYER_TICKS,
                "runPhysicalMainDisconnect"),
                "physical ownership begins at onDisconnect, not this server-only producer");
        assertEquals(1, typeCount(disconnectHandler, Opcodes.INSTANCEOF,
                SERVER_GAME_LISTENER));
        assertTrue(defer >= 0 && blockingOriginal > defer,
                "transformed disconnect must defer an owner before vanilla executeBlocking");
        MethodNode disconnectBridge = uniqueMethod(listener,
                candidate -> candidate.name.startsWith("mixinextras$bridge$executeBlocking$")
                        && callCount(candidate, MINECRAFT_SERVER, "executeBlocking",
                        "(Ljava/lang/Runnable;)V") == 1,
                "ServerCommonPacketListenerImpl.executeBlocking Operation bridge");
        assertEquals(1, callCount(disconnectBridge, MINECRAFT_SERVER,
                "executeBlocking", "(Ljava/lang/Runnable;)V"));
    }

    private static void verifyCarpetFakeA5(ClassNode fake) {
        String componentDesc = "(Lnet/minecraft/network/chat/Component;)V";
        String onDisconnectDesc =
                "(Lnet/minecraft/network/DisconnectionDetails;)V";

        MethodNode duplicateLoginKill = method(fake, "kill", componentDesc);
        MethodNode scheduledKill = method(fake, "lambda$kill$0", componentDesc);
        assertEquals(1, callCount(duplicateLoginKill, SERVER_GAME_LISTENER,
                "onDisconnect", onDisconnectDesc),
                "duplicate-login teardown must reach the listener-wide terminal scope");
        assertEquals(1, callCount(scheduledKill, SERVER_GAME_LISTENER,
                "onDisconnect", onDisconnectDesc),
                "scheduled teardown must reach the listener-wide terminal scope");
        assertEquals(2, classCallCount(fake, SERVER_GAME_LISTENER, "onDisconnect"),
                "the two audited Carpet direct calls must remain unchanged");
        assertTrue(fake.methods.stream().noneMatch(method ->
                        method.name.contains("$fabrolia$scopeDuplicateLoginDisconnect")
                                || method.name.contains("$fabrolia$scopeScheduledKillDisconnect")
                                || method.name.equals("fabrolia$runDirectDisconnect")),
                "callsite-specific disconnect wrappers would nest the global listener scope");


        MethodNode tick = method(fake, "tick", "()V");
        int physicalTick = callIndex(tick, MINECRAFT_SERVER, "getTickCount", "()I");
        int logicalTick = generatedCallIndex(tick, fake.name,
                "$fabrolia$islandLogicalTick");
        assertEquals(1, callCount(tick, MINECRAFT_SERVER, "getTickCount", "()I"));
        assertEquals(1, generatedCallCount(tick, fake.name,
                "$fabrolia$islandLogicalTick"));
        assertTrue(physicalTick >= 0 && logicalTick > physicalTick,
                "Carpet fake maintenance cadence must translate Main time to island L");

        assertEquals(1, generatedCallCount(tick, fake.name,
                "$fabrolia$pinCombinedEarly"));
        assertEquals(1, generatedCallCount(tick, fake.name,
                "$fabrolia$pinCombinedLate"));
        assertEquals(0, callCount(tick, SERVER_PLAYER, "tick", "()V"),
                "Carpet's wrapped invokespecial ServerPlayer.tick must not be duplicated");
        assertEquals(0, callCount(tick, fake.name, "doTick", "()V"),
                "Carpet's wrapped combined doTick must not be duplicated");

        MethodNode logicalHandler = generatedMethod(fake,
                "$fabrolia$islandLogicalTick");
        assertEquals(1, callCount(logicalHandler, A5_PLAYER_TICKS,
                "carpetTickCount", "(I)I"));
        assertCarpetCombinedFailureHandler(fake,
                "$fabrolia$pinCombinedEarly");
        assertCarpetCombinedFailureHandler(fake,
                "$fabrolia$pinCombinedLate");

        MethodNode earlyBridge = uniqueMethod(fake,
                candidate -> candidate.name.startsWith("mixinextras$bridge$tick$")
                        && callCount(candidate, SERVER_PLAYER, "tick", "()V") == 1,
                "Carpet invokespecial ServerPlayer.tick Operation bridge");
        assertEquals(1, callCount(earlyBridge, SERVER_PLAYER, "tick", "()V"));
        MethodNode lateBridge = uniqueMethod(fake,
                candidate -> candidate.name.startsWith("mixinextras$bridge$doTick$")
                        && callCount(candidate, fake.name, "doTick", "()V") == 1,
                "Carpet fake doTick Operation bridge");
        assertEquals(1, callCount(lateBridge, fake.name, "doTick", "()V"));
    }

    private static void assertCarpetCombinedFailureHandler(ClassNode fake,
                                                            String handlerSuffix) {
        MethodNode handler = generatedMethod(fake, handlerSuffix);
        assertEquals(1, callCount(handler, OPERATION, "call", OPERATION_CALL_DESC));
        assertEquals(1, callCount(handler, A5_PLAYER_TICKS,
                "translateCarpetCombinedFailure",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Ljava/lang/NullPointerException;)Ljava/lang/RuntimeException;"));
        assertTrue(handler.tryCatchBlocks.stream().anyMatch(block ->
                        "java/lang/NullPointerException".equals(block.type)),
                "Carpet's outer NPE containment must be translated before A5 can commit");
        int translate = callIndex(handler, A5_PLAYER_TICKS,
                "translateCarpetCombinedFailure",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Ljava/lang/NullPointerException;)Ljava/lang/RuntimeException;");
        assertTrue(opcodeIndexAfter(handler, Opcodes.ATHROW, translate) > translate,
                "translated Carpet combined failure must escape as a non-NPE fail-stop");
    }








    /** Final transformed lifecycle wrappers retain protected entry and W cleanup. */
    private static void verifyA5LifecycleLeaseComposition(
            ClassNode player, ClassNode playerList) {
        MethodNode playerLease = uniqueMethod(player,
                candidate -> candidate.name.equals("fabrolia$withPlayerLifecycleLease"),
                "transformed ServerPlayer lifecycle helper");
        int scopeFinish = callIndexByName(playerLease,
                PLAYER_LIFECYCLE_SCOPE, "finish");
        int cleanupMerge = callIndexByName(playerLease,
                SERVER_PLAYER, "fabrolia$mergePlayerTickFailure");
        int cleanupWinner = callIndexByName(playerLease,
                REGION_SCHEDULER, "publishFatalAndSelectWinner");
        int cleanupMerges = callCountByName(playerLease,
                SERVER_PLAYER, "fabrolia$mergePlayerTickFailure");
        int winnerSelections = callCountByName(playerLease,
                REGION_SCHEDULER, "publishFatalAndSelectWinner");
        assertEquals(1, callCountByName(playerLease,
                REGION_SCHEDULER, "commitSuccessIfHealthy"));
        assertTrue(winnerSelections > 0 && winnerSelections == cleanupMerges + 2,
                "cleanup merges reselect W; body and final success-cut catches select it "
                        + "without merging behind entered vanilla P");
        assertTrue(scopeFinish >= 0 && cleanupMerge > scopeFinish
                        && cleanupWinner > cleanupMerge,
                "only unexpected scope cleanup may be merged, then shared W is reselected");

        MethodNode place = uniqueMethod(playerList,
                candidate -> candidate.name.contains(
                        "fabrolia$leasePlayerSessionPublication")
                        && callCountByName(candidate, PLAYER_LIST,
                        "fabrolia$withPlayerLifecycleLease") == 1,
                "transformed placeNewPlayer lifecycle wrapper");
        MethodNode respawn = uniqueMethod(playerList,
                candidate -> candidate.name.contains("fabrolia$leasePlayerRespawn")
                        && callCountByName(candidate, PLAYER_LIST,
                        "fabrolia$withPlayerLifecycleLease") == 1,
                "transformed respawn lifecycle wrapper");
        assertEquals(1, callCountByName(place, PLAYER_LIST,
                "fabrolia$withPlayerLifecycleLease"));
        assertEquals(1, callCountByName(respawn, PLAYER_LIST,
                "fabrolia$withPlayerLifecycleLease"));
    }

    private static void verifyCarpetMutationRouter(ClassNode router) {
        assertFalse(router.name.startsWith(FABROLIA_MIXIN_PACKAGE),
                "runtime helpers referenced by transformed targets must live outside the "
                        + "defined Fabrolia Mixin package: " + router.name);
        assertTrue((router.access & Opcodes.ACC_PUBLIC) != 0,
                "cross-package Carpet mutation router must be ACC_PUBLIC");

        MethodNode route = method(router, "route", CARPET_MUTATION_ROUTE_DESC);
        assertTrue((route.access & Opcodes.ACC_PUBLIC) != 0,
                "cross-package Carpet mutation route must be ACC_PUBLIC");
        assertTrue((route.access & Opcodes.ACC_STATIC) != 0,
                "cross-package Carpet mutation route must be ACC_STATIC");
    }

    private static void verifyCarpetCommandA5(ClassNode command) {
        String manipulateDesc = "(Lcom/mojang/brigadier/context/CommandContext;"
                + "Ljava/util/function/Consumer;)I";
        MethodNode manipulate = method(command, "manipulate", manipulateDesc);
        assertEquals(1, generatedCallFamilyCount(manipulate, command.name,
                "$fabrolia$routeActionPackMutation"));
        assertEquals(0, callCount(manipulate, "java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V"));
        MethodNode actionHandler = generatedMethod(command,
                "$fabrolia$routeActionPackMutation");
        uniqueMethod(command,
                candidate -> candidate.name.contains(
                        "$fabrolia$routeActionPackMutation$mixinextras$bridge$")
                        && generatedCallCount(candidate, command.name,
                        "$fabrolia$routeActionPackMutation") == 1,
                "Carpet action-pack LocalRef sugar bridge");
        assertEquals(1, staticCallCount(actionHandler, CARPET_MUTATION_ROUTER,
                "route", CARPET_MUTATION_ROUTE_DESC));
        assertEquals(1, callCount(actionHandler, OPERATION, "call", OPERATION_CALL_DESC));
        MethodNode actionBridge = uniqueMethod(command,
                candidate -> candidate.name.startsWith("mixinextras$bridge$accept$")
                        && callCount(candidate, "java/util/function/Consumer", "accept",
                        "(Ljava/lang/Object;)V") == 1,
                "Carpet action-pack Consumer.accept Operation bridge");
        assertEquals(1, callCount(actionBridge, "java/util/function/Consumer", "accept",
                "(Ljava/lang/Object;)V"));

        String killDesc = "(Lcom/mojang/brigadier/context/CommandContext;)I";
        MethodNode kill = method(command, "kill", killDesc);
        assertEquals(1, generatedCallCount(kill, command.name,
                "$fabrolia$routeFakeKill"));
        assertEquals(0, callCount(kill, SERVER_PLAYER, "kill",
                "(Lnet/minecraft/server/level/ServerLevel;)V"));
        MethodNode killHandler = generatedMethod(command, "$fabrolia$routeFakeKill");
        assertEquals(1, staticCallCount(killHandler, CARPET_MUTATION_ROUTER,
                "route", CARPET_MUTATION_ROUTE_DESC));
        assertEquals(1, callCount(killHandler, OPERATION, "call", OPERATION_CALL_DESC));
        MethodNode killBridge = uniqueMethod(command,
                candidate -> candidate.name.startsWith("mixinextras$bridge$kill$")
                        && callCount(candidate, SERVER_PLAYER, "kill",
                        "(Lnet/minecraft/server/level/ServerLevel;)V") == 1,
                "Carpet fake kill Operation bridge");
        assertEquals(1, callCount(killBridge, SERVER_PLAYER, "kill",
                "(Lnet/minecraft/server/level/ServerLevel;)V"));
    }

    /** Final Carpet task waits must fail before observing either blocking primitive. */
    private static void verifyCarpetThreadValueBlockingWaitGuards(
            ClassNode threadValue) {
        String valueDesc = "()Lcarpet/script/value/Value;";

        MethodNode join = method(threadValue, "join", valueDesc);
        MethodInsnNode joinGuardDispatch = generatedHandlerCall(
                join, threadValue, "$guardJoin");
        int joinGuard = callIndex(join, threadValue.name,
                joinGuardDispatch.name, joinGuardDispatch.desc);
        int taskFuture = fieldIndex(join, Opcodes.GETFIELD,
                threadValue.name, "taskFuture",
                "Ljava/util/concurrent/CompletableFuture;");
        int futureGet = callIndex(join, "java/util/concurrent/CompletableFuture",
                "get", "()Ljava/lang/Object;");
        assertEquals(1, callCount(join, "java/util/concurrent/CompletableFuture",
                "get", "()Ljava/lang/Object;"));
        assertTrue(joinGuard >= 0 && taskFuture > joinGuard && futureGet > taskFuture,
                "ThreadValue.join must run its HEAD guard before reading and blocking on "
                        + "the original taskFuture");
        verifyCarpetBlockingWaitHandler(method(threadValue,
                        joinGuardDispatch.name, joinGuardDispatch.desc),
                "ThreadValue.join");

        MethodNode typedNext = method(threadValue, "next", valueDesc);
        MethodInsnNode awaitGuardDispatch = generatedHandlerCall(
                typedNext, threadValue, "$guardAwait");
        int awaitGuard = callIndex(typedNext, threadValue.name,
                awaitGuardDispatch.name, awaitGuardDispatch.desc);
        int coState = fieldIndex(typedNext, Opcodes.GETFIELD,
                threadValue.name, "coState", "Ljava/util/Deque;");
        int dequeWait = callIndex(typedNext, "java/util/Deque", "wait", "(J)V");
        assertEquals(1, callCount(typedNext,
                "java/util/Deque", "wait", "(J)V"));
        assertTrue(awaitGuard >= 0 && coState > awaitGuard && dequeWait > coState,
                "typed ThreadValue.next must run its HEAD guard before entering the "
                        + "original coroutine queue wait");
        verifyCarpetBlockingWaitHandler(method(threadValue,
                        awaitGuardDispatch.name, awaitGuardDispatch.desc),
                "typed ThreadValue.next");

        MethodNode bridgeNext = method(threadValue, "next", "()Ljava/lang/Object;");
        assertEquals(1, callCount(bridgeNext, threadValue.name,
                "next", valueDesc),
                "the Iterator bridge must delegate exactly once to guarded typed next");
        assertEquals(0, generatedCallFamilyCount(bridgeNext, threadValue.name,
                "$guardAwait"),
                "the bridge must not duplicate the typed next HEAD guard");
    }

    private static void verifyCarpetBlockingWaitHandler(
            MethodNode handler, String description) {
        assertEquals(1, staticCallCount(handler,
                REGION_MAIN_BLOCKING_WAIT_GUARD,
                "failIfCurrentThreadParticipates", "(Ljava/lang/String;)V"),
                description + " handler must enter the shared fail-stop guard once");
        assertTrue(handler.tryCatchBlocks == null || handler.tryCatchBlocks.isEmpty(),
                description + " handler must not suppress a fatal failure");
    }

    /**
     * Final merged proof for PoiSection's copy-on-write read publication. Lithium
     * deliberately leaves its old private implementations in the target class;
     * the safety property is that none of the six public entry points can reach
     * those live-map bodies because Fabrolia's replacement handlers never invoke
     * their supplied Operation.
     */
    private static void verifyPoiSectionSnapshotComposition(ClassNode poi) {
        String snapshotDesc = "L" + POI_READ_SNAPSHOT + ";";
        String snapshotAccessorDesc = "()" + snapshotDesc;
        String groupedSnapshotDesc = "()L" + GROUPED_SNAPSHOT + ";";
        String captureDesc = "(Ljava/lang/Object;Ljava/util/Map;"
                + "Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;Z)"
                + snapshotDesc;

        List<FieldNode> snapshotFields = poi.fields.stream()
                .filter(field -> field.name.equals("fabrolia$readSnapshot")
                        && field.desc.equals(snapshotDesc))
                .toList();
        assertEquals(1, snapshotFields.size(),
                "transformed PoiSection must own one cached read snapshot field");
        FieldNode snapshotField = snapshotFields.getFirst();
        assertTrue((snapshotField.access & Opcodes.ACC_VOLATILE) != 0,
                "PoiSection snapshot publication must remain volatile");
        assertEquals(0, snapshotField.access & Opcodes.ACC_STATIC,
                "PoiSection snapshot must remain per-section state");
        assertEquals(1, poi.interfaces.stream()
                        .filter(POI_READ_ACCESS::equals).count(),
                "transformed PoiSection must expose the cached snapshot duck");

        String rootConstructorDesc = "(Ljava/lang/Runnable;ZLjava/util/List;)V";
        MethodNode rootConstructor = method(poi, "<init>", rootConstructorDesc);
        int recordsInitialization = fieldIndex(rootConstructor, Opcodes.PUTFIELD,
                poi.name, "records",
                "Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;");
        int groupsInitialization = fieldIndex(rootConstructor, Opcodes.PUTFIELD,
                poi.name, "byType", "Ljava/util/Map;");
        int validityInitialization = fieldIndex(rootConstructor, Opcodes.PUTFIELD,
                poi.name, "isValid", "Z");
        int initialPublication = generatedCallIndex(rootConstructor, poi.name,
                "$fabrolia$publishInitialReadSnapshot");
        int constructorReturn = lastOpcodeIndex(rootConstructor, Opcodes.RETURN);
        assertEquals(1, generatedCallCount(rootConstructor, poi.name,
                "$fabrolia$publishInitialReadSnapshot"));
        assertTrue(recordsInitialization >= 0 && groupsInitialization >= 0
                        && validityInitialization >= 0
                        && initialPublication > recordsInitialization
                        && initialPublication > groupsInitialization
                        && initialPublication > validityInitialization
                        && constructorReturn > initialPublication,
                "the exact root constructor must publish after all live indexes and validity "
                        + "are initialized, before RETURN");

        MethodNode initialHandler = generatedMethod(poi,
                "$fabrolia$publishInitialReadSnapshot");
        assertEquals(1, opcodeCount(initialHandler, Opcodes.MONITORENTER),
                "initial PoiSection snapshot capture must own the section monitor");
        assertTrue(opcodeCount(initialHandler, Opcodes.MONITOREXIT) >= 2,
                "initial PoiSection snapshot capture must release its monitor on every exit");
        assertEquals(1, callCount(initialHandler, poi.name,
                "fabrolia$publishReadSnapshotLocked", "()V"));
        int initialAcquire = opcodeIndex(initialHandler, Opcodes.MONITORENTER);
        int initialCapture = callIndex(initialHandler, poi.name,
                "fabrolia$publishReadSnapshotLocked", "()V");
        int initialRelease = opcodeIndexAfter(initialHandler,
                Opcodes.MONITOREXIT, initialCapture);
        assertTrue(initialAcquire >= 0 && initialCapture > initialAcquire
                        && initialRelease > initialCapture,
                "constructor publication must execute completely inside the section monitor");

        MethodNode publisher = method(poi,
                "fabrolia$publishReadSnapshotLocked", "()V");
        assertEquals(0, publisher.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(publisher, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(publisher, Opcodes.MONITOREXIT));
        assertEquals(1, staticCallCount(publisher, THREAD,
                "holdsLock", "(Ljava/lang/Object;)Z"),
                "snapshot publication must fail closed outside the writer monitor");
        assertEquals(1, staticCallCount(publisher, POI_READ_SNAPSHOT,
                "capture", captureDesc));
        assertEquals(1, fieldCount(publisher, Opcodes.PUTFIELD, poi.name,
                "fabrolia$readSnapshot", snapshotDesc));
        assertEquals(1, fieldCount(publisher, Opcodes.GETFIELD, poi.name,
                "byType", "Ljava/util/Map;"));
        assertEquals(1, fieldCount(publisher, Opcodes.GETFIELD, poi.name,
                "records", "Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;"));
        assertEquals(1, fieldCount(publisher, Opcodes.GETFIELD, poi.name,
                "isValid", "Z"));
        int lockAssertion = callIndex(publisher, THREAD,
                "holdsLock", "(Ljava/lang/Object;)Z");
        int capture = callIndex(publisher, POI_READ_SNAPSHOT,
                "capture", captureDesc);
        int publication = fieldIndex(publisher, Opcodes.PUTFIELD, poi.name,
                "fabrolia$readSnapshot", snapshotDesc);
        assertTrue(lockAssertion >= 0 && capture > lockAssertion
                        && publication > capture,
                "publisher must prove monitor ownership, capture one coherent view, then "
                        + "perform the volatile publication");
        assertEquals(1, poi.methods.stream().mapToInt(candidate ->
                        staticCallCount(candidate, POI_READ_SNAPSHOT,
                                "capture", captureDesc)).sum(),
                "PoiSection may capture only in its writer-side publisher, never per query");

        MethodNode snapshotAccessor = method(poi,
                "fabrolia$poiReadSnapshot", snapshotAccessorDesc);
        assertPoiLockFreeSnapshotBody(poi, snapshotAccessor);
        assertEquals(1, fieldCount(snapshotAccessor, Opcodes.GETFIELD, poi.name,
                "fabrolia$readSnapshot", snapshotDesc));
        assertEquals(1, typeCount(snapshotAccessor, Opcodes.NEW,
                "java/lang/IllegalStateException"),
                "an impossible missing constructor publication must fail closed");

        MethodNode publishBeforeDirty = generatedMethod(poi,
                "$fabrolia$publishBeforeDirty");
        assertEquals(0, opcodeCount(publishBeforeDirty, Opcodes.MONITORENTER),
                "dirty publication inherits the surrounding exact writer monitor");
        assertEquals(0, opcodeCount(publishBeforeDirty, Opcodes.MONITOREXIT));
        assertEquals(1, callCount(publishBeforeDirty, poi.name,
                "fabrolia$publishReadSnapshotLocked", "()V"));
        assertEquals(1, callCount(publishBeforeDirty, OPERATION,
                "call", OPERATION_CALL_DESC));
        assertEquals(0, callCount(publishBeforeDirty,
                "java/lang/Runnable", "run", "()V"),
                "the dirty callback must remain behind its exact wrapped operation");
        int republish = callIndex(publishBeforeDirty, poi.name,
                "fabrolia$publishReadSnapshotLocked", "()V");
        int dirtyNotification = callIndex(publishBeforeDirty, OPERATION,
                "call", OPERATION_CALL_DESC);
        assertTrue(republish >= 0 && dirtyNotification > republish,
                "the new COW view must become visible before vanilla's exact dirty callback");

        String addDesc = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)"
                + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;";
        assertPoiMutationPublicationRoute(poi, "add", addDesc,
                "$fabrolia$syncSectionAdd", true);
        assertPoiMutationPublicationRoute(poi, "remove",
                "(Lnet/minecraft/core/BlockPos;)V",
                "$fabrolia$syncSectionRemove", true);
        assertPoiMutationPublicationRoute(poi, "refresh",
                "(Ljava/util/function/Consumer;)V",
                "$fabrolia$syncSectionRefresh", true);
        assertPoiMutationPublicationRoute(poi, "release",
                "(Lnet/minecraft/core/BlockPos;)Z",
                "$fabrolia$syncSectionRelease", false);
        assertEquals(3, poi.methods.stream().mapToInt(candidate ->
                        generatedCallCount(candidate, poi.name,
                                "$fabrolia$publishBeforeDirty")).sum(),
                "only add/remove/refresh structural mutations may republish membership");

        MethodNode pack = assertPoiSnapshotReaderRoute(poi, "pack",
                "()Lnet/minecraft/world/entity/ai/village/poi/PoiSection$Packed;",
                "$fabrolia$snapshotSectionPack", true);
        assertEquals(1, callCount(pack, POI_READ_SNAPSHOT,
                "records", "()Ljava/util/List;"));
        assertEquals(1, callCount(pack, POI_READ_SNAPSHOT,
                "isValid", "()Z"));
        assertEquals(1, callCount(pack, POI_SECTION + "$Packed", "<init>",
                "(ZLjava/util/List;)V"));

        MethodNode recordsReader = assertPoiSnapshotReaderRoute(poi,
                "getRecords",
                "(Ljava/util/function/Predicate;"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)"
                        + "Ljava/util/stream/Stream;",
                "$fabrolia$snapshotSectionRecords", true);
        assertEquals(1, callCount(recordsReader, POI_READ_SNAPSHOT,
                "groupedRecords", groupedSnapshotDesc));
        assertEquals(1, callCount(recordsReader, GROUPED_SNAPSHOT, "stream",
                "(Ljava/util/function/Predicate;Ljava/util/function/Predicate;)"
                        + "Ljava/util/stream/Stream;"),
                "vanilla getRecords must stream only the eager immutable group copy");

        MethodNode recordLookup = assertPoiSnapshotReaderRoute(poi,
                "getPoiRecord",
                "(Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;",
                "$fabrolia$snapshotSectionRecordLookup", false);
        assertEquals(1, callCount(recordLookup, POI_READ_SNAPSHOT,
                "recordAt", "(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;"));

        MethodNode validityReader = assertPoiSnapshotReaderRoute(poi,
                "isValid", "()Z",
                "$fabrolia$snapshotSectionValidityRead", true);
        assertEquals(1, callCount(validityReader, POI_READ_SNAPSHOT,
                "isValid", "()Z"));

        MethodNode lithiumSnapshot = method(poi,
                "fabrolia$lithiumPoiSnapshot", snapshotAccessorDesc);
        assertPoiLockFreeSnapshotBody(poi, lithiumSnapshot);
        assertEquals(1, callCount(lithiumSnapshot, POI_READ_ACCESS,
                "fabrolia$poiReadSnapshot", snapshotAccessorDesc),
                "Lithium readers must share the same volatile COW publication");

        assertPoiLithiumSnapshotRoute(poi,
                "lithium$collectMatchingPoints",
                "(Ljava/util/function/Predicate;"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;"
                        + "Ljava/util/function/Consumer;)V",
                "$fabrolia$snapshotLithiumMatchingPoints",
                "groupedRecords", groupedSnapshotDesc);
        assertPoiLithiumSnapshotRoute(poi,
                "lithium$getL2ClosestMatchingPoint",
                "(Lnet/minecraft/core/BlockPos;Ljava/util/function/Predicate;"
                        + "Ljava/util/function/Predicate;)"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;",
                "$fabrolia$snapshotLithiumClosestPoint",
                "groupedRecords", groupedSnapshotDesc);
        assertPoiLithiumSnapshotRoute(poi,
                "lithium$collectMatchingPointsL2Limited",
                "(Lnet/minecraft/core/BlockPos;JLjava/util/function/Predicate;"
                        + "Ljava/util/function/Predicate;Ljava/util/function/Consumer;I)V",
                "$fabrolia$snapshotLithiumLimitedPoints",
                "groupedRecords", groupedSnapshotDesc);
        assertPoiLithiumSnapshotRoute(poi,
                "lithium$getFirstMatchingPoint",
                "(Lnet/minecraft/core/BlockPos;JLjava/util/function/Predicate;"
                        + "Ljava/util/function/Predicate;"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;",
                "$fabrolia$snapshotLithiumFirstPoint",
                "groupedRecords", groupedSnapshotDesc);
        assertPoiLithiumSnapshotRoute(poi,
                "lithium$getAt",
                "(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;",
                "$fabrolia$snapshotLithiumRecordLookup",
                "recordAt", "(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/entity/ai/village/poi/PoiRecord;");
        assertPoiLithiumSnapshotRoute(poi,
                "lithium$iterate",
                "(Ljava/util/function/Predicate;)Ljava/util/Iterator;",
                "$fabrolia$snapshotLithiumIterator",
                "groupedRecords", groupedSnapshotDesc);
        assertEquals(6, poi.methods.stream().mapToInt(candidate ->
                        callCount(candidate, poi.name,
                                "fabrolia$lithiumPoiSnapshot", snapshotAccessorDesc)).sum(),
                "all six Lithium public readers must enter the cached snapshot path once");
    }

    private static MethodNode assertPoiMutationPublicationRoute(
            ClassNode poi, String targetName, String targetDesc,
            String syncHandlerSuffix, boolean publishesMembership) {
        MethodNode target = method(poi, targetName, targetDesc);
        assertEquals(0, target.access & Opcodes.ACC_SYNCHRONIZED,
                targetName + targetDesc + " must use the exact per-section wrapper monitor");
        assertEquals(0, opcodeCount(target, Opcodes.MONITORENTER));
        assertEquals(0, opcodeCount(target, Opcodes.MONITOREXIT));
        assertEquals(1, generatedCallCount(target, poi.name, syncHandlerSuffix));

        MethodNode syncHandler = generatedMethod(poi, syncHandlerSuffix);
        assertEquals(1, opcodeCount(syncHandler, Opcodes.MONITORENTER),
                syncHandler.name + " must acquire the exact PoiSection monitor");
        assertTrue(opcodeCount(syncHandler, Opcodes.MONITOREXIT) >= 2,
                syncHandler.name + " must release the monitor on normal and exceptional exits");
        assertEquals(1, callCount(syncHandler, OPERATION,
                "call", OPERATION_CALL_DESC));
        int acquire = opcodeIndex(syncHandler, Opcodes.MONITORENTER);
        int original = callIndex(syncHandler, OPERATION,
                "call", OPERATION_CALL_DESC);
        int release = opcodeIndexAfter(syncHandler, Opcodes.MONITOREXIT, original);
        assertTrue(acquire >= 0 && original > acquire && release > original,
                syncHandler.name + " must retain the complete vanilla mutation under monitor");

        MethodNode vanillaBody = uniqueMethod(poi,
                candidate -> candidate.name.startsWith(
                                targetName + "$mixinextras$wrapped$")
                        && candidate.desc.equals(targetDesc),
                "PoiSection wrapped vanilla " + targetName + targetDesc);
        int expectedPublications = publishesMembership ? 1 : 0;
        assertEquals(expectedPublications,
                generatedCallCount(vanillaBody, poi.name,
                        "$fabrolia$publishBeforeDirty"),
                targetName + targetDesc + " has the wrong COW publication census");
        if (publishesMembership) {
            assertEquals(0, callCount(vanillaBody,
                    "java/lang/Runnable", "run", "()V"),
                    targetName + " must not bypass publish-before-dirty");
        }
        return vanillaBody;
    }

    private static MethodNode assertPoiSnapshotReaderRoute(
            ClassNode poi, String targetName, String targetDesc,
            String handlerSuffix, boolean publicSurface) {
        MethodNode target = method(poi, targetName, targetDesc);
        if (publicSurface) {
            assertTrue((target.access & Opcodes.ACC_PUBLIC) != 0,
                    targetName + targetDesc + " must remain a public reader surface");
        }
        assertPoiLockFreeSnapshotBody(poi, target);
        assertEquals(1, generatedCallCount(target, poi.name, handlerSuffix),
                targetName + targetDesc + " must route once through its COW replacement");

        MethodNode handler = generatedMethod(poi, handlerSuffix);
        assertPoiLockFreeSnapshotBody(poi, handler);
        assertEquals(1, callCount(handler, poi.name,
                "fabrolia$poiReadSnapshot",
                "()L" + POI_READ_SNAPSHOT + ";"));
        return handler;
    }

    private static MethodNode assertPoiLithiumSnapshotRoute(
            ClassNode poi, String targetName, String targetDesc,
            String handlerSuffix, String snapshotMethod, String snapshotMethodDesc) {
        MethodNode target = method(poi, targetName, targetDesc);
        assertTrue((target.access & Opcodes.ACC_PUBLIC) != 0,
                targetName + targetDesc + " must remain a public Lithium reader");
        assertPoiLockFreeSnapshotBody(poi, target);
        assertEquals(1, generatedCallCount(target, poi.name, handlerSuffix),
                targetName + targetDesc + " must route once through Fabrolia's snapshot");

        MethodNode handler = generatedMethod(poi, handlerSuffix);
        assertPoiLockFreeSnapshotBody(poi, handler);
        assertEquals(1, callCount(handler, poi.name,
                "fabrolia$lithiumPoiSnapshot",
                "()L" + POI_READ_SNAPSHOT + ";"));
        assertEquals(1, callCount(handler, POI_READ_SNAPSHOT,
                snapshotMethod, snapshotMethodDesc));
        return handler;
    }

    private static void assertPoiLockFreeSnapshotBody(ClassNode poi, MethodNode body) {
        assertEquals(0, body.access & Opcodes.ACC_SYNCHRONIZED,
                body.name + body.desc + " must remain lock-free");
        assertEquals(0, opcodeCount(body, Opcodes.MONITORENTER),
                body.name + body.desc + " must not acquire the section monitor");
        assertEquals(0, opcodeCount(body, Opcodes.MONITOREXIT),
                body.name + body.desc + " must not release a section monitor it never owns");
        assertTrue(body.tryCatchBlocks == null || body.tryCatchBlocks.isEmpty(),
                body.name + body.desc + " must not suppress a failed snapshot read");
        assertEquals(0, callCount(body, OPERATION,
                "call", OPERATION_CALL_DESC),
                body.name + body.desc + " must not escape to its original live-map body");
        assertEquals(0, staticCallCount(body, POI_READ_SNAPSHOT, "capture",
                "(Ljava/lang/Object;Ljava/util/Map;"
                        + "Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;Z)"
                        + "L" + POI_READ_SNAPSHOT + ";"),
                body.name + body.desc + " must not rebuild a snapshot per query");
        assertEquals(0, fieldCount(body, Opcodes.GETFIELD, poi.name,
                "byType", "Ljava/util/Map;"),
                body.name + body.desc + " must not read live byType membership");
        assertEquals(0, fieldCount(body, Opcodes.GETFIELD, poi.name,
                "records", "Lit/unimi/dsi/fastutil/shorts/Short2ObjectMap;"),
                body.name + body.desc + " must not read live position membership");
        assertEquals(0, fieldCount(body, Opcodes.GETFIELD, poi.name,
                "isValid", "Z"),
                body.name + body.desc + " must not bypass snapshot validity");
        long rawTraversalCalls = calls(body).stream().filter(call ->
                (call.owner.equals("java/util/Map")
                        && Set.of("entrySet", "get", "values", "forEach")
                        .contains(call.name))
                        || (call.owner.equals("java/util/Set")
                        && Set.of("iterator", "stream", "spliterator")
                        .contains(call.name))
                        || (call.owner.equals(
                        "it/unimi/dsi/fastutil/shorts/Short2ObjectMap")
                        && Set.of("get", "values", "short2ObjectEntrySet")
                        .contains(call.name))).count();
        assertEquals(0, rawTraversalCalls,
                body.name + body.desc + " must not traverse a raw live POI index");
    }

    private static void verifyLithium(ClassNode level, ClassNode tracker) {
        MethodNode blockEntity = method(level, "lithium$getLoadedExistingBlockEntity",
                "(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/level/block/entity/BlockEntity;");
        int current = callIndex(blockEntity, THREAD, "currentThread", THREAD_DESC);
        int hook = generatedCallIndex(blockEntity, level.name,
                "$fabrolia$logicalLoadedBlockEntityThread");
        int physical = fieldIndexAfter(blockEntity, Opcodes.GETFIELD, level.name,
                "thread", "Ljava/lang/Thread;", hook);
        assertEquals(1, callCount(blockEntity, THREAD, "currentThread", THREAD_DESC));
        assertEquals(1, generatedCallCount(blockEntity, level.name,
                "$fabrolia$logicalLoadedBlockEntityThread"));
        assertTrue(current >= 0 && hook > current && physical > hook,
                "Lithium loaded-block-entity thread comparison is not actor-aware");
        MethodNode levelHandler = generatedMethod(level,
                "$fabrolia$logicalLoadedBlockEntityThread");
        assertEquals(1, callCount(levelHandler, PLAYER_TICKS, "inExactPlayerActor", "()Z"));
        assertEquals(1, callCount(levelHandler, A5_PLAYER_TICKS, "inOwnerTurn", "()Z"));
        int v2Owner = callIndex(levelHandler, PLAYER_TICKS, "inExactPlayerActor", "()Z");
        int a5Owner = callIndex(levelHandler, A5_PLAYER_TICKS, "inOwnerTurn", "()Z");
        int levelThread = fieldIndex(levelHandler, Opcodes.GETFIELD, level.name,
                "thread", "Ljava/lang/Thread;");
        assertTrue(v2Owner >= 0 && a5Owner > v2Owner && levelThread > a5Owner,
                "Lithium loaded-BE hook must authenticate exact v2 OR autonomous A5 before "
                        + "substituting the physical Level.thread");
        assertEquals(1, fieldCount(levelHandler, Opcodes.GETFIELD, level.name,
                "thread", "Ljava/lang/Thread;"));

        MethodNode generatedAccessor = method(level, "getThread", THREAD_DESC);
        assertEquals(0, generatedCallCount(generatedAccessor, level.name,
                        "$fabrolia$logicalLoadedBlockEntityThread"),
                "Lithium accessor body must not be used as an injector target");
        assertEquals(1, fieldCount(generatedAccessor, Opcodes.GETFIELD, level.name,
                "thread", "Ljava/lang/Thread;"));

        assertLithiumTrackerHook(tracker,
                "onChunkAccessible",
                "(Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V",
                "$fabrolia$logicalAccessibleThread");
        assertLithiumTrackerHook(tracker,
                "onChunkInaccessible",
                "(Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/level/ChunkPos;)V",
                "$fabrolia$logicalInaccessibleThread");
    }

    private static void assertLithiumTrackerHook(ClassNode tracker, String methodName,
                                                  String desc, String handlerSuffix) {
        MethodNode target = method(tracker, methodName, desc);
        List<MethodInsnNode> calls = calls(target);
        int accessor = callListIndex(calls, call -> call.owner.equals(
                        "net/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor")
                && call.name.equals("getThread") && call.desc.equals(THREAD_DESC));
        int hook = callListIndex(calls, call -> call.owner.equals(tracker.name)
                && call.name.endsWith(handlerSuffix));
        int current = callListIndex(calls, call -> call.owner.equals(THREAD)
                && call.name.equals("currentThread") && call.desc.equals(THREAD_DESC));
        assertEquals(1, callCount(target,
                "net/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor",
                "getThread", THREAD_DESC));
        assertEquals(1, generatedCallCount(target, tracker.name, handlerSuffix));
        assertEquals(accessor + 1, hook,
                methodName + " must modify the exact accessor return at its caller");
        assertEquals(hook + 1, current,
                methodName + " logical thread must feed the existing currentThread compare");

        MethodNode handler = generatedMethod(tracker, handlerSuffix);
        assertEquals(0, callCount(handler, A5_PLAYER_TICKS, "inOwnerTurn", "()Z"),
                "Lithium chunk-status callbacks must remain v2-only");
        assertEquals(1, callCount(handler, PLAYER_TICKS, "inExactPlayerActor", "()Z"));
        assertEquals(1, callCount(handler, THREAD, "currentThread", THREAD_DESC));
    }

    private static void verifyC2meCollisionReplacement(ClassNode serverLevel,
                                                        ClassNode level) {
        String handlerSuffix = "$fabrolia$c2meActorAwareEntityIdCollisionCheck";
        MethodNode nextId = method(serverLevel, "getNextEntityId", "()I");
        assertEquals(1, generatedCallCount(nextId, serverLevel.name, handlerSuffix),
                "Fabrolia C2ME collision replacement is missing from getNextEntityId");
        assertEquals(0, callCount(nextId, SERVER_CHUNK_CACHE,
                "hasEntityWithId", "(I)Z"),
                "wrapped collision seam must not also retain a direct duplicate call");

        assertTrue(serverLevel.methods.stream()
                        .noneMatch(candidate -> candidate.name.contains("wrapIsTrackingEntity")),
                "cancelled C2ME wrapIsTrackingEntity handler is still merged");
        assertEquals(0, serverLevel.methods.stream()
                        .mapToInt(candidate -> callCountByName(candidate,
                                "com/ishland/c2me/base/mixin/access/IWorld", "getThread"))
                        .sum(),
                "cancelled upstream C2ME IWorld.getThread predicate is still reachable");

        MethodNode replacement = generatedMethod(serverLevel, handlerSuffix);
        List<MethodInsnNode> calls = calls(replacement);
        int physical = callListIndex(calls,
                call -> call.name.equals("fabrolia$getPhysicalLevelThread")
                        && call.desc.equals(THREAD_DESC));
        int current = callListIndex(calls, call -> call.owner.equals(THREAD)
                && call.name.equals("currentThread") && call.desc.equals(THREAD_DESC));
        int actor = callListIndex(calls, call -> call.owner.equals(PLAYER_TICKS)
                && call.name.equals("inExactPlayerActor") && call.desc.equals("()Z"));
        int original = callListIndex(calls, call -> call.owner.equals(OPERATION)
                && call.name.equals("call") && call.desc.equals(OPERATION_CALL_DESC));
        assertTrue(physical >= 0 && current > physical && actor > current && original > actor,
                "C2ME replacement must preserve physical owner, then add only exact actor, "
                        + "then call the original collision seam");
        assertEquals(1, callCount(replacement, OPERATION, "call", OPERATION_CALL_DESC));

        MethodNode accessor = method(level, "fabrolia$getPhysicalLevelThread", THREAD_DESC);
        assertEquals(1, fieldCount(accessor, Opcodes.GETFIELD, level.name,
                "thread", "Ljava/lang/Thread;"),
                "C2ME replacement accessor must read the physical Level.thread field");

        MethodNode bridge = uniqueMethod(serverLevel,
                candidate -> candidate.name.startsWith("mixinextras$bridge$hasEntityWithId$")
                        && callCount(candidate, SERVER_CHUNK_CACHE,
                        "hasEntityWithId", "(I)Z") == 1,
                "C2ME collision Operation bridge");
        assertEquals(1, callCount(bridge, SERVER_CHUNK_CACHE,
                "hasEntityWithId", "(I)Z"));
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .toList();
        assertEquals(1, matches.size(),
                "expected one " + owner.name + "." + name + desc + ", found "
                        + matches.stream().map(candidate -> candidate.name + candidate.desc).toList());
        return matches.getFirst();
    }

    private static MethodNode generatedMethod(ClassNode owner, String suffix) {
        return uniqueMethod(owner, candidate -> candidate.name.endsWith(suffix),
                owner.name + " generated method *" + suffix);
    }

    private static MethodInsnNode generatedHandlerCall(
            MethodNode target, ClassNode owner, String nameToken) {
        List<MethodInsnNode> matches = calls(target).stream()
                .filter(call -> call.owner.equals(owner.name)
                        && call.name.contains(nameToken))
                .toList();
        assertEquals(1, matches.size(),
                "expected one " + owner.name + " generated handler call containing "
                        + nameToken + " from " + target.name + target.desc + ", found "
                        + methodNames(matches));
        return matches.getFirst();
    }

    private static MethodNode uniqueMethod(ClassNode owner, Predicate<MethodNode> predicate,
                                           String description) {
        List<MethodNode> matches = owner.methods.stream().filter(predicate).toList();
        assertEquals(1, matches.size(),
                "expected one " + description + ", found "
                        + matches.stream().map(candidate -> candidate.name + candidate.desc).toList());
        return matches.getFirst();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call);
        }
        return result;
    }

    private static List<String> methodNames(List<MethodInsnNode> calls) {
        return calls.stream().map(call -> call.owner + "." + call.name + call.desc).toList();
    }

    private static int callCount(MethodNode method, String owner, String name, String desc) {
        return (int) calls(method).stream()
                .filter(call -> call.owner.equals(owner)
                        && call.name.equals(name) && call.desc.equals(desc))
                .count();
    }

    private static int staticCallCount(MethodNode method, String owner,
                                       String name, String desc) {
        return (int) calls(method).stream()
                .filter(call -> call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(owner) && call.name.equals(name)
                        && call.desc.equals(desc))
                .count();
    }

    private static int callCountByName(MethodNode method, String owner, String name) {
        return (int) calls(method).stream()
                .filter(call -> call.owner.equals(owner) && call.name.equals(name))
                .count();
    }

    private static int callIndex(MethodNode method, String owner, String name, String desc) {
        return callIndexAfter(method, owner, name, desc, -1);
    }

    private static int callIndexAfter(MethodNode method, String owner, String name,
                                      String desc, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int callIndexByName(MethodNode method, String owner, String name) {
        return callIndexByNameAfter(method, owner, name, -1);
    }

    private static int callIndexByNameAfter(MethodNode method, String owner, String name,
                                            int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static int lastCallIndexByName(MethodNode method, String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) found = index;
            index++;
        }
        return found;
    }

    private static int firstCallIndexByNames(MethodNode method, String owner,
                                             List<String> names) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && names.stream().anyMatch(name -> call.name.equals(name)
                    || call.name.startsWith(name))) return index;
            index++;
        }
        return -1;
    }

    private static int lastCallIndex(MethodNode method, String owner,
                                     String name, String desc) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) found = index;
            index++;
        }
        return found;
    }

    /** The guarded call is inside a Throwable region whose handler closes before rethrow. */
    private static boolean hasThrowableCleanupAroundCall(MethodNode method, int guardedCall,
                                                         String cleanupOwner,
                                                         String cleanupName,
                                                         String cleanupDesc) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (!"java/lang/Throwable".equals(block.type)
                    || start < 0 || start > guardedCall || end <= guardedCall
                    || handler < 0) continue;
            boolean cleaned = false;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (index < handler) {
                    index++;
                    continue;
                }
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(cleanupOwner) && call.name.equals(cleanupName)
                        && call.desc.equals(cleanupDesc)) cleaned = true;
                if (instruction.getOpcode() == Opcodes.ATHROW && cleaned) return true;
                index++;
            }
        }
        return false;
    }


    private static boolean hasCatchAllCleanupAroundCall(MethodNode method, int guardedCall,
                                                        String cleanupOwner,
                                                        String cleanupName,
                                                        String cleanupDesc) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start);
            int end = method.instructions.indexOf(block.end);
            int handler = method.instructions.indexOf(block.handler);
            if (block.type != null || start < 0 || start > guardedCall
                    || end <= guardedCall || handler < 0) continue;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (index >= handler && instruction instanceof MethodInsnNode call
                        && call.owner.equals(cleanupOwner) && call.name.equals(cleanupName)
                        && call.desc.equals(cleanupDesc)) return true;
                index++;
            }
        }
        return false;
    }


    private static int fieldWriteCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.PUTFIELD
                    || instruction.getOpcode() == Opcodes.PUTSTATIC) count++;
        }
        return count;
    }

    private static int opcodeIndexAfter(MethodNode method, int opcode, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction.getOpcode() == opcode) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeIndex(MethodNode method, int opcode) {
        return opcodeIndexAfter(method, opcode, -1);
    }

    private static int lastOpcodeIndex(MethodNode method, int opcode) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) found = index;
            index++;
        }
        return found;
    }

    private static int previousRealOpcode(MethodNode method, int before) {
        for (int index = before - 1; index >= 0; index--) {
            int opcode = method.instructions.get(index).getOpcode();
            if (opcode >= 0) return opcode;
        }
        return -1;
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getNext();
        }
        assertNotNull(instruction, "expected a following executable instruction");
        return instruction;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }


    private static int generatedCallCount(MethodNode method, String owner, String suffix) {
        return (int) calls(method).stream()
                .filter(call -> call.owner.equals(owner) && call.name.endsWith(suffix))
                .count();
    }

    private static int invokedynamicTargetCount(MethodNode method, String owner,
                                                String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode dynamic)) continue;
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle target
                        && target.getOwner().equals(owner)
                        && target.getName().equals(name)
                        && target.getDesc().equals(desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int invokedynamicTargetIndex(MethodNode method, String owner,
                                                String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle target
                            && target.getOwner().equals(owner)
                            && target.getName().equals(name)
                            && target.getDesc().equals(desc)) return index;
                }
            }
            index++;
        }
        return -1;
    }

    private static int generatedCallFamilyCount(MethodNode method, String owner,
                                                String routeToken) {
        return (int) calls(method).stream()
                .filter(call -> call.owner.equals(owner)
                        && call.name.contains(routeToken))
                .count();
    }

    private static int generatedCallIndex(MethodNode method, String owner, String suffix) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.endsWith(suffix)) return index;
            index++;
        }
        return -1;
    }

    private static int callListIndex(List<MethodInsnNode> calls,
                                     Predicate<MethodInsnNode> predicate) {
        for (int i = 0; i < calls.size(); i++) {
            if (predicate.test(calls.get(i))) return i;
        }
        return -1;
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        return fieldIndexAfter(method, opcode, owner, name, desc, -1);
    }

    private static int fieldIndexAfter(MethodNode method, int opcode, String owner,
                                       String name, String desc, int after) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index > after && instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.owner.equals(owner)
                    && field.name.equals(name) && field.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldCount(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode && field.owner.equals(owner)
                    && field.name.equals(name) && field.desc.equals(desc)) count++;
        }
        return count;
    }


    private static int classCallCount(ClassNode type, String owner, String name) {
        int count = 0;
        for (MethodNode method : type.methods) {
            count += callCountByName(method, owner, name);
        }
        return count;
    }


    private static boolean hasBackwardJump(MethodNode method) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label) <= index) {
                return true;
            }
            index++;
        }
        return false;
    }



    private static int fieldCountByName(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.owner.equals(owner) && field.name.equals(name)) count++;
        }
        return count;
    }

    private static boolean hasImmediateIntFieldWrite(MethodNode method, String owner,
                                                     String name, int constantOpcode,
                                                     Integer ldcValue) {
        int write = fieldIndex(method, Opcodes.PUTFIELD, owner, name, "I");
        if (write < 0 || fieldCount(method, Opcodes.PUTFIELD, owner, name, "I") != 1) {
            return false;
        }
        AbstractInsnNode value = previousOpcodeInstruction(method.instructions.get(write));
        if (value == null || value.getOpcode() != constantOpcode) return false;
        return ldcValue == null || value instanceof LdcInsnNode ldc
                && ldcValue.equals(ldc.cst);
    }

    private static boolean hasCapacityConstructedFieldWrite(MethodNode method, String owner,
                                                            String name, String type,
                                                            int capacity) {
        String descriptor = "L" + type + ";";
        int write = fieldIndex(method, Opcodes.PUTFIELD, owner, name, descriptor);
        if (write < 0 || fieldCount(method, Opcodes.PUTFIELD, owner, name, descriptor) != 1) {
            return false;
        }
        AbstractInsnNode constructor = previousOpcodeInstruction(method.instructions.get(write));
        AbstractInsnNode argument = previousOpcodeInstruction(constructor);
        AbstractInsnNode duplicate = previousOpcodeInstruction(argument);
        AbstractInsnNode allocation = previousOpcodeInstruction(duplicate);
        int capacityOpcode = switch (capacity) {
            case 0 -> Opcodes.ICONST_0;
            case 1 -> Opcodes.ICONST_1;
            case 2 -> Opcodes.ICONST_2;
            case 3 -> Opcodes.ICONST_3;
            case 4 -> Opcodes.ICONST_4;
            case 5 -> Opcodes.ICONST_5;
            default -> -1;
        };
        return constructor instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL
                && type.equals(call.owner)
                && "<init>".equals(call.name) && "(I)V".equals(call.desc)
                && argument != null && argument.getOpcode() == capacityOpcode
                && duplicate != null && duplicate.getOpcode() == Opcodes.DUP
                && allocation instanceof TypeInsnNode allocated
                && allocation.getOpcode() == Opcodes.NEW
                && type.equals(allocated.desc);
    }

    private static AbstractInsnNode previousOpcodeInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (AbstractInsnNode previous = instruction.getPrevious(); previous != null;
             previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }


    private static int typeCount(MethodNode method, int opcode, String internalName) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && type.desc.equals(internalName)) count++;
        }
        return count;
    }

    private static int typeIndex(MethodNode method, int opcode, String internalName) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == opcode && type.desc.equals(internalName)) return index;
            index++;
        }
        return -1;
    }




    private record Export(Path root, Path artifact, Map<String, ClassNode> classes) {
        ClassNode type(String internalName) {
            ClassNode node = classes.get(internalName);
            if (node == null) fail("class was not loaded from " + root + ": " + internalName);
            return node;
        }

        ClassNode optionalType(String internalName) {
            return classes.get(internalName);
        }
    }
}
