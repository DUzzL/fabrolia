package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.FutureChain;
import net.minecraft.util.TaskChainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Actual pool admission and vanilla FutureChain; no transformed listener or game launch. */
@Isolated
class AsyncIslandContinuationRegistrationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unownedRegistrationDuringExactPauseKeepsVanillaExecutorAndCallbackOrder()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.pauseExactTurnAndAwait(() -> {});
            CompletableFuture<Integer> first = new CompletableFuture<>();
            TaskChainer chain = AsyncIslandPacketContinuations.mainTaskChainer(
                    null, f.vanilla, "unowned-chat");
            Throwable publicationFailure = foreign(() -> {
                chain.append(first, f.effects::add);
                chain.append(CompletableFuture.completedFuture(2), f.effects::add);
            });
            assertNull(publicationFailure,
                    "vanilla registration is not an off-thread simulation body");
            assertEquals(List.of(), f.effects);
            first.complete(1);
            assertEquals(List.of(), f.effects, "completion must retain the vanilla executor");
            f.drain();
            assertEquals(List.of(1, 2), f.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void unownedChatPublicationDuringNormalDrainRetainsItsExecutorSlot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.beginDraining(Thread.currentThread());
            Throwable publicationFailure = foreign(() ->
                    AsyncIslandPacketContinuations.register(null, () -> {
                        f.tasks.add(() -> RegionScheduler.runSuccessBodyIfHealthy(
                                () -> f.effects.add(1)));
                        return null;
                    }));
            assertNull(publicationFailure);
            assertEquals(List.of(), f.effects);
            f.drain();
            assertEquals(List.of(1), f.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void fatalAfterRegistrationRejectsQueuedConsumerAndLaterPublication() throws Exception {
        try (Fixture f = new Fixture()) {
            TaskChainer chain = AsyncIslandPacketContinuations.mainTaskChainer(
                    null, f.vanilla, "failed-chat");
            assertNull(foreign(() -> chain.append(
                    CompletableFuture.completedFuture(1), f.effects::add)));
            AssertionError winner = new AssertionError("fatal before consumer");
            f.pool.publishFatalFailure(winner);
            // FutureChain logs/catches callback failures; the pool must still retain W.
            f.drain();
            assertEquals(List.of(), f.effects);
            assertSame(winner, f.pool.fatalFailureOrNull());
            assertSame(winner, assertThrows(AssertionError.class, () ->
                    AsyncIslandPacketContinuations.register(null, () -> {
                        f.effects.add(2);
                        return null;
                    })));
            assertEquals(List.of(), f.effects);
        }
    }

    @Test
    void rejectedUnownedRegistrationSelectsItsOriginalFailureWithoutRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pool.pauseExactTurnAndAwait(() -> {});
            RejectedExecutionException rejected = new RejectedExecutionException("executor closed");
            Runnable published = () -> RegionScheduler.runSuccessBodyIfHealthy(
                    () -> f.effects.add(0));
            Throwable observed = foreign(() ->
                    AsyncIslandPacketContinuations.register(null, () -> {
                        f.tasks.add(published);
                        throw rejected;
                    }));
            assertSame(rejected, observed);
            assertSame(rejected, f.pool.fatalFailureOrNull());
            assertSame(published, f.tasks.poll());
            assertNull(f.tasks.poll(), "partial publication must not be retried");
            assertSame(rejected, assertThrows(RejectedExecutionException.class, published::run));
            assertSame(rejected, assertThrows(RejectedExecutionException.class, () ->
                    AsyncIslandPacketContinuations.register(null, () -> {
                        f.effects.add(1);
                        return null;
                    })));
            assertEquals(List.of(), f.effects);
        }
    }

    @Test
    void synchronousVanillaConsumerCannotBorrowTheRegistrationBypass() throws Exception {
        try (Fixture f = new Fixture(); FutureChain direct = new FutureChain(Runnable::run)) {
            f.pool.pauseExactTurnAndAwait(() -> {});
            TaskChainer chain = AsyncIslandPacketContinuations.mainTaskChainer(
                    null, direct, "inline-chat");
            Throwable observed = foreign(() -> chain.append(
                    CompletableFuture.completedFuture(1), f.effects::add));
            assertInstanceOf(IllegalStateException.class, observed);
            assertSame(observed, f.pool.fatalFailureOrNull());
            assertEquals(List.of(), f.effects,
                    "an inline body still needs its own exact pool admission");
        }
    }

    @Test
    void previouslyOwnedCompletionRunsOnDrainOwnerAfterVenueDisable() throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<Integer> prepared = new CompletableFuture<>();
            TaskChainer chain = AsyncIslandPacketContinuations.mainTaskChainer(
                    f.owner(), f.vanilla, "captured-chat");
            chain.append(prepared, f.effects::add);
            f.runtime.asyncActive = false;
            f.pool.beginDraining(Thread.currentThread());
            assertNull(foreign(() -> prepared.complete(1)));
            assertEquals(List.of(), f.effects);
            f.drain();
            assertEquals(List.of(1), f.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    @Test
    void staleOwnedCompletionCannotMutateTheSuccessorAfterVenueDisable() throws Exception {
        try (Fixture f = new Fixture()) {
            CompletableFuture<Integer> prepared = new CompletableFuture<>();
            AsyncIslandPacketContinuations.mainTaskChainer(
                    f.owner(), f.vanilla, "stale-chat").append(prepared, f.effects::add);
            f.player.fabrolia$bumpPlayerLifecycleEpoch();
            f.runtime.asyncActive = false;
            f.pool.beginDraining(Thread.currentThread());
            assertNull(foreign(() -> prepared.complete(1)));
            f.drain();
            assertEquals(List.of(), f.effects);
            assertNull(f.pool.fatalFailureOrNull());
        }
    }

    private static Throwable foreign(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { body.run(); }
            catch (Throwable thrown) { failure.set(thrown); }
        }, "continuation-registration-producer");
        producer.start();
        producer.join(5_000L);
        assertFalse(producer.isAlive(), "publication must not await the exclusive Main turn");
        return failure.get();
    }

    private static final class Fixture implements AutoCloseable {
        final RegionWorkerPool pool = new RegionWorkerPool(1);
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final FutureChain vanilla = new FutureChain(tasks::add);
        final List<Integer> effects = new ArrayList<>();
        final AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        final TestPlayer player;
        final TestListener listener;
        final ServerLevel level;
        final RegionizedServer runtime;
        final Map<ServerLevel, RegionizedServer> levels;
        final Object previousPool;
        final Object previousActive;
        final Object previousFailure;
        final Object previousEnabled;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe.allocateInstance(RegionizedServer.class);
            field(RegionizedServer.class, "level").set(runtime, level);
            ThreadedRegionizer regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            field(RegionizedServer.class, "regionizer").set(runtime, regionizer);
            field(RegionizedServer.class, "islandRegistry").set(runtime, new IslandRegistry());
            runtime.asyncActive = true;
            player = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
            player.localLevel = level;
            listener = (TestListener) unsafe.allocateInstance(TestListener.class);
            listener.state = state;
            listener.player = player;
            player.connection = listener;
            levels = (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
            previousPool = field(RegionScheduler.class, "pool").get(null);
            previousActive = field(RegionScheduler.class, "active").get(null);
            previousFailure = field(RegionScheduler.class, "orphanFatalFailure").get(null);
            previousEnabled = field(RegionalPlayerEntityTicksV2.class, "enabled").get(null);
            assertNull(previousPool, "requires no live scheduler generation");
            pool.start();
            field(RegionScheduler.class, "pool").set(null, pool);
            field(RegionScheduler.class, "active").set(null, true);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            field(RegionalPlayerEntityTicksV2.class, "enabled").set(null, true);
            levels.put(level, runtime);
        }

        AsyncIslandPacketContinuations.ContinuationOwner owner() throws Exception {
            // Substitute Mixin lifecycle storage only. This tests a captured token's
            // downstream lifetime, NOT capture's exact ServerPlayer class allow-list.
            var constructor = AsyncIslandPacketContinuations.ContinuationOwner.class
                    .getDeclaredConstructor(ServerGamePacketListenerImpl.class, ServerPlayer.class,
                            AsyncIslandPlayerTicks.PlayerState.class, ServerLevel.class,
                            RegionizedServer.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(listener, player, state, level, runtime,
                    player.fabrolia$playerLifecycleEpoch());
        }

        void drain() {
            for (Runnable task; (task = tasks.poll()) != null;) task.run();
        }

        @Override
        public void close() throws Exception {
            Throwable winner = pool.fatalFailureOrNull();
            try {
                if (pool.ownsPausedExactTurn(Thread.currentThread())) {
                    try { pool.resumeExactTurn(); }
                    catch (Throwable failure) { if (failure != winner) throw failure; }
                }
                try { pool.stop(); }
                catch (Throwable failure) { if (failure != winner) throw failure; }
            } finally {
                vanilla.close();
                tasks.clear();
                levels.remove(level, runtime);
                field(RegionScheduler.class, "pool").set(null, previousPool);
                field(RegionScheduler.class, "active").set(null, previousActive);
                field(RegionScheduler.class, "orphanFatalFailure").set(null, previousFailure);
                field(RegionalPlayerEntityTicksV2.class, "enabled").set(null, previousEnabled);
            }
        }
    }

    private static final class TestPlayer extends ServerPlayer implements PlayerTickPhaseStamp {
        ServerLevel localLevel;
        long lifecycle;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel level() { return localLevel; }
        @Override public long fabrolia$playerLifecycleEpoch() { return lifecycle; }
        @Override public void fabrolia$bumpPlayerLifecycleEpoch() { lifecycle++; }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl
            implements AsyncIslandListenerAccess {
        AsyncIslandPlayerTicks.PlayerState state;
        private TestListener() { super(null, null, null, null); }
        @Override public AsyncIslandPlayerTicks.PlayerState fabrolia$asyncIslandPlayerState() { return state; }
        @Override public boolean fabrolia$bindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            if (state != null) return false;
            state = expected;
            return true;
        }
        @Override public boolean fabrolia$unbindAsyncIslandPlayerState(AsyncIslandPlayerTicks.PlayerState expected) {
            if (state != expected) return false;
            state = null;
            return true;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
