package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.mixin.server.PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Pass;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.Isolated
final class EntityStatusOwnerTransactionTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private final java.util.Map<String, Object> savedGate = new java.util.HashMap<>();

    @org.junit.jupiter.api.BeforeEach
    void isolateGate() throws Exception {
        for (String name : List.of("pool", "transitionPool", "orphanFatalFailure", "server")) {
            var field = schedulerField(name);
            savedGate.put(name, field.get(null));
            field.set(null, null);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGate() throws Exception {
        for (var entry : savedGate.entrySet()) schedulerField(entry.getKey()).set(null, entry.getValue());
    }

    private static java.lang.reflect.Field schedulerField(String name) throws Exception {
        var field = com.axalotl.fabrolia.common.regions.RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static IslandRegistry.PIsland owner(long id) throws Exception {
        var constructor = IslandRegistry.PIsland.class.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, 0L);
    }

    @Test
    void enteredMoveFinishesBeforeAnySectionOfTheChunkIsDemoted() throws Exception {
        IslandRegistry.PIsland owner = owner(712L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch moveNow = new CountDownLatch(1);
        CountDownLatch moved = new CountDownLatch(1);
        AtomicReference<Throwable> moveFailure = new AtomicReference<>();
        Set<TestEntity> tracked = new HashSet<>();
        List<String> transitions = new ArrayList<>();
        TestEntity mover = new TestEntity(1, 0);
        TestEntity upper = new TestEntity(2, 16);
        LevelCallback<TestEntity> callbacks = new Callbacks() {
            @Override public void onTickingEnd(TestEntity entity) {
                // The vanilla status loop has already changed this section to
                // HIDDEN, but has not called stopTracking. An unfenced status
                // body releases the entered mover at exactly the reported cut.
                if (entity == mover && owner.unitClaim.get()
                        && owner.claimHolder != Thread.currentThread()) {
                    moveNow.countDown();
                    await(moved);
                }
            }
            @Override public void onTrackingStart(TestEntity entity) {
                if (!tracked.add(entity)) throw new IllegalStateException("duplicate startTracking");
                transitions.add("start:" + entity.id);
            }
            @Override public void onTrackingEnd(TestEntity entity) {
                if (!tracked.remove(entity)) throw new IllegalStateException("unbalanced stopTracking");
                transitions.add("stop:" + entity.id);
            }
        };
        PersistentEntitySectionManager<TestEntity> manager = new PersistentEntitySectionManager<>(
                TestEntity.class, callbacks, new Storage());
        ChunkPos chunk = new ChunkPos(0, 0);
        manager.updateChunkStatus(chunk, Visibility.TICKING);
        assertTrue(manager.addNewEntity(mover));
        assertTrue(manager.addNewEntity(upper));
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        Thread worker = new Thread(() -> {
            try {
                assertTrue(owner.tryClaimAutonomousWork());
                owner.noteClaim("entered-status-test-mover");
                entered.countDown();
                await(moveNow);
                mover.moveTo(16);
            } catch (Throwable failure) {
                moveFailure.set(failure);
            } finally {
                owner.clearClaim();
                owner.unitClaim.set(false);
                moved.countDown();
            }
        });
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            statuses.offer(chunk.pack(), () -> manager.updateChunkStatus(chunk, Visibility.HIDDEN));
            statuses.drain((key, body) ->
                    PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(owner, false, body),
                    Pass.CALLBACK);
            assertTrue(manager.canPositionTick(chunk), "busy notification must leave all visibility untouched");
        } finally {
            moveNow.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(moveFailure.get());
        statuses.drain((key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(owner, false, body),
                    Pass.CALLBACK);
        assertFalse(manager.canPositionTick(chunk));
        assertEquals(Set.of(), tracked);
        assertEquals(2, transitions.stream().filter(s -> s.startsWith("start:")).count());
        assertEquals(2, transitions.stream().filter(s -> s.startsWith("stop:")).count());
        assertTrue(statuses.isEmpty());
    }

    @Test
    void queuedPromotionCannotOvertakeDemotionAndCallbacksBorrowTheExactMainLease() throws Exception {
        IslandRegistry.PIsland owner = owner(713L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        owner.unitClaim.set(true);
        statuses.offer(1L, () -> {
            assertTrue(IslandCommands.mainHolds(owner));
            assertFalse(IslandCommands.mainClaimScoped(owner), "callback borrows, never self-waits");
            events.add("hidden");
            statuses.offer(1L, () -> events.add("tracked"));
        });
        statuses.offer(1L, () -> events.add("ticking"));
        statuses.drain((key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(owner, false, body),
                    Pass.CALLBACK);
        assertEquals(List.of(), events);
        owner.unitClaim.set(false);
        statuses.drain((key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(owner, false, body),
                    Pass.CALLBACK);
        assertEquals(List.of("hidden", "ticking", "tracked"), events);
        assertFalse(owner.unitClaim.get());
        assertFalse(IslandCommands.mainHolds(owner));
    }

    @Test
    void partialStatusFailureReleasesItsClaimAndNeverReplaysTheBodyOrSuccessors() throws Exception {
        IslandRegistry.PIsland owner = owner(714L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        Error primary = new Error("partial visibility callback");
        List<String> events = new ArrayList<>();
        statuses.offer(1L, () -> { events.add("entered"); throw primary; });
        statuses.offer(1L, () -> events.add("successor"));
        assertSame(primary, assertThrows(Error.class, () -> statuses.drain((key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(owner, false, body),
                    Pass.CALLBACK)));
        assertEquals(List.of("entered"), events);
        assertSame(primary, schedulerField("orphanFatalFailure").get(null));
        assertFalse(owner.unitClaim.get());
        assertFalse(IslandCommands.mainHolds(owner));
        assertTrue(statuses.isEmpty());
    }

    @Test
    void unclaimableOwnerDefersOnlyItsOwnChunksAndKeepsPerChunkOrder() throws Exception {
        IslandRegistry.PIsland busy = owner(715L);
        IslandRegistry.PIsland free = owner(716L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner runner = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(
                        key == 1L ? busy : free, false, body);
        busy.unitClaim.set(true);
        statuses.offer(1L, () -> events.add("busy:hidden"));
        statuses.offer(2L, () -> {
            events.add("free:hidden");
            statuses.offer(2L, () -> events.add("free:tracked"));
        });
        statuses.offer(1L, () -> events.add("busy:tracked"));
        statuses.offer(3L, () -> events.add("free3:hidden"));
        statuses.drain(runner, Pass.TICK_HEAD);
        assertEquals(List.of("free:hidden", "free3:hidden", "free:tracked"), events,
                "a busy island's head must not block other islands' status changes");
        assertTrue(statuses.hasPending(1L));
        assertFalse(statuses.hasPending(2L));
        assertFalse(statuses.isEmpty());
        statuses.offer(1L, () -> events.add("busy:ticking"));
        busy.unitClaim.set(false);
        statuses.drain(runner, Pass.TICK_HEAD);
        assertEquals(List.of("free:hidden", "free3:hidden", "free:tracked",
                "busy:hidden", "busy:tracked", "busy:ticking"), events,
                "deferred entries of one chunk keep their order ahead of later offers");
        assertTrue(statuses.isEmpty());
        assertFalse(busy.unitClaim.get());
        assertFalse(IslandCommands.mainHolds(busy));
    }

    @Test
    void callbackDrainStopsAtTheFirstDeferredEntryAndTheTickHeadPassSkipsPastIt() throws Exception {
        IslandRegistry.PIsland busy = owner(719L);
        IslandRegistry.PIsland free = owner(720L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        int[] attempts = {0};
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner runner = (key, body) -> {
            attempts[0]++;
            return PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(
                    key == 1L ? busy : free, false, body);
        };
        busy.unitClaim.set(true);
        statuses.offer(2L, () -> events.add("free:hidden"));
        statuses.offer(1L, () -> events.add("busy:hidden"));
        statuses.offer(3L, () -> events.add("free3:hidden"));
        statuses.offer(1L, () -> events.add("busy:tracked"));
        statuses.offer(2L, () -> events.add("free:tracked"));

        statuses.drain(runner, Pass.CALLBACK);
        assertEquals(List.of("free:hidden"), events,
                "a status callback drain applies up to the first deferred entry only");
        assertEquals(2, attempts[0], "entries behind the deferred head are not visited");
        assertTrue(statuses.hasPending(1L));
        assertTrue(statuses.hasPending(2L), "the later free-chunk entry stays queued behind the deferral");
        assertTrue(statuses.hasPending(3L));

        statuses.offer(3L, () -> events.add("free3:tracked"));
        statuses.drain(runner, Pass.CALLBACK);
        assertEquals(List.of("free:hidden"), events, "the deferred head still ends the callback pass");
        assertEquals(3, attempts[0]);

        statuses.drainAtTickHead(runner, key -> key == 1L ? busy : free);
        assertEquals(List.of("free:hidden", "free3:hidden", "free:tracked", "free3:tracked"), events,
                "the tick-head pass applies every other chunk in offer order");
        assertTrue(statuses.hasPending(1L));
        assertFalse(statuses.hasPending(2L));
        assertFalse(statuses.hasPending(3L));

        busy.unitClaim.set(false);
        statuses.drain(runner, Pass.CALLBACK);
        assertEquals(List.of("free:hidden", "free3:hidden", "free:tracked", "free3:tracked",
                "busy:hidden", "busy:tracked"), events,
                "the deferred chunk keeps its own order once its owner is free");
        assertTrue(statuses.isEmpty());
        assertFalse(IslandCommands.mainHolds(busy));
    }

    @Test
    void persistentlyClaimedOwnerGetsClaimInterestAfterEscalationPassesAndLosesItOnceApplied() throws Exception {
        IslandRegistry.PIsland busy = owner(717L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner runner = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(busy, false, body);
        int threshold = PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.ESCALATE_AFTER_PASSES;
        busy.unitClaim.set(true);
        statuses.offer(1L, () -> events.add("hidden"));
        statuses.offer(1L, () -> events.add("tracked"));
        for (int pass = 1; pass < threshold; pass++) {
            statuses.drainAtTickHead(runner, key -> busy);
            assertFalse(busy.statusClaimWanted, "pass " + pass + " is below the escalation threshold");
        }
        statuses.drainAtTickHead(runner, key -> busy);
        assertTrue(busy.statusClaimWanted, "pass " + threshold + " announces Main's interest");
        assertEquals(List.of(), events);
        statuses.drainAtTickHead(runner, key -> busy);
        assertTrue(busy.statusClaimWanted, "interest stands while the chunk stays deferred");
        assertFalse(statuses.idle());

        busy.unitClaim.set(false);
        statuses.drainAtTickHead(runner, key -> busy);
        assertEquals(List.of("hidden", "tracked"), events);
        assertFalse(busy.statusClaimWanted, "applied status drops the interest in the same pass");
        assertTrue(statuses.idle());
        assertFalse(busy.unitClaim.get());
        assertFalse(IslandCommands.mainHolds(busy));

        // The counter restarted: a fresh deferral needs the full threshold again.
        busy.unitClaim.set(true);
        statuses.offer(1L, () -> events.add("ticking"));
        for (int pass = 1; pass < threshold; pass++) {
            statuses.drainAtTickHead(runner, key -> busy);
        }
        assertFalse(busy.statusClaimWanted);
        statuses.drainAtTickHead(runner, key -> busy);
        assertTrue(busy.statusClaimWanted);
        busy.unitClaim.set(false);
        statuses.drainAtTickHead(runner, key -> busy);
        assertFalse(busy.statusClaimWanted);
    }

    @Test
    void onlyTheTickHeadPassTouchesClaimInterestAndASiblingChunkKeepsIt() throws Exception {
        IslandRegistry.PIsland busy = owner(718L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner runner = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(busy, false, body);
        int threshold = PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.ESCALATE_AFTER_PASSES;
        busy.unitClaim.set(true);
        statuses.offer(1L, () -> events.add("one"));
        statuses.offer(2L, () -> events.add("two"));
        for (int pass = 0; pass < threshold; pass++) {
            // Status-callback drains between ticks neither count nor escalate.
            statuses.drain(runner, Pass.CALLBACK);
            statuses.drainAtTickHead(runner, key -> busy);
        }
        assertTrue(busy.statusClaimWanted);

        // Chunk 1 settles outside the tick-head pass (a status callback drain
        // while the unit happens to be between claims); chunk 2 is offered a
        // successor first so it stays pending after the same drain.
        statuses.offer(2L, () -> events.add("two-later"));
        busy.unitClaim.set(false);
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner onlyChunkOne = (key, body) ->
                key == 1L && PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(busy, false, body);
        statuses.drain(onlyChunkOne, Pass.CALLBACK);
        assertEquals(List.of("one"), events);
        assertTrue(busy.statusClaimWanted, "a non-tick-head drain never drops the interest");
        busy.unitClaim.set(true);
        statuses.drainAtTickHead(runner, key -> busy);
        assertTrue(busy.statusClaimWanted, "chunk 2 is still escalated on the same owner");
        assertFalse(statuses.idle());

        busy.unitClaim.set(false);
        statuses.drainAtTickHead(runner, key -> busy);
        assertEquals(List.of("one", "two", "two-later"), events);
        assertFalse(busy.statusClaimWanted);
        assertTrue(statuses.idle());
    }

    @Test
    void ownerChangeRestartsTheDeferralStreakAndMovesTheInterest() throws Exception {
        IslandRegistry.PIsland first = owner(719L);
        IslandRegistry.PIsland second = owner(720L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> events = new ArrayList<>();
        int threshold = PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.ESCALATE_AFTER_PASSES;
        first.unitClaim.set(true);
        second.unitClaim.set(true);
        statuses.offer(1L, () -> events.add("moved"));

        // Streak starts under `first`, then the chunk moves to `second` one
        // pass short of escalation: the count restarts, nothing is raised.
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner underFirst = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(first, false, body);
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner underSecond = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(second, false, body);
        for (int pass = 1; pass < threshold; pass++) statuses.drainAtTickHead(underFirst, key -> first);
        for (int pass = 1; pass < threshold; pass++) {
            statuses.drainAtTickHead(underSecond, key -> second);
            assertFalse(second.statusClaimWanted, "pass " + pass + " under the new owner");
        }
        assertFalse(first.statusClaimWanted);
        statuses.drainAtTickHead(underSecond, key -> second);
        assertTrue(second.statusClaimWanted);

        // An escalated chunk that moves again releases the old owner at once.
        statuses.drainAtTickHead(underFirst, key -> first);
        assertFalse(second.statusClaimWanted, "the old owner's interest ends when the chunk moves");
        assertFalse(first.statusClaimWanted, "the new owner needs its own full streak");
        assertEquals(List.of(), events);

        first.unitClaim.set(false);
        statuses.drainAtTickHead(underFirst, key -> first);
        assertEquals(List.of("moved"), events);
        assertTrue(statuses.idle());
    }

    @Test
    void statusInterestIsNotSavedOrRestoredByMainClaimScopes() throws Exception {
        IslandRegistry.PIsland busy = owner(721L);
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.Runner runner = (key, body) ->
                PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.runWithOwner(busy, false, body);
        int threshold = PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions.ESCALATE_AFTER_PASSES;
        busy.unitClaim.set(true);
        // A mainClaim spin's saved claimWanted=false must not be able to undo
        // the escalation: the two flags never share a writer.
        boolean savedClaimWanted = busy.claimWanted;
        busy.claimWanted = true;
        statuses.offer(1L, () -> {});
        for (int pass = 0; pass < threshold; pass++) statuses.drainAtTickHead(runner, key -> busy);
        assertTrue(busy.statusClaimWanted);
        busy.claimWanted = savedClaimWanted;
        assertFalse(busy.claimWanted);
        assertTrue(busy.statusClaimWanted, "restoring claimWanted leaves the status interest raised");
        busy.unitClaim.set(false);
        statuses.drainAtTickHead(runner, key -> busy);
        assertFalse(busy.statusClaimWanted);
        assertTrue(statuses.idle());
    }

    @Test
    void failedGenerationCancelsUnenteredStatusBodiesWithoutAcknowledgingTheirEffects() {
        var statuses = new PersistentEntityUnloadRetryMixin.FabroliaStatusTransitions();
        List<String> effects = new ArrayList<>();
        statuses.offer(1L, () -> effects.add("hidden"));
        statuses.cancel();
        statuses.drain((key, body) -> { body.run(); return true; }, Pass.FLUSH);
        assertEquals(List.of(), effects);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("interleaving did not advance");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static class Callbacks implements LevelCallback<TestEntity> {
        public void onCreated(TestEntity entity) {}
        public void onDestroyed(TestEntity entity) {}
        public void onTickingStart(TestEntity entity) {}
        public void onTickingEnd(TestEntity entity) {}
        public void onTrackingStart(TestEntity entity) {}
        public void onTrackingEnd(TestEntity entity) {}
        public void onSectionChange(TestEntity entity) {}
    }

    private static final class Storage implements EntityPersistentStorage<TestEntity> {
        public CompletableFuture<ChunkEntities<TestEntity>> loadEntities(ChunkPos pos) {
            return CompletableFuture.completedFuture(new ChunkEntities<>(pos, List.of()));
        }
        public void storeEntities(ChunkEntities<TestEntity> entities) {}
        public void flush(boolean flushStorage) {}
    }

    private static final class TestEntity implements EntityAccess {
        final int id;
        final UUID uuid;
        BlockPos pos;
        boolean removed;
        EntityInLevelCallback callback = EntityInLevelCallback.NULL;
        TestEntity(int id, int y) {
            this.id = id;
            this.uuid = new UUID(0, id);
            this.pos = new BlockPos(0, y, 0);
        }
        void moveTo(int y) { pos = new BlockPos(0, y, 0); callback.onMove(); }
        public int getId() { return id; }
        public UUID getUUID() { return uuid; }
        public boolean isRemoved() { return removed; }
        public BlockPos blockPosition() { return pos; }
        public AABB getBoundingBox() { return new AABB(pos); }
        public void setLevelCallback(EntityInLevelCallback callback) { this.callback = callback; }
        public Stream<TestEntity> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<TestEntity> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) { removed = true; callback.onRemove(reason); }
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
    }
}
