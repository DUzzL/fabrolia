package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Drain-scoped cache for {@code NbtPredicate.getEntityTagToCompare(player)}, plus the
 * concurrent-unsafe marker that forbids full entity saves on eval paths that overlap
 * live region ticking.
 *
 * <p><b>Safety model (2026-07-20 rework).</b> A full {@code Entity.saveWithoutId} walks
 * every live player collection (inventory, effects, {@code enderPearls}, root vehicle).
 * Under the deadline drive there is no global quiescent point implied by any drain
 * lane — island units tick independently of main's batch structure — so the only
 * places a full save is legal are: (a) inside a held fence window (all island claims
 * taken — {@code IslandCommands.fenceWindowHeld()}), which is how the batch-end
 * deferred drain now runs, and (b) paths where async islands are inactive. The
 * {@link #CONCURRENT_UNSAFE} marker is bound on the worker-lane eval
 * ({@code drainOnWorker}), where saves are always forbidden; the choke-point wrap on
 * {@code NbtPredicate.getEntityTagToCompare} (NbtPredicateMixin) enforces it for EVERY
 * caller — including 26.2's {@code EntityNbtPredicate}, which calls the static fetch
 * directly and bypassed the old {@code NbtPredicate.matches(Entity)} wrap (production
 * CME via {@code ServerPlayer.saveEnderPearls}, 2026-07-20, player Sorayamii).
 *
 * <p>Outside a drain scope {@link #CURRENT} is unbound and lookups return {@code null} —
 * callers fall through to the vanilla path. This matters when
 * {@code enableFabroliaAdvancements=false}: each trigger fires inline at the moment of
 * the trigger call, so consecutive evals on the same player can legitimately observe
 * different mid-tick states; a cache surviving across those calls would falsely pin the
 * first snapshot.
 *
 * <p>The expensive part vanilla pays is the full {@code Entity.saveWithoutId} →
 * {@code ServerPlayer.addAdditionalSaveData} → Codec encode chain — profiled at ~0.7%
 * of server-thread self in the u9jK2v2LbY 23-player session. With this cache, the first
 * predicate eval per (player, drain) pays the full cost; every subsequent eval in the
 * same drain reuses the tag — K matching listeners × N buffered pendings collapses from
 * K·N saves to one.
 */
public final class AdvancementCacheContext {

    private AdvancementCacheContext() {}

    /** ScopedValue (JDK 25 final, JEP 506) — the eval window is a perfect scope: bound
     *  around each eval task, auto-unwound on any exit path, ~10ns reads vs ~30-50ns
     *  ThreadLocal. NB: get() on an UNBOUND scoped value throws — always gate reads on
     *  isBound() (no null-sentinel idiom). */
    private static final ScopedValue<ConcurrentHashMap<UUID, CompoundTag>> CURRENT =
            ScopedValue.newInstance();

    /** Bound true only on the drainOnWorker eval path — that runs CONCURRENTLY with the region
     *  entity phase, where player state (enderPearls, inventory, …) is still mutated by region
     *  workers (an ender-pearl entity ticking → ServerPlayer.enderPearls). A full
     *  {@code saveWithoutId} there iterates those live collections → CME (live 2026-06-28,
     *  and again 2026-07-20 through the EntityNbtPredicate bypass). When set, full saves
     *  must NOT run; the pending defers to the fenced batch-end drain. */
    private static final ScopedValue<Boolean> CONCURRENT_UNSAFE = ScopedValue.newInstance();

    /** Runs {@code body} with a fresh cache bound for its duration. Callers must hold a
     *  fence window (or async must be inactive) — full player saves happen inside. */
    public static void runScoped(Runnable body) {
        ScopedValue.where(CURRENT, new ConcurrentHashMap<UUID, CompoundTag>(4)).run(body);
    }

    /** Like {@link #runScoped} but marks the scope concurrent-unsafe (drainOnWorker): full
     *  player/entity NBT saves are forbidden and must defer to batch-end. */
    public static void runScopedConcurrent(Runnable body) {
        ScopedValue.where(CURRENT, new ConcurrentHashMap<UUID, CompoundTag>(4))
                .where(CONCURRENT_UNSAFE, Boolean.TRUE).run(body);
    }

    /** True when evaluating on the concurrent path, where a full {@code saveWithoutId} would
     *  race live player-state mutation. */
    public static boolean isConcurrentUnsafe() {
        return CONCURRENT_UNSAFE.isBound() && CONCURRENT_UNSAFE.get();
    }

    /** Cache read only — never computes. {@code null} = no scope or no entry. */
    public static CompoundTag peekPlayerNbt(ServerPlayer player) {
        if (!CURRENT.isBound()) return null;
        return CURRENT.get().get(player.getUUID());
    }

    /**
     * Compute-through cache fill. {@code vanillaFetch} MUST be the original (un-wrapped)
     * {@code getEntityTagToCompare} body — passing the public method would recurse through
     * the choke-point wrap back into this cache (CHM computeIfAbsent is not reentrant for
     * the same key). Caller is responsible for only invoking this where a full save is
     * legal (fence held / async off / main-parked legacy drain).
     *
     * @return cached or freshly computed tag; {@code null} when no drain scope is active
     *         — caller falls through to the vanilla path.
     */
    public static CompoundTag getOrComputePlayerNbt(ServerPlayer player, Supplier<CompoundTag> vanillaFetch) {
        if (!CURRENT.isBound()) return null;
        ConcurrentHashMap<UUID, CompoundTag> cache = CURRENT.get();
        // computeIfAbsent serializes concurrent compute attempts for the same key — under
        // heavy fan-out (32 workers all evaluating predicates for the same player on the
        // same tick) only one worker pays the saveWithoutId cost; others wait briefly on
        // the CHM bin lock (microseconds vs the ~milliseconds a full save costs).
        return cache.computeIfAbsent(player.getUUID(), uuid -> vanillaFetch.get());
    }
}
