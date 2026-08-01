package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.utils.PoiStripes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * this test hammers — the mixin wiring is pinned in
 * {@link PoiBlanketSyncRemovalTest}.
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
                Optional<BlockPos> got = PoiStripes.takeTicketAtomically(record);
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
        assertTrue(PoiStripes.takeTicketAtomically(record).isPresent());
        for (int i = 0; i < 4; i++) {
            assertTrue(PoiStripes.takeTicketAtomically(record).isEmpty(),
                    "a full POI must not be handed out — this is the discarded-boolean bug");
            assertEquals(0, record.getFreeTickets());
        }
    }

    /**
     * The stripe key discipline itself: identical record position → identical
     * monitor, however the caller reached it (take by center, release by pos,
     * Lithium takeAt by pos). The review's "second hole" was takes and
     * releases holding different stripes for the same record.
     */
    @Test
    void samePositionAlwaysMapsToTheSameStripe() {
        BlockPos pos = new BlockPos(1023, 70, -517);
        assertSame(PoiStripes.stripe(pos), PoiStripes.stripe(pos.getX() >> 4, pos.getZ() >> 4));
        // Any position inside the same chunk shares the monitor with the
        // chunk-keyed entry points (add/remove/checkConsistency).
        assertSame(PoiStripes.stripe(pos), PoiStripes.stripe(new BlockPos(1008, 12, -528)));
    }
}
