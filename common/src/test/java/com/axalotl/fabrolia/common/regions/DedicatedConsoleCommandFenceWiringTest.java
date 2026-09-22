package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.rcon.thread.RconClient;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the exact 26.2 console and dual-receipt RCON fence topologies. */
final class DedicatedConsoleCommandFenceWiringTest {

    private static final String COMMANDS = "net/minecraft/commands/Commands";
    private static final String PREFIXED_DESC =
            "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V";
    private static final String SCOPE =
            "com/axalotl/fabrolia/common/regions/ConsoleCommandFenceScope";
    private static final String RCON_SCOPE =
            "com/axalotl/fabrolia/common/regions/RconCommandFenceScope";
    private static final String PLAYER_SCOPE =
            "com/axalotl/fabrolia/common/regions/PlayerCommandFenceScope";
    private static final String ISLAND_COMMANDS =
            "com/axalotl/fabrolia/common/regions/IslandCommands";
    private static final String MIXIN =
            "com/axalotl/fabrolia/common/mixin/commands/"
                    + "DedicatedServerConsoleCommandMixin";
    private static final String RCON_CLIENT_MIXIN =
            "com/axalotl/fabrolia/common/mixin/commands/"
                    + "RconClientCommandFenceMixin";
    private static final String COMMANDS_MIXIN =
            "com/axalotl/fabrolia/common/mixin/commands/CommandsMixin";

    @Test
    void vanilla262HasOneConsoleInvokeAndASeparateRconInvoke() throws Exception {
        ClassNode minecraft = readClass(MinecraftServer.class);
        MethodNode tickChildren = uniqueMethod(minecraft, "tickChildren");
        assertEquals(1, calls(tickChildren,
                "net/minecraft/server/MinecraftServer", "tickConnection", "()V"));

        ClassNode server = readClass(DedicatedServer.class);
        MethodNode tickConnection = uniqueMethod(server, "tickConnection");
        int superTick = callIndex(tickConnection,
                "net/minecraft/server/MinecraftServer", "tickConnection", "()V");
        int consoleDrain = callIndex(tickConnection,
                "net/minecraft/server/dedicated/DedicatedServer",
                "handleConsoleInputs", "()V");
        assertEquals(1, calls(tickConnection,
                "net/minecraft/server/dedicated/DedicatedServer",
                "handleConsoleInputs", "()V"));
        assertTrue(superTick >= 0 && consoleDrain > superTick,
                "connection/network callbacks and their monitors must finish before console");
        MethodNode console = uniqueMethod(server, "handleConsoleInputs");
        assertEquals("()V", console.desc);
        assertEquals(1, calls(console, COMMANDS,
                "performPrefixedCommand", PREFIXED_DESC));
        assertEquals(0, tickConnection.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, console.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(tickConnection, Opcodes.MONITORENTER)
                + opcodeCount(tickConnection, Opcodes.MONITOREXIT));
        assertEquals(0, opcodeCount(console, Opcodes.MONITORENTER)
                + opcodeCount(console, Opcodes.MONITOREXIT));

        MethodNode rcon = uniqueMethod(server, "lambda$runCommand$0");
        assertEquals(1, calls(rcon, COMMANDS,
                "performPrefixedCommand", PREFIXED_DESC),
                "RCON is a separate topology and cannot borrow the console grant");
    }

    @Test
    void vanilla262PinsTheAuthenticatedRconAndCleanMainDrainReceipts()
            throws Exception {
        MethodNode clientRun = uniqueMethod(readClass(RconClient.class), "run");
        assertEquals("()V", clientRun.desc);
        assertEquals(1, calls(clientRun,
                "net/minecraft/server/ServerInterface", "runCommand",
                "(Ljava/lang/String;)Ljava/lang/String;"),
                "only the authenticated vanilla RCON command edge may offer origin");

        ClassNode dedicated = readClass(DedicatedServer.class);
        MethodNode runCommand = uniqueMethod(dedicated, "runCommand");
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", runCommand.desc);
        int prepare = callIndex(runCommand,
                "net/minecraft/server/rcon/RconConsoleSource",
                "prepareForCommand", "()V");
        int executeBlocking = callIndex(runCommand,
                "net/minecraft/server/dedicated/DedicatedServer",
                "executeBlocking", "(Ljava/lang/Runnable;)V");
        int response = callIndex(runCommand,
                "net/minecraft/server/rcon/RconConsoleSource",
                "getCommandResponse", "()Ljava/lang/String;");
        assertEquals(1, calls(runCommand,
                "net/minecraft/server/dedicated/DedicatedServer",
                "executeBlocking", "(Ljava/lang/Runnable;)V"));
        assertTrue(prepare >= 0 && executeBlocking > prepare && response > executeBlocking,
                "runCommand must prepare, synchronously execute its one task, then reply");
        assertEquals(0, runCommand.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(runCommand, Opcodes.MONITORENTER)
                + opcodeCount(runCommand, Opcodes.MONITOREXIT));

        MethodNode lambda = uniqueMethod(dedicated, "lambda$runCommand$0");
        assertEquals("(Ljava/lang/String;)V", lambda.desc);
        assertEquals(1, calls(lambda,
                "net/minecraft/server/rcon/RconConsoleSource",
                "createCommandSourceStack",
                "()Lnet/minecraft/commands/CommandSourceStack;"));
        assertEquals(1, calls(lambda, COMMANDS,
                "performPrefixedCommand", PREFIXED_DESC));
        assertEquals(0, lambda.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(lambda, Opcodes.MONITORENTER)
                + opcodeCount(lambda, Opcodes.MONITOREXIT));

        ClassNode minecraft = readClass(MinecraftServer.class);
        MethodNode spinRoot = uniqueMethod(minecraft, "lambda$spin$0");
        assertEquals("(Ljava/util/concurrent/atomic/AtomicReference;)V",
                spinRoot.desc);
        assertEquals(1, calls(spinRoot,
                "net/minecraft/server/MinecraftServer", "runServer", "()V"));

        MethodNode runServer = uniqueMethod(minecraft, "runServer");
        assertEquals(1, calls(runServer,
                "net/minecraft/server/MinecraftServer",
                "waitUntilNextTick", "()V"),
                "only the real server loop may offer the top-level wait receipt");
        MethodNode wait = uniqueMethod(minecraft, "waitUntilNextTick");
        int runAllTasks = callIndex(wait,
                "net/minecraft/server/MinecraftServer", "runAllTasks", "()V");
        int managedBlock = callIndex(wait,
                "net/minecraft/server/MinecraftServer", "managedBlock",
                "(Ljava/util/function/BooleanSupplier;)V");
        assertEquals(1, calls(wait,
                "net/minecraft/server/MinecraftServer", "runAllTasks", "()V"));
        assertEquals(1, calls(wait,
                "net/minecraft/server/MinecraftServer", "managedBlock",
                "(Ljava/util/function/BooleanSupplier;)V"));
        assertTrue(runAllTasks >= 0 && managedBlock > runAllTasks,
                "the clean runAllTasks drain precedes the top-level managedBlock drain");
        assertEquals(0, wait.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(wait, Opcodes.MONITORENTER)
                + opcodeCount(wait, Opcodes.MONITOREXIT));

        MethodNode doRunTask = uniqueMethod(
                readClass(ReentrantBlockableEventLoop.class), "doRunTask");
        String loop = "net/minecraft/util/thread/ReentrantBlockableEventLoop";
        int taskBody = callIndex(doRunTask,
                "net/minecraft/util/thread/BlockableEventLoop", "doRunTask",
                "(Ljava/lang/Runnable;)V");
        assertTrue(taskBody >= 0,
                "vanilla must increment reentrantCount around every task body; "
                        + "top-level depth is one and a nested pump is at least two");
        assertEquals(3, fieldCount(doRunTask, Opcodes.GETFIELD,
                loop, "reentrantCount", "I"));
        assertEquals(3, fieldCount(doRunTask, Opcodes.PUTFIELD,
                loop, "reentrantCount", "I"));
        assertEquals(1, opcodeCount(doRunTask, Opcodes.IADD));
        assertEquals(2, opcodeCount(doRunTask, Opcodes.ISUB));
        assertEquals(1, fieldCountBefore(doRunTask, taskBody,
                Opcodes.PUTFIELD, loop, "reentrantCount", "I"));
        assertEquals(2, fieldCountAfter(doRunTask, taskBody,
                Opcodes.PUTFIELD, loop, "reentrantCount", "I"));

        MethodNode tickTaskRun = uniqueMethod(readClass(TickTask.class), "run");
        int capturedRunnable = fieldIndex(tickTaskRun, Opcodes.GETFIELD,
                "net/minecraft/server/TickTask", "runnable",
                "Ljava/lang/Runnable;");
        int capturedBody = callIndex(tickTaskRun,
                "java/lang/Runnable", "run", "()V");
        assertEquals(1, fieldCount(tickTaskRun, Opcodes.GETFIELD,
                "net/minecraft/server/TickTask", "runnable",
                "Ljava/lang/Runnable;"));
        assertEquals(1, calls(tickTaskRun,
                "java/lang/Runnable", "run", "()V"));
        assertTrue(capturedRunnable >= 0 && capturedBody > capturedRunnable,
                "TickTask must invoke the exact Runnable captured at FIFO publication");
        assertTrue(tickTaskRun.tryCatchBlocks.isEmpty(),
                "TickTask itself must not catch, suppress, or replay its captured body");
        assertEquals(0, tickTaskRun.access & Opcodes.ACC_SYNCHRONIZED);
        assertEquals(0, opcodeCount(tickTaskRun, Opcodes.MONITORENTER)
                + opcodeCount(tickTaskRun, Opcodes.MONITOREXIT));
    }

    @Test
    void nestedSameServerDifferentCommandCannotStealTheOuterTaskReceipt() {
        Object server = new Object();
        Object commandA = new Object();
        Object commandB = new Object();
        Runnable rawA = () -> {};
        Runnable rawB = () -> {};
        Runnable[] wrappedA = new Runnable[1];
        Runnable[] observedB = new Runnable[1];

        String result = RconCommandFenceScope.runRconClientCommand(
                server, commandA,
                () -> RconCommandFenceScope.runDedicatedRconCommandEntry(
                        server, commandA, () -> {
                            String nested = RconCommandFenceScope
                                    .runDedicatedRconCommandEntry(
                                            server, commandB, () -> {
                                                observedB[0] = RconCommandFenceScope
                                                        .wrapDedicatedRconExecuteTask(
                                                                server, rawB);
                                                return "B";
                                            });
                            assertEquals("B", nested);
                            wrappedA[0] = RconCommandFenceScope
                                    .wrapDedicatedRconExecuteTask(server, rawA);
                            return "A";
                        }));

        assertEquals("A", result);
        assertSame(rawB, observedB[0],
                "nested command B is depth two and must keep its task raw");
        assertNotSame(rawA, wrappedA[0],
                "the outer identity-matched command A retains the one-shot task receipt");
    }

    @Test
    void dedicatedAndRconClientMixinsPinBothReceiptsAndKeepConsoleExact()
            throws Exception {
        ClassNode mixin = readClass(MIXIN);

        MethodNode rconClientEntry = uniqueMethod(mixin,
                "fabrolia$frameExactRconRunCommand");
        assertPinnedWrapMethod(rconClientEntry,
                "runCommand(Ljava/lang/String;)Ljava/lang/String;");
        assertEquals(1, callsNamed(rconClientEntry, RCON_SCOPE,
                "runDedicatedRconCommandEntry"));
        assertCapturedOriginalCalledOnce(mixin, rconClientEntry.name);

        MethodNode rconTask = uniqueMethod(mixin,
                "fabrolia$wrapExactRconMainTask");
        assertPinnedWrapOperation(rconTask,
                "runCommand(Ljava/lang/String;)Ljava/lang/String;",
                "Lnet/minecraft/server/dedicated/DedicatedServer;"
                        + "executeBlocking(Ljava/lang/Runnable;)V");
        int taskWrap = callIndex(rconTask, RCON_SCOPE,
                "wrapDedicatedRconExecuteTask");
        int rawExecuteBlocking = callIndex(rconTask,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call");
        assertEquals(1, callsNamed(rconTask, RCON_SCOPE,
                "wrapDedicatedRconExecuteTask"));
        assertEquals(1, callsNamed(rconTask,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call"), "the raw executeBlocking Operation must be called once");
        assertTrue(taskWrap >= 0 && rawExecuteBlocking > taskWrap,
                "wrap the exact Runnable, then invoke vanilla executeBlocking once");

        MethodNode rconLambdaEntry = uniqueMethod(mixin,
                "fabrolia$frameExactRconLambda");
        assertPinnedWrapMethod(rconLambdaEntry,
                "lambda$runCommand$0(Ljava/lang/String;)V");
        assertEquals(1, callsNamed(rconLambdaEntry, RCON_SCOPE,
                "runDedicatedRconLambdaEntry"));
        assertCapturedOriginalCalledOnce(mixin, rconLambdaEntry.name);

        MethodNode rconCommand = uniqueMethod(mixin,
                "fabrolia$markExactRconCommand");
        assertPinnedWrapOperation(rconCommand,
                "lambda$runCommand$0(Ljava/lang/String;)V",
                "Lnet/minecraft/commands/Commands;performPrefixedCommand"
                        + PREFIXED_DESC);
        assertEquals(1, callsNamed(rconCommand, RCON_SCOPE,
                "runDedicatedRconPrefixedCommand"));
        assertCapturedOriginalCalledOnce(mixin, rconCommand.name);

        MethodNode tickEntry = uniqueMethod(mixin,
                "fabrolia$frameExactTickConnectionEntry");
        assertPinnedWrapMethod(tickEntry, "tickConnection()V");
        assertEquals(1, calls(tickEntry, SCOPE,
                "runDedicatedTickConnectionEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        assertCapturedOriginalCalledOnce(mixin, tickEntry.name);
        MethodNode drainHandler = uniqueMethod(mixin,
                "fabrolia$markExactConsoleInputDrain");
        assertPinnedWrapOperation(drainHandler, "tickConnection()V",
                "Lnet/minecraft/server/dedicated/DedicatedServer;"
                        + "handleConsoleInputs()V");
        assertEquals(1, calls(drainHandler, SCOPE,
                "runDedicatedConsoleInputs",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        assertCapturedOriginalCalledOnce(mixin, drainHandler.name);
        MethodNode drainEntry = uniqueMethod(mixin,
                "fabrolia$frameExactConsoleInputEntry");
        assertPinnedWrapMethod(drainEntry, "handleConsoleInputs()V");
        assertEquals(1, calls(drainEntry, SCOPE,
                "runDedicatedConsoleInputsEntry",
                "(Ljava/lang/Object;Ljava/lang/Runnable;)V"));
        assertCapturedOriginalCalledOnce(mixin, drainEntry.name);

        MethodNode handler = uniqueMethod(mixin,
                "fabrolia$markExactConsoleCommand");
        assertPinnedWrapOperation(handler, "handleConsoleInputs()V",
                "Lnet/minecraft/commands/Commands;performPrefixedCommand"
                        + PREFIXED_DESC);
        assertEquals(1, calls(handler, SCOPE,
                "runDedicatedConsolePrefixedCommand",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;"
                        + "Ljava/lang/Runnable;)V"));
        assertCapturedOriginalCalledOnce(mixin, handler.name);
        assertEquals(8, mixin.methods.stream().mapToInt(method -> callsNamed(
                method, "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call")).sum(), "both console and RCON bodies remain exact-once");
        assertEquals(4, annotationCount(mixin,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"));
        assertEquals(4, annotationCount(mixin,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));

        ClassNode clientMixin = readClass(RCON_CLIENT_MIXIN);
        MethodNode clientHandler = uniqueMethod(clientMixin,
                "fabrolia$markExactRconClientCommand");
        assertPinnedWrapOperation(clientHandler, "run()V",
                "Lnet/minecraft/server/ServerInterface;"
                        + "runCommand(Ljava/lang/String;)Ljava/lang/String;");
        assertEquals(1, callsNamed(clientHandler, RCON_SCOPE,
                "runRconClientCommand"));
        assertOriginalCalledOnce(clientHandler);
        assertCapturedOriginalCalledOnce(clientMixin, clientHandler.name);
        assertEquals(2, clientMixin.methods.stream().mapToInt(method -> callsNamed(
                method,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call")).sum(), "the dedicated and non-dedicated branches each preserve "
                        + "their single original callsite");
        assertEquals(1, annotationCount(clientMixin,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));
    }


    private static AnnotationNode annotation(MethodNode method, String desc) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode value : method.visibleAnnotations) {
                if (value.desc.equals(desc)) return value;
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode value : method.invisibleAnnotations) {
                if (value.desc.equals(desc)) return value;
            }
        }
        throw new AssertionError("annotation not found: " + desc);
    }

    private static void assertPinnedWrapMethod(MethodNode method, String target) {
        AnnotationNode wrap = annotation(method,
                "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
        Map<String, Object> values = annotationValues(wrap);
        assertEquals(List.of(target), values.get("method"));
        assertEquals(1, values.get("require"));
        assertEquals(1, values.get("allow"));
    }

    private static void assertPinnedWrapOperation(
            MethodNode method, String targetMethod, String targetInvoke) {
        AnnotationNode wrap = annotation(method,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        Map<String, Object> values = annotationValues(wrap);
        assertEquals(List.of(targetMethod), values.get("method"));
        assertEquals(1, values.get("require"));
        assertEquals(1, values.get("allow"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> points = (List<AnnotationNode>) values.get("at");
        assertEquals(1, points.size());
        Map<String, Object> at = annotationValues(points.getFirst());
        assertEquals("INVOKE", at.get("value"));
        assertEquals(targetInvoke, at.get("target"));
    }

    private static void assertOriginalCalledOnce(MethodNode method) {
        assertEquals(1, callsNamed(method,
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                "call"), method.name + " must enter its original body exactly once");
    }

    private static void assertCapturedOriginalCalledOnce(
            ClassNode owner, String wrapperName) {
        String prefix = "lambda$" + wrapperName + "$";
        List<MethodNode> captures = owner.methods.stream()
                .filter(method -> method.name.startsWith(prefix)).toList();
        assertEquals(1, captures.size(),
                wrapperName + " must capture one exact original body");
        assertOriginalCalledOnce(captures.getFirst());
    }

    private static int annotationCount(ClassNode owner, String desc) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            if (hasAnnotation(method.visibleAnnotations, desc)
                    || hasAnnotation(method.invisibleAnnotations, desc)) count++;
        }
        return count;
    }

    private static boolean hasAnnotation(
            List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(desc)) return true;
        }
        return false;
    }

    private static Map<String, Object> annotationValues(AnnotationNode annotation) {
        java.util.HashMap<String, Object> values = new java.util.HashMap<>();
        if (annotation.values == null) return values;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            values.put((String) annotation.values.get(i),
                    annotation.values.get(i + 1));
        }
        return values;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name) {
        List<MethodNode> methods = owner.methods.stream()
                .filter(method -> method.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner.name + "." + name);
        return methods.getFirst();
    }

    private static int calls(MethodNode method, String owner,
                             String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static int callsNamed(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static int callIndex(MethodNode method, String owner,
                                 String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static int fieldCount(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        return fieldCountBetween(method, 0, method.instructions.size(), opcode,
                owner, name, desc);
    }

    private static int fieldIndex(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)
                    && field.desc.equals(desc)) return index;
            index++;
        }
        return -1;
    }

    private static int fieldCountBefore(
            MethodNode method, int before, int opcode, String owner,
            String name, String desc) {
        return fieldCountBetween(method, 0, before, opcode, owner, name, desc);
    }

    private static int fieldCountAfter(
            MethodNode method, int after, int opcode, String owner,
            String name, String desc) {
        return fieldCountBetween(method, after + 1, method.instructions.size(),
                opcode, owner, name, desc);
    }

    private static int fieldCountBetween(
            MethodNode method, int from, int to, int opcode, String owner,
            String name, String desc) {
        int count = 0;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (index >= from && index < to
                    && instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)
                    && field.desc.equals(desc)) count++;
            index++;
        }
        return count;
    }


    private static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + Type.getInternalName(type) + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return read(input.readAllBytes());
        }
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (InputStream input = DedicatedConsoleCommandFenceWiringTest.class
                .getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(input, internalName);
            return read(input.readAllBytes());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

}
