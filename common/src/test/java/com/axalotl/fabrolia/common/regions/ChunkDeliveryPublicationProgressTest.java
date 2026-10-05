package com.axalotl.fabrolia.common.regions;

import io.netty.channel.DefaultEventLoop;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes real selection, reservations, admission, receipt consumption, Main
 * completion and ACK. Unsafe substitutes an already-loaded world/READY snapshot
 * and the listener duck normally installed by Mixin; packet encoding and world
 * simulation are deliberately outside this fixture. No publication state is set.
 *
 * The first publication starts at sendNextChunks' post-selection scheduling
 * boundary: the real selectCandidate creates its nonempty reservation list, the
 * real constructor receives it, and only the Main-owned active slot is installed
 * reflectively. Splitting the unchanged publish phases lets the actual EventLoop
 * consume its receipt before Main resumes, without sleeps or a production hook.
 */
@Isolated
class ChunkDeliveryPublicationProgressTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest(name = "empty receipt consumed before Main tail: {0}")
    @ValueSource(booleans = {true, false})
    void emptyReceiptDoesNotStrandTheNextReadyChunk(boolean consumeBeforeMainTail)
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkDelivery.Rec stale = fixture.ready();
            ChunkDelivery.Entry staleEntry = stale.entry;
            ArrayList<ChunkDelivery.Reservation> selected = new ArrayList<>();
            Method select = ChunkDelivery.class.getDeclaredMethod("selectCandidate",
                    ServerLevel.class, RegionizedServer.class, ChunkDelivery.PlayerState.class,
                    ChunkDelivery.Rec.class, ArrayList.class);
            select.setAccessible(true);
            invoke(select, null, fixture.level, null, fixture.state, stale, selected);
            assertEquals(1, selected.size(), "production must select a real READY reservation");

            Class<?> publicationType = Class.forName(ChunkDelivery.class.getName() + "$BatchPublication");
            Constructor<?> constructor = publicationType.getDeclaredConstructor(
                    ChunkDelivery.PlayerState.class, ChunkDelivery.Reservation[].class,
                    boolean.class, boolean.class);
            constructor.setAccessible(true);
            Object publication = constructor.newInstance(fixture.state,
                    selected.toArray(ChunkDelivery.Reservation[]::new), false, false);
            field(ChunkDelivery.PlayerState.class, "activePublication").set(fixture.state, publication);

            // The snapshot was valid at selection. A real write begins during
            // the offer, so admitAfterBatchOffer must reject its sealed generation.
            fixture.listener.onOffer = fixture.chunk::fabrolia$bumpWriteGen;
            Method admit = publicationType.getDeclaredMethod("admitPublication", ServerPlayer.class);
            Method finish = publicationType.getDeclaredMethod("finishPublicationOnMain", Throwable.class);
            admit.setAccessible(true);
            finish.setAccessible(true);
            Throwable failure = (Throwable) invoke(admit, publication, fixture.player);
            assertNull(failure);
            assertEquals(1, fixture.state.retriesFence);
            assertEquals(0, staleEntry.refs.get(), "the stale snapshot is no longer retained");

            if (consumeBeforeMainTail) {
                fixture.listener.release.countDown();
                fixture.awaitReceipts();
            }
            invoke(finish, publication, failure);

            assertEquals(List.of(), fixture.listener.packets,
                    "an all-stale receipt must emit no Start, full packet or Finished");
            assertEquals(0, ChunkDelivery.applyCompletedChunkBatch(fixture.player));
            assertTrue(fixture.state.outstanding.isEmpty(), "empty work creates no ACK obligation");

            ChunkDelivery.Rec successor = fixture.ready();
            ClientboundLevelChunkWithLightPacket expectedFull = successor.entry.packet;
            assertEquals(0, ChunkDelivery.sendNextChunks(fixture.player, fixture.level, 1));
            // In the delayed case, the old empty receipt is still queued while
            // this newer publication owns the sender slot. Its completion must
            // not detach the successor or manufacture an old ACK obligation.
            fixture.listener.release.countDown();
            fixture.awaitReceipts();
            assertEquals(3, fixture.listener.packets.size(),
                    "a consumed empty receipt must not permanently occupy the sender slot: "
                            + "the later READY chunk needs Start/full/Finished");
            assertSame(ClientboundChunkBatchStartPacket.INSTANCE, fixture.listener.packets.get(0));
            assertSame(expectedFull, fixture.listener.packets.get(1));
            ClientboundChunkBatchFinishedPacket finished = assertInstanceOf(
                    ClientboundChunkBatchFinishedPacket.class, fixture.listener.packets.get(2));
            assertEquals(1, finished.batchSize());
            assertEquals(1, ChunkDelivery.applyCompletedChunkBatch(fixture.player),
                    "only the real successful successor advances Main pacing");
            assertEquals(0, ChunkDelivery.applyCompletedChunkBatch(fixture.player),
                    "the successful completion is consumed exactly once");
            assertEquals(1, fixture.state.outstanding.size());
            ChunkDelivery.onAck(fixture.player);
            assertEquals(ChunkDelivery.St.ACKNOWLEDGED, successor.st,
                    "one ACK must acknowledge the successor, not an empty predecessor");
            assertTrue(fixture.state.outstanding.isEmpty());
            assertEquals(1, fixture.state.acked);
            assertEquals(0, fixture.state.staleAcks);
        }
    }

    @Test
    void mainDimensionTransitionPreservesOldAckAfterWorkerRefusal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkDelivery.Rec old = fixture.ready();
            fixture.listener.release.countDown();
            ChunkDelivery.sendNextChunks(fixture.player, fixture.level, 1);
            fixture.awaitReceipts();
            assertEquals(1, ChunkDelivery.applyCompletedChunkBatch(fixture.player));

            TestLevel destination = fixture.allocate(TestLevel.class);
            var server = fixture.allocate(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
            field(net.minecraft.server.MinecraftServer.class, "serverThread")
                    .set(server, Thread.currentThread());
            field(ServerLevel.class, "server").set(destination, server);
            TestChunk destinationChunk = fixture.allocate(TestChunk.class);
            destinationChunk.localLevel = destination;
            destinationChunk.position = fixture.chunk.position;
            destinationChunk.fence = new ChunkWriteEnqueueFence();
            destination.source = fixture.allocate(TestChunkSource.class);
            destination.source.chunk = destinationChunk;
            fixture.player.localLevel = destination;
            FutureTask<Void> transition = new FutureTask<>(() -> {
                ChunkDelivery.track(fixture.player, destinationChunk);
                return null;
            });
            Thread worker = new Thread(transition, "chunk-delivery-portal-worker");
            worker.start();
            try {
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> transition.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            } finally {
                worker.join(5_000);
                assertFalse(worker.isAlive());
            }

            ChunkDelivery.Rec successor = fixture.ready(destinationChunk);
            ClientboundLevelChunkWithLightPacket expected = successor.entry.packet;
            fixture.listener.packets.clear();
            ChunkDelivery.sendNextChunks(fixture.player, destination, 1);
            fixture.awaitReceipts();
            assertEquals(3, fixture.listener.packets.size());
            assertSame(ClientboundChunkBatchStartPacket.INSTANCE, fixture.listener.packets.get(0));
            assertSame(expected, fixture.listener.packets.get(1));
            assertEquals(1, assertInstanceOf(ClientboundChunkBatchFinishedPacket.class,
                    fixture.listener.packets.get(2)).batchSize());
            assertEquals(1, ChunkDelivery.applyCompletedChunkBatch(fixture.player));
            ChunkDelivery.onAck(fixture.player);
            assertNotEquals(ChunkDelivery.St.ACKNOWLEDGED, successor.st,
                    "the old-world ACK must not consume the new-world batch");
            ChunkDelivery.onAck(fixture.player);
            assertEquals(ChunkDelivery.St.ACKNOWLEDGED, successor.st);
            assertTrue(fixture.state.outstanding.isEmpty());
            assertEquals(ChunkDelivery.St.CANCELLED, old.st);
        }
    }

    @Test
    void updateDuringSnapshotCopyCannotBeStampedAsTheNewGeneration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkDelivery.track(fixture.player, fixture.chunk);
            var record = fixture.state.records.get(fixture.chunk.position.pack());
            long beforeCopy = fixture.chunk.fabrolia$writeGen();
            var stalePacket = fixture.allocate(ClientboundLevelChunkWithLightPacket.class);
            fixture.chunk.fabrolia$bumpWriteGen();
            var staleEntry = new ChunkDelivery.Entry(
                    fixture.chunk, beforeCopy, stalePacket, 1L, null);
            staleEntry.refs.incrementAndGet();
            record.entry = staleEntry;
            assertTrue(record.cas(ChunkDelivery.St.TRACKED, ChunkDelivery.St.READY));
            fixture.listener.release.countDown();
            ChunkDelivery.sendNextChunks(fixture.player, fixture.level, 1);
            fixture.awaitReceipts();
            assertTrue(fixture.listener.packets.isEmpty(),
                    "a write during the copy must reject the old payload");
            assertEquals(0, ChunkDelivery.applyCompletedChunkBatch(fixture.player));
            assertTrue(fixture.state.outstanding.isEmpty());

            var fresh = fixture.ready();
            ChunkDelivery.sendNextChunks(fixture.player, fixture.level, 1);
            fixture.awaitReceipts();
            assertSame(fresh.entry.packet, fixture.listener.packets.get(1));
            assertEquals(1, ChunkDelivery.applyCompletedChunkBatch(fixture.player));
            ChunkDelivery.onAck(fixture.player);
            assertEquals(ChunkDelivery.St.ACKNOWLEDGED, fresh.st);
        }
    }

    /**
     * A cached seal older than the record's mailbox lease is the payload lease()
     * already rejected. Adopting it counted a false fence failure per sender
     * pass, and three of those forced a fenced Main build (a Main claim on the
     * island) for a chunk that was merely waiting one owner unit for its seal.
     */
    @Test
    void requestedRecordWaitsForItsOwnSealInsteadOfFailingOnAnOlderOne() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long key = fixture.chunk.position.pack();
            ChunkDelivery.Entry older = fixture.entry(fixture.chunk.fabrolia$writeGen());
            fixture.publish(key, older);
            fixture.chunk.fabrolia$bumpWriteGen();
            ChunkDelivery.Rec record = fixture.requested();

            ArrayList<ChunkDelivery.Reservation> selected = new ArrayList<>();
            for (int pass = 0; pass < ChunkDelivery.ESCALATE_AFTER + 1; pass++) {
                fixture.select(record, selected);
            }
            assertEquals(ChunkDelivery.St.REQUESTED, record.st);
            assertEquals(0, record.fenceFails);
            assertEquals(0, fixture.state.retriesFence);
            assertEquals(0, older.refs.get(), "the older seal is never adopted");
            assertTrue(selected.isEmpty());

            ChunkDelivery.Entry answer = fixture.entry(fixture.chunk.fabrolia$writeGen());
            fixture.publish(key, answer);
            fixture.select(record, selected);
            assertEquals(1, selected.size(), "the seal built for this request is adopted");
            assertSame(answer, selected.get(0).entry);
            selected.get(0).rollback();
        }
    }

    /**
     * The REQUESTED bound exists for owners that never seal. A sender paused
     * past it (unacked-batch cap) must adopt the seal that already answered,
     * not escalate to a fenced Main rebuild of the same chunk.
     */
    @Test
    void answeredRequestIsAdoptedEvenPastTheEscalationBound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkDelivery.Rec record = fixture.requested();
            ChunkDelivery.Entry answer = fixture.entry(fixture.chunk.fabrolia$writeGen());
            fixture.publish(fixture.chunk.position.pack(), answer);
            record.stateNanos = System.nanoTime() - 2 * ChunkDelivery.REQUESTED_TIMEOUT_NANOS;

            ArrayList<ChunkDelivery.Reservation> selected = new ArrayList<>();
            fixture.select(record, selected);
            assertEquals(1, selected.size());
            assertSame(answer, selected.get(0).entry);
            assertEquals(0, fixture.state.requestedTimeouts);
            selected.get(0).rollback();
        }
    }

    /** Dead shared snapshots (chunk gone or written since the seal) are dropped;
     *  referenced, young and still-reusable ones stay. */
    @Test
    void pruneDropsOnlyUnreferencedSnapshotsNoRequestCanUse() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // Only the fixture chunk's own position resolves to a loaded chunk.
            long live = fixture.chunk.position.pack();
            long unloaded = new ChunkPos(-3, 5).pack();
            long referenced = new ChunkPos(-5, 5).pack();
            long young = new ChunkPos(-6, 5).pack();
            long generation = fixture.chunk.fabrolia$writeGen();
            ChunkDelivery.Entry reusable = fixture.aged(fixture.entry(generation));
            ChunkDelivery.Entry gone = fixture.aged(fixture.entry(generation));
            ChunkDelivery.Entry held = fixture.aged(fixture.entry(generation));
            held.refs.incrementAndGet();
            ChunkDelivery.Entry fresh = fixture.entry(generation);
            fixture.publish(live, reusable);
            fixture.publish(unloaded, gone);
            fixture.publish(referenced, held);
            fixture.publish(young, fresh);

            ChunkDelivery.pruneDeadSnapshots();
            var cache = fixture.cache();
            assertSame(reusable, cache.get(live), "loaded and unwritten: still reusable");
            assertFalse(cache.containsKey(unloaded));
            assertSame(held, cache.get(referenced), "a record still owns this payload");
            assertSame(fresh, cache.get(young), "may still answer a REQUESTED record");

            // Written since seal at a loaded position: the live chunk matches,
            // only the write generation tells the payload is unusable.
            fixture.chunk.fabrolia$bumpWriteGen();
            ChunkDelivery.pruneDeadSnapshots();
            assertFalse(cache.containsKey(live));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe;
        final DefaultEventLoop eventLoop = new DefaultEventLoop();
        final Map<String, Object> schedulerGlobals = new LinkedHashMap<>();
        final Map<AtomicLong, Long> counters = new LinkedHashMap<>();
        final TestLevel level;
        final TestChunk chunk;
        final TestPlayer player;
        final TestListener listener;
        final ChunkDelivery.PlayerState state;
        final String previousFaults = ChunkDelivery.faults;
        final long previousTraceKey;

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            level = allocate(TestLevel.class);
            chunk = allocate(TestChunk.class);
            chunk.localLevel = level;
            chunk.position = new ChunkPos(7, 11);
            chunk.fence = new ChunkWriteEnqueueFence();
            level.source = allocate(TestChunkSource.class);
            level.source.chunk = chunk;
            player = allocate(TestPlayer.class);
            player.localLevel = level;
            field(Entity.class, "uuid").set(player, UUID.randomUUID());
            field(Entity.class, "chunkPosition").set(player, chunk.position);
            listener = allocate(TestListener.class);
            listener.eventLoop = eventLoop;
            listener.release = new CountDownLatch(1);
            listener.packets = new ArrayList<>();
            listener.receipts = new ArrayList<>();
            listener.player = player;
            player.connection = listener;
            for (String name : List.of("active", "pool", "transitionPool", "orphanFatalFailure")) {
                schedulerGlobals.put(name, field(RegionScheduler.class, name).get(null));
            }
            for (String name : List.of("CONN_GENS", "BATCH_IDS", "FENCE_RETRIES")) {
                AtomicLong counter = (AtomicLong) field(ChunkDelivery.class, name).get(null);
                counters.put(counter, counter.get());
            }
            previousTraceKey = field(ChunkDelivery.class, "traceKey").getLong(null);
            field(ChunkDelivery.class, "traceKey").setLong(null, Long.MIN_VALUE);
            field(RegionScheduler.class, "active").set(null, false);
            field(RegionScheduler.class, "pool").set(null, null);
            field(RegionScheduler.class, "transitionPool").set(null, null);
            field(RegionScheduler.class, "orphanFatalFailure").set(null, null);
            ChunkDelivery.faults = "";
            state = ChunkDelivery.join(player);
            assertFalse(eventLoop.inEventLoop(), "Main is not the connection EventLoop");
        }

        <T> T allocate(Class<T> type) throws InstantiationException {
            return type.cast(unsafe.allocateInstance(type));
        }

        ChunkDelivery.Rec ready() throws Exception {
            return ready(chunk);
        }

        ChunkDelivery.Rec ready(TestChunk chunk) throws Exception {
            ChunkDelivery.track(player, chunk);
            ChunkDelivery.Rec record = state.records.get(chunk.position.pack());
            ChunkDelivery.Entry entry = new ChunkDelivery.Entry(chunk, chunk.fabrolia$writeGen(),
                    allocate(ClientboundLevelChunkWithLightPacket.class), 1L, null);
            // Legal sealed-payload boundary; snapshot/codec construction is not
            // the protocol under test. Reference ownership matches adoptSeal.
            entry.refs.incrementAndGet();
            record.entry = entry;
            assertTrue(record.cas(ChunkDelivery.St.TRACKED, ChunkDelivery.St.READY));
            return record;
        }

        ChunkDelivery.Rec requested() throws Exception {
            ChunkDelivery.track(player, chunk);
            ChunkDelivery.Rec record = state.records.get(chunk.position.pack());
            record.leaseNanos = System.nanoTime();
            assertTrue(record.cas(ChunkDelivery.St.TRACKED, ChunkDelivery.St.REQUESTED));
            return record;
        }

        ChunkDelivery.Entry entry(long writeGenAtSeal) throws Exception {
            return new ChunkDelivery.Entry(chunk, writeGenAtSeal,
                    allocate(ClientboundLevelChunkWithLightPacket.class), 1L, null);
        }

        ChunkDelivery.Entry aged(ChunkDelivery.Entry entry) throws Exception {
            field(ChunkDelivery.Entry.class, "builtNanos").setLong(entry,
                    System.nanoTime() - 10_000_000_000L);
            return entry;
        }

        void publish(long key, ChunkDelivery.Entry entry) throws Exception {
            Method publish = ChunkDelivery.class.getDeclaredMethod("publish",
                    ServerLevel.class, long.class, ChunkDelivery.Entry.class);
            publish.setAccessible(true);
            invoke(publish, null, level, key, entry);
        }

        @SuppressWarnings("unchecked")
        Map<Long, ChunkDelivery.Entry> cache() throws Exception {
            var all = (Map<ServerLevel, Map<Long, ChunkDelivery.Entry>>)
                    field(ChunkDelivery.class, "CACHE").get(null);
            return all.get(level);
        }

        void select(ChunkDelivery.Rec record,
                    ArrayList<ChunkDelivery.Reservation> selected) throws Exception {
            Method select = ChunkDelivery.class.getDeclaredMethod("selectCandidate",
                    ServerLevel.class, RegionizedServer.class, ChunkDelivery.PlayerState.class,
                    ChunkDelivery.Rec.class, ArrayList.class);
            select.setAccessible(true);
            invoke(select, null, level, null, state, record, selected);
        }

        void awaitReceipts() throws Exception {
            for (Future<?> receipt : listener.receipts) {
                receipt.get(5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() throws Exception {
            listener.release.countDown();
            try {
                eventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    ChunkDelivery.quit(player);
                    ChunkDelivery.dropLevelCache(level);
                } finally {
                    ChunkDelivery.faults = previousFaults;
                    field(ChunkDelivery.class, "traceKey").setLong(null, previousTraceKey);
                    for (Map.Entry<AtomicLong, Long> entry : counters.entrySet()) {
                        entry.getKey().set(entry.getValue());
                    }
                    for (Map.Entry<String, Object> entry : schedulerGlobals.entrySet()) {
                        field(RegionScheduler.class, entry.getKey()).set(null, entry.getValue());
                    }
                }
            }
        }
    }

    private static final class TestListener extends ServerGamePacketListenerImpl
            implements FabroliaFullChunkPublisher {
        DefaultEventLoop eventLoop;
        CountDownLatch release;
        List<Packet<?>> packets;
        List<Future<?>> receipts;
        Runnable onOffer;

        private TestListener() { super(null, null, null, null); }

        @Override
        public void fabrolia$enqueueFullChunkBatch(FullChunkBatchPublication publication) {
            assertFalse(eventLoop.inEventLoop());
            if (onOffer != null) {
                Runnable action = onOffer;
                onOffer = null;
                action.run();
            }
            receipts.add(eventLoop.submit(() -> {
                assertTrue(eventLoop.inEventLoop());
                assertTrue(release.await(5, TimeUnit.SECONDS), "Main must release the queued receipt");
                publication.runPublished(packets::add);
                return null;
            }));
        }
    }

    private static final class TestPlayer extends ServerPlayer {
        ServerLevel localLevel;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel level() { return localLevel; }
    }

    private static final class TestLevel extends ServerLevel {
        TestChunkSource source;
        private TestLevel() { super(null, null, null, null, null, null, false, 0L, null, false); }
        @Override public ServerChunkCache getChunkSource() { return source; }
    }

    private static final class TestChunkSource extends ServerChunkCache {
        TestChunk chunk;
        private TestChunkSource() {
            super(null, null, null, null, Runnable::run, null, 0, 0, false, null);
        }
        @Override public LevelChunk getChunkNow(int x, int z) {
            return chunk.position.x() == x && chunk.position.z() == z ? chunk : null;
        }
    }

    private static final class TestChunk extends LevelChunk implements FabroliaWriteGen {
        ServerLevel localLevel;
        ChunkPos position;
        ChunkWriteEnqueueFence fence;
        private TestChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public Level getLevel() { return localLevel; }
        @Override public ChunkPos getPos() { return position; }
        @Override public long fabrolia$writeGen() { return fence.generation(); }
        @Override public void fabrolia$bumpWriteGen() { fence.bump(); }
        @Override public boolean fabrolia$tryReserveFullChunkEnqueue(long generation) {
            return fence.tryReserveEnqueue(generation);
        }
        @Override public void fabrolia$releaseFullChunkEnqueue(long generation) {
            fence.releaseEnqueue(generation);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        }
    }
}
