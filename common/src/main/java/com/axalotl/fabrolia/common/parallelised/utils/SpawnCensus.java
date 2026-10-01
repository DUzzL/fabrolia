package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;

/**
 * Incremental regional spawn census (task #21, 2026-07-22): authoritative
 * per-chunk per-category counts of census-eligible entities, maintained at the
 * SAME lifecycle seams the {@link SpawnIndex} membership mirror already rides
 * (tracking start/end, section moves) plus the two in-place eligibility flips
 * (persistence, death). {@code NaturalSpawner.createState} then reduces
 * O(occupied chunks × categories) instead of traversing every loaded entity.
 * Only occupied keys are snapshotted; live chunk queries and vector reads remain
 * serial on the caller's gameplay owner, in sorted-key order.
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
 * two-strike purge + ground-truth reconcile on the producer owner, and corrects
 * exclusively through per-entity {@link #update} calls — never by vector
 * surgery — so the ledger invariant survives every correction. Drift direction
 * until the audit lands is counts-too-high (spawn suppression), never runaway.
 */
public final class SpawnCensus {

    private SpawnCensus() {}

    private static final MobCategory[] CATEGORIES = MobCategory.values();
    private static final int C = CATEGORIES.length;
    private static final VectorUpdate[] VECTOR_UPDATES = fabrolia$vectorUpdates();
    static final int REDUCE_GRAIN = 512;


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
        // Updates (including both category/cap slots) and zero eviction share
        // the map's per-key compute lock. Readers remain weakly consistent.
        final ConcurrentHashMap<Long, AtomicIntegerArray> live = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, AtomicIntegerArray> corpse = new ConcurrentHashMap<>();
        /** Entities whose TYPE has a MobSpawnCost in at least one source of the
         *  level's natural_mob_spawns attribute — the only entities that can
         *  contribute potential charges. Usually empty outside the nether. */
        final Set<Entity> costBearing = ConcurrentHashMap.newKeySet();
        /** Null while unbuilt; {@link #ANY_COST_TYPE} when a timeline supplies spawn settings. */
        volatile java.util.function.Predicate<net.minecraft.world.entity.EntityType<?>> costTypes;
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
        map.compute(chunkKey, VECTOR_UPDATES[((state >>> 2) << 1) | (sign > 0 ? 1 : 0)]);
    }

    /** Cache the finite update operations instead of capturing state on every event. */
    private static VectorUpdate[] fabrolia$vectorUpdates() {
        VectorUpdate[] updates = new VectorUpdate[4 * C];
        for (int category = 0; category < C; category++) {
            for (int mob = 0; mob < 2; mob++) {
                int index = (category << 2) | (mob << 1);
                updates[index] = new VectorUpdate(category, mob != 0, -1);
                updates[index + 1] = new VectorUpdate(category, mob != 0, 1);
            }
        }
        return updates;
    }

    private static final class VectorUpdate
            implements BiFunction<Long, AtomicIntegerArray, AtomicIntegerArray> {
        private final int category;
        private final boolean mob;
        private final int sign;

        private VectorUpdate(int category, boolean mob, int sign) {
            this.category = category;
            this.mob = mob;
            this.sign = sign;
        }

        @Override
        public AtomicIntegerArray apply(Long key, AtomicIntegerArray vector) {
            if (vector == null) vector = new AtomicIntegerArray(2 * C);
            vector.addAndGet(category, sign);
            if (mob) vector.addAndGet(C + category, sign);
            return vector;
        }
    }

    private static void fabrolia$syncCostSet(ServerLevel level, Entity e, boolean contributing) {
        LevelCensus c = census(level);
        if (!fabrolia$costTypes(level, c).test(e.getType())) {
            return;
        }
        if (contributing) {
            c.costBearing.add(e);
        } else {
            c.costBearing.remove(e);
        }
    }

    private static final java.util.function.Predicate<net.minecraft.world.entity.EntityType<?>> ANY_COST_TYPE =
            type -> true;

    /** Types that can have a MobSpawnCost anywhere in the level: the 26.3
     *  natural_mob_spawns attribute is the dimension type's value overlaid by
     *  the biome's (costs are merged, so the union is exact), then by timeline
     *  tracks. Timeline keyframes are not enumerated: a dimension whose
     *  timelines supply the attribute makes every type a candidate (charges are
     *  still looked up per entity at its exact position). Static per datapack
     *  state; rebuilt lazily and refreshed at most every 6000 gt so a datapack
     *  reload converges without a dedicated hook. */
    private static java.util.function.Predicate<net.minecraft.world.entity.EntityType<?>> fabrolia$costTypes(
            ServerLevel level, LevelCensus c) {
        var t = c.costTypes;
        long now = level.getGameTime();
        if (t != null && now - c.costTypesBuiltAt < 6000L) {
            return t;
        }
        var attribute = net.minecraft.world.attribute.EnvironmentAttributes.NATURAL_MOB_SPAWNS;
        boolean timelineSupplied = false;
        for (var timeline : level.dimensionType().timelines()) {
            if (timeline.value().attributes().contains(attribute)) {
                timelineSupplied = true;
                break;
            }
        }
        java.util.function.Predicate<net.minecraft.world.entity.EntityType<?>> built;
        if (timelineSupplied) {
            built = ANY_COST_TYPE;
        } else {
            Set<net.minecraft.world.entity.EntityType<?>> types = new java.util.HashSet<>(
                    level.dimensionType().attributes()
                            .applyModifier(attribute, net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                            .allSpawnCosts().keySet());
            for (var biome : level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)) {
                types.addAll(biome.getAttributes()
                        .applyModifier(attribute, net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                        .allSpawnCosts().keySet());
            }
            Set<net.minecraft.world.entity.EntityType<?>> frozen = Set.copyOf(types);
            built = frozen::contains;
        }
        c.costTypes = built;
        c.costTypesBuiltAt = now;
        return built;
    }

    // ---- the census reduce (createState point) -------------------------

    public record Reduced(Object2IntOpenHashMap<MobCategory> counts,
                          Long2ObjectOpenHashMap<int[]> mobSeed,
                          int totalMobs) {}

    /** Result accumulator for one contiguous range [from, to) of the sorted key snapshot. */
    static final class CensusPart {
        final int from;
        final int to;
        final int[] counts = new int[C];
        int[][] mobSeedByOffset;
        int mobSeedSize;
        int totalMobs;

        CensusPart(int from, int to) {
            if (from < 0 || to < from) {
                throw new IllegalArgumentException("invalid census range " + from + ".." + to);
            }
            this.from = from;
            this.to = to;
        }

        void putMobSeed(int sortedKeysIndex, int[] mobs) {
            int offset = sortedKeysIndex - from;
            if (offset < 0 || offset >= to - from || mobs == null) {
                throw new IllegalArgumentException("invalid census seed index " + sortedKeysIndex);
            }
            int[][] byOffset = mobSeedByOffset;
            if (byOffset == null) {
                mobSeedByOffset = byOffset = new int[to - from][];
            }
            if (byOffset[offset] != null) {
                throw new IllegalStateException("duplicate census seed index " + sortedKeysIndex);
            }
            byOffset[offset] = mobs;
            mobSeedSize++;
        }
    }

    static int fabrolia$reduceBatchSize(int keyCount, int parallelism) {
        if (keyCount < 0) {
            throw new IllegalArgumentException("negative key count");
        }
        int workers = Math.max(1, parallelism);
        int fairShare = (int) (((long) keyCount + workers - 1L) / workers);
        return Math.max(REDUCE_GRAIN, fairShare);
    }

    static int fabrolia$reduceBatchCount(int keyCount, int batchSize) {
        if (keyCount < 0 || batchSize <= 0) {
            throw new IllegalArgumentException("invalid census batch shape");
        }
        return (int) (((long) keyCount + batchSize - 1L) / batchSize);
    }

    /**
     * O(occupied chunks × categories). Only occupied-key membership is
     * snapshotted. Chunk spawnability ({@code chunkGetter.query}), live/corpse
     * vectors, and the corpse entity-ticking predicate are still read together
     * at the exact per-key census body, never cached ahead of that query.
     * Large key sets fan out over the shared pool (Main's level census).
     */
    public static Reduced reduce(ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter) {
        return reduce(level, chunkGetter, false, null);
    }

    /** Same census, strictly on the calling thread — an island coordinator's
     *  lane census: island concurrency never authorizes foreign-executor
     *  fan-out. {@code laneKeys} pre-selects the lane's chunk keys before any
     *  admitted body (the level ledger holds every lane's chunks);
     *  {@code chunkGetter} remains the authoritative per-key membership and
     *  spawnability rule inside the body. */
    public static Reduced reduceSerial(ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter,
                                       java.util.function.LongPredicate laneKeys) {
        return reduce(level, chunkGetter, true, laneKeys);
    }

    private static Reduced reduce(ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter,
                                  boolean serial, java.util.function.LongPredicate laneKeys) {
        LevelCensus c = census(level);
        Long2IntConcurrentHashMap ticking = ((TickingChunksAccess)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager()).fabrolia$tickingChunks();
        long[] keys = fabrolia$snapshotKeys(c, laneKeys);
        if (keys.length == 0) {
            Reduced[] empty = new Reduced[1];
            RegionScheduler.runSuccessBodyIfHealthy(() -> empty[0] = new Reduced(
                    new Object2IntOpenHashMap<>(C),
                    new Long2ObjectOpenHashMap<>(16), 0));
            return empty[0];
        }

        if (!serial
                && keys.length >= 1024
                && ParallelProcessor.executor instanceof ForkJoinPool fjp
                && ParallelProcessor.getPoolSize() > 1) {
            int batchSize = fabrolia$reduceBatchSize(keys.length, ParallelProcessor.getPoolSize());
            int batchCount = fabrolia$reduceBatchCount(keys.length, batchSize);
            CensusPart[] parts = new CensusPart[batchCount];
            CensusRangeTask[] tasks = new CensusRangeTask[batchCount];
            ParallelSpawnHelper.SpawnFanoutGate gate = ParallelSpawnHelper.fabrolia$newSpawnFanoutGate();
            int b = 0;
            for (int from = 0; from < keys.length; from += batchSize) {
                int to = Math.min(from + batchSize, keys.length);
                CensusPart part = new CensusPart(from, to);
                parts[b] = part;
                tasks[b] = new CensusRangeTask(gate, c, ticking, chunkGetter, keys, part);
                b++;
            }
            int submitted = 0;
            try {
                while (submitted < batchCount) {
                    CensusRangeTask task = tasks[submitted];
                    submitted++;
                    gate.runNext(() -> fjp.execute(task));
                }
            } catch (Throwable submissionFailure) {
                gate.publishFailure(submissionFailure);
            }
            fabrolia$awaitCensusFanIn(tasks, submitted, gate);
            return fabrolia$mergeParts(keys, parts);
        }

        CensusPart part = new CensusPart(0, keys.length);
        fabrolia$reduceRange(c, ticking, chunkGetter, keys, part, null);
        return fabrolia$mergeParts(keys, new CensusPart[] {part});
    }

    private static long[] fabrolia$snapshotKeys(LevelCensus census,
                                                java.util.function.LongPredicate keyFilter) {
        long[][] snapshot = new long[1][];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> {
                    LongOpenHashSet union = new LongOpenHashSet();
                    fabrolia$addKeys(union, census.live.keySet(), keyFilter);
                    fabrolia$addKeys(union, census.corpse.keySet(), keyFilter);
                    long[] keys = union.toLongArray();
                    // CHM iteration order is deliberately unspecified. Sorting
                    // gives stable ranges and a stable serial merge order.
                    Arrays.sort(keys);
                    snapshot[0] = keys;
                });
        return snapshot[0];
    }

    private static void fabrolia$addKeys(LongOpenHashSet into, Set<Long> keys,
                                         java.util.function.LongPredicate keyFilter) {
        if (keyFilter == null) {
            into.addAll(keys);
            return;
        }
        for (Long key : keys) {
            long k = key;
            if (keyFilter.test(k)) into.add(k);
        }
    }

    static void fabrolia$reduceRange(
            LevelCensus census,
            Long2IntConcurrentHashMap ticking,
            NaturalSpawner.ChunkGetter chunkGetter,
            long[] keys,
            CensusPart out,
            ParallelSpawnHelper.SpawnFanoutGate gate) {
        boolean[] queryAccepted = new boolean[1];
        java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> accepted =
                chunk -> queryAccepted[0] = true;
        for (int i = out.from; i < out.to; i++) {
            if (gate != null) {
                gate.admitNext();
            }
            long key = keys[i];
            int sortedKeysIndex = i;
            // One key is one admitted live query + ledger read. If W wins,
            // no later key enters; an already-entered body fails normally.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                queryAccepted[0] = false;
                chunkGetter.query(key, accepted);
                if (!queryAccepted[0]) return;

                AtomicIntegerArray live = census.live.get(key);
                AtomicIntegerArray corpse = ticking.get(key) != 0
                        ? census.corpse.get(key) : null;
                int[] mobs = null;
                for (int cat = 0; cat < C; cat++) {
                    int n = (live != null ? live.get(cat) : 0)
                            + (corpse != null ? corpse.get(cat) : 0);
                    if (n > 0) out.counts[cat] += n;

                    int m = (live != null ? live.get(C + cat) : 0)
                            + (corpse != null ? corpse.get(C + cat) : 0);
                    if (m > 0) {
                        if (mobs == null) {
                            mobs = new int[C];
                            out.putMobSeed(sortedKeysIndex, mobs);
                        }
                        mobs[cat] = m;
                        out.totalMobs += m;
                    }
                }
            });
        }
    }

    private static void fabrolia$awaitCensusFanIn(
            CensusRangeTask[] tasks, int count, ParallelSpawnHelper.SpawnFanoutGate gate) {
        if (!ForkJoinTask.inForkJoinPool()) {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try {
                        RegionScheduler.throwWorkerFailureIfAny();
                    } catch (Throwable fatal) {
                        gate.publishFailure(fatal);
                    }
                    if (gate.failureOrNull() != null) {
                        for (int i = 0; i < count; i++) {
                            tasks[i].cancelBeforeRun();
                        }
                    }
                    boolean complete = true;
                    for (int i = 0; i < count; i++) {
                        if (!tasks[i].isDone()) {
                            complete = false;
                            break;
                        }
                    }
                    if (!complete) {
                        if (Thread.interrupted()) {
                            interrupted = true;
                        }
                        LockSupport.parkNanos("Fabrolia spawn-census quiescence", 25_000L);
                    }
                    if (complete) {
                        if (Thread.interrupted()) {
                            interrupted = true;
                        }
                        break;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        for (int i = 0; i < count; i++) {
            if (gate.failureOrNull() != null) {
                for (int k = 0; k < count; k++) {
                    tasks[k].cancelBeforeRun();
                }
            }
            try {
                tasks[i].join();
            } catch (Throwable encodedFailure) {
                if (gate.failureOrNull() == null) {
                    gate.publishFailure(encodedFailure);
                }
            }
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable fatal) {
            gate.publishFailure(fatal);
        }
        Throwable failure = gate.failureOrNull();
        if (failure != null) {
            fabrolia$rethrow(failure);
        }
    }

    static Reduced fabrolia$mergeParts(long[] keys, CensusPart[] parts) {
        Reduced[] merged = new Reduced[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            int seedEntryCount = 0;
            int expectedFrom = 0;
            for (CensusPart part : parts) {
                if (part == null || part.from != expectedFrom || part.to < part.from || part.to > keys.length) {
                    throw new IllegalStateException("non-contiguous census merge at " + expectedFrom);
                }
                int rangeLength = part.to - part.from;
                if (part.mobSeedSize < 0
                        || part.mobSeedSize > rangeLength
                        || (part.mobSeedByOffset != null && part.mobSeedByOffset.length != rangeLength)
                        || part.mobSeedSize > keys.length - seedEntryCount) {
                    throw new IllegalStateException("invalid census seed shape at " + part.from);
                }
                seedEntryCount += part.mobSeedSize;
                expectedFrom = part.to;
            }
            if (expectedFrom != keys.length) {
                throw new IllegalStateException("incomplete census merge " + expectedFrom + "/" + keys.length);
            }
            Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>(C);
            Long2ObjectOpenHashMap<int[]> mobSeed = new Long2ObjectOpenHashMap<>(Math.max(16, seedEntryCount));
            int totalMobs = 0;
            int mergedSeedEntries = 0;
            for (CensusPart part : parts) {
                for (int cat = 0; cat < C; cat++) {
                    int n = part.counts[cat];
                    if (n != 0) counts.addTo(CATEGORIES[cat], n);
                }
                int[][] byOffset = part.mobSeedByOffset;
                if (byOffset != null) {
                    for (int offset = 0; offset < byOffset.length; offset++) {
                        int[] mobs = byOffset[offset];
                        if (mobs != null) {
                            mobSeed.put(keys[part.from + offset], mobs);
                            mergedSeedEntries++;
                        }
                    }
                }
                totalMobs += part.totalMobs;
            }
            if (mergedSeedEntries != seedEntryCount) {
                throw new IllegalStateException(
                        "census seed cardinality mismatch " + mergedSeedEntries + "/" + seedEntryCount);
            }
            merged[0] = new Reduced(counts, mobSeed, totalMobs);
        });
        return merged[0];
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static final class CensusRangeTask extends RecursiveAction {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final ParallelSpawnHelper.SpawnFanoutGate gate;
        private final LevelCensus census;
        private final Long2IntConcurrentHashMap ticking;
        private final NaturalSpawner.ChunkGetter chunkGetter;
        private final long[] keys;
        private final CensusPart out;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private CensusRangeTask(
                ParallelSpawnHelper.SpawnFanoutGate gate,
                LevelCensus census,
                Long2IntConcurrentHashMap ticking,
                NaturalSpawner.ChunkGetter chunkGetter,
                long[] keys,
                CensusPart out) {
            this.gate = gate;
            this.census = census;
            this.ticking = ticking;
            this.chunkGetter = chunkGetter;
            this.keys = keys;
            this.out = out;
        }

        @Override
        protected void compute() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                return;
            }
            Throwable failure = null;
            try {
                gate.admitNext();
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> fabrolia$reduceRange(census, ticking, chunkGetter, keys, out, gate));
                gate.admitNext();
            } catch (Throwable thrown) {
                failure = gate.publishFailure(thrown);
            }
            state.set(TERMINAL);
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable terminalFailure) {
                    failure = gate.publishFailure(terminalFailure);
                }
            }
            if (failure != null) {
                fabrolia$rethrow(failure);
            }
        }

        void cancelBeforeRun() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                Throwable failure = gate.failureOrNull();
                if (failure == null) {
                    complete(null);
                } else {
                    completeExceptionally(failure);
                }
            }
        }
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

    // ---- audit (producer owner, 256-gt cadence) ------------------------

    /** Ground-truth pass reusing the established two-strike purge + reconcile:
     *  corrections land exclusively through {@link #update}, preserving the
     *  ledger invariant. Also sweeps zero vectors and logs vector-vs-ground
     *  drift to {@link SpawnCensusDiag} (diagnostic; the entity-level heals
     *  are what converge the counts). */
    public static void audit(ServerLevel level) {
        var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                .fabrolia$entityManager();
        var storage = ((TickingChunksAccess) em).fabrolia$sectionStorage();
        for (Entity e : SpawnIndex.getEntities(level)) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (e == null) return;
                boolean gone = e.isRemoved()
                        || !ParallelSpawnHelper.fabrolia$inSectionsTolerant(level, e);
                if (!gone) {
                    SpawnIndex.clearStrike(level, e);
                    update(level, e, true);
                } else if (SpawnIndex.strikeForPurge(level, e)) {
                    SpawnIndex.getEntities(level).remove(e);
                    SpawnIndex.bumpVersion();
                    update(level, e, false);
                    SpawnCensusDiag.onPurgeSections(e);
                }
            });
        }
        int[] healedSlot = new int[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> healedSlot[0] = SpawnIndex.reconcile(level, storage));
        int healed = healedSlot[0];
        if (healed > 0) {
            RegionScheduler.commitSuccessIfHealthy(
                    () -> SpawnCensusDiag.RECONCILE_HEALED.addAndGet(healed));
        }
        LevelCensus c = census(level);
        fabrolia$sweepZeroVectors(c.live);
        fabrolia$sweepZeroVectors(c.corpse);
    }

    private static void fabrolia$sweepZeroVectors(ConcurrentHashMap<Long, AtomicIntegerArray> map) {
        for (Long key : map.keySet()) {
            RegionScheduler.runSuccessBodyIfHealthy(() ->
                    map.computeIfPresent(key, SpawnCensus::fabrolia$retainNonZeroVector));
        }
    }

    private static AtomicIntegerArray fabrolia$retainNonZeroVector(
            Long key, AtomicIntegerArray vector) {
        for (int i = 0; i < 2 * C; i++) {
            if (vector.get(i) != 0) return vector;
        }
        return null;
    }
}
