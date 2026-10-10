package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Actual block-event callbacks and the 26.3 native drain, with chunk gates stubbed. */
class BlockEventPendingParityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void native26_3RetainsPendingDedupAndCancellationUntilRemoveFirst() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.level.nativeQueue = true;
            f.post(0);
            f.post(1);
            f.level.callback = event -> { if (event.paramA() == 0) f.post(1); };
            f.drainNative();
            assertEquals(List.of(0, 1), f.level.ran);
            f.level.ran.clear();
            f.post(0);
            f.post(1);
            f.level.callback = event -> { if (event.paramA() == 0) f.clear(1); };
            f.drainNative();
            assertEquals(List.of(0), f.level.ran);
        }
    }

    @Test
    void mainCallbackCannotDuplicateThePendingSuffix() throws Exception { duplicate(false); }

    @Test
    void islandCallbackCannotDuplicateThePendingSuffix() throws Exception { duplicate(true); }

    private static void duplicate(boolean island) throws Exception {
        try (Fixture f = new Fixture(island)) {
            f.post(0);
            f.post(1);
            f.level.callback = event -> {
                assertFalse(Thread.holdsLock(f.level.queueLock), "callbacks must hold no queue lock");
                if (event.paramA() == 0) f.post(1);
            };
            f.drain();
            assertEquals(List.of(0, 1), f.level.ran);
            assertTrue(f.level.pending.isEmpty());
        }
    }

    @Test
    void mainCallbackCanCancelThePendingSuffix() throws Exception { cancellation(false, false); }

    @Test
    void islandCallbackCanCancelThePendingSuffix() throws Exception { cancellation(true, false); }

    @Test
    void mainCancellationThenEqualRepostRunsExactlyOneSuccessor() throws Exception { cancellation(false, true); }

    @Test
    void islandCancellationThenEqualRepostRunsExactlyOneSuccessor() throws Exception { cancellation(true, true); }

    private static void cancellation(boolean island, boolean repost) throws Exception {
        try (Fixture f = new Fixture(island)) {
            f.post(0);
            f.post(1);
            f.level.callback = event -> {
                if (event.paramA() == 0) {
                    f.clear(1);
                    if (repost) f.post(1);
                }
            };
            f.drain();
            assertEquals(repost ? List.of(0, 1) : List.of(0), f.level.ran);
            assertTrue(f.level.pending.isEmpty());
        }
    }

    @Test
    void alreadyDequeuedEventMayBePostedAgainAtTheTail() throws Exception {
        for (boolean island : List.of(false, true)) {
            try (Fixture f = new Fixture(island)) {
                f.post(0);
                f.post(1);
                f.level.callback = event -> {
                    if (event.paramA() == 0 && f.level.ran.size() == 1) f.post(0);
                };
                f.drain();
                assertEquals(List.of(0, 1, 0), f.level.ran);
            }
        }
    }

    @Test
    void islandStashAndSharedQueueRetainOneCanonicalPendingIdentity() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.post(1);
            assertTrue(IslandBlockEvents.runPartitioned(f.level));
            assertEquals(1, f.island.eventStashDepths()[0]);
            assertFalse(IslandBlockEvents.enqueue(f.level, f.event(1)), "stash must still dedup a Main post");
            assertTrue(f.level.events.isEmpty());
            f.clear(1);
            assertEquals(0, f.island.eventStashDepths()[0]);
            f.drain();
            assertTrue(f.level.ran.isEmpty());
        }
    }

    @Test
    void nonTickableEventsRemainDedupedAndRunOnceWhenTheirGateOpens() throws Exception {
        for (boolean island : List.of(false, true)) {
            try (Fixture f = new Fixture(island)) {
                f.level.tickable = false;
                f.post(1);
                f.drain();
                assertTrue(f.level.ran.isEmpty());
                assertEquals(1, f.level.pending.size());
                assertFalse(IslandBlockEvents.enqueue(f.level, f.event(1)));
                f.level.tickable = true;
                f.drain();
                assertEquals(List.of(1), f.level.ran);
                assertTrue(f.level.pending.isEmpty());
            }
        }
    }

    @Test
    void abortedCaptureRetainsItsUnconsumedRolloverExactlyOnce() throws Exception {
        try (Fixture f = new Fixture(true)) {
            IslandBlockEvents.beginUnitCapture(f.island);
            try { f.post(1); } finally { IslandBlockEvents.endUnitCapture(f.level); }
            assertEquals(1, f.level.events.size());
            assertEquals(1, f.level.pending.size());
            assertFalse(IslandBlockEvents.enqueue(f.level, f.event(1)));
            f.drain();
            assertEquals(List.of(1), f.level.ran);
        }
    }

    @Test
    void cancellationAlsoRemovesLaterPhaseNextUnitEvents() throws Exception {
        try (Fixture f = new Fixture(true)) {
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try { f.post(1); } catch (Throwable thrown) { failure.set(thrown); }
            }, "next-unit-block-event");
            worker.currentDomain = f.island.domain;
            worker.start();
            worker.join(3_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(1, f.island.eventStashDepths()[1]);
            assertFalse(IslandBlockEvents.enqueue(f.level, f.event(1)));
            f.clear(1);
            assertEquals(0, f.island.eventStashDepths()[1]);
            f.drain();
            assertTrue(f.level.ran.isEmpty());
        }
    }

    @Test
    void cancellationDuringCarryCannotResurrectAnEqualNewAdmission() throws Exception {
        try (Fixture f = new Fixture(true)) {
            BlockEventData old = f.event(1);
            assertTrue(IslandBlockEvents.enqueue(f.level, old));
            assertTrue(IslandBlockEvents.runPartitioned(f.level));
            var carried = f.island.takeAllStampedEvents(0);
            assertEquals(1, carried.size());
            f.clear(1);
            BlockEventData successor = f.event(1);
            assertNotSame(old, successor);
            assertTrue(IslandBlockEvents.enqueue(f.level, successor));
            IslandBlockEvents.carryPendingEvent(f.level, f.island, carried.getFirst());
            assertEquals(0, f.island.eventStashDepths()[0], "the cancelled carry is no longer admitted");
            assertSame(successor, f.level.pending.get(old));
            f.drain();
            assertEquals(List.of(1), f.level.ran);
        }
    }

    @Test
    void nativeAndMixinWiringUseSingleDequeueAndSharedAdmission() throws Exception {
        ClassNode vanilla = read(ServerLevel.class);
        var nativeDrain = vanilla.methods.stream().filter(m -> m.name.equals("runBlockEvents")).findFirst().orElseThrow();
        assertTrue(calls(nativeDrain, "removeFirst"));
        ClassNode mixin = read(com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin.class);
        assertHook(mixin, "overwriteQueueAdd", "enqueue");
        assertHook(mixin, "overwriteQueueRemoveIf", "clearBlockEvents");
        assertHook(mixin, "overwriteQueueRemoveFirst", "pollShared");
        assertHook(mixin, "overwriteQueueAddAll", "requeueShared");
    }

    private static void assertHook(ClassNode node, String hook, String callee) {
        var method = node.methods.stream().filter(m -> m.name.equals(hook)).findFirst().orElseThrow();
        assertTrue(calls(method, callee), hook + " must use the shared pending owner");
    }

    private static boolean calls(org.objectweb.asm.tree.MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static ClassNode read(Class<?> type) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(type.getName()).accept(node, 0);
        return node;
    }

    private static final class EventBlock extends Block {
        private EventBlock() { super(BlockBehaviour.Properties.of()); }
        static EventBlock create(sun.misc.Unsafe unsafe) throws Exception {
            // Registries are frozen after bootstrap. Copy a native plain block
            // and state without registering a new holder; only the state owner
            // changes so the actual native triggerEvent dispatch reaches us.
            EventBlock block = (EventBlock) unsafe.allocateInstance(EventBlock.class);
            copyFields(Blocks.STONE, block, Block.class);
            net.minecraft.core.Holder.Reference<Block> holder = net.minecraft.core.Holder.Reference.createStandAlone(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,
                            net.minecraft.resources.Identifier.fromNamespaceAndPath("fabrolia", "test_event_block")));
            Method bind = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
            bind.setAccessible(true);
            bind.invoke(holder, block);
            field(Block.class, "builtInRegistryHolder").set(block, holder);
            BlockState state = (BlockState) unsafe.allocateInstance(BlockState.class);
            copyFields(Blocks.STONE.defaultBlockState(), state, BlockState.class);
            field(net.minecraft.world.level.block.state.StateHolder.class, "owner").set(state, block);
            field(Block.class, "defaultBlockState").set(block, state);
            assertSame(block, state.getBlock());
            return block;
        }
        @Override protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int a, int b) {
            TestLevel test = (TestLevel) level;
            BlockEventData event = new BlockEventData(pos, this, a, b);
            test.ran.add(a);
            test.callback.accept(event);
            return false; // Actual doBlockEvent runs; broadcast is irrelevant to the queue contract.
        }
    }

    private static final class TestLevel extends ServerLevel implements BlockEventQueueAccess {
        Object queueLock;
        ObjectLinkedOpenHashSet<BlockEventData> events;
        ObjectLinkedOpenHashSet<BlockEventData> pending;
        Block block;
        List<Integer> ran;
        Consumer<BlockEventData> callback;
        boolean tickable;
        boolean nativeQueue;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public Object fabrolia$blockEventLock() { return queueLock; }
        @Override public ObjectLinkedOpenHashSet<BlockEventData> fabrolia$blockEvents() { return events; }
        @Override public ObjectLinkedOpenHashSet<BlockEventData> fabrolia$pendingBlockEventIdentities() { return pending; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 0; }
        @Override public boolean shouldTickBlocksAt(BlockPos pos) { return tickable; }
        @Override public BlockState getBlockState(BlockPos pos) { return block.defaultBlockState(); }
        @Override public void blockEvent(BlockPos pos, Block block, int a, int b) {
            if (nativeQueue) super.blockEvent(pos, block, a, b);
            else IslandBlockEvents.enqueue(this, new BlockEventData(pos, block, a, b));
        }
        @Override public void clearBlockEvents(BoundingBox box) {
            if (nativeQueue) super.clearBlockEvents(box);
            else IslandBlockEvents.clearBlockEvents(this, event -> box.isInside(event.pos()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final Map<ServerLevel, RegionizedServer> runtimes;
        final boolean oldActive;
        @SuppressWarnings("unchecked")
        Fixture(boolean owned) throws Exception {
            Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
            level.queueLock = new Object();
            level.events = new ObjectLinkedOpenHashSet<>();
            level.pending = new ObjectLinkedOpenHashSet<>();
            level.block = EventBlock.create(unsafe);
            level.ran = new ArrayList<>();
            level.callback = event -> {};
            level.tickable = true;
            field(ServerLevel.class, "blockEvents").set(level, level.events);
            field(ServerLevel.class, "blockEventsToReschedule").set(level, new ArrayList<BlockEventData>());
            runtime = new RegionizedServer(level, new RegionGrid(3));
            runtime.asyncActive = true;
            Region region = runtime.regionizer().regionForChunk(0L);
            if (owned) {
                runtime.islandRegistry.applyRebuildCells(List.of(new LongArrayList(new long[]{region.gridKey})), 0);
                island = runtime.islandRegistry.byCell(region.gridKey);
                island.level = level;
                region.domain = island.domain;
            } else island = null;
            runtimes = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            oldActive = field(RegionScheduler.class, "active").getBoolean(null);
            runtimes.put(level, runtime);
            field(RegionScheduler.class, "active").setBoolean(null, true);
        }
        BlockEventData event(int n) { return new BlockEventData(new BlockPos(n, 64, 0), level.block, n, 0); }
        void post(int n) {
            BlockEventData event = event(n);
            level.blockEvent(event.pos(), event.block(), event.paramA(), event.paramB());
        }
        void clear(int n) { level.clearBlockEvents(new BoundingBox(n, 64, 0, n, 64, 0)); }
        void drain() {
            assertTrue(IslandBlockEvents.runPartitioned(level));
            if (island != null) {
                IslandBlockEvents.beginUnitCapture(island);
                try { IslandBlockEvents.runIslandEvents(runtime, island, island.domain); }
                finally { IslandBlockEvents.endUnitCapture(level); }
            }
        }
        void drainNative() throws Exception {
            Method method = ServerLevel.class.getDeclaredMethod("runBlockEvents");
            method.setAccessible(true);
            method.invoke(level);
        }
        @Override public void close() throws Exception {
            IslandBlockEvents.endUnitCapture(level);
            runtimes.remove(level);
            field(RegionScheduler.class, "active").setBoolean(null, oldActive);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void copyFields(Object source, Object target, Class<?> start) throws Exception {
        for (Class<?> type = start; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(target, field.get(source));
            }
        }
    }
}
