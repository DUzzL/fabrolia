package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * POI write-path gate, lock-striped by position chunk (2026-06-10, third iteration).
 *
 * <p>History, because every step was paid for live: (1) one monitor over all seven query
 * methods — global static, later the region lock — serialized every villager AI query in
 * a dense region (12 of 30 workers parked on the getInChunk wrap at 3k villagers);
 * (2) striping the QUERIES deadlocked — query streams are consumed inside
 * findClosest/take under the stripe, and brain predicates issue NESTED queries with
 * different centers → stripe-A→stripe-B vs stripe-B→stripe-A cycles (JVM-detected,
 * 02:53); the old single lock survived nesting only через monitor reentrancy.
 *
 * <p>Resolution: queries are NOT locked at all. Their only effect was serializing
 * queries against each other — reads never raced writes under that lock either (writes
 * were never wrapped, and that shape was stable for months), and the section map above
 * them is concurrent (SectionStorageMixin: Long2ObjectConcurrentHashMap + synchronized
 * unpackChunk), so lazy section load is safe.
 *
 * <p>Only take/release stay locked, striped by the record's chunk: check-and-acquire of
 * the ticket must be atomic against другого такера ("one bed — one villager"), the same
 * record always maps to the same stripe, and these paths never nest POI calls inside
 * the stripe — no cycle possible.
 */
@Mixin(PoiManager.class)
public class PoiManagerMixin {

    @Unique
    private static final Object[] fabrolia$stripes = new Object[64];

    static {
        for (int i = 0; i < fabrolia$stripes.length; i++) fabrolia$stripes[i] = new Object();
    }

    @Unique
    private static Object fabrolia$stripe(int chunkX, int chunkZ) {
        // Same spread as ChunkPos.hash-lite; 64 stripes — collisions just serialize.
        int h = (chunkX * 31 + chunkZ) & (fabrolia$stripes.length - 1);
        return fabrolia$stripes[h];
    }

    @Unique
    private static Object fabrolia$stripe(BlockPos center) {
        return fabrolia$stripe(center.getX() >> 4, center.getZ() >> 4);
    }

    @WrapMethod(method = "take")
    private Optional<BlockPos> fabrolia$syncTake(Predicate<Holder<PoiType>> typePredicate,
                                              BiPredicate<Holder<PoiType>, BlockPos> combinedPredicate,
                                              BlockPos center, int distance,
                                              Operation<Optional<BlockPos>> original) {
        synchronized (fabrolia$stripe(center)) {
            return original.call(typePredicate, combinedPredicate, center, distance);
        }
    }

    @WrapMethod(method = "release")
    private boolean fabrolia$syncRelease(BlockPos pos, Operation<Boolean> original) {
        synchronized (fabrolia$stripe(pos)) {
            return original.call(pos);
        }
    }

    /**
     * Sync POI mutations on the same chunk stripe as {@code take}/{@code release}
     * (2026-06-14). Vanilla's {@code PoiSection.records} is a fastutil
     * {@code Short2ObjectOpenHashMap} (not thread-safe) and {@code byType} is
     * a plain {@code HashMap} — concurrent {@code add}/{@code remove} from
     * worker scheduled-tick / BE-tick slices that hit POI types (workstations
     * near a sorter: barrel, lectern, blast_furnace, fletching_table…)
     * corrupt the records map and trigger
     * {@code "POI data mismatch: never registered at"} when a later removal
     * finds an empty bucket.
     *
     * <p>Same stripe as take/release: identical positions hit the same monitor,
     * so add-then-remove on one BlockPos is serialised. Cross-position stripe
     * collisions just serialise too — cheap, since POI mutations are rare
     * compared to AI queries (which stay unlocked, see class javadoc).
     */
    @WrapMethod(method = "add")
    private PoiRecord fabrolia$syncAdd(BlockPos pos, net.minecraft.core.Holder<PoiType> type, Operation<PoiRecord> original) {
        synchronized (fabrolia$stripe(pos)) {
            return original.call(pos, type);
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$syncRemove(BlockPos pos, Operation<Void> original) {
        synchronized (fabrolia$stripe(pos)) {
            original.call(pos);
        }
    }

    /**
     * Sync the consistency-check entry point on the same per-chunk stripe.
     *
     * <p>{@code checkConsistencyWithBlocks} (called on chunk load by C2ME
     * workers and by vanilla {@code ServerChunkCache}) invokes
     * {@code PoiSection.refresh} which calls {@code clear()} on the
     * section's {@code records} map and rebuilds it. While that runs,
     * a concurrent {@code remove(pos)} on a tick worker hits a freshly
     * cleared map and trips the
     * {@code "POI data mismatch: never registered at"} log — observed
     * 2026-06-14 in the user's piston-sorter area where villager
     * workstations cluster (lectern, barrel, fletching_table) and
     * chunks are constantly reloading from C2ME's ticket churn.
     *
     * <p>{@code ensureLoadedAndValid} forwards to {@code checkConsistencyWithBlocks}
     * for any sections still in {@code !isValid} state, so wrapping that
     * one covers both. Stripe derived from the section's chunk coords —
     * same key as add/remove for any pos inside the section.
     */
    @WrapMethod(method = "checkConsistencyWithBlocks")
    private void fabrolia$syncCheckConsistency(net.minecraft.core.SectionPos sectionPos,
                                            net.minecraft.world.level.chunk.LevelChunkSection section,
                                            Operation<Void> original) {
        synchronized (fabrolia$stripe(sectionPos.x(), sectionPos.z())) {
            original.call(sectionPos, section);
        }
    }
}
