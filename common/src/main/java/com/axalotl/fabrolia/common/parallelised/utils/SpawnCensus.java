package com.axalotl.fabrolia.common.parallelised.utils;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Incremental regional spawn census (task #21, 2026-07-22): authoritative
 * per-chunk per-category counts of census-eligible entities, maintained at the
 * SAME lifecycle seams the {@link SpawnIndex} membership mirror already rides
 * (tracking start/end, section moves) plus the two in-place eligibility flips
 * (persistence, death). {@code NaturalSpawner.createState} then reduces
 * O(occupied chunks × categories) instead of traversing every loaded entity —
 * and the reduction runs entirely on main with no pool submission, no join,
 * and no barrier.
 *
 * <p><b>Ledger invariant (drift-proof by construction):</b> every entity
 * carries its last-applied contribution (chunk, bucket, category, mob-flag) in
 * mixin fields ({@link Ledger}). Every event — enter, leave, move, persistence
 * flip, death — first subtracts exactly the recorded contribution, re-evaluates
 * eligibility, then adds and re-records the new one, under the entity's census
 * lock. Vectors therefore always equal the sum of their ledgers; no event
 * interleaving can double-count or leak.
 *
 * <p><b>Two buckets:</b> {@code live} and {@code corpse} (dead-but-unremoved
 * living entities). The corpse bucket is counted only while its chunk is
 * entity-ticking — the vanilla-exact corpse rule from the 2026-07-21 refill
 * parity fix — and that ticking test is evaluated against the LIVE map at
 * reduce time, so no chunk-transition observer is needed at all. Likewise the
 * spawnable-chunk predicate ({@code chunkGetter.query} success — c2me's
 * ticking filter or vanilla FULL) is evaluated live per occupied chunk during
 * the reduce: eligibility timing is bit-equivalent to the scan it replaces.
 *
 * <p><b>What stays evented:</b> only entity-membership facts (which chunk, which
 * bucket, which category). Chunk-status facts are read fresh each census.
 *
 * <p><b>Self-heal:</b> the c2me chunk system can drop entities without firing
 * the mirrored seams (task #32). The 256-gt audit reuses the established
 * two-strike purge + ground-truth reconcile, running OFF-MAIN, and corrects
 * exclusively through per-entity {@link #update} calls — never by vector
 * surgery — so the ledger invariant survives every correction. Drift direction
 * until the audit lands is counts-too-high (spawn suppression), never runaway.
 */
public final class SpawnCensus {

    private SpawnCensus() {}

    private static final MobCategory[] CATEGORIES = MobCategory.values();
    private static final int C = CATEGORIES.length;

    // ---- ledger (mixed into Entity) ------------------------------------

    /** Mixin duck on {@link Entity}: the last-applied census contribution. */
    public interface Ledger {
        Object fabrolia$censusLock();
        boolean fabrolia$censusTracked();
        void fabrolia$setCensusTracked(boolean tracked);
        long fabrolia$censusChunk();
        void fabrolia$setCensusChunk(long chunkKey);
        /** 0 = no contribution; else bits0-1 bucket (1 live, 2 corpse),
         *  bit2 mob-flag, bits3+ category ordinal. */
        int fabrolia$censusState();
        void fabrolia$setCensusState(int state);
    }

    // ---- per-level state ----------------------------------------------

    static final class LevelCensus {
        /** chunkKey → int[2C]: [cat] entity count, [C+cat] Mob-only count. */
        final ConcurrentHashMap<Long, AtomicIntegerArray> live = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, AtomicIntegerArray> corpse = new ConcurrentHashMap<>();
        /** Entities whose TYPE has a MobSpawnCost in at least one of the
         *  level's biomes — the only entities that can contribute potential
         *  charges. Usually empty outside the nether. */
        final Set<Entity> costBearing = ConcurrentHashMap.newKeySet();
        volatile Set<net.minecraft.world.entity.EntityType<?>> costTypes;
        volatile long costTypesBuiltAt = Long.MIN_VALUE;
    }

    private static final ConcurrentHashMap<ServerLevel, LevelCensus> LEVELS = new ConcurrentHashMap<>();

    static LevelCensus census(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, k -> new LevelCensus());
    }

    public static void clearLevel(ServerLevel level) {
        LEVELS.remove(level);
    }

    // ---- events --------------------------------------------------------

    /** Single funnel: subtract the recorded contribution, re-evaluate, add the
     *  new one. {@code tracked} is the SpawnIndex visibility fact (null = keep
     *  the ledger's current tracked flag — used by move/flip events). */
    public static void update(ServerLevel level, Entity e, Boolean tracked) {
        if (!(e instanceof Ledger led) || level == null) {
            return;
        }
        synchronized (led.fabrolia$censusLock()) {
            boolean t = tracked != null ? tracked : led.fabrolia$censusTracked();
            led.fabrolia$setCensusTracked(t);
            int oldState = led.fabrolia$censusState();
            long oldChunk = led.fabrolia$censusChunk();
            int newState = 0;
            long newChunk = 0L;
            if (t && !e.isRemoved()) {
                MobCategory cat = e.getType().getCategory();
                boolean persistentSkip = e instanceof Mob m
                        && (m.isPersistenceRequired() || m.requiresCustomPersistence());
                if (cat != MobCategory.MISC && !persistentSkip) {
                    boolean corpse = e instanceof LivingEntity le && le.isDeadOrDying();
                    newChunk = ChunkPos.pack(e.blockPosition());
                    newState = (corpse ? 2 : 1)
                            | (e instanceof Mob ? 4 : 0)
                            | (cat.ordinal() << 3);
                }
            }
            if (oldState == newState && (newState == 0 || oldChunk == newChunk)) {
                fabrolia$syncCostSet(level, e, newState != 0);
                return;
            }
            LevelCensus c = census(level);
            if (oldState != 0) {
                fabrolia$apply(c, oldChunk, oldState, -1);
            }
            if (newState != 0) {
                fabrolia$apply(c, newChunk, newState, +1);
            }
            led.fabrolia$setCensusChunk(newChunk);
            led.fabrolia$setCensusState(newState);
            fabrolia$syncCostSet(level, e, newState != 0);
        }
    }

    private static void fabrolia$apply(LevelCensus c, long chunkKey, int state, int sign) {
        (sign > 0 ? SpawnCensusDiag.EV_ADD : SpawnCensusDiag.EV_REMOVE).incrementAndGet();
        var map = (state & 3) == 2 ? c.corpse : c.live;
        AtomicIntegerArray vec = map.computeIfAbsent(chunkKey, k -> new AtomicIntegerArray(2 * C));
        int cat = state >>> 3;
        vec.addAndGet(cat, sign);
        if ((state & 4) != 0) {
            vec.addAndGet(C + cat, sign);
        }
    }

    private static void fabrolia$syncCostSet(ServerLevel level, Entity e, boolean contributing) {
        LevelCensus c = census(level);
        Set<net.minecraft.world.entity.EntityType<?>> types = fabrolia$costTypes(level, c);
        if (types.isEmpty() || !types.contains(e.getType())) {
            return;
        }
        if (contributing) {
            c.costBearing.add(e);
        } else {
            c.costBearing.remove(e);
        }
    }

    /** Types with a MobSpawnCost in ANY of the level's biomes — static per
     *  datapack state; rebuilt lazily and refreshed at most every 6000 gt so a
     *  datapack reload converges without a dedicated hook. */
    private static Set<net.minecraft.world.entity.EntityType<?>> fabrolia$costTypes(ServerLevel level, LevelCensus c) {
        Set<net.minecraft.world.entity.EntityType<?>> t = c.costTypes;
        long now = level.getGameTime();
        if (t != null && now - c.costTypesBuiltAt < 6000L) {
            return t;
        }
        Set<net.minecraft.world.entity.EntityType<?>> built = ConcurrentHashMap.newKeySet();
        try {
            for (var biome : level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)) {
                built.addAll(((com.axalotl.fabrolia.common.mixin.entity.spawn.MobSpawnSettingsAccessor)
                        (Object) biome.getMobSettings()).fabrolia$mobSpawnCosts().keySet());
            }
        } catch (Throwable ignored) {
            // registry unavailable mid-boot: empty set, retried next window
        }
        c.costTypes = built;
        c.costTypesBuiltAt = now;
        return built;
    }

    // ---- the census reduce (main thread, createState point) ------------

    public record Reduced(Object2IntOpenHashMap<MobCategory> counts,
                          Long2ObjectOpenHashMap<int[]> mobSeed,
                          int totalMobs) {}

    /** O(occupied chunks × categories). Chunk spawnability ({@code chunkGetter}
     *  parity) and the corpse entity-ticking rule are evaluated against LIVE
     *  state here — identical timing semantics to the full scan. */
    public static Reduced reduce(ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter) {
        LevelCensus c = census(level);
        var ticking = ((TickingChunksAccess)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager()).fabrolia$tickingChunks();
        Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>(C);
        Long2ObjectOpenHashMap<int[]> mobSeed = new Long2ObjectOpenHashMap<>(Math.max(16, c.live.size()));
        int totalMobs = 0;
        java.util.HashSet<Long> keys = new java.util.HashSet<>(c.live.keySet());
        keys.addAll(c.corpse.keySet());
        boolean[] ok = new boolean[1];
        for (long key : keys) {
            ok[0] = false;
            chunkGetter.query(key, ch -> ok[0] = true);
            if (!ok[0]) {
                continue;
            }
            AtomicIntegerArray live = c.live.get(key);
            AtomicIntegerArray corpse = ticking.get(key) != 0 ? c.corpse.get(key) : null;
            int[] mobs = null;
            for (int cat = 0; cat < C; cat++) {
                int n = (live != null ? live.get(cat) : 0) + (corpse != null ? corpse.get(cat) : 0);
                if (n > 0) {
                    counts.addTo(CATEGORIES[cat], n);
                }
                int m = (live != null ? live.get(C + cat) : 0) + (corpse != null ? corpse.get(C + cat) : 0);
                if (m > 0) {
                    if (mobs == null) {
                        mobs = new int[C];
                        mobSeed.put(key, mobs);
                    }
                    mobs[cat] = m;
                    totalMobs += m;
                }
            }
        }
        return new Reduced(counts, mobSeed, totalMobs);
    }

    /** Raw incremental totals for the drift report: [cat] = live + corpse-in-
     *  entity-ticking-chunk (no chunkGetter filter — comparable to the diag's
     *  section-walk censusable counts). */
    public static long[] debugTotals(ServerLevel level) {
        LevelCensus c = census(level);
        var ticking = ((TickingChunksAccess)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager()).fabrolia$tickingChunks();
        long[] out = new long[C];
        c.live.forEach((key, vec) -> {
            for (int cat = 0; cat < C; cat++) {
                out[cat] += vec.get(cat);
            }
        });
        c.corpse.forEach((key, vec) -> {
            long k = key; // primitive overload: get(Object) returns null for absent
            if (ticking.get(k) == 0) {
                return;
            }
            for (int cat = 0; cat < C; cat++) {
                out[cat] += vec.get(cat);
            }
        });
        return out;
    }

    /** Raw map shape for drift debugging: entry counts, per-map totals
     *  (including negatives), negative-slot count, and up to 3 sample
     *  vectors per map. */
    public static String debugDump(ServerLevel level) {
        LevelCensus c = census(level);
        StringBuilder sb = new StringBuilder(192);
        sb.append("liveChunks=").append(c.live.size())
          .append(" corpseChunks=").append(c.corpse.size())
          .append(" identity=").append(System.identityHashCode(c));
        long[] liveTot = new long[C];
        long[] corpseTot = new long[C];
        int negSlots = 0;
        int samples = 0;
        for (var e : c.live.entrySet()) {
            AtomicIntegerArray v = e.getValue();
            StringBuilder one = samples < 3 ? new StringBuilder(32) : null;
            for (int i = 0; i < 2 * C; i++) {
                int x = v.get(i);
                if (x < 0) negSlots++;
                if (i < C) liveTot[i] += x;
                if (one != null && x != 0) one.append(i).append(':').append(x).append(' ');
            }
            if (one != null && one.length() > 0) {
                samples++;
                sb.append(" L").append(Long.toHexString(e.getKey()))
                  .append('[').append(one.toString().trim()).append(']');
            }
        }
        for (var e : c.corpse.entrySet()) {
            AtomicIntegerArray v = e.getValue();
            for (int i = 0; i < 2 * C; i++) {
                int x = v.get(i);
                if (x < 0) negSlots++;
                if (i < C) corpseTot[i] += x;
            }
        }
        sb.append(" liveTot=").append(java.util.Arrays.toString(liveTot));
        sb.append(" corpseTot=").append(java.util.Arrays.toString(corpseTot));
        sb.append(" negSlots=").append(negSlots);
        return sb.toString();
    }

    // ---- audit (off-main, 256-gt cadence) ------------------------------

    /** Ground-truth pass reusing the established two-strike purge + reconcile:
     *  corrections land exclusively through {@link #update}, preserving the
     *  ledger invariant. Also sweeps zero vectors and logs vector-vs-ground
     *  drift to {@link SpawnCensusDiag} (diagnostic; the entity-level heals
     *  are what converge the counts). */
    public static void audit(ServerLevel level) {
        try {
            var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                    .fabrolia$entityManager();
            var storage = ((TickingChunksAccess) em).fabrolia$sectionStorage();
            for (Entity e : SpawnIndex.getEntities(level)) {
                if (e == null) {
                    continue;
                }
                boolean gone = e.isRemoved() || !ParallelSpawnHelper.fabrolia$inSectionsTolerant(level, e);
                if (!gone) {
                    SpawnIndex.clearStrike(level, e);
                    update(level, e, true);
                } else if (SpawnIndex.strikeForPurge(level, e)) {
                    SpawnIndex.getEntities(level).remove(e);
                    SpawnIndex.bumpVersion();
                    update(level, e, false);
                    SpawnCensusDiag.onPurgeSections(e);
                }
            }
            int healed = SpawnIndex.reconcile(level, storage);
            if (healed > 0) {
                SpawnCensusDiag.RECONCILE_HEALED.addAndGet(healed);
            }
            LevelCensus c = census(level);
            fabrolia$sweepZeroVectors(c.live);
            fabrolia$sweepZeroVectors(c.corpse);
        } catch (Throwable t) {
            SpawnCensusDiag.AUDIT_ERRORS.incrementAndGet();
        }
    }

    private static void fabrolia$sweepZeroVectors(ConcurrentHashMap<Long, AtomicIntegerArray> map) {
        for (Map.Entry<Long, AtomicIntegerArray> e : map.entrySet()) {
            AtomicIntegerArray v = e.getValue();
            boolean zero = true;
            for (int i = 0; i < 2 * C && zero; i++) {
                zero = v.get(i) == 0;
            }
            if (zero) {
                // remove-if-same: a concurrent increment replaces via computeIfAbsent
                // only when absent, so remove(key, value) is exact.
                map.remove(e.getKey(), v);
            }
        }
    }
}
