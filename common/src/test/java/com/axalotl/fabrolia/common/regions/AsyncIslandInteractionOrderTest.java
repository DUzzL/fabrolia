package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.server.PlayerInteractionOwnerMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.predicates.entity.EntityNbtPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.TickTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the production reserved replay and Main FIFO, with observable packet bodies. */
@Isolated
@Timeout(20)
class AsyncIslandInteractionOrderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rawHandlersReachVanillaRequeueWithoutObservingThePlayer() throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
        class RawWrappers extends PlayerInteractionOwnerMixin
                implements com.axalotl.fabrolia.common.mixin.network.ServerCommonPacketListenerAccessor {
            @Override public MinecraftServer fabrolia$getServer() { return null; }
        }
        PlayerInteractionOwnerMixin wrappers = new RawWrappers();
        // A null player makes even level()/chunk/inventory/vehicle reads fail.
        List<String> entered = new ArrayList<>();
        Map<String, Class<?>> handlers = Map.of(
                "fabrolia$ownUseItem", ServerboundUseItemPacket.class,
                "fabrolia$ownPlayerAction", ServerboundPlayerActionPacket.class,
                "fabrolia$ownMoveVehicle", ServerboundMoveVehiclePacket.class,
                "fabrolia$ownContainerClick", ServerboundContainerClickPacket.class);
        for (var entry : handlers.entrySet()) {
            Method handler = PlayerInteractionOwnerMixin.class.getDeclaredMethod(
                    entry.getKey(), entry.getValue(), Operation.class);
            handler.setAccessible(true);
            Operation<Void> requeue = ignored -> {
                entered.add(entry.getKey());
                throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
            };
            InvocationTargetException requeued = assertThrows(InvocationTargetException.class,
                    () -> handler.invoke(wrappers, unsafe.allocateInstance(entry.getValue()), requeue));
            assertSame(RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD,
                    requeued.getCause());
        }
        assertEquals(handlers.keySet(), new java.util.HashSet<>(entered));
        assertEquals(handlers.size(), entered.size(), "each raw entry reaches vanilla once");
    }

    @Test
    void stalledRetrieveKeepsReservedAndNewlyDecodedInteractionsBehindItsWholePacket() throws Exception {
        try (Fixture f = new Fixture()) {
            f.offer(1);
            f.offer(2);
            var permit = f.reserve(1L);
            f.replay(permit);
            assertEquals(List.of(), f.listener.effects,
                    "neither the stalled retrieval nor its reserved successor may enter packet.handle");
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(3), f.listener));
            var handoff = f.state.session.preparePacketPrefixMainHandoff(permit).orElseThrow();
            assertTrue(f.state.session.acceptMainHandoff(handoff));
            assertFalse(f.state.session.attach(f.nextOwner()),
                    "a cross-island replacement cannot pass the still-owed interaction");
            assertEquals(List.of(), f.listener.effects);
            f.closeHandoff(handoff);
            f.drain();
            assertEquals(List.of(1, 2, 3), f.listener.effects);

            f.owner = f.nextOwner();
            assertTrue(f.state.session.attach(f.owner));
            f.offer(4);
            f.replay(f.reserve(1L));
            assertEquals(List.of(1, 2, 3, 4), f.listener.effects,
                    "the successor island must not repeat the old retrieval or its suffix");
        }
    }

    @Test
    void completedPacketCanHandOffItsStillOwedEntityBodyWithoutAnInteractionOrLifecycleMutation()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.player.fishing = null;
            f.offer(1);
            f.offer(2);
            var permit = f.reserve(1L);
            f.replay(permit);
            assertEquals(List.of(1), f.listener.effects,
                    "the completed packet is consumed; its suffix remains unentered");
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(3), f.listener));
            synchronized (f.state) {
                invoke("schedulePacketPrefixMainHandoffLocked",
                        new Class<?>[]{AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                                IslandPlayerTurnSession.EarlyPermit.class},
                        f.state, f.player, permit);
            }
            assertEquals(IslandPlayerTurnSession.Phase.MAIN_HANDOFF, f.state.session.view().phase());
            assertFalse(f.state.session.attach(f.nextOwner()),
                    "the owed entity body and FIFO suffix must finish before a new island owner");
            assertEquals(List.of(1), f.listener.effects,
                    "publication must not replay the completed packet or enter its successors inline");
        }
    }
    @Test
    void usePacketFencesActiveInventoryCriteriaBeforeAnyHandlerPrefix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.player.fishing = null;
            var nbt = EntityPredicate.wrap(new EntityPredicate(Map.of(
                    EntityNbtPredicate.CODEC, new EntityNbtPredicate(new NbtPredicate(new CompoundTag())))));
            AdvancementHolder holder = Advancement.Builder.advancement()
                    .addCriterion("inventory", CriteriaTriggers.INVENTORY_CHANGED.createCriterion(
                            new InventoryChangeTrigger.TriggerInstance(
                                    Optional.of(nbt), InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of())))
                    .build(Identifier.fromNamespaceAndPath("fabrolia", "use_inventory_admission"));
            PreparedAdvancementTestResources.install(f.server, List.of(holder));
            f.advancements.register(holder);
            f.offer(1);
            f.offer(2);
            var permit = f.reserve(1L);
            f.replay(permit);
            assertEquals(List.of(), f.listener.effects,
                    "use may mutate inventory before USING_ITEM; no eager packet prefix may run locally");
            var handoff = f.state.session.preparePacketPrefixMainHandoff(permit).orElseThrow();
            assertTrue(f.state.session.acceptMainHandoff(handoff));
            f.closeHandoff(handoff);
            f.drain();
            assertEquals(List.of(1, 2), f.listener.effects,
                    "the original packet and reserved suffix each run once on their selected Main venue");
        }
    }

    @Test
    void teleportAcknowledgementHandsOffBeforeItsSnapAndRetainsThePacketSuffix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.player.fishing = null;
            f.state.session.enqueuePacket(f.owner, new AsyncIslandPlayerTicks.InboundPacket(
                    new ServerboundAcceptTeleportationPacket(1), f.listener)).orElseThrow();
            f.offer(2);
            var permit = f.reserve(1L);
            f.replay(permit);
            assertEquals(List.of(), f.listener.effects,
                    "the retained teleport destination has no island packet footprint certificate");
            var handoff = f.state.session.preparePacketPrefixMainHandoff(permit).orElseThrow();
            assertTrue(f.state.session.acceptMainHandoff(handoff));
            f.closeHandoff(handoff);
            f.drain();
            assertEquals(List.of(-1, 2), f.listener.effects,
                    "the acknowledgement and reserved suffix each enter once after the Main handoff");
        }
    }


    @Test
    void disconnectDuringStalledHandoffDiscardsTheOwedInteractionAndItsFollowers() throws Exception {
        try (Fixture f = new Fixture()) {
            f.offer(1);
            f.offer(2);
            var permit = f.reserve(1L);
            f.replay(permit);
            var handoff = f.state.session.preparePacketPrefixMainHandoff(permit).orElseThrow();
            assertTrue(f.state.session.acceptMainHandoff(handoff));
            AsyncIslandPlayerTicks.runPhysicalMainDisconnect(f.listener, f.server,
                    () -> f.listener.effects.add(-1));
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(3), f.listener));
            f.closeHandoff(handoff);
            invoke("finishPhysicalMainDisconnect",
                    new Class<?>[]{AsyncIslandPlayerTicks.PlayerState.class,
                            ServerGamePacketListenerImpl.class}, f.state, f.listener);
            assertEquals(IslandPlayerTurnSession.Phase.DISCONNECTED, f.state.session.view().phase());
            assertEquals(List.of(-1), f.listener.effects);
            assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(4), f.listener));
            assertEquals(List.of(-1), f.listener.effects);
        }
    }

    @Test
    void anEnteredFailingInteractionCannotBeRestoredAlongsideItsUntouchedSuffix() throws Exception {
        try (Fixture f = new Fixture()) {
            f.player.fishing = null;
            f.listener.failSequence = 1;
            f.offer(1);
            f.offer(2);
            var permit = f.reserve(1L);
            RuntimeException winner = assertThrows(RuntimeException.class, () -> f.replay(permit));
            assertSame(f.listener.bodyFailure, winner);
            assertEquals(List.of(1), f.listener.effects);
            var handoff = f.state.session.preparePacketPrefixMainHandoff(permit).orElseThrow();
            assertTrue(f.state.session.acceptMainHandoff(handoff));
            var suffix = f.state.session.completeMainHandoff(handoff).orElseThrow();
            assertEquals(List.of(2), suffix.stream().map(envelope ->
                    ((ServerboundUseItemPacket) envelope.packet().packet()).getSequence()).toList(),
                    "the entered packet is terminal even when its body fails");
            assertFalse(f.state.session.tryMoveToMainFallback(permit).isPresent());
        }
    }

    @Test
    void decodedMainFifoSchedulesWithoutReadingMutablePlayerStateOnNetty() throws Exception {
        try (Fixture f = new Fixture()) {
            f.listener.failSequence = -1;
            // A retained island packet keeps this Main-only fixture subclass in
            // the listener FIFO; an otherwise idle binding is intentionally inert.
            f.offer(0);
            var handoff = f.state.session.beginMainHandoff(f.owner).orElseThrow();
            f.closeHandoff(handoff);
            f.player.rejectLevelRead = true;
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread netty = new Thread(() -> {
                try {
                    assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(1), f.listener));
                    assertTrue(AsyncIslandPlayerTicks.enqueuePacket(packet(2), f.listener));
                } catch (Throwable failure) {
                    failed.set(failure);
                }
            });
            netty.start();
            netty.join();
            if (failed.get() != null) throw new AssertionError(failed.get());
            assertEquals(List.of(), f.listener.effects);
            f.player.rejectLevelRead = false;
            for (TickTask task; (task = f.tasks.poll()) != null;) task.run();
            assertEquals(List.of(0, 1, 2), f.listener.effects,
                    "Netty publications must follow the retained island prefix without reading the live level");
        }
    }

    private static ServerboundUseItemPacket packet(int sequence) {
        return new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, sequence, 0.0F, 0.0F);
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final IslandRegistry.PIsland island;
        final TestPlayer player;
        final TestListener listener;
        final TestAdvancements advancements;
        final MinecraftServer server;
        final ServerLevel level;
        final RegionizedServer runtime;
        IslandPlayerTurnSession.OwnerStamp owner;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousActive;
        final Object previousEnabled;
        final Object previousPool;
        final Object previousFailure;
        final Object previousServer;
        final Object previousSchedulerServer;
        final Queue<TickTask> tasks = new ConcurrentLinkedQueue<>();
        final ThreadLocal<Object> turn;
        final Class<?> contextType;
        final Constructor<?> contextConstructor;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) get(sun.misc.Unsafe.class, null, "theUnsafe");
            player = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
            listener = (TestListener) unsafe.allocateInstance(TestListener.class);
            server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            PreparedAdvancementTestResources.install(server);
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            runtime.asyncActive = true;
            IslandRegistry registry = new IslandRegistry();
            ThreadedRegionizer regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            long cell = regionizer.grid.keyFor(0, 0);
            registry.applyRebuildCells(List.of(
                    new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{cell})), 0L);
            island = registry.byCell(cell);
            island.level = level;
            regionizer.regionForGridKey(cell).initializeDomainAtCreation(island.domain);
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "islandRegistry", registry);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            set(RegionizedServer.class, runtime, "level", level);
            set(MinecraftServer.class, server, "serverThread", Thread.currentThread());
            set(MinecraftServer.class, server, "levels", Map.of());
            set(ServerLevel.class, level, "server", server);
            set(net.minecraft.world.entity.Entity.class, player, "chunkPosition",
                    new net.minecraft.world.level.ChunkPos(0, 0));
            set(net.minecraft.world.entity.Entity.class, player, "blockPosition",
                    new net.minecraft.core.BlockPos(0, 64, 0));
            set(BlockableEventLoop.class, server, "pendingRunnables", tasks);
            player.localState = state;
            advancements = (TestAdvancements) unsafe.allocateInstance(TestAdvancements.class);
            advancements.listeners = new IdentityHashMap<>();
            set(PlayerAdvancements.class, advancements, "activeTriggers", advancements.listeners);
            set(PlayerAdvancements.class, advancements, "progress", new HashMap<>());
            set(PlayerAdvancements.class, advancements, "progressChanged", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "visible", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "rootsToUpdate", new HashSet<>());
            set(PlayerAdvancements.class, advancements, "tree", new AdvancementTree());
            set(ServerPlayer.class, player, "advancements", advancements);
            player.localLevel = level;
            player.indexedKey = cell;
            player.lastRegionTickToken = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            // The packet classifier needs item identity, not globally bound data-pack components.
            player.rod = new ItemStack(new net.minecraft.core.Holder.Direct<>(
                    Items.FISHING_ROD, net.minecraft.core.component.DataComponentMap.EMPTY));
            player.fishing = (FishingHook) unsafe.allocateInstance(FishingHook.class);
            player.connection = listener;
            listener.player = player;
            listener.binding = new AtomicReference<>(state);
            listener.effects = new ArrayList<>();
            listener.bodyFailure = new IllegalStateException("entered interaction failed");
            state.listenerGate = listener;
            owner = new IslandPlayerTurnSession.OwnerStamp(player, listener,
                    island, island.id, island.gen, island.domain, 0L, 1L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
            assertTrue(state.session.attach(owner));
            turn = (ThreadLocal<Object>) get(AsyncIslandPlayerTicks.class, null, "TURN");
            assertNull(turn.get());
            contextType = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$TurnContext");
            contextConstructor = contextType.getDeclaredConstructor(
                    AsyncIslandPlayerTicks.PlayerState.class, ServerPlayer.class,
                    IslandRegistry.PIsland.class, IslandPlayerTurnSession.OwnerStamp.class,
                    IslandPlayerTurnSession.EarlyPermit.class, boolean.class);
            contextConstructor.setAccessible(true);
            levels = (Map<ServerLevel, RegionizedServer>) get(RegionScheduler.class, null, "PER_LEVEL");
            previousActive = get(RegionScheduler.class, null, "active");
            previousEnabled = get(RegionalPlayerEntityTicksV2.class, null, "enabled");
            previousPool = get(RegionScheduler.class, null, "pool");
            previousFailure = get(RegionScheduler.class, null, "orphanFatalFailure");
            previousServer = get(ParallelProcessor.class, null, "server");
            previousSchedulerServer = get(RegionScheduler.class, null, "server");
            set(RegionScheduler.class, null, "server", server);
            set(ParallelProcessor.class, null, "server", server);
            levels.put(level, runtime);
            set(RegionScheduler.class, null, "active", true);
            set(RegionalPlayerEntityTicksV2.class, null, "enabled", true);
            set(RegionScheduler.class, null, "pool", null);
            set(RegionScheduler.class, null, "orphanFatalFailure", null);
        }

        void offer(int sequence) {
            state.session.enqueuePacket(owner,
                    new AsyncIslandPlayerTicks.InboundPacket(packet(sequence), listener)).orElseThrow();
        }

        IslandPlayerTurnSession.EarlyPermit<AsyncIslandPlayerTicks.InboundPacket> reserve(long token) {
            return state.session.tryReserveEarly(owner, token).orElseThrow();
        }

        void replay(IslandPlayerTurnSession.EarlyPermit<AsyncIslandPlayerTicks.InboundPacket> permit)
                throws Exception {
            Object context = contextConstructor.newInstance(
                    state, player, owner.islandIdentity(), owner, permit, false);
            turn.set(context);
            try {
                invoke("replayReservedPackets", new Class<?>[]{contextType}, context);
            } finally {
                turn.remove();
            }
        }

        void closeHandoff(IslandPlayerTurnSession.MainHandoff<AsyncIslandPlayerTicks.InboundPacket> handoff)
                throws Exception {
            var prefix = state.session.completeMainHandoff(handoff).orElseThrow();
            synchronized (state) {
                invoke("prependMainIngressLocked", new Class<?>[]{AsyncIslandPlayerTicks.PlayerState.class,
                        ServerGamePacketListenerImpl.class, List.class}, state, listener, prefix);
                assertTrue(state.session.finishMainHandoff(handoff));
            }
        }

        void drain() throws Exception {
            // This fixture exercises the packet lane, not the separate guarded
            // entity consumer whose owed ordinal is covered by its own regression.
            state.fallbackEntityAfterHandoff = false;
            state.mainIngressScheduled = true;
            invoke("drainMainIngress", new Class<?>[]{AsyncIslandPlayerTicks.PlayerState.class,
                    ServerGamePacketListenerImpl.class}, state, listener);
        }

        IslandPlayerTurnSession.OwnerStamp nextOwner() {
            IslandRegistry.PIsland next = new IslandRegistry.PIsland(712L, 0L);
            return new IslandPlayerTurnSession.OwnerStamp(player, listener, next,
                    next.id, next.gen, new EpochDomain(), 0L, 2L,
                    IslandPlayerTurnSession.PlayerKind.REAL);
        }

        @Override
        public void close() throws Exception {
            try {
                IslandCommands.releaseAllExcept();
            } finally {
                turn.remove();
                tasks.clear();
                set(ParallelProcessor.class, null, "server", previousServer);
                set(RegionScheduler.class, null, "server", previousSchedulerServer);
                levels.remove(level, runtime);
                set(RegionScheduler.class, null, "pool", previousPool);
                set(RegionScheduler.class, null, "orphanFatalFailure", previousFailure);
                set(RegionScheduler.class, null, "active", previousActive);
                set(RegionalPlayerEntityTicksV2.class, null, "enabled", previousEnabled);
            }
        }
    }
    private static final class TestAdvancements extends PlayerAdvancements
            implements AdvancementRequirements.ActiveListeners {
        Map<CriterionTrigger<?>, Map<TriggerInstanceKey, ? extends CriterionTriggerInstance>> listeners;

        private TestAdvancements() { super(null, null, null, null, null); }

        @Override
        public Map<CriterionTrigger<?>, Map<TriggerInstanceKey, ? extends CriterionTriggerInstance>>
                fabrolia$activeTriggers() {
            assertTrue(Thread.holdsLock(this));
            return listeners;
        }

        void register(AdvancementHolder holder) throws Exception {
            Method method = PlayerAdvancements.class.getDeclaredMethod("registerListeners", AdvancementHolder.class);
            method.setAccessible(true);
            synchronized (this) {
                method.invoke(this, holder);
            }
        }
    }


    // Mixin storage and the observable handler effect are substituted; queue,
    // refinement, replay, disconnect and handoff code are the production methods.
    private static final class TestPlayer extends ServerPlayer
            implements AsyncIslandPlayerAccess, PlayerTickPhaseStamp, RegionKeyed {
        AsyncIslandPlayerTicks.PlayerState localState;
        ServerLevel localLevel;
        ItemStack rod;
        volatile boolean rejectLevelRead;
        long lifecycle;
        volatile long indexedKey;
        java.util.concurrent.atomic.AtomicLong lastRegionTickToken;
        private TestPlayer() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return localState; }
        @Override public long fabrolia$playerLifecycleEpoch() { return lifecycle; }
        @Override public void fabrolia$bumpPlayerLifecycleEpoch() { lifecycle++; }
        @Override public long fabrolia$getRegionKey() { return indexedKey; }
        @Override public void fabrolia$setRegionKey(long key) { indexedKey = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) {
            long observed;
            do {
                observed = lastRegionTickToken.get();
                if (observed == token) return false;
            } while (!lastRegionTickToken.compareAndSet(observed, token));
            return true;
        }
        @Override public boolean fabrolia$carryRegionTickMark(long from, long to) {
            return from != to && lastRegionTickToken.compareAndSet(from, to);
        }
        @Override public ServerLevel level() {
            if (rejectLevelRead) throw new AssertionError("Netty read mutable player level");
            return localLevel;
        }
        @Override public ItemStack getItemInHand(InteractionHand hand) { return rod; }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl implements AsyncIslandListenerAccess {
        AtomicReference<AsyncIslandPlayerTicks.PlayerState> binding;
        List<Integer> effects;
        int failSequence;
        RuntimeException bodyFailure;
        private TestListener() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return binding.get(); }
        @Override public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(null, expected);
        }
        @Override public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            return binding.compareAndSet(expected, null);
        }
        @Override public void handleUseItem(ServerboundUseItemPacket packet) {
            effects.add(packet.getSequence());
            if (packet.getSequence() == failSequence) throw bodyFailure;
            player.fishing = null;
        }
        @Override public void handleAcceptTeleportPacket(ServerboundAcceptTeleportationPacket packet) {
            effects.add(-1);
        }
    }

    private static Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = AsyncIslandPlayerTicks.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failure.getCause());
        }
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
