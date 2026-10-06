package com.axalotl.fabrolia.common.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The POI write-path stripe locks, extracted from {@code PoiManagerMixin} so
 * that every acquisition path — vanilla {@code take}, Lithium's
 * {@code lithium$takeAt} (a different mixin class), {@code release},
 * {@code add}/{@code remove}, {@code checkConsistencyWithBlocks} — keys into
 * the SAME 64 monitors, and so the ticket logic is plain testable code.
 *
 * <p><b>Stripe key discipline (task #38, D6):</b> ticket atomicity must be
 * striped by the <em>record's</em> position, not by the query center. Vanilla
 * {@code take} searches up to {@code distance} blocks around {@code center}
 * and can acquire a record in a different chunk; {@code release(pos)} stripes
 * by the record position. A take serialised on {@code stripe(center)} and a
 * release serialised on {@code stripe(record.pos)} can therefore hold
 * different monitors while racing the same ticket counter — which is exactly
 * how "one bed — two villagers" survived the old locking. All ticket
 * check-and-acquire now goes through {@link #takeTicketAtomically}.
 *
 * <p><b>Single-stripe invariant:</b> no code path may acquire a second stripe
 * while holding one (nested queries with different centers deadlocked in
 * production once — see {@code PoiManagerMixin}'s history javadoc). The ticket
 * acquire below takes exactly one stripe and calls nothing that can take
 * another: {@code acquireTicket → setDirty.run()} is a dirty-set add, a
 * deferred tracker enqueue and a storage read — no POI entry point.
 */
public final class PoiStripes {

    private static final Object[] STRIPES = new Object[64];

    static {
        for (int i = 0; i < STRIPES.length; i++) {
            STRIPES[i] = new Object();
        }
    }

    private PoiStripes() {
    }

    public static Object stripe(int chunkX, int chunkZ) {
        // Same spread as ChunkPos.hash-lite; 64 stripes — collisions just serialize.
        int h = (chunkX * 31 + chunkZ) & (STRIPES.length - 1);
        return STRIPES[h];
    }

    /**
     * Retain the resident column through the complete mutation, including its
     * dirty publication. Admission precedes the stripe; neither a loadLock nor
     * a storage read lock is held across unpack, the stripe, or the body.
     */
    public static <T> T mutateColumn(FabroliaPoiLoadGate storage, int chunkX, int chunkZ, Supplier<T> body) {
        return storage.fabrolia$acquirePoiColumn(ChunkPos.pack(chunkX, chunkZ), () -> {
            synchronized (stripe(chunkX, chunkZ)) {
                return body.get();
            }
        });
    }

    /**
     * The one true ticket acquire: atomic check-and-acquire under the stripe of
     * the record's own position, honouring {@code acquireTicket()}'s boolean.
     *
     * <p>{@code acquireTicket} returns {@code false} on a full record without
     * mutating; both vanilla {@code take} and Lithium's {@code takeAt} used to
     * discard that boolean, so under concurrency the loser "took" a full POI
     * and two villagers pathed to one bed. Empty-on-failure is
     * indistinguishable from single-threaded vanilla (where the HAS_SPACE
     * pre-filter makes failure unreachable) — the caller's retry logic
     * (AcquirePoi's JitteredLinearRetry) already handles an empty result.
     *
     * <p>{@code PoiRecord.acquireTicket} is access-widened (fabrolia.accesswidener
     * / accesstransformer.cfg).
     */
    public static Optional<BlockPos> takeTicketAtomically(FabroliaPoiLoadGate storage, PoiRecord record) {
        BlockPos pos = record.getPos();
        return mutateColumn(storage, pos.getX() >> 4, pos.getZ() >> 4, () -> {
            // Queries intentionally return immutable membership snapshots, not
            // residency leases. A selected record may have been removed or
            // unloaded/reloaded before admission. Never mutate that orphan (or
            // silently substitute a new record that the query did not select).
            Optional<PoiSectionReadSnapshot> section = storage.fabrolia$portalSectionSnapshot(SectionPos.asLong(pos));
            if (section == null || section.isEmpty() || section.get().recordAt(pos) != record) {
                return Optional.empty();
            }
            return record.acquireTicket() ? Optional.of(pos) : Optional.empty();
        });
    }
}
