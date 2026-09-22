package com.axalotl.fabrolia.common.regions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NativeVanishCachePublicationTest {
    private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String OPERATION = Type.getInternalName(Operation.class);
    private static final String READ_HANDLER = "wrapMethod$fixture$fabrolia$readVanishObserverCache";
    private static final String DIRTY_HANDLER = "wrapMethod$fixture$fabrolia$dirtyVanishObserverCache";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);

    @Test
    void canonicalGraphVerifiesAndWarmReadsDoNotAcquireTheColdMonitor() throws Exception {
        try (Fixture fixture = new Fixture(Variant.CANONICAL)) {
            fixture.state.value = true;
            assertTrue(fixture.read());
            // A second thread must finish even while the original wrapper's monitor is held.
            synchronized (fixture.monitor()) {
                assertTrue(fixture.start(fixture::read).get());
            }
            assertEquals(1, fixture.state.loads.get());
            fixture.state.value = false;
            assertTrue(fixture.read(), "the published cache remains authoritative until dirtied");
            fixture.dirty();
            assertFalse(fixture.read());
            fixture.state.value = null;
            fixture.dirty();
            assertFalse(fixture.read(), "missing player data has the native false result");
            assertEquals(3, fixture.state.loads.get());
        }
    }

    @Test
    void concurrentColdReadersShareOneSuccessfulRefresh() throws Exception {
        try (Fixture fixture = new Fixture(Variant.CANONICAL)) {
            fixture.state.value = true;
            fixture.state.blockNextLoad();
            Worker<Boolean> first = fixture.start(fixture::read);
            await(fixture.state.entered);
            List<Worker<Boolean>> followers = new ArrayList<>();
            for (int i = 0; i < 3; i++) followers.add(fixture.start(fixture::read));
            for (Worker<Boolean> follower : followers) follower.awaitBlocked();
            assertEquals(1, fixture.state.loads.get());
            fixture.state.release.countDown();
            assertTrue(first.get());
            for (Worker<Boolean> follower : followers) assertTrue(follower.get());
            assertEquals(1, fixture.state.loads.get(), "each waiting reader rechecks dirty under the monitor");
        }
    }

    @Test
    void dirtyRacingBlockedRefreshIsNotOverwrittenByThatRefresh() throws Exception {
        try (Fixture fixture = new Fixture(Variant.CANONICAL)) {
            fixture.state.value = false;
            fixture.state.blockNextLoad();
            Worker<Boolean> cold = fixture.start(fixture::read);
            await(fixture.state.entered);
            // The in-flight loader already captured false; the invalidation belongs to true.
            fixture.state.value = true;
            Worker<Void> dirty = fixture.start(() -> {
                fixture.dirty();
                return null;
            });
            dirty.awaitBlocked();
            fixture.state.release.countDown();
            assertFalse(cold.get());
            dirty.get();
            assertTrue(fixture.read(), "completion of the older refresh must not lose the invalidation");
            assertEquals(2, fixture.state.loads.get());
            assertTrue(fixture.read());
            assertEquals(2, fixture.state.loads.get());
        }
    }

    @Test
    void failedRefreshPreservesOriginalExceptionAndReleasesMonitorForRetry() throws Exception {
        try (Fixture fixture = new Fixture(Variant.CANONICAL)) {
            IllegalStateException failure = new IllegalStateException("fixture loader failure");
            fixture.state.failure = failure;
            assertSame(failure, assertThrows(IllegalStateException.class, fixture::read));
            fixture.state.failure = null;
            fixture.state.value = true;
            // Retry elsewhere also detects a monitor leaked by the exceptional path.
            assertTrue(fixture.start(fixture::read).get());
            assertTrue(fixture.read());
            assertEquals(2, fixture.state.loads.get());
        }
    }

    @Test
    void foreignGetterAndLateHooksRemainObservableOnEveryWarmInvocation() throws Exception {
        for (Variant variant : List.of(Variant.GETTER_HOOK, Variant.HANDLER_HOOK, Variant.LATE_WRAPPER)) {
            try (Fixture fixture = new Fixture(variant)) {
                fixture.state.value = true;
                assertTrue(fixture.read());
                assertTrue(fixture.read());
                assertTrue(fixture.read());
                assertEquals(3, fixture.state.hooks.get(), variant.name());
                assertEquals(1, fixture.state.loads.get(), variant.name());
                fixture.state.value = false;
                fixture.dirty();
                assertFalse(fixture.read());
                assertEquals(4, fixture.state.hooks.get(), variant.name());
                assertEquals(2, fixture.state.loads.get(), variant.name());
            }
        }
    }

    @Test
    void foreignPrivateLeafHandleKeepsWarmReadsOnTheOriginalMonitor() throws Exception {
        try (Fixture fixture = new Fixture(Variant.FOREIGN_HANDLE)) {
            fixture.state.value = true;
            assertTrue((Boolean) invoke(fixture.player.getClass().getMethod("foreignRead"), fixture.player));
            Worker<Boolean> warm;
            synchronized (fixture.monitor()) {
                warm = fixture.start(fixture::read);
                warm.awaitBlocked();
            }
            assertTrue(warm.get());
            assertEquals(1, fixture.state.loads.get());
            fixture.state.value = false;
            fixture.dirty();
            assertFalse(fixture.read());
            assertEquals(2, fixture.state.loads.get());
        }
    }

    @Test
    void foreignWriterCannotExposeIntermediateCacheWhileHoldingTheOriginalMonitor() throws Exception {
        try (Fixture fixture = new Fixture(Variant.FOREIGN_WRITER)) {
            fixture.state.value = false;
            assertFalse(fixture.read());
            fixture.state.blockNextLoad();
            Worker<Void> writer = fixture.start(() -> {
                invoke(fixture.player.getClass().getMethod("foreignWrite"), fixture.player);
                return null;
            });
            await(fixture.state.entered);
            Worker<Boolean> reader = fixture.start(fixture::read);
            try {
                reader.awaitBlocked();
            } finally {
                fixture.state.release.countDown();
            }
            writer.get();
            assertFalse(reader.get(), "warm reads must not expose the foreign writer's intermediate true");
            assertEquals(1, fixture.state.loads.get());
        }
    }

    private enum Variant { CANONICAL, GETTER_HOOK, HANDLER_HOOK, LATE_WRAPPER, FOREIGN_HANDLE, FOREIGN_WRITER }

    private static final class Fixture implements AutoCloseable {
        final LoaderState state = new LoaderState();
        final Object player;
        final Method getter;
        final Method invalidator;
        final List<Worker<?>> workers = new ArrayList<>();

        Fixture(Variant variant) throws Exception {
            Map<String, String> names = Map.of(
                    Type.getInternalName(FixturePlayer.class), PLAYER,
                    Type.getInternalName(FixtureServer.class), SERVER,
                    Type.getInternalName(FixtureData.class), "me/drex/vanish/util/VanishData",
                    Type.getInternalName(FixtureManager.class), "me/drex/vanish/util/VanishManager",
                    Type.getInternalName(FixtureStorage.class), "eu/pb4/playerdata/api/storage/PlayerDataStorage",
                    Type.getInternalName(FixtureApi.class), "eu/pb4/playerdata/api/PlayerDataApi");
            Map<String, byte[]> definitions = new HashMap<>();
            for (Class<?> source : List.of(FixturePlayer.class, FixtureServer.class, FixtureData.class,
                    FixtureManager.class, FixtureStorage.class, FixtureApi.class)) {
                ClassNode node = remap(source, names);
                if (node.name.equals(PLAYER)) {
                    replaceWrapper(node, "vanish$isVanished", "Z", "Boolean", READ_HANDLER, "getLeaf");
                    replaceWrapper(node, "vanish$setDirty", "V", "Void", DIRTY_HANDLER, "dirtyLeaf");
                    // Native MixinExtras may clear empty tables before export.
                    // A serialization round-trip would hide this callback shape.
                    for (MethodNode method : node.methods) {
                        if (method.tryCatchBlocks != null && method.tryCatchBlocks.isEmpty()) {
                            method.tryCatchBlocks = null;
                        }
                    }
                    switch (variant) {
                        case GETTER_HOOK -> method(node, "getLeaf").instructions.insert(hook());
                        case HANDLER_HOOK -> method(node, READ_HANDLER).instructions.insert(hook());
                        case LATE_WRAPPER -> addLateWrapper(node);
                        case FOREIGN_WRITER -> { }
                        case FOREIGN_HANDLE -> addForeignHandle(node);
                        case CANONICAL -> { }
                    }
                    if (variant != Variant.FOREIGN_WRITER) node.methods.remove(method(node, "foreignWrite"));
                    byte[] before = bytes(node);
                    assertEquals(variant == Variant.CANONICAL, NativeVanishCachePublication.publish(node),
                            "accept the actual supported graph, reject the foreign graph: " + variant);
                    if (variant != Variant.CANONICAL) {
                        assertArrayEquals(before, bytes(node), "rejection must leave the entire graph unchanged");
                    }
                }
                definitions.put(node.name.replace('/', '.'), bytes(node));
            }
            ClassLoader loader = new FixtureLoader(definitions);
            Class<?> serverType = loader.loadClass(SERVER.replace('/', '.'));
            Object server = serverType.getConstructor(LoaderState.class).newInstance(state);
            Class<?> playerType = loader.loadClass(PLAYER.replace('/', '.'));
            player = playerType.getConstructor(serverType).newInstance(server);
            getter = playerType.getMethod("vanish$isVanished");
            invalidator = playerType.getMethod("vanish$setDirty");
            for (String name : List.of("vanished$dirty", "vanished$vanished")) {
                assertEquals(variant == Variant.CANONICAL,
                        Modifier.isVolatile(playerType.getDeclaredField(name).getModifiers()), name);
            }
        }

        boolean read() throws Exception { return (Boolean) invoke(getter, player); }
        void dirty() throws Exception { invoke(invalidator, player); }

        Object monitor() throws Exception {
            Field field = player.getClass().getDeclaredField("fabrolia$vanishObserverCacheLock");
            field.setAccessible(true);
            return field.get(player);
        }

        <T> Worker<T> start(Callable<T> task) {
            Worker<T> worker = new Worker<>(task);
            workers.add(worker);
            worker.thread.start();
            return worker;
        }

        @Override
        public void close() throws InterruptedException {
            state.release.countDown();
            for (Worker<?> worker : workers) worker.thread.interrupt();
            for (Worker<?> worker : workers) {
                worker.thread.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(worker.thread.isAlive(), "fixture worker did not terminate");
            }
        }
    }

    private static final class Worker<T> {
        final FutureTask<T> result;
        final Thread thread;

        Worker(Callable<T> task) {
            result = new FutureTask<>(task);
            thread = new Thread(result, "vanish-publication-fixture");
            thread.setDaemon(true);
        }

        T get() throws Exception { return result.get(5, TimeUnit.SECONDS); }

        void awaitBlocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.BLOCKED && !result.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, thread.getState(), "expected contention on the original monitor");
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "fixture latch timed out");
    }

    private static Object invoke(Method method, Object receiver) throws Exception {
        try {
            return method.invoke(receiver);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static ClassNode remap(Class<?> fixture, Map<String, String> names) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream input = fixture.getResourceAsStream("/" + Type.getInternalName(fixture) + ".class")) {
            if (input == null) throw new IOException("Missing fixture bytecode: " + fixture.getName());
            new ClassReader(input).accept(new ClassRemapper(node, new SimpleRemapper(names)), 0);
        }
        // Every cross-class fixture member is public: no nestmate privileges are needed or retained.
        node.nestHostClass = null;
        node.nestMembers = null;
        node.innerClasses.clear();
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        // Preserve javac's frames and exception tables; the JVM, not COMPUTE_FRAMES, verifies the transform.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    /** MixinExtras's native five-instruction wrapper and argument-checking boxing bridge. */
    private static void replaceWrapper(ClassNode node, String name, String result, String boxed,
                                       String handler, String leaf) {
        String bridgeName = "mixinextras$bridge$fixture$" + leaf;
        String bridgeDescriptor = "([Ljava/lang/Object;)Ljava/lang/" + boxed + ";";
        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + result, null, null);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitVarInsn(Opcodes.ALOAD, 0);
        wrapper.visitInvokeDynamicInsn("call", "(L" + PLAYER + ";)L" + OPERATION + ";", METAFACTORY,
                Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESPECIAL, PLAYER, bridgeName, bridgeDescriptor, false),
                Type.getMethodType(bridgeDescriptor));
        wrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, PLAYER, handler, "(L" + OPERATION + ";)" + result, false);
        wrapper.visitInsn(result.equals("Z") ? Opcodes.IRETURN : Opcodes.RETURN);
        node.methods.set(node.methods.indexOf(method(node, name)), wrapper);

        MethodNode bridge = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                bridgeName, bridgeDescriptor, null, null);
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitIntInsn(Opcodes.BIPUSH, 0);
        bridge.visitLdcInsn("[]");
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime", "checkArgumentCount",
                "([Ljava/lang/Object;ILjava/lang/String;)V", false);
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitInsn(Opcodes.POP);
        bridge.visitMethodInsn(Opcodes.INVOKESPECIAL, PLAYER, leaf, "()" + result, false);
        if (result.equals("Z")) {
            bridge.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        } else {
            bridge.visitInsn(Opcodes.ACONST_NULL);
            bridge.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Void");
        }
        bridge.visitInsn(Opcodes.ARETURN);
        node.methods.add(bridge);
    }

    private static InsnList hook() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, PLAYER, "server", "L" + SERVER + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SERVER, "state", Type.getDescriptor(LoaderState.class)));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LoaderState.class), "hook", "()V", false));
        return code;
    }

    private static void addLateWrapper(ClassNode node) {
        MethodNode original = method(node, "vanish$isVanished");
        original.name = "foreign$wrappedGetter";
        original.access = Opcodes.ACC_PRIVATE;
        MethodNode outer = new MethodNode(Opcodes.ACC_PUBLIC, "vanish$isVanished", "()Z", null, null);
        outer.instructions.add(hook());
        outer.visitVarInsn(Opcodes.ALOAD, 0);
        outer.visitMethodInsn(Opcodes.INVOKESPECIAL, PLAYER, original.name, "()Z", false);
        outer.visitInsn(Opcodes.IRETURN);
        node.methods.add(outer);
    }

    private static void addForeignHandle(ClassNode node) {
        MethodNode foreign = new MethodNode(Opcodes.ACC_PUBLIC, "foreignRead", "()Z", null, null);
        foreign.visitLdcInsn(new Handle(Opcodes.H_INVOKESPECIAL, PLAYER, "getLeaf", "()Z", false));
        foreign.visitVarInsn(Opcodes.ALOAD, 0);
        foreign.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
                "(L" + PLAYER + ";)Z", false);
        foreign.visitInsn(Opcodes.IRETURN);
        node.methods.add(foreign);
    }

    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        FixtureLoader(Map<String, byte[]> definitions) {
            super(NativeVanishCachePublicationTest.class.getClassLoader());
            this.definitions = definitions;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = definitions.get(name);
                loaded = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    // Independent Java model: javac supplies both leaves and synchronized handlers, including
    // exception regions. Only symbols and MixinExtras-generated wrappers/bridges are replaced.
    // No Minecraft/Vanish class bytes or production certificate strings are used as fixtures.
    public static final class FixturePlayer {
        private boolean vanished$dirty = true;
        private boolean vanished$vanished;
        private final Object fabrolia$vanishObserverCacheLock = new Object();
        public final FixtureServer server;
        public final UUID uuid = new UUID(0, 1);

        public FixturePlayer(FixtureServer server) { this.server = server; }
        public boolean vanish$isVanished() { return getLeaf(); }
        public void vanish$setDirty() { dirtyLeaf(); }

        private boolean getLeaf() {
            if (vanished$dirty) {
                FixtureData data = (FixtureData) FixtureApi.getCustomDataFor(server, uuid, FixtureManager.VANISH_DATA_STORAGE);
                vanished$vanished = data != null && data.vanished;
                vanished$dirty = false;
            }
            return vanished$vanished;
        }

        private void dirtyLeaf() { vanished$dirty = true; }

        public void foreignWrite() {
            synchronized (fabrolia$vanishObserverCacheLock) {
                vanished$vanished = true;
                server.state.pauseWriter();
                vanished$vanished = false;
            }
        }

        private boolean wrapMethod$fixture$fabrolia$readVanishObserverCache(Operation<Boolean> original) {
            synchronized (fabrolia$vanishObserverCacheLock) {
                return original.call();
            }
        }

        private void wrapMethod$fixture$fabrolia$dirtyVanishObserverCache(Operation<Void> original) {
            synchronized (fabrolia$vanishObserverCacheLock) {
                original.call();
            }
        }
    }

    public static final class FixtureServer {
        public final LoaderState state;
        public FixtureServer(LoaderState state) { this.state = state; }
    }

    public static final class FixtureData {
        public final boolean vanished;
        public FixtureData(boolean vanished) { this.vanished = vanished; }
    }

    public static final class FixtureStorage { }

    public static final class FixtureManager {
        public static final FixtureStorage VANISH_DATA_STORAGE = new FixtureStorage();
    }

    public static final class FixtureApi {
        public static Object getCustomDataFor(FixtureServer server, UUID uuid, FixtureStorage storage) {
            Boolean value = server.state.load();
            return value == null ? null : new FixtureData(value);
        }
    }

    public static final class LoaderState {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger hooks = new AtomicInteger();
        volatile Boolean value;
        volatile RuntimeException failure;
        volatile CountDownLatch entered = new CountDownLatch(0);
        volatile CountDownLatch release = new CountDownLatch(0);

        void blockNextLoad() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        public Boolean load() {
            loads.incrementAndGet();
            Boolean captured = value;
            RuntimeException capturedFailure = failure;
            entered.countDown();
            try {
                await(release);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (capturedFailure != null) throw capturedFailure;
            return captured;
        }

        public void pauseWriter() {
            entered.countDown();
            try {
                await(release);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        public void hook() { hooks.incrementAndGet(); }
    }
}
