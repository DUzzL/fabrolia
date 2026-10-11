package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.utils.LocalCapBridge;
import com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public final class ParallelSpawnHelper {

    private static final int SPAWN_GRAIN = 256;
    private static final MobCategory[] CATEGORIES = MobCategory.values();
    private static final int CATEGORY_COUNT = CATEGORIES.length;
    private static final int MISC_ORDINAL = MobCategory.MISC.ordinal();

    private static Entity[] entityBuf = new Entity[1024];

    private static boolean fabrolia$localCapLogged = false;
    private static boolean fabrolia$reservationDeclineLogged = false;
    /** The calculator can outlive its native allocation and then uses its Java shadow. */
    private record CapArena(LocalCapBridge.Arena nativeArena, FabroliaLocalCapView view) {}

    /** Close admission before detaching, then wait only a bounded time for readers.
     * A deferred free belongs to the final reader, including after the final level unload. */
    private static void fabrolia$detachAndFree(CapArena a, Throwable primary) {
        a.nativeArena().close();
        Throwable failure = primary;
        try {
            a.view().fabrolia$setCapArena(null);
        } catch (Throwable cleanup) {
            failure = failure == null ? cleanup : fabrolia$mergeFailure(failure, cleanup);
        }
        try {
            long deadline = System.nanoTime() + 1_000_000L;
            while (!a.nativeArena().freeIfQuiescent()) {
                if (System.nanoTime() >= deadline) {
                    a.nativeArena().deferFree(failure);
                    break;
                }
                Thread.onSpinWait();
            }
        } catch (Throwable cleanup) {
            failure = failure == null ? cleanup : fabrolia$mergeFailure(failure, cleanup);
        }
        if (failure != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
    }

    /** Mandatory cleanup preserves the exact already-selected winner, never retries free. */
    private static Throwable fabrolia$freeUnpublishedArena(
            LocalCapBridge.Provider p, CapArena arena, long handle, Throwable winner) {
        try {
            if (arena != null) {
                fabrolia$detachAndFree(arena, winner);
            } else {
                // Allocation returned but constructing its Java owner failed before attachment.
                p.free(handle);
            }
        } catch (Throwable cleanup) {
            fabrolia$mergeFailure(winner, cleanup);
        }
        return winner;
    }

    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, CapArena> fabrolia$lastArena =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ParallelSpawnHelper() {}

    /** Reusable main-thread marshalling arrays for the arena's initial-mob seed. */
    private static long[] fabrolia$mobChunkBuf = new long[1024];
    private static int[] fabrolia$mobCatBuf = new int[1024];

    /** Ask the registered {@link LocalCapBridge} provider (Oxidizium, when present + enabled) to
     *  build this tick's LOCAL-cap arena — SEEDED with the merged initial-mob entries in one
     *  native pass (the per-entry addMob replay on main was 4.1% of the server thread at 51
     *  players) — and attach its handle to {@code localMobCap}, so canSpawn/addMob route to the
     *  provider instead of the concurrent maps. The GLOBAL cap is untouched (stays vanilla
     *  Java — ServerCore multiplier / phantom wrap). The provider owns all Rust marshalling.
     *
     *  @return true if the arena is active AND consumed the initial mobs — the caller must
     *          then SKIP its Java replay (counting them twice would halve the local cap). */
    private static boolean fabrolia$buildLocalCapArena(ServerLevel level, LocalMobCapCalculator localMobCap,
                                                    List<MobCapEntry> initialMobs) {
        return fabrolia$buildLocalCapArenaSeed(
                level, localMobCap, initialMobs, null, 0);
    }

    /** Incremental-census seed: retain the compact per-chunk weights until the
     * provider and reservation-capability gates have both accepted the arena.
     * The native ABI still receives one primitive entry per mob, in the exact
     * same chunk-table/category/count order as the former MobCapEntry expansion. */
    private static boolean fabrolia$buildLocalCapArenaWeighted(
            ServerLevel level, LocalMobCapCalculator localMobCap,
            Long2ObjectOpenHashMap<int[]> weightedSeed, int weightedCount) {
        return fabrolia$buildLocalCapArenaSeed(
                level, localMobCap, null, weightedSeed, weightedCount);
    }

    private static boolean fabrolia$buildLocalCapArenaSeed(
            ServerLevel level, LocalMobCapCalculator localMobCap,
            List<MobCapEntry> initialMobs,
            Long2ObjectOpenHashMap<int[]> weightedSeed, int weightedCount) {
        if (level == null || localMobCap == null) {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
            return false;
        }
        LocalCapBridge.Provider[] provider = new LocalCapBridge.Provider[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> provider[0] = LocalCapBridge.provider());
        LocalCapBridge.Provider p = provider[0];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            CapArena prev = fabrolia$lastArena.remove(level);
            if (prev != null) {
                fabrolia$detachAndFree(prev, null);
            }
        });
        if (p == null) {
            RegionScheduler.commitSuccessIfHealthy(() -> {});
            return false;
        }
        // Pack reservation (task #32 endgame) needs an atomic fused
        // check+reserve the arena ABI must provide; a reservation-blind
        // provider would reopen the parallel local-cap window the reservation
        // exists to close. Decline the arena and keep the Java path (full
        // replay seed below — reservation-capable by construction) until the
        // provider ships supportsPackReservation. Runtime lever:
        // /fabrolia regions localcapreserve off restores the arena as-is.
        boolean reservationDeclined = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> FabroliaConfig.fabroliaSpawnLocalCapReservation
                        && !p.supportsPackReservation());
        if (reservationDeclined) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (!fabrolia$reservationDeclineLogged) {
                    fabrolia$reservationDeclineLogged = true;
                    FabroliaConfig.LOGGER.info("[Fabrolia] Local mob-cap arena DECLINED — provider lacks "
                            + "pack-reservation support; using the Java local-cap path (vanilla-equivalent). "
                            + "Update the provider or /fabrolia regions localcapreserve off to re-enable the arena.");
                }
            });
            RegionScheduler.commitSuccessIfHealthy(() -> {});
            return false;
        }
        int[] count = new int[1];
        long[][] chunkBuffer = new long[1][];
        int[][] categoryBuffer = new int[1][];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            boolean listSeed = initialMobs != null;
            if (listSeed == (weightedSeed != null)) {
                throw new IllegalStateException("local-cap seed must have exactly one representation");
            }
            int n = listSeed ? initialMobs.size() : weightedCount;
            if (n < 0) {
                throw new IllegalStateException("negative local-cap seed count " + n);
            }
            long[] chunks = fabrolia$mobChunkBuf;
            int[] cats = fabrolia$mobCatBuf;
            if (chunks.length < n) {
                int cap = Math.max(n, chunks.length * 2);
                fabrolia$mobChunkBuf = chunks = new long[cap];
                fabrolia$mobCatBuf = cats = new int[cap];
            }
            count[0] = n;
            chunkBuffer[0] = chunks;
            categoryBuffer[0] = cats;
        });
        int n = count[0];
        long[] chunks = chunkBuffer[0];
        int[] cats = categoryBuffer[0];
        if (initialMobs != null) {
            for (int i = 0; i < n; i++) {
                int entryIndex = i;
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    MobCapEntry e = initialMobs.get(entryIndex);
                    chunks[entryIndex] = e.chunkPos().pack();
                    cats[entryIndex] = e.category().ordinal();
                });
            }
        } else {
            int[] written = new int[1];
            for (var it = weightedSeed.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                // One occupied chunk is one atomic pure-marshalling body. A peer
                // W either precedes this chunk or observes the complete chunk;
                // no native/provider state exists until all chunks validate.
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    long chunkKey = entry.getLongKey();
                    int[] mobs = entry.getValue();
                    if (mobs == null || mobs.length < CATEGORY_COUNT) {
                        throw new IllegalStateException(
                                "invalid weighted local-cap vector for chunk " + chunkKey);
                    }
                    int cursor = written[0];
                    for (int category = 0; category < CATEGORY_COUNT; category++) {
                        int copies = mobs[category];
                        if (copies < 0 || copies > n - cursor) {
                            throw new IllegalStateException(
                                    "invalid weighted local-cap count " + copies
                                            + " for chunk " + chunkKey
                                            + " category " + category);
                        }
                        int end = cursor + copies;
                        java.util.Arrays.fill(chunks, cursor, end, chunkKey);
                        java.util.Arrays.fill(cats, cursor, end, category);
                        cursor = end;
                    }
                    written[0] = cursor;
                });
            }
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (written[0] != n) {
                    throw new IllegalStateException(
                            "weighted local-cap seed mismatch " + written[0] + "/" + n);
                }
            });
        }

        FabroliaLocalCapView[] viewSlot = new FabroliaLocalCapView[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> viewSlot[0] = (FabroliaLocalCapView) (Object) localMobCap);
        FabroliaLocalCapView view = viewSlot[0];
        long[] builtHandle = new long[1];
        CapArena[] ownership = new CapArena[1];
        boolean[] ownershipPublished = new boolean[1];
        try {
            // Native construction is one exact body. If its terminal permit
            // observes peer W after the provider returned, builtHandle retains
            // the otherwise-unowned allocation for mandatory cleanup below.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                builtHandle[0] = p.buildArena(level, chunks, cats, n);
                if (builtHandle[0] != 0L) {
                    ownership[0] = new CapArena(new LocalCapBridge.Arena(p, builtHandle[0]), view);
                }
            });
            long h = builtHandle[0];
            if (h == 0L) {
                RegionScheduler.commitSuccessIfHealthy(() -> {});
                return false;
            }

            CapArena arena = ownership[0];
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                view.fabrolia$setCapArena(arena.nativeArena());
                CapArena existing = fabrolia$lastArena.putIfAbsent(level, arena);
                if (existing != null) {
                    throw new IllegalStateException(
                            "native local-cap arena already owned for level");
                }
                ownershipPublished[0] = true;
            });
        } catch (Throwable winner) {
            long h = builtHandle[0];
            CapArena arena = ownership[0];
            boolean published = ownershipPublished[0]
                    || arena != null && fabrolia$lastArena.get(level) == arena;
            if (h != 0L && !published) {
                winner = fabrolia$freeUnpublishedArena(p, arena, h, winner);
            }
            ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(winner);
        }
        // SHADOW-SEED the Java maps on the producer owner: this cookie can
        // outlive its arena — the handle is detached at the NEXT tick's build
        // while lastSpawnState (debug probes, mods) keeps reading the
        // calculator. Post-detach canSpawn/addMob fall back to the Java maps;
        // empty maps mean vanilla canSpawn approves every near player (no
        // counts entry ⇒ "under cap"), which once silently disabled the
        // per-player LOCAL cap for chunk work consumed a tick late (the task
        // #32 cod runaway; island lanes now build their own SpawnState on the
        // plain Java calculator and never consume Main's cookie). The shadow
        // replay finishes before publication; SEED_BYPASS keeps it out of
        // native routing (the arena already holds these counts). Intra-tick
        // addMob traffic still routes native-only, so a mid-life detach sees
        // seed-exact counts (missing at most the current tick's own spawns —
        // bounded, self-correcting at the next build).
        long[][] seedChunkSlot = new long[1][];
        int[][] seedCategorySlot = new int[1][];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            seedChunkSlot[0] = java.util.Arrays.copyOf(chunks, n);
            seedCategorySlot[0] = java.util.Arrays.copyOf(cats, n);
        });
        long[] seedChunks = seedChunkSlot[0];
        int[] seedCats = seedCategorySlot[0];
        // This shadow is correctness state for a handle which outlives the native
        // arena, not best-effort telemetry. Finish it before publishing SpawnState;
        // a failure propagates through the current vanilla tick boundary.
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> view.fabrolia$seedJavaShadow(
                        seedChunks, seedCats, seedChunks.length));
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (!fabrolia$localCapLogged) {
                fabrolia$localCapLogged = true;
                FabroliaConfig.LOGGER.info("[Fabrolia] Local mob-cap arena ACTIVE (provider) — handle built, {} initial mobs seeded natively (+Java shadow)", n);
            }
        });
        RegionScheduler.commitSuccessIfHealthy(() -> {});
        return true;
    }

    /** Retire the level's arena; outstanding leases own deferred final-reader cleanup. */
    public static void fabrolia$freeLocalCapHandle(ServerLevel level) {
        CapArena prev = fabrolia$lastArena.get(level);
        if (prev != null) {
            if (!fabrolia$lastArena.remove(level, prev)) {
                throw new IllegalStateException("native local-cap arena ownership changed during teardown");
            }
            fabrolia$detachAndFree(prev, null);
        }
    }

    public static final class BatchResult {
        final int[] counts = new int[CATEGORY_COUNT];
        final List<ChargeEntry> charges;
        final List<MobCapEntry> mobCaps;

        BatchResult(int sizeHint) {
            this.charges = new ArrayList<>(Math.max(16, sizeHint >> 2));
            this.mobCaps = new ArrayList<>(Math.max(16, sizeHint >> 1));
        }

        void absorb(BatchResult other) {
            for (int i = 0; i < CATEGORY_COUNT; i++) {
                this.counts[i] += other.counts[i];
            }
            this.charges.addAll(other.charges);
            this.mobCaps.addAll(other.mobCaps);
        }
    }

    public record ChargeEntry(BlockPos pos, double charge) {}
    public record MobCapEntry(ChunkPos chunkPos, MobCategory category) {}

    /** Tick-head precompute submitted to the pool; membership changes invalidate it. */
    private record Pre(long version, PrecomputeTask task) {}

    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, Pre> PRECOMPUTE =
            new java.util.concurrent.ConcurrentHashMap<>();

    static SpawnFanoutGate fabrolia$newSpawnFanoutGate() {
        return new SpawnFailureGate();
    }

    private static void fabrolia$awaitPrecomputeQuiescence(PrecomputeTask task) {
        boolean interrupted = Thread.interrupted();
        try {
            while (!task.completion.isDone()) {
                if (Thread.interrupted()) interrupted = true;
                try {
                    RegionScheduler.throwWorkerFailureIfAny();
                } catch (Throwable fatal) {
                    task.gate.publishFailure(fatal);
                    task.cancelBeforeRun();
                }
                LockSupport.parkNanos("Fabrolia spawn-precompute quiescence", 25_000L);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static BatchResult fabrolia$awaitPrecompute(PrecomputeTask task) {
        fabrolia$awaitPrecomputeQuiescence(task);
        Throwable failure = task.gate.failureOrNull();
        if (failure != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
        RegionScheduler.throwWorkerFailureIfAny();
        return task.result;
    }

    private static void fabrolia$discardPrecompute(Pre pre) {
        if (pre != null) {
            pre.task().cancelBeforeRun();
            fabrolia$awaitPrecompute(pre.task());
        }
    }

    public static void precompute(ServerLevel level, NaturalSpawner.ChunkGetter getter) {
        if (level == null) return;
        fabrolia$discardPrecompute(PRECOMPUTE.remove(level));
        if (FabroliaConfig.fabroliaIncrementalSpawnCensus) return;
        ExecutorService exec = ParallelProcessor.executor;
        if (getter == null || exec == null || exec.isShutdown()) return;

        Collection<Entity> idx = SpawnIndex.getEntities(level);
        if (idx.isEmpty()) return;
        long version = SpawnIndex.version();
        SpawnFailureGate gate = new SpawnFailureGate();
        PrecomputeTask task = new PrecomputeTask(gate, () -> {
            ArrayList<Entity> live = new ArrayList<>(idx.size() + 64);
            for (Entity e : idx) {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    if (e == null || e.isRemoved() || fabrolia$deadCorpse(level, e)
                            || !fabrolia$inSectionsTolerant(level, e)) return;
                    live.add(e);
                });
            }
            Entity[][] snapshot = new Entity[1][];
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> snapshot[0] = live.toArray(Entity[]::new));
            return collect(level, snapshot[0], snapshot[0].length, getter);
        });
        Pre pre = new Pre(version, task);
        Pre displaced = PRECOMPUTE.put(level, pre);
        if (displaced != null) {
            PRECOMPUTE.remove(level, pre);
            task.cancelBeforeRun();
            fabrolia$discardPrecompute(displaced);
            throw new IllegalStateException("spawn precompute slot changed during publication");
        }
        try {
            task.gate.runNext(() -> exec.execute(task));
        } catch (Throwable publicationFailure) {
            Throwable winner = task.gate.publishFailure(publicationFailure);
            task.cancelBeforeRun();
            PRECOMPUTE.remove(level, pre);
            fabrolia$awaitPrecomputeQuiescence(task);
            Throwable authoritative = task.gate.failureOrNull();
            ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(
                    authoritative == null ? winner : authoritative);
        }
    }

    /** The serial tail shared by both paths: potential charges + per-chunk mob caps. */
    private static NaturalSpawner.SpawnState finishState(ServerLevel level,
                                                         int spawnableChunkCount,
                                                         BatchResult merged,
                                                         LocalMobCapCalculator localMobCap) {
        PotentialCalculator[] potentialSlot = new PotentialCalculator[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> potentialSlot[0] = new PotentialCalculator());
        PotentialCalculator potential = potentialSlot[0];
        for (int i = 0, m = merged.charges.size(); i < m; i++) {
            ChargeEntry c = merged.charges.get(i);
            // Each potential replay is a separate vanilla body.  The terminal
            // permit observes a peer W before this loop may enter the next one.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> potential.addCharge(c.pos(), c.charge()));
        }
        // Arena path: the provider seeds the local counts from merged.mobCaps in ONE native
        // pass during build (bit-exact addMob equivalent — same near-player predicate). The
        // Java replay below runs ONLY when the arena explicitly declined before
        // ownership (no provider / disabled / buildArena returned 0) — running
        // both would double-count and halve the local cap. A provider Throwable
        // after entry is terminal and never selects this path.
        boolean arenaSeeded = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> fabrolia$buildLocalCapArena(level, localMobCap, merged.mobCaps));
        if (!arenaSeeded) {
            for (int i = 0, m = merged.mobCaps.size(); i < m; i++) {
                MobCapEntry m2 = merged.mobCaps.get(i);
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> localMobCap.addMob(m2.chunkPos(), m2.category()));
            }
        }
        NaturalSpawner.SpawnState[] result = new NaturalSpawner.SpawnState[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            Object2IntOpenHashMap<MobCategory> countsMap =
                    new Object2IntOpenHashMap<>(CATEGORY_COUNT);
            for (int i = 0; i < CATEGORY_COUNT; i++) {
                int c = merged.counts[i];
                if (c != 0) countsMap.put(CATEGORIES[i], c);
            }
            result[0] = new NaturalSpawner.SpawnState(
                    spawnableChunkCount, countsMap, potential, localMobCap);
        });
        return result[0];
    }

    /** Full per-level teardown (level unload, both loaders): precompute slot,
     *  spawn index, and the native arena — each retained the ServerLevel (and
     *  the arena its off-heap allocation) forever with dynamic dimensions. */
    public static void onLevelUnload(ServerLevel level) {
        fabrolia$discardPrecompute(PRECOMPUTE.remove(level));
        SpawnCensus.clearLevel(level);
        com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex.clearLevel(level);
        ((FabroliaThreadNeighborUpdaters) level).fabrolia$releaseThreadNeighborUpdaters();
        com.axalotl.fabrolia.common.parallelised.utils.PlayerChunkIndex.onLevelUnload(level);
        fabrolia$freeLocalCapHandle(level);
        // Audit 2026-07-26: two more per-level registries retained the unloaded
        // ServerLevel for the life of the process. ChunkDelivery.dropLevelCache
        // existed for exactly this and had NO callers — its cache carries a
        // 96 MB byte budget of chunk payloads plus the level key. The portal
        // registries hold the level inside their value records.
        com.axalotl.fabrolia.common.regions.ChunkDelivery.dropLevelCache(level);
        com.axalotl.fabrolia.common.regions.NetherPortalTransfers.onLevelUnload(level);
    }

    public static NaturalSpawner.SpawnState buildState(int spawnableChunkCount, ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCap) {
        RegionScheduler.throwWorkerFailureIfAny();
        // Prefer the incremental SpawnIndex — bypasses the level-wide entity walk
        // (4.14% of server thread in R5FaKJnHJg). chunkGetter.query inside
        // computeRange still filters out non-ticking chunks at observation time,
        // so extra entries cost only their live chunk lookup.

        // INCREMENTAL CENSUS (task #21): O(occupied chunks × categories) reduce
        // over event-maintained counts — no entity traversal, no pool submission,
        // no main-thread wait. The scan below remains as the config-gated legacy path.
        if (FabroliaConfig.fabroliaIncrementalSpawnCensus) {
            // Discard the legacy precompute after a runtime config flip.
            Pre[] displaced = new Pre[1];
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> displaced[0] = PRECOMPUTE.remove(level));
            fabrolia$discardPrecompute(displaced[0]);
            return fabrolia$buildStateIncremental(level, spawnableChunkCount, chunkGetter, localMobCap);
        }

        // The LOCAL-cap arena (provider, e.g. Oxidizium) is built inside finishState — it
        // needs the MERGED initial-mob list to seed the counts in one native pass. All
        // canSpawn/addMob traffic starts after finishState attaches the handle.

        // Tick-head precompute: bit-identical when the membership version held.
        Pre[] preSlot = new Pre[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> preSlot[0] = PRECOMPUTE.remove(level));
        Pre pre = preSlot[0];
        // Every 256 ticks force the inline path: it PURGES stale index
        // entries (removed / section-absent) that the precompute only
        // filters — without this cadence the set's strong refs grow for
        // the whole session under region mode (precompute serves ~every
        // tick there).
        if (pre != null) {
            boolean candidate = RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> (level.getGameTime() & 255) != 0
                            && pre.version() == SpawnIndex.version());
            if (!candidate) {
                RegionScheduler.runSuccessBodyIfHealthy(pre.task()::cancelBeforeRun);
            }
            BatchResult merged = fabrolia$awaitPrecompute(pre.task());
            boolean stillCandidate = candidate
                    && RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> pre.version() == SpawnIndex.version());
            if (stillCandidate) {
                NaturalSpawner.SpawnState result = finishState(
                        level, spawnableChunkCount, merged, localMobCap);
                RegionScheduler.commitSuccessIfHealthy(
                        SpawnCensusDiag.CENSUS_PRE::incrementAndGet);
                return result;
            }
        }

        // Ground-truth resync at the forced-inline tick (task #32): re-add any
        // accessible live entity the index lost (missed attach seams, race
        // evictions) BEFORE counting, so the census below sees the healed set.
        boolean forceInline = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> (level.getGameTime() & 255) == 0);
        if (forceInline) {
            int[] healedSlot = new int[1];
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                var storage = ((TickingChunksAccess)
                        ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                                .fabrolia$entityManager()).fabrolia$sectionStorage();
                healedSlot[0] = SpawnIndex.reconcile(level, storage);
            });
            int healed = healedSlot[0];
            if (healed > 0) {
                RegionScheduler.commitSuccessIfHealthy(
                        () -> SpawnCensusDiag.RECONCILE_HEALED.addAndGet(healed));
            }
        }

        @SuppressWarnings("unchecked")
        Collection<Entity>[] indexSlot = new Collection[1];
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                indexSlot[0] = com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex
                        .getEntities(level));
        Collection<Entity> indexEntities = indexSlot[0];

        int n = 0;
        try {
            boolean useIndex = indexEntities != null
                    && RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> !indexEntities.isEmpty());
            if (useIndex) {
                n = fillBufferFromIndex(level, indexEntities);
                RegionScheduler.commitSuccessIfHealthy(
                        SpawnCensusDiag.CENSUS_INLINE::incrementAndGet);
            } else {
                // Vanilla createState's own source. Third-party wraps of that
                // call (C2ME notickvd, Lithium) sit inside the replaced body;
                // chunkGetter.query below applies the ticking-chunk filter.
                n = fillBuffer(level.getAllEntities());
            }
            BatchResult merged = collect(level, entityBuf, n, chunkGetter);
            // (a parallel playersNearChunk pre-warm was tried and reverted 2026-06-12 —
            // dispatch overhead exceeded the addMob saving; the arena seed made it moot)
            return finishState(level, spawnableChunkCount, merged, localMobCap);
        } finally {
            // encloses the FILL too — a fill-time throw must not strand up to
            // ~13k strong Entity refs in the static buffer (crash-day leak).
            // A throwing fill never returns its count, so clear the written
            // prefix itself: fills write from 0 contiguously and every census
            // leaves the buffer all-null behind it.
            Entity[] b = entityBuf;
            for (int i = 0; i < b.length && b[i] != null; i++) b[i] = null;
        }
    }

    /** Incremental-census createState (task #21). Semantics vs vanilla's scan:
     *  counts/mob-caps/charges come from the ledgered per-chunk vectors and the
     *  cost-bearing subset, with chunk spawnability ({@code chunkGetter}) and
     *  the corpse entity-ticking rule evaluated LIVE per occupied chunk at this
     *  exact point — the same moment the scan would have evaluated them.
     *
     *  <p>Saturation skip: a probe state (counts only) is asked through the
     *  REAL {@code getFilteredSpawningCategories(probe, true, true)} — the
     *  superset flags — so mod wraps on the global-cap gate (ServerCore
     *  multiplier, phantom wrap) keep applying. No spawnable category ⇒ the
     *  potential charges and the native local-cap arena are skipped outright
     *  (both are consulted only by spawn attempts); the Java local-cap maps
     *  are still seeded count-weighted so mods reading {@code lastSpawnState}
     *  observe vanilla-complete local counts. */
    private static NaturalSpawner.SpawnState fabrolia$buildStateIncremental(ServerLevel level,
            int spawnableChunkCount, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCap) {
        long t0 = System.nanoTime();
        boolean auditTick = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> (level.getGameTime() & 255) == 0);
        if (auditTick) {
            // ground-truth audit: two-strike purge + reconcile + zero-vector
            // sweep. This rare correctness repair is single-entry on Main: a
            // rejected execute cannot prove that an async repair did not enter,
            // so rejection->inline would be a partially-entered retry.
            fabrolia$auditFailStop(level);
        }
        SpawnCensus.Reduced[] reducedSlot = new SpawnCensus.Reduced[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> reducedSlot[0] = SpawnCensus.reduce(level, chunkGetter));
        SpawnCensus.Reduced red = reducedSlot[0];
        NaturalSpawner.SpawnState[] probeSlot = new NaturalSpawner.SpawnState[1];
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                probeSlot[0] = new NaturalSpawner.SpawnState(
                        spawnableChunkCount, red.counts(),
                        new PotentialCalculator(), localMobCap));
        NaturalSpawner.SpawnState probe = probeSlot[0];
        @SuppressWarnings("unchecked")
        java.util.List<MobCategory>[] spawnableSlot = new java.util.List[1];
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                spawnableSlot[0] = NaturalSpawner.getFilteredSpawningCategories(
                        probe, true, true));
        java.util.List<MobCategory> spawnable = spawnableSlot[0];
        if (spawnable.isEmpty()) {
            fabrolia$seedJavaCountWeighted(localMobCap, red.mobSeed());
            long elapsed = System.nanoTime() - t0;
            RegionScheduler.commitSuccessIfHealthy(() -> {
                SpawnCensusDiag.CENSUS_FAST.incrementAndGet();
                SpawnCensusDiag.CENSUS_SKIP_ALL.incrementAndGet();
                SpawnCensusDiag.addReduceNanos(elapsed);
            });
            return probe;
        }
        PotentialCalculator potential = fabrolia$potentialCharges(level, chunkGetter, null);
        // Keep the census seed weighted through provider/capability decline.
        // An accepted arena marshals it directly into the reusable primitive
        // ABI buffers; the Java fallback remains count-weighted as well.
        boolean arenaSeeded = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> fabrolia$buildLocalCapArenaWeighted(
                        level, localMobCap, red.mobSeed(), red.totalMobs()));
        if (!arenaSeeded) {
            fabrolia$seedJavaCountWeighted(localMobCap, red.mobSeed());
        }
        NaturalSpawner.SpawnState[] result = new NaturalSpawner.SpawnState[1];
        RegionScheduler.runSuccessBodyIfHealthy(() ->
                result[0] = new NaturalSpawner.SpawnState(
                        spawnableChunkCount, red.counts(), potential, localMobCap));
        long elapsed = System.nanoTime() - t0;
        RegionScheduler.commitSuccessIfHealthy(() -> {
            SpawnCensusDiag.CENSUS_FAST.incrementAndGet();
            SpawnCensusDiag.addReduceNanos(elapsed);
        });
        return result[0];
    }

    /** Potential charges: only cost-bearing types can contribute; evaluate
     *  their exact position/biome now (vanilla-identical), tiny N. The
     *  {@code chunkGetter} decides lane membership exactly as it does for
     *  the census. A non-null {@code laneKeys} (an island lane's own-cell
     *  predicate, the same rule its getter applies first) skips another
     *  lane's entities before any per-entity body: the level-wide set is
     *  shared by every lane, the charges are not. */
    private static PotentialCalculator fabrolia$potentialCharges(
            ServerLevel level, NaturalSpawner.ChunkGetter chunkGetter,
            java.util.function.LongPredicate laneKeys) {
        PotentialCalculator[] potentialSlot = new PotentialCalculator[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> potentialSlot[0] = new PotentialCalculator());
        PotentialCalculator potential = potentialSlot[0];
        for (Entity e : SpawnCensus.census(level).costBearing) {
            if (laneKeys != null && !laneKeys.test(ChunkPos.pack(e.blockPosition()))) continue;
            ChargeEntry[] chargeSlot = new ChargeEntry[1];
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                if (e == null || e.isRemoved()) return;
                if (e instanceof Mob mob
                        && (mob.isPersistenceRequired()
                        || mob.requiresCustomPersistence())) return;
                if (e.getType().getCategory() == MobCategory.MISC) return;
                if (fabrolia$deadCorpse(level, e)) return;
                BlockPos pos = e.blockPosition();
                chunkGetter.query(ChunkPos.pack(pos), chunk -> {
                    MobSpawnSettings.MobSpawnCost cost = level.environmentAttributes()
                            .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                            .getMobSpawnCost(e.getType());
                    if (cost != null) {
                        chargeSlot[0] = new ChargeEntry(pos, cost.charge());
                    }
                });
            });
            ChargeEntry charge = chargeSlot[0];
            if (charge != null) {
                RegionScheduler.runSuccessBodyIfHealthy(() ->
                        potential.addCharge(charge.pos(), charge.charge()));
            }
        }
        return potential;
    }

    /** One lane's census: category counts and potential charges over the
     *  chunks {@code laneGetter} admits, with {@code localMobCap}'s Java maps
     *  seeded count-weighted. */
    public record LaneCensus(Object2IntOpenHashMap<MobCategory> counts,
                             PotentialCalculator potential) {}

    /**
     * Island chunk-pass census, run by the island's coordinator on its own
     * thread. {@code ownCells} are the island's grid cells (cut on
     * {@code cellShift}): only their ledger keys are reduced. {@code laneGetter}
     * admits exactly the island's FULL chunks ({@code laneKeys} is its
     * own-cell half), so the ledger reduce, the potential charges and the
     * local-cap seed are all the island's own — vanilla's createState
     * restricted to one island.
     *
     * <p>The local cap stays on the plain Java {@code LocalMobCapCalculator}:
     * the island's chunk pass is sequential (one coordinator), which is
     * exactly the setting vanilla's calculator is correct in; the native
     * pack-reservation arena exists for Main's parallel lane only. The
     * incremental ledger is the sole census source here (no entity scan, no
     * pool fan-out); its 256-gt ground-truth audit remains Main's.
     */
    public static LaneCensus censusIslandLane(ServerLevel level,
                                              NaturalSpawner.ChunkGetter laneGetter,
                                              java.util.function.LongPredicate laneKeys,
                                              long[] ownCells, int cellShift,
                                              LocalMobCapCalculator localMobCap) {
        RegionScheduler.throwWorkerFailureIfAny();
        SpawnCensus.Reduced[] reducedSlot = new SpawnCensus.Reduced[1];
        RegionScheduler.runSuccessBodyIfHealthy(
                () -> reducedSlot[0] = SpawnCensus.reduceSerialCells(level, laneGetter, ownCells, cellShift));
        SpawnCensus.Reduced red = reducedSlot[0];
        PotentialCalculator potential = fabrolia$potentialCharges(level, laneGetter, laneKeys);
        fabrolia$seedJavaCountWeighted(localMobCap, red.mobSeed());
        RegionScheduler.commitSuccessIfHealthy(SpawnCensusDiag.CENSUS_FAST::incrementAndGet);
        return new LaneCensus(red.counts(), potential);
    }

    /** A periodic reconciliation is correctness work, not telemetry. Its exact
     *  failure is published into the shared first-wins fatal slot before leaving
     *  the single Main invocation. */
    private static void fabrolia$auditFailStop(ServerLevel level) {
        try {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> SpawnCensus.audit(level));
        } catch (Throwable failure) {
            try {
                com.axalotl.fabrolia.common.regions.RegionScheduler
                        .publishFatalFailure(failure);
            } catch (Throwable publicationFailure) {
                if (publicationFailure != failure) {
                    try {
                        failure.addSuppressed(publicationFailure);
                    } catch (Throwable ignored) {
                        // Fatal publication diagnostics cannot replace the audit primary.
                    }
                }
            }
            ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    /** Whether Main's createState runs the incremental ledger path — and with it
     *  the 256-gt ground-truth audit that keeps the island lanes' census honest. */
    public static boolean incrementalCensusActive() {
        return !FabroliaConfig.disabled && FabroliaConfig.enableFabroliaSpawn
                && ParallelProcessor.executor != null
                && FabroliaConfig.fabroliaIncrementalSpawnCensus;
    }

    /** Main-only ledger audit for the island lanes when Main's own census does
     *  not take the incremental path (vanilla / legacy scan): the islands still
     *  census from the ledger, so its self-heal must still run on Main's
     *  single-entry cadence. */
    public static void auditLedgerForIslandLanes(ServerLevel level) {
        fabrolia$auditFailStop(level);
    }

    private static void fabrolia$seedJavaCountWeighted(LocalMobCapCalculator calc,
            Long2ObjectOpenHashMap<int[]> mobSeed) {
        FabroliaLocalCapView view = (FabroliaLocalCapView) (Object) calc;
        for (var it = mobSeed.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            int[] mobs = entry.getValue();
            for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
                if (mobs[cat] > 0) {
                    long chunkKey = entry.getLongKey();
                    int category = cat;
                    int count = mobs[cat];
                    RegionScheduler.runSuccessBodyIfHealthy(() ->
                            view.fabrolia$addMobs(chunkKey, category, count));
                }
            }
        }
    }

    /** Dead-but-unremoved mobs (health 0, tickDeath pending) never leave a
     *  chunk that stopped entity-ticking — vanilla's removal needs ~20 ticks
     *  of tickDeath, so an admin-kill into a lazy mob switch leaves frozen
     *  corpses that hold the cap until someone walks close (a vanilla edge
     *  artifact, invisible in practice because looking = ticking).
     *
     *  <p>REFINED 2026-07-21 (refill-cadence parity): vanilla's census counts a
     *  dying mob for its whole ~20-tick death animation — after a mass /kill
     *  the caps stay held for that window and refill starts LATE. The original
     *  blanket corpse-skip released caps the same instant the kill landed,
     *  shifting the entire refill curve ~20 ticks early (measured: vanilla
     *  t+20 ≈ 0-2 respawns, Fabrolia ≈ 70-77 — the production "refills 2-3×
     *  faster" report). Vanilla-exact rule: count the corpse while its chunk
     *  is ENTITY-TICKING (it will remove itself within ≤20 ticks — same
     *  transient vanilla counts); skip it only when the chunk is NOT ticking
     *  (frozen corpse: tickDeath can never run, vanilla would hold the cap
     *  FOREVER — the 2026-07-18 mob-switch class-B release this filter exists
     *  for, and the Z1→Z2→Z3 regression gate depends on). */
    private static boolean fabrolia$deadCorpse(ServerLevel level, Entity e) {
        if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isDeadOrDying()) {
            return false;
        }
        var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                .fabrolia$entityManager();
        var ticking = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) em)
                .fabrolia$tickingChunks();
        return ticking.get(e.chunkPosition().pack()) == 0;
    }

    /** AUTHORITATIVE census membership (2026-07-18 mob-switch release): an
     *  entity exists for gameplay iff its CURRENT section contains it —
     *  selectors, kills, and collisions all resolve through section storage.
     *  The c2me chunk system unloads entities without firing stopTracking OR
     *  clearing byId (vanilla's own byId leaks the stale objects identically;
     *  invisible to baseline only because c2me's census filters to ticking
     *  chunks), so neither isRemoved nor byId identity can detect them. The
     *  section check is one map get + an identity scan of one section's
     *  small list, at census time, on whatever thread reads the index —
     *  independent of any island ticking or unload path firing. */
    private static boolean fabrolia$inSections(ServerLevel level, Entity e) {
        var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager()).fabrolia$sectionStorage();
        var section = storage.getSection(net.minecraft.core.SectionPos.asLong(e.blockPosition()));
        if (section == null) return false;
        final boolean[] found = {false};
        section.getEntities().forEach(x -> { if (x == e) found[0] = true; });
        return found[0];
    }

    /** Membership check with one settle retry for an in-flight section move.
     * A thrown collection failure propagates: treating an unreadable section as
     * present silently changes the vanilla census and can retain a false mob cap. */
    static boolean fabrolia$inSectionsTolerant(ServerLevel level, Entity e) {
        if (fabrolia$inSections(level, e)) return true;
        Thread.onSpinWait();
        return fabrolia$inSections(level, e);
    }

    private static Throwable fabrolia$mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Mandatory failure reporting cannot replace the exact first primary.
            }
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Fills {@link #entityBuf} from the {@link com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex}
     * snapshot, applying the lifecycle filters (removed, fake-player, fast-removed) inline so
     * downstream {@link #computeRange} can do raw per-entity work without re-checking. The
     * c2me-notickvd "is in ticking chunk" filter is intentionally NOT applied here — it's
     * handled by {@code chunkGetter.query} inside {@code computeRange}, which silently no-ops
     * for non-ticking chunks. That keeps semantics identical to vanilla's filtered iterator
     * while skipping the per-entity {@code ChunkPos.pack} + {@code Long2ByteConcurrentHashMap.containsKey}
     * pre-check chain that costs ~2% of server thread on a 23-player session.
     */
    private static int fillBufferFromIndex(ServerLevel level, Collection<Entity> entities) {
        // size() of the LIVE concurrent set is a capacity HINT, never a bound
        // (2026-07-17 production AIOOBE: buffer sized exactly to a stale
        // size(), weakly-consistent iteration observed a concurrent island
        // arrival, idx ran past the array). Growth is handled in-loop; the
        // slack absorbs the common few-arrivals case without reallocating.
        Entity[][] buffer = new Entity[][] {entityBuf};
        int[] size = new int[1];
        boolean[] purged = new boolean[1];
        @SuppressWarnings("unchecked")
        java.util.Iterator<Entity>[] iterator = new java.util.Iterator[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            int hint = entities.size();
            if (buffer[0].length < hint + 64) {
                buffer[0] = new Entity[Math.max(hint + 64, buffer[0].length * 2)];
                entityBuf = buffer[0];
            }
            iterator[0] = entities.iterator();
        });
        java.util.Iterator<Entity> it = iterator[0];
        while (RegionScheduler.callSuccessBooleanIfHealthy(it::hasNext)) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                Entity e = it.next();
                if (e == null) return;
                int purgeReason = e.isRemoved() ? 1
                        : fabrolia$deadCorpse(level, e) ? 2
                        : !fabrolia$inSectionsTolerant(level, e) ? 3 : 0;
                if (purgeReason == 3 && !SpawnIndex.strikeForPurge(level, e)) {
                    SpawnCensusDiag.SECT_STRIKE1.incrementAndGet();
                    // First failed sighting stays census-visible; a second
                    // exact sighting performs the authoritative removal.
                    purgeReason = 0;
                } else if (purgeReason == 0) {
                    SpawnIndex.clearStrike(level, e);
                }
                if (purgeReason != 0) {
                    it.remove();
                    purged[0] = true;
                    SpawnIndex.clearStrike(level, e);
                    switch (purgeReason) {
                        case 1 -> SpawnCensusDiag.onPurgeRemoved(e);
                        case 2 -> SpawnCensusDiag.onPurgeCorpse(e);
                        default -> SpawnCensusDiag.onPurgeSections(e);
                    }
                    return;
                }
                if (size[0] == buffer[0].length) {
                    buffer[0] = java.util.Arrays.copyOf(buffer[0],
                            buffer[0].length + (buffer[0].length >> 1) + 64);
                    entityBuf = buffer[0];
                }
                buffer[0][size[0]++] = e;
            });
        }
        if (purged[0]) {
            RegionScheduler.runSuccessBodyIfHealthy(SpawnIndex::bumpVersion);
        } else {
            RegionScheduler.commitSuccessIfHealthy(() -> { });
        }
        return size[0];
    }

    private static int fillBuffer(Iterable<Entity> entities) {
        // One growth-tolerant loop for every source (2026-07-17 audit): the
        // old Collection fast path returned the PRE-iteration size after a
        // toArray on a possibly-mutating source — a shrink left null slots
        // inside the processed range (NPE in computeRange), a grow silently
        // truncated and leaked un-nulled refs past the stale count. Sizing is
        // only a capacity hint; the returned count is the written count, and
        // the lifecycle filter now holds on every path (computeRange's
        // no-recheck contract).
        Entity[][] buffer = new Entity[][] {entityBuf};
        int[] size = new int[1];
        @SuppressWarnings("unchecked")
        java.util.Iterator<Entity>[] iterator = new java.util.Iterator[1];
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            if (entities instanceof java.util.Collection<?> c) {
                int hint = c.size() + 64;
                if (buffer[0].length < hint) {
                    buffer[0] = new Entity[Math.max(hint, buffer[0].length * 2)];
                    entityBuf = buffer[0];
                }
            }
            iterator[0] = entities.iterator();
        });
        java.util.Iterator<Entity> it = iterator[0];
        while (RegionScheduler.callSuccessBooleanIfHealthy(it::hasNext)) {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                Entity e = it.next();
                if (e == null || e.isRemoved()) return;
                if (size[0] == buffer[0].length) {
                    buffer[0] = java.util.Arrays.copyOf(buffer[0],
                            buffer[0].length + (buffer[0].length >> 1) + 64);
                    entityBuf = buffer[0];
                }
                buffer[0][size[0]++] = e;
            });
        }
        RegionScheduler.commitSuccessIfHealthy(() -> { });
        return size[0];
    }

    private static BatchResult collect(ServerLevel level, Entity[] entities, int n,
                                       NaturalSpawner.ChunkGetter chunkGetter) {
        if (n == 0) return new BatchResult(0);
        if (n > SPAWN_GRAIN && ParallelProcessor.executor instanceof ForkJoinPool fjp) {
            int poolSize = Math.max(1, ParallelProcessor.getPoolSize());
            int batchSize = Math.max(64, (n + poolSize - 1) / poolSize);
            int batchCount = (n + batchSize - 1) / batchSize;
            BatchResult[] parts = new BatchResult[batchCount];
            CollectRangeTask[] tasks = new CollectRangeTask[batchCount];
            SpawnFailureGate gate = new SpawnFailureGate();
            int b = 0;
            for (int from = 0; from < n; from += batchSize, b++) {
                int to = Math.min(from + batchSize, n);
                parts[b] = new BatchResult(to - from);
                tasks[b] = new CollectRangeTask(gate, level, entities, chunkGetter, from, to, parts[b]);
            }
            int submitted = 0;
            try {
                while (submitted < batchCount) {
                    CollectRangeTask task = tasks[submitted];
                    submitted++;
                    gate.runNext(() -> fjp.execute(task));
                }
            } catch (Throwable submissionFailure) {
                gate.publishFailure(submissionFailure);
            }
            fabrolia$awaitCollectFanIn(tasks, submitted, gate);
            BatchResult[] merged = new BatchResult[1];
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                BatchResult result = new BatchResult(n);
                for (BatchResult p : parts) result.absorb(p);
                merged[0] = result;
            });
            return merged[0];
        }
        BatchResult result = new BatchResult(n);
        computeRange(level, entities, chunkGetter, 0, n, result);
        return result;
    }

    private static void fabrolia$awaitCollectFanIn(CollectRangeTask[] tasks, int count,
                                                    SpawnFailureGate gate) {
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
                        for (int i = 0; i < count; i++) tasks[i].cancelBeforeRun();
                    }
                    boolean complete = true;
                    for (int i = 0; i < count; i++) {
                        if (!tasks[i].isDone()) {
                            complete = false;
                            break;
                        }
                    }
                    if (Thread.interrupted()) interrupted = true;
                    if (complete) break;
                    LockSupport.parkNanos("Fabrolia spawn-collect quiescence", 25_000L);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < count; i++) {
            if (gate.failureOrNull() != null) {
                for (int k = 0; k < count; k++) tasks[k].cancelBeforeRun();
            }
            try {
                tasks[i].join();
            } catch (Throwable encodedFailure) {
                if (gate.failureOrNull() == null) gate.publishFailure(encodedFailure);
            }
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable fatal) {
            gate.publishFailure(fatal);
        }
        Throwable failure = gate.failureOrNull();
        if (failure != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
    }

    private static void computeRange(ServerLevel level, Entity[] entities, NaturalSpawner.ChunkGetter chunkGetter,
                                     int from, int to, BatchResult out) {
        computeRange(level, entities, chunkGetter, from, to, out, null);
    }

    private static void computeRange(ServerLevel level, Entity[] entities, NaturalSpawner.ChunkGetter chunkGetter,
                                     int from, int to, BatchResult out, SpawnFailureGate gate) {
        for (int i = from; i < to; i++) {
            if (gate != null) gate.admitNext();
            Entity entry = entities[i];
            // This live observation is a multi-entry drain. Atomically cut
            // every entity/query body against the physical generation W; the
            // permit terminal prevents the next entity from entering after a
            // concurrent winner.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> computeOne(level, entry, chunkGetter, out));
        }
    }

    /** Vanilla 26.3 createState's per-entity step. */
    private static void computeOne(ServerLevel level, Entity e,
                                   NaturalSpawner.ChunkGetter chunkGetter,
                                   BatchResult out) {
        if (e == null) return;
        if (e instanceof Mob mob
                && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return;
        MobCategory cat = e.getType().getCategory();
        int ord = cat.ordinal();
        if (ord == MISC_ORDINAL) return;
        BlockPos pos = e.blockPosition();
        chunkGetter.query(ChunkPos.pack(pos), chunk -> {
            MobSpawnSettings.MobSpawnCost cost = level.environmentAttributes()
                    .getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos)
                    .getMobSpawnCost(e.getType());
            if (cost != null) out.charges.add(new ChargeEntry(pos, cost.charge()));
            if (e instanceof Mob) out.mobCaps.add(new MobCapEntry(chunk.getPos(), cat));
            out.counts[ord]++;
        });
    }

    private static final class CollectRangeTask extends RecursiveAction {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final SpawnFailureGate gate;
        private final ServerLevel level;
        private final Entity[] entities;
        private final NaturalSpawner.ChunkGetter chunkGetter;
        private final int from;
        private final int to;
        private final BatchResult out;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private CollectRangeTask(SpawnFailureGate gate, ServerLevel level, Entity[] entities,
                                 NaturalSpawner.ChunkGetter chunkGetter,
                                 int from, int to, BatchResult out) {
            this.gate = gate;
            this.level = level;
            this.entities = entities;
            this.chunkGetter = chunkGetter;
            this.from = from;
            this.to = to;
            this.out = out;
        }

        @Override
        protected void compute() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            Throwable failure = null;
            try {
                gate.admitNext();
                RegionScheduler.runSuccessBodyIfHealthy(
                        () -> computeRange(level, entities, chunkGetter, from, to, out, gate));
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
            if (failure != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
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

    private static final class PrecomputeTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final SpawnFailureGate gate;
        private final Supplier<BatchResult> body;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final CompletableFuture<BatchResult> completion = new CompletableFuture<>();
        private volatile BatchResult result;

        private PrecomputeTask(SpawnFailureGate gate, Supplier<BatchResult> body) {
            this.gate = gate;
            this.body = body;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            try {
                gate.admitNext();
                RegionScheduler.runSuccessBodyIfHealthy(() -> result = body.get());
                gate.admitNext();
            } catch (Throwable thrown) {
                gate.publishFailure(thrown);
            } finally {
                state.set(TERMINAL);
                gate.completeTerminal(completion, result);
            }
        }

        void cancelBeforeRun() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                gate.completeTerminal(completion, null);
            }
        }
    }

    private static final class SpawnFailureGate extends SpawnFanoutGate {
        private Throwable failure;

        @Override
        void runNext(Runnable body) {
            if (body == null) throw new NullPointerException("body");
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    synchronized (this) {
                        if (failure != null) {
                            ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(failure);
                        }
                    }
                    body.run();
                });
            } catch (Throwable thrown) {
                ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(publishFailure(thrown));
            }
        }

        @Override
        void admitNext() {
            Throwable observed = failureOrNull();
            if (observed != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(observed);
            try {
                RegionScheduler.throwWorkerFailureIfAny();
            } catch (Throwable fatal) {
                ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(publishFailure(fatal));
            }
            observed = failureOrNull();
            if (observed != null) ParallelSpawnHelper.<RuntimeException>fabrolia$rethrow(observed);
        }

        @Override
        synchronized Throwable failureOrNull() {
            return failure;
        }

        @Override
        synchronized Throwable publishFailure(Throwable candidate) {
            if (candidate == null) candidate = new NullPointerException("async spawn failure");
            if (failure == null) {
                failure = candidate;
            } else if (failure != candidate) {
                fabrolia$mergeFailure(failure, candidate);
            }
            failure = RegionScheduler.publishFatalAndSelectWinner(failure);
            return failure;
        }

        synchronized <T> void completeTerminal(CompletableFuture<T> completion, T result) {
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {
                        if (!completion.complete(result)) {
                            throw new IllegalStateException("async spawn precompute completed more than once");
                        }
                    });
                } catch (Throwable fatal) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(fatal);
                }
            }
            if (failure != null) completion.completeExceptionally(failure);
        }
    }

    abstract static class SpawnFanoutGate {
        abstract void runNext(Runnable body);

        abstract void admitNext();

        abstract Throwable failureOrNull();

        abstract Throwable publishFailure(Throwable candidate);
    }
}
