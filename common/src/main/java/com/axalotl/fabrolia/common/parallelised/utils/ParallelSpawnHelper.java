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

public final class ParallelSpawnHelper {

    private static final MobCategory[] CATEGORIES = MobCategory.values();
    private static final int CATEGORY_COUNT = CATEGORIES.length;

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
     *  build this tick's LOCAL-cap arena — SEEDED with the census' per-chunk mob counts in one
     *  native pass — and attach its handle to {@code localMobCap}, so canSpawn/addMob route to the
     *  provider instead of the concurrent maps. The GLOBAL cap is untouched (stays vanilla
     *  Java — ServerCore multiplier / phantom wrap). The provider owns all Rust marshalling;
     *  the native ABI receives one primitive entry per mob, in chunk-table/category order.
     *
     *  @return true if the arena is active AND consumed the initial mobs — the caller must
     *          then SKIP its Java replay (counting them twice would halve the local cap). */
    private static boolean fabrolia$buildLocalCapArena(
            ServerLevel level, LocalMobCapCalculator localMobCap,
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
            int n = weightedCount;
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

    public record ChargeEntry(BlockPos pos, double charge) {}
    /** Full per-level teardown (level unload, both loaders): census ledger,
     *  spawn index, and the native arena — each retained the ServerLevel (and
     *  the arena its off-heap allocation) forever with dynamic dimensions. */
    public static void onLevelUnload(ServerLevel level) {
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
    public static NaturalSpawner.SpawnState buildState(int spawnableChunkCount, ServerLevel level,
            NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCap) {
        RegionScheduler.throwWorkerFailureIfAny();
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
                () -> fabrolia$buildLocalCapArena(
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
                && ParallelProcessor.executor != null;
    }

    /** Main-only ledger audit for the island lanes when Main's own census
     *  uses vanilla createState: the islands still census from the ledger,
     *  so its self-heal must still run on Main's single-entry cadence. */
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

    /** First-wins failure gate of one census fan-out (SpawnCensus.reduce). */
    static final class SpawnFanoutGate {
        private Throwable failure;

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

        synchronized Throwable failureOrNull() {
            return failure;
        }

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

    }

}
