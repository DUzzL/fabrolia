package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.commands.ConfigCommand;
import com.axalotl.fabrolia.common.commands.RegionCommand;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.mixin.server.ServerPlayerMixin;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins v1 removal while allowing the separate awaited entity-only v2 command. */
class PlayerTickExperimentRemovalTest {

    private static final String[] REMOVED_CLASSES = {
            "com/axalotl/fabrolia/common/regions/RegionalPlayerTicks.class",
            "com/axalotl/fabrolia/common/regions/RegionalPlayerTicks$Sim.class",
            "com/axalotl/fabrolia/common/regions/RegionalPlayerTicks$LaneItem.class",
            "com/axalotl/fabrolia/common/regions/PlayerOwnership.class",
            "com/axalotl/fabrolia/common/regions/PlayerOwnership$Owner.class",
            "com/axalotl/fabrolia/common/regions/PlayerOwnership$Domain.class",
            "com/axalotl/fabrolia/common/parallelised/utils/FabroliaListenerLane.class",
            "com/axalotl/fabrolia/common/mixin/server/ServerGamePacketRoutingMixin.class",
            "com/axalotl/fabrolia/common/mixin/network/PacketUtilsWorkerMixin.class"
    };

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void removedClassesAndMixinRegistrationsStayAbsent() throws Exception {
        ClassLoader loader = PlayerTickExperimentRemovalTest.class.getClassLoader();
        for (String resource : REMOVED_CLASSES) {
            assertNull(loader.getResource(resource), "removed experiment class returned: " + resource);
        }

        try (InputStream in = loader.getResourceAsStream("fabrolia.common.mixins.json")) {
            assertNotNull(in, "compiled mixin resource missing");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(json.contains("ServerGamePacketRoutingMixin"));
            assertFalse(json.contains("PacketUtilsWorkerMixin"));
            assertTrue(json.contains("server.PlayerInteractionOwnerMixin"),
                    "independent main-handler/island ownership guard disappeared");
        }
    }

    @Test
    void obsoleteConfigKeysStayAbsentAndV2CommandIsExplicit() {
        Set<String> fields = new HashSet<>();
        for (Field field : FabroliaConfig.class.getDeclaredFields()) fields.add(field.getName());
        assertFalse(fields.contains("enableRegionPlayerTicks"));
        assertFalse(fields.contains("regionalPlayerTicksExperimental"));

        CommandNode<CommandSourceStack> root = RegionCommand.register(Commands.literal("fabrolia")).build();
        CommandNode<CommandSourceStack> regions = root.getChild("regions");
        assertNotNull(regions);
        CommandNode<CommandSourceStack> playerTicks = regions.getChild("playerticks");
        assertNotNull(playerTicks, "v2 controlled-test command missing");
        assertNotNull(playerTicks.getCommand(), "bare playerticks must report status");
        assertNotNull(playerTicks.getChild("on"));
        assertNotNull(playerTicks.getChild("off"));
        assertNotNull(playerTicks.getChild("on").getCommand());
        assertNotNull(playerTicks.getChild("off").getCommand());
        assertEquals(Set.of("on", "off"), playerTicks.getChildren().stream()
                .map(CommandNode::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void playerEntityTicksCannotEnableWithoutAServer() {
        try {
            assertTrue(RegionalPlayerEntityTicksV2.setEnabled(null, false));
            assertFalse(RegionalPlayerEntityTicksV2.enabled());
            assertFalse(RegionalPlayerEntityTicksV2.setEnabled(null, true));
            assertFalse(RegionalPlayerEntityTicksV2.enabled());
        } finally {
            RegionalPlayerEntityTicksV2.setEnabled(null, false);
        }
    }

    @Test
    void playerActorPreferenceIsASeparateDefaultOffConfigValue() throws Exception {
        Field preference = FabroliaConfig.class.getDeclaredField("enablePlayerTickActor");
        assertEquals(boolean.class, preference.getType());
        assertTrue(java.lang.reflect.Modifier.isStatic(preference.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isVolatile(preference.getModifiers()),
                "config reload and the server lifecycle share this operator preference");

        String commonConfig = readRepositoryFile(
                "common/src/main/java/com/axalotl/fabrolia/common/config/FabroliaConfig.java");
        assertTrue(commonConfig.matches(
                        "(?s).*public static volatile boolean enablePlayerTickActor\\s*=\\s*false;.*"),
                "a new installation must remain opt-in even though later choices persist");

        List<String> mutableRuntimeBooleans = new ArrayList<>();
        for (Field field : RegionalPlayerEntityTicksV2.class.getDeclaredFields()) {
            if (field.getType() == boolean.class
                    && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                mutableRuntimeBooleans.add(field.getName());
            }
        }
        assertEquals(List.of("enabled"), mutableRuntimeBooleans,
                "the persisted preference must not become a second runtime feature gate");
    }

    @Test
    void startupAndLiveReloadApplyThePreferenceThroughTheAuthoritativeGate() throws Exception {
        String playerTicks =
                "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2";
        String config = "com/axalotl/fabrolia/common/config/FabroliaConfig";

        ClassNode coordinator = readClass(RegionalPlayerEntityTicksV2.class);
        MethodNode apply = method(coordinator, "applyConfiguredPreference",
                "(Lnet/minecraft/server/MinecraftServer;)Z");
        assertEquals(3, callCount(apply, playerTicks, "setEnabled"),
                "OFF, safe ON, and refused-ON normalization must share the central gate");
        assertEquals(1, fieldIndices(apply, Opcodes.GETSTATIC,
                config, "enablePlayerTickActor").size(),
                "the lifecycle seam must apply the loaded preference, including OFF");

        ClassNode scheduler = readClass(RegionScheduler.class);
        MethodNode start = method(scheduler, "start",
                "(Lnet/minecraft/server/MinecraftServer;)V");
        List<Integer> activeWrites = fieldIndices(start, Opcodes.PUTSTATIC,
                "com/axalotl/fabrolia/common/regions/RegionScheduler", "active");
        assertEquals(1, activeWrites.size(),
                "the direct start body retains only failed-start active=false cleanup");
        MethodNode activeCommitBody = scheduler.methods.stream()
                .filter(candidate -> candidate.name.startsWith("lambda$start$"))
                .filter(candidate -> fieldIndices(candidate, Opcodes.PUTSTATIC,
                        "com/axalotl/fabrolia/common/regions/RegionScheduler", "active")
                        .size() == 1)
                .findFirst().orElseThrow();
        assertEquals(1, fieldIndices(activeCommitBody, Opcodes.PUTSTATIC,
                "com/axalotl/fabrolia/common/regions/RegionScheduler", "active").size(),
                "startup success must publish active=true inside its pool-fatal commit cut");
        List<Integer> poolCommits = callIndices(start,
                "com/axalotl/fabrolia/common/regions/RegionWorkerPool",
                "commitSuccessIfHealthy");
        assertTrue(poolCommits.size() >= 2,
                "worker start and ACTIVE publication need distinct transition-pool cuts");
        int poolCommit = poolCommits.getLast();
        List<Integer> healthyBooleanStages = callIndices(start,
                "com/axalotl/fabrolia/common/regions/RegionScheduler",
                "callSuccessBooleanIfHealthy");
        assertTrue(healthyBooleanStages.size() >= 2
                        && healthyBooleanStages.get(1) > poolCommit,
                "ON cannot pass its region-scheduler prerequisite until startup commits active=true");
        MethodNode bootApply = uniqueMethodCalling(
                scheduler, playerTicks, "applyConfiguredPreference");
        assertEquals(1, callCount(bootApply, playerTicks, "applyConfiguredPreference"));

        ClassNode configCommand = readClass(ConfigCommand.class);
        MethodNode reload = uniqueMethodCalling(configCommand,
                "com/axalotl/fabrolia/common/platform/PlatformUtils", "reloadConfig");
        int loaderReload = callIndex(reload,
                "com/axalotl/fabrolia/common/platform/PlatformUtils", "reloadConfig");
        int liveApply = callIndex(reload, playerTicks, "applyConfiguredPreference");
        int success = callIndex(reload,
                "net/minecraft/commands/CommandSourceStack", "sendSuccess");
        assertTrue(loaderReload >= 0 && liveApply > loaderReload && success > liveApply,
                "reload may report success only after the loaded player preference is applied");
    }

    @Test
    void fabricAndNeoForgeRoundTripTheSamePlayerActorKey() throws Exception {
        String fabric = readRepositoryFile(
                "fabric/src/main/java/com/axalotl/fabrolia/fabric/config/FabroliaConfig.java");
        assertTrue(fabric.contains("bool(\"enablePlayerTickActor\""),
                "Fabric schema must retain the preference across atomic TOML rewrites");
        assertTrue(fabric.contains(
                        "() -> enablePlayerTickActor, v -> enablePlayerTickActor = v"),
                "Fabric must read and write the common preference, not a loader-local copy");

        String neoForge = readRepositoryFile(
                "neoforge/src/main/java/com/axalotl/fabrolia/neoforge/config/FabroliaConfig.java");
        assertTrue(neoForge.contains(
                        "ModConfigSpec.BooleanValue enablePlayerTickActor"),
                "NeoForge schema is missing the player actor preference");
        assertTrue(neoForge.contains("define(\"enablePlayerTickActor\""));
        assertTrue(neoForge.contains(
                        "FabroliaConfig.enablePlayerTickActor = enablePlayerTickActor.get()"),
                "NeoForge load must restore the boot preference");
        assertTrue(neoForge.contains(
                        "enablePlayerTickActor.set(com.axalotl.fabrolia.common.config."
                                + "FabroliaConfig.enablePlayerTickActor)"),
                "NeoForge save must flush command changes back to the spec");
    }

    @Test
    void masterToggleIsOneRollbackCapableLifecycleTransaction() throws Exception {
        MethodNode master = method(readClass(RegionCommand.class), "setEnabled",
                "(Lnet/minecraft/commands/CommandSourceStack;Z)V");
        String playerTicks = "com/axalotl/fabrolia/common/regions/RegionalPlayerEntityTicksV2";
        String scheduler = "com/axalotl/fabrolia/common/regions/RegionScheduler";
        String config = "com/axalotl/fabrolia/common/config/FabroliaConfig";
        String platform = "com/axalotl/fabrolia/common/platform/PlatformUtils";
        String source = "net/minecraft/commands/CommandSourceStack";

        int physicalPreflight = callIndex(master, playerTicks, "isPhysicalServerThread");
        int phasePreflight = callIndex(master, playerTicks, "schedulerLifecycleTransitionAllowed");
        List<Integer> configWrites = fieldIndices(master, Opcodes.PUTSTATIC,
                config, "enableRegionThreading");
        List<Integer> saves = callIndices(master, platform, "saveConfig");
        assertEquals(3, configWrites.size(),
                "target persist plus the persist-failure and lifecycle-failure rollbacks are required");
        assertEquals(2, saves.size(),
                "the target and a lifecycle-failure rollback must each be persisted exactly once");
        int targetWrite = configWrites.getFirst();
        int targetSave = saves.getFirst();
        assertTrue(physicalPreflight >= 0 && phasePreflight > physicalPreflight);
        assertTrue(dominates(master, physicalPreflight, targetWrite)
                        && dominates(master, physicalPreflight, targetSave)
                        && dominates(master, phasePreflight, targetWrite)
                        && dominates(master, phasePreflight, targetSave),
                "both command preflights must dominate the first config mutation and persistence");
        assertTrue(targetWrite < targetSave,
                "the requested value must be installed before it is persisted");

        int priorConfig = fieldIndex(master, Opcodes.GETSTATIC,
                config, "enableRegionThreading");
        assertTrue(priorConfig > phasePreflight && priorConfig < targetWrite,
                "rollback metadata must be captured before the first transaction mutation");

        int start = callIndex(master, scheduler, "start");
        int stop = callIndex(master, scheduler, "stop");
        assertTrue(start > targetSave && stop > targetSave,
                "lifecycle execution must follow successful target persistence");
        List<TryCatchBlockNode> lifecycleCatches = master.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)
                        && covers(master, block, start) && covers(master, block, stop))
                .toList();
        assertEquals(1, lifecycleCatches.size(),
                "one catch-covered lifecycle transaction must contain both start and stop");
        TryCatchBlockNode lifecycleCatch = lifecycleCatches.getFirst();
        List<Integer> coveredStateChecks = callIndices(master, scheduler, "isActive").stream()
                .filter(index -> covers(master, lifecycleCatch, index)).toList();
        assertEquals(2, coveredStateChecks.size(),
                "start and stop must each verify the resulting scheduler state before success");

        int handler = instructionIndex(master, lifecycleCatch.handler);
        VarInsnNode storedFailure = firstVarInstruction(master, handler, Opcodes.ASTORE);
        assertNotNull(storedFailure, "lifecycle catch must retain the thrown failure");
        JumpInsnNode failureCheck = firstNullCheck(master, handler);
        assertNotNull(failureCheck,
                "the caught lifecycle failure must control a distinct rollback fallthrough");
        assertEquals(Opcodes.IFNULL, failureCheck.getOpcode(),
                "a non-null caught failure must fall through into rollback");
        int failureCheckIndex = instructionIndex(master, failureCheck);
        int winnerSelection = callIndices(master, scheduler,
                "publishFatalAndSelectWinner").stream()
                .filter(index -> index > handler && index < failureCheckIndex)
                .findFirst().orElse(-1);
        assertTrue(winnerSelection > handler && winnerSelection < failureCheckIndex,
                "the exact lifecycle W must be selected before rollback is exposed");
        assertTrue(previousRealInstruction(failureCheck) instanceof VarInsnNode checkedFailure
                        && checkedFailure.getOpcode() == Opcodes.ALOAD,
                "the IFNULL guard must inspect the selected lifecycle winner");
        int successBlock = instructionIndex(master, failureCheck.label);
        assertTrue(successBlock > failureCheckIndex);

        int rollbackWrite = onlyFieldIndexBetween(master, Opcodes.PUTSTATIC,
                config, "enableRegionThreading", failureCheckIndex, successBlock);
        int rollbackSave = onlyCallIndexBetween(master, platform, "saveConfig",
                failureCheckIndex, successBlock);
        assertTrue(rollbackWrite > failureCheckIndex && rollbackSave > rollbackWrite,
                "the lifecycle exceptional path must restore and persist the prior config");
        assertTrue(coversAny(master, rollbackSave, "java/lang/Throwable"),
                "rollback persistence itself must be best-effort and catch-covered");

        List<Integer> gateWrites = callIndices(master, playerTicks, "setEnabled");
        assertEquals(1, gateWrites.size(),
                "the disable body may enter once; a failed body must never be re-enabled/retried");
        int fatalPublication = onlyCallIndexBetween(master,
                "com/axalotl/fabrolia/common/regions/IslandCommands",
                "publishAndPropagate", failureCheckIndex, successBlock);
        int rethrow = onlyOpcodeIndexBetween(master, Opcodes.ATHROW,
                fatalPublication, successBlock);
        int finalSuccess = lastCallIndex(master, source, "sendSuccess");
        assertTrue(fatalPublication > rollbackSave && rethrow > fatalPublication
                        && finalSuccess >= successBlock,
                "every lifecycle Throwable must publish fatal after cleanup and cannot reach success");
        assertEquals(0, callCountBetween(master, source, "sendFailure",
                        failureCheckIndex, successBlock),
                "an entered lifecycle failure is not a recoverable command response");
        assertEquals(0, callCountBetween(master, source, "sendSuccess",
                        failureCheckIndex, successBlock),
                "the transition-failure block must not emit a success message");
        assertFalse(reachable(master, failureCheckIndex + 1, finalSuccess),
                "the non-null lifecycle-failure fallthrough must have no CFG path to success");
    }

    @Test
    void fabroliaAddsNoDirectServerPlayerDoTickInvocation() throws Exception {
        assertFalse(calls(readClass(ServerPlayerMixin.class),
                "net/minecraft/server/level/ServerPlayer", "doTick"));

        URI location = ParallelProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path classes = Path.of(location);
        assertTrue(Files.isDirectory(classes), "expected Gradle main-class directory: " + classes);
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode node = read(Files.readAllBytes(path));
                if (node.name.startsWith("com/axalotl/fabrolia/")) {
                    assertFalse(calls(node, "net/minecraft/server/level/ServerPlayer", "doTick"),
                            "Fabrolia class adds direct doTick call: " + node.name);
                }
            }
        }
    }

    @Test
    void exactVanillaTickPlayerRetainsItsSingleDoTickBracket() throws Exception {
        ClassNode listener = readClass(ServerGamePacketListenerImpl.class);
        MethodNode tickPlayer = method(listener, "tickPlayer", "()Z");
        int reset = callIndex(tickPlayer, "resetPosition");
        int doTick = callIndex(tickPlayer, "doTick");
        int snap = callIndex(tickPlayer, "absSnapTo");
        assertTrue(reset >= 0 && doTick > reset && snap > doTick);

        Set<String> oldPositionWrites = new HashSet<>();
        int directDoTicks = 0;
        int index = 0;
        for (AbstractInsnNode insn : tickPlayer.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && Set.of("xo", "yo", "zo").contains(field.name)) {
                assertTrue(index > reset && index < doTick,
                        field.name + " moved outside resetPosition/doTick bracket");
                oldPositionWrites.add(field.name);
            }
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ServerPlayer")
                    && call.name.equals("doTick") && call.desc.equals("()V")) {
                directDoTicks++;
            }
            index++;
        }
        assertEquals(Set.of("xo", "yo", "zo"), oldPositionWrites);
        assertEquals(1, directDoTicks, "vanilla tickPlayer must invoke doTick exactly once");
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            root = root.getParent();
        }
        throw new AssertionError("repository file not found: " + relative);
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "class bytes missing for " + type.getName());
            return read(in.readAllBytes());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing " + node.name + "." + name + descriptor));
    }

    private static MethodNode uniqueMethodCalling(ClassNode node, String owner, String name) {
        var methods = node.methods.stream().filter(m -> calls(m, owner, name)).toList();
        assertEquals(1, methods.size(),
                "expected one " + node.name + " method calling " + owner + "." + name);
        return methods.getFirst();
    }

    private static boolean calls(ClassNode node, String owner, String name) {
        return node.methods.stream().anyMatch(method -> calls(method, owner, name));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static int lastCallIndex(MethodNode method, String owner, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) found = index;
            index++;
        }
        return found;
    }

    private static List<Integer> callIndices(MethodNode method, String owner, String name) {
        List<Integer> found = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) found.add(index);
            index++;
        }
        return found;
    }

    private static List<Integer> fieldIndices(MethodNode method, int opcode,
                                              String owner, String name) {
        List<Integer> found = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) found.add(index);
            index++;
        }
        return found;
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner, String name) {
        List<Integer> found = fieldIndices(method, opcode, owner, name);
        assertEquals(1, found.size(), "expected one " + owner + "." + name + " field access");
        return found.getFirst();
    }

    private static boolean dominates(MethodNode method, int candidate, int target) {
        return reachable(method, 0, target) && !reachableAvoiding(method, 0, target, candidate);
    }

    private static boolean reachable(MethodNode method, int from, int target) {
        return reachableAvoiding(method, from, target, -1);
    }

    private static boolean reachableAvoiding(MethodNode method, int from, int target, int blocked) {
        if (from == blocked) return false;
        boolean[] seen = new boolean[method.instructions.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            if (index < 0 || index >= seen.length || index == blocked || seen[index]) continue;
            if (index == target) return true;
            seen[index] = true;
            for (int successor : normalSuccessors(method, index)) pending.addLast(successor);
        }
        return false;
    }

    private static List<Integer> normalSuccessors(MethodNode method, int index) {
        AbstractInsnNode insn = method.instructions.get(index);
        int opcode = insn.getOpcode();
        List<Integer> successors = new ArrayList<>();
        if (insn instanceof JumpInsnNode jump) {
            successors.add(instructionIndex(method, jump.label));
            if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR && index + 1 < method.instructions.size()) {
                successors.add(index + 1);
            }
            return successors;
        }
        if (insn instanceof LookupSwitchInsnNode lookup) {
            successors.add(instructionIndex(method, lookup.dflt));
            lookup.labels.forEach(label -> successors.add(instructionIndex(method, label)));
            return successors;
        }
        if (insn instanceof TableSwitchInsnNode table) {
            successors.add(instructionIndex(method, table.dflt));
            table.labels.forEach(label -> successors.add(instructionIndex(method, label)));
            return successors;
        }
        if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
            return successors;
        }
        if (index + 1 < method.instructions.size()) successors.add(index + 1);
        return successors;
    }

    private static boolean covers(MethodNode method, TryCatchBlockNode block, int index) {
        return instructionIndex(method, block.start) <= index
                && index < instructionIndex(method, block.end);
    }

    private static boolean coversAny(MethodNode method, int index, String type) {
        return method.tryCatchBlocks.stream()
                .anyMatch(block -> type.equals(block.type) && covers(method, block, index));
    }

    private static int instructionIndex(MethodNode method, AbstractInsnNode instruction) {
        int index = method.instructions.indexOf(instruction);
        if (index < 0) throw new AssertionError("instruction is not in " + method.name + method.desc);
        return index;
    }

    private static VarInsnNode firstVarInstruction(MethodNode method, int from, int opcode) {
        for (int index = from; index < method.instructions.size(); index++) {
            AbstractInsnNode insn = method.instructions.get(index);
            if (insn instanceof VarInsnNode var && var.getOpcode() == opcode) return var;
        }
        return null;
    }

    private static JumpInsnNode firstNullCheck(MethodNode method, int from) {
        for (int index = from; index < method.instructions.size(); index++) {
            AbstractInsnNode insn = method.instructions.get(index);
            if (insn instanceof JumpInsnNode jump
                    && (jump.getOpcode() == Opcodes.IFNULL || jump.getOpcode() == Opcodes.IFNONNULL)) return jump;
        }
        return null;
    }

    private static AbstractInsnNode previousRealInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static int onlyFieldIndexBetween(MethodNode method, int opcode, String owner,
                                             String name, int after, int before) {
        List<Integer> matches = fieldIndices(method, opcode, owner, name).stream()
                .filter(index -> index > after && index < before).toList();
        assertEquals(1, matches.size(), "expected one rollback field write");
        return matches.getFirst();
    }

    private static int onlyCallIndexBetween(MethodNode method, String owner, String name,
                                            int after, int before) {
        List<Integer> matches = callIndices(method, owner, name).stream()
                .filter(index -> index > after && index < before).toList();
        assertEquals(1, matches.size(), "expected one " + owner + "." + name + " in range");
        return matches.getFirst();
    }

    private static int callCountBetween(MethodNode method, String owner, String name,
                                        int after, int before) {
        return (int) callIndices(method, owner, name).stream()
                .filter(index -> index > after && index < before).count();
    }

    private static int onlyTypeIndexBetween(MethodNode method, int opcode, String type,
                                            int after, int before) {
        List<Integer> matches = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && index < before && insn instanceof TypeInsnNode typed
                    && typed.getOpcode() == opcode && typed.desc.equals(type)) matches.add(index);
            index++;
        }
        assertEquals(1, matches.size(), "expected one type instruction " + opcode + " " + type);
        return matches.getFirst();
    }

    private static int onlyOpcodeIndexBetween(MethodNode method, int opcode,
                                              int after, int before) {
        List<Integer> matches = new ArrayList<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (index > after && index < before && insn.getOpcode() == opcode) matches.add(index);
            index++;
        }
        assertEquals(1, matches.size(), "expected one opcode " + opcode + " in range");
        return matches.getFirst();
    }

    private static int callIndex(MethodNode method, String name) {
        int found = -1;
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) {
                if (found >= 0) throw new AssertionError("multiple calls to " + name);
                found = index;
            }
            index++;
        }
        return found;
    }
}
