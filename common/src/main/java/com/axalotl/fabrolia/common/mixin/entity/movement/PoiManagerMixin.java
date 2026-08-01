package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongOpenHashSet;
import com.axalotl.fabrolia.common.utils.PoiStripes;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * POI write-path gate, lock-striped by position chunk (2026-06-10; stripes now
 * live in {@link PoiStripes} so Lithium's {@code lithium$takeAt} path keys into
 * the same monitors — task #38).
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
 * them is guarded per-op by the {@code LockedLong2ObjectMap} wrapper plus the
 * unpack handshake (task #38, {@code SectionStorageMixin}), so lazy section load
 * is safe.
 *
 * <p><b>Ticket atomicity moved to the record's stripe (task #38, review D6).</b>
 * {@code take} used to hold {@code stripe(center)} across its whole
 * stream-and-acquire — but vanilla take acquires records up to {@code distance}
 * blocks from the center, so {@code stripe(center) ≠ stripe(record.pos)} for
 * cross-chunk hits while {@code release(pos)} stripes by the record position:
 * take-vs-release on the same record could hold DIFFERENT monitors, and the
 * ignored {@code acquireTicket()} boolean meant the loser "took" a full POI
 * anyway. Worse, fixing that by nesting {@code stripe(record.pos)} inside a held
 * {@code stripe(center)} is a take-vs-take AB-BA (outer=s1/inner=s2 against
 * outer=s2/inner=s1) — the exact nested-stripe cycle class item (2) above died
 * of. So {@code take} now holds NO outer stripe: the stream runs lock-free like
 * every other query, and the single point of truth is
 * {@link PoiStripes#takeTicketAtomically} — atomic check-and-acquire under
 * {@code stripe(record.pos)}, honouring the boolean, on both the vanilla path
 * (here) and Lithium's ({@code FabroliaLithiumPoiTakeAtMixin}).
 */
@Mixin(PoiManager.class)
public class PoiManagerMixin {

    /**
     * Task #38, review D5: {@code PoiManager}'s own {@code loadedChunks} (the
     * {@code ensureLoadedAndValid} dedup set — distinct from
     * {@code SectionStorage}'s field of the same name) is a plain
     * {@code LongOpenHashSet} mutated from region workers via portal logic and
     * brain sensors. With Lithium's {@code fast_portals} overwrite cancelled
     * ({@code MixinCanceller} — its {@code @Unique preloadedCenterChunks} was an
     * unguarded live AIOOBE vector and its body bridges to
     * {@code getChunk(EMPTY)}, so a monitor wrap would recreate the task-#32
     * chunk-bridge deadlock shape), the restored vanilla body funnels through
     * this set — replaced with the lock-free concurrent implementation.
     * Deliberately NOT via the {@code SynchronisePlugin} rewrite table:
     * {@code LongOpenHashSet} is instantiated in dozens of hot targeted classes
     * and the table is config-global.
     */
    @Mutable
    @Shadow
    @Final
    private LongSet loadedChunks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fabrolia$concurrentLoadedChunks(CallbackInfo ci) {
        this.loadedChunks = new ConcurrentLongOpenHashSet();
    }

    /**
     * Reimplements vanilla {@code take} (never calls {@code original} —
     * precedent: {@code TickDomains.drainRemoveIfErased}): same stream, same
     * filters, but the acquire is atomic under {@code stripe(record.pos)} and a
     * failed acquire yields {@code Optional.empty()} instead of handing out a
     * full POI. Empty-on-failure is unreachable in single-threaded vanilla (the
     * HAS_SPACE filter guarantees success there), so semantics vs vanilla are
     * unchanged; under contention it is the correct answer and AcquirePoi's
     * retry logic already handles it.
     */
    @WrapMethod(method = "take")
    private Optional<BlockPos> fabrolia$atomicTake(Predicate<Holder<PoiType>> typePredicate,
                                                   BiPredicate<Holder<PoiType>, BlockPos> combinedPredicate,
                                                   BlockPos center, int distance,
                                                   Operation<Optional<BlockPos>> original) {
        PoiManager self = (PoiManager) (Object) this;
        return self.getInRange(typePredicate, center, distance, PoiManager.Occupancy.HAS_SPACE)
                .filter(poi -> combinedPredicate.test(poi.getPoiType(), poi.getPos()))
                .findFirst()
                .flatMap(PoiStripes::takeTicketAtomically);
    }

    @WrapMethod(method = "release")
    private boolean fabrolia$syncRelease(BlockPos pos, Operation<Boolean> original) {
        synchronized (PoiStripes.stripe(pos)) {
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
        synchronized (PoiStripes.stripe(pos)) {
            return original.call(pos, type);
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$syncRemove(BlockPos pos, Operation<Void> original) {
        synchronized (PoiStripes.stripe(pos)) {
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
     * same key as add/remove for any pos inside the section. This stripe is
     * also what serialises {@code getOrCreate}'s check-then-put for a given
     * section (its only callers are {@code add} and this method, both striped
     * by the same chunk).
     */
    @WrapMethod(method = "checkConsistencyWithBlocks")
    private void fabrolia$syncCheckConsistency(net.minecraft.core.SectionPos sectionPos,
                                            net.minecraft.world.level.chunk.LevelChunkSection section,
                                            Operation<Void> original) {
        synchronized (PoiStripes.stripe(sectionPos.x(), sectionPos.z())) {
            original.call(sectionPos, section);
        }
    }
}
