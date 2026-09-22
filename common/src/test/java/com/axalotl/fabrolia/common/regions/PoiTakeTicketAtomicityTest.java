package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.PoiStripes;
import com.axalotl.fabrolia.common.utils.FabroliaPoiLoadGate;
import com.axalotl.fabrolia.common.utils.PoiChunkLoadFence;
import com.axalotl.fabrolia.common.utils.PoiSectionReadSnapshot;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #38, review D6 — "one bed, one villager", against real
 * {@link PoiRecord} mechanics. Lithium's {@code lithium$takeAt} called
 * {@code acquireTicket()} with no lock and DISCARDED its boolean
 * ({@code acquireTicket} returns {@code false} on a full record without
 * mutating), so N racing villagers all got {@code Optional.of(pos)} for a
 * one-ticket POI. Both acquisition paths (vanilla {@code take} in
 * {@code PoiManagerMixin}, Lithium's in {@code FabroliaLithiumPoiTakeAtMixin})
 * now funnel through {@link PoiStripes#takeTicketAtomically}, which is what
 * this test exercises under contention.
 *
 * <p>{@code PoiRecord.acquireTicket} is access-widened
 * (fabrolia.accesswidener), which is also what lets the production helper call
 * it without an accessor mixin.
 */
@Timeout(30)
class PoiTakeTicketAtomicityTest {

    private static PoiRecord oneBed(BlockPos pos) {
        // maxTickets = 1: a bed. No registries needed — PoiType is a plain record.
        return new PoiRecord(pos, Holder.direct(new PoiType(Set.of(), 1, 1)), () -> { });
    }

    @Test
    void oneTicketOneWinner() throws Exception {
        BlockPos bed = new BlockPos(100, 64, 200);
        PoiRecord record = oneBed(bed);
        Store storage = new Store(record);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger claims = new AtomicInteger();
        Thread[] villagers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            villagers[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                Optional<BlockPos> got = PoiStripes.takeTicketAtomically(storage, record);
                got.ifPresent(pos -> {
                    assertEquals(bed, pos);
                    claims.incrementAndGet();
                });
            }, "villager-" + i);
            villagers[i].start();
        }
        for (Thread t : villagers) {
            t.join(10_000);
            assertFalse(t.isAlive());
        }
        assertEquals(1, claims.get(), "a one-ticket POI was claimed more than once (or never)");
        assertEquals(0, record.getFreeTickets(), "ticket accounting corrupted");
        assertFalse(record.hasSpace());
    }

    /** A full record answers empty and mutates nothing — repeatably. */
    @Test
    void fullRecordYieldsEmptyWithoutMutation() {
        PoiRecord record = oneBed(new BlockPos(0, 0, 0));
        Store storage = new Store(record);
        assertTrue(PoiStripes.takeTicketAtomically(storage, record).isPresent());
        assertTrue(PoiStripes.takeTicketAtomically(storage, record).isEmpty(),
                "a full POI must not be handed out — this is the discarded-boolean bug");
        assertEquals(0, record.getFreeTickets());
    }

    @Test
    void selectedOrphanCannotClaimUnloadedOrReplacementRecord() {
        BlockPos pos = new BlockPos(64, 70, -17);
        PoiRecord selected = oneBed(pos);
        Store storage = new Store(selected);
        storage.fabrolia$unloadPoiColumn(ChunkPos.pack(4, -2), () -> storage.snapshot = null);
        assertTrue(PoiStripes.takeTicketAtomically(storage, selected).isEmpty());
        assertEquals(1, selected.getFreeTickets());

        PoiRecord replacement = oneBed(pos);
        storage.install(replacement);
        assertTrue(PoiStripes.takeTicketAtomically(storage, selected).isEmpty(),
                "same position and type do not make the old record resident");
        assertEquals(1, selected.getFreeTickets());
        assertEquals(1, replacement.getFreeTickets());
        assertEquals(Optional.of(pos), PoiStripes.takeTicketAtomically(storage, replacement));
        assertEquals(0, replacement.getFreeTickets());
    }

    @Test
    void unloadCannotFlushBetweenTicketMutationAndDirtyPublication() {
        BlockPos pos = new BlockPos(0, 64, 0);
        Store storage = new Store(oneBed(pos));
        AtomicReference<PoiRecord> selected = new AtomicReference<>();
        AtomicInteger savedTickets = new AtomicInteger(-1);
        PoiRecord record = new PoiRecord(pos, Holder.direct(new PoiType(Set.of(), 1, 1)), () -> {
            storage.fabrolia$unloadPoiColumn(ChunkPos.pack(0, 0), () -> {
                savedTickets.set(selected.get().getFreeTickets());
                storage.snapshot = null;
            });
            storage.fence.drainUnloads(storage.loadLock, ignored -> false);
            assertSame(selected.get(), storage.snapshot.recordAt(pos),
                    "ticket dirty callback ran against an unloaded section");
        });
        selected.set(record);
        storage.install(record);
        assertEquals(Optional.of(pos), PoiStripes.takeTicketAtomically(storage, record));
        assertEquals(-1, savedTickets.get(), "unload ran inside the ticket mutation receipt");
        storage.fence.drainUnloads(storage.loadLock, ignored -> false);
        assertEquals(0, savedTickets.get(), "unload persisted the pre-acquisition ticket count");
        assertNull(storage.snapshot);
    }

    /** Real fence and immutable resident identity, without Minecraft disk IO. */
    private static final class Store implements FabroliaPoiLoadGate {
        private final Object loadLock = new Object();
        private final PoiChunkLoadFence fence = new PoiChunkLoadFence();
        private volatile PoiSectionReadSnapshot snapshot;

        private Store(PoiRecord record) {
            install(record);
        }

        private void install(PoiRecord record) {
            Short2ObjectOpenHashMap<PoiRecord> records = new Short2ObjectOpenHashMap<>();
            records.put(SectionPos.sectionRelativePos(record.getPos()), record);
            snapshot = PoiSectionReadSnapshot.capture(loadLock,
                    Map.of(record.getPoiType(), Set.of(record)), records, true);
        }

        @Override
        public <T> T fabrolia$acquirePoiColumn(long chunkKey, Supplier<T> body) {
            return fence.acquire(loadLock, chunkKey, body);
        }

        @Override
        public void fabrolia$unloadPoiColumn(long chunkKey, Runnable body) {
            fence.unload(loadLock, chunkKey, ignored -> false, body);
        }

        @Override
        public void fabrolia$beginPoiResidency(long chunkKey) {
            fence.cancelUnload(loadLock, chunkKey);
        }

        @Override
        public Optional<PoiSectionReadSnapshot> fabrolia$portalSectionSnapshot(long sectionKey) {
            synchronized (loadLock) {
                return snapshot == null ? null : Optional.of(snapshot);
            }
        }

        @Override
        public void fabrolia$materializePortalColumn(ChunkPos chunk, int sectionY) {
            throw new AssertionError("ticket selection must not load or substitute a new resident");
        }
    }
}
