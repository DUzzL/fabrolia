package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.axalotl.fabrolia.common.utils.LocalCapBridge;
import com.axalotl.fabrolia.api.spawn.FabroliaLocalCapView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

public final class ParallelSpawnHelper {

    private static final int SPAWN_GRAIN = 256;
    private static final MobCategory[] CATEGORIES = MobCategory.values();
    private static final int CATEGORY_COUNT = CATEGORIES.length;
    private static final int MISC_ORDINAL = MobCategory.MISC.ordinal();

    private static Entity[] entityBuf = new Entity[1024];

    private static boolean fabrolia$localCapLogged = false;
    private static boolean fabrolia$reservationDeclineLogged = false;
    /** Previous tick's local-cap arena state per level — freed at the next build for that level
     *  (by then this level's spawn phase is done, barrier-ordered). Carries the calculator the
     *  handle was attached to so the handle can be DETACHED (set 0) before the native free:
     *  {@code ServerChunkCache.lastSpawnState} retains the previous tick's SpawnState (and its
     *  {@code localMobCapCalculator}) across ticks — any mod calling {@code canSpawn}/{@code
     *  addMob} on that stale calculator after the free would otherwise cross FFI with a
     *  dangling arena pointer (use-after-free). Detached, it falls back to the Java maps. */
    private record CapArena(long handle, FabroliaLocalCapView view) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<CapArena> fabrolia$deferredFree =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Native-lifetime guard (2026-07-17 audit UAF): detach the handle, then
     *  free ONLY once no thread is inside a canSpawn/addMob FFI call on it —
     *  gate-1 island spawn arms legitimately run one tick behind main with
     *  the previous cookie. Undrained after the bounded wait → deferred to
     *  the next build (never freed under a live reader). */
    private static void fabrolia$detachAndFree(LocalCapBridge.Provider p, CapArena a) {
        // Belt (2026-07-21 crash): any throw out of the view (a mixin-state anomaly on
        // an exotic calculator instance) must never kill the tick loop — detach is
        // already done below; defer the free and move on.
        try {
            a.view().fabrolia$setCapHandle(0L);
            long deadline = System.nanoTime() + 1_000_000L;
            while (a.view().fabrolia$capInFlight() > 0) {
                if (System.nanoTime() > deadline) {
                    fabrolia$deferredFree.add(a);
                    return;
                }
                Thread.onSpinWait();
            }
        } catch (Throwable t) {
            if (!fabrolia$detachAnomalyLogged) {
                fabrolia$detachAnomalyLogged = true;
                FabroliaConfig.LOGGER.warn("[Fabrolia] local-cap detach anomaly — deferring native free", t);
            }
            fabrolia$deferredFree.add(a);
            return;
        }
        try { p.free(a.handle()); } catch (Throwable ignored) {}
    }

    private static volatile boolean fabrolia$detachAnomalyLogged;
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
        if (level == null || localMobCap == null) {
            return false;
        }
        LocalCapBridge.Provider p = LocalCapBridge.provider();
        if (p == null) {
            // Provider absent (no Oxidizium) or disabled after a seam throw: the native
            // handles are unfreeable without it — drop the map entries so at least the
            // ServerLevel keys aren't retained for the rest of the session.
            if (!fabrolia$lastArena.isEmpty()) fabrolia$lastArena.clear();
            return false;
        }
        CapArena prev = fabrolia$lastArena.remove(level);
        if (prev != null) {
            fabrolia$detachAndFree(p, prev);
        }
        // Retry arenas whose in-flight FFI callers hadn't drained at free time.
        CapArena d;
        while ((d = fabrolia$deferredFree.poll()) != null) {
            fabrolia$detachAndFree(p, d);
        }
        // Pack reservation (task #32 endgame) needs an atomic fused
        // check+reserve the arena ABI must provide; a reservation-blind
        // provider would reopen the parallel local-cap window the reservation
        // exists to close. Decline the arena and keep the Java path (full
        // replay seed below — reservation-capable by construction) until the
        // provider ships supportsPackReservation. Runtime lever:
        // /fabrolia regions localcapreserve off restores the arena as-is.
        if (FabroliaConfig.fabroliaSpawnLocalCapReservation && !p.supportsPackReservation()) {
            if (!fabrolia$reservationDeclineLogged) {
                fabrolia$reservationDeclineLogged = true;
                FabroliaConfig.LOGGER.info("[Fabrolia] Local mob-cap arena DECLINED — provider lacks "
                        + "pack-reservation support; using the Java local-cap path (vanilla-equivalent). "
                        + "Update the provider or /fabrolia regions localcapreserve off to re-enable the arena.");
            }
            return false;
        }
        int n = initialMobs.size();
        long[] chunks = fabrolia$mobChunkBuf;
        int[] cats = fabrolia$mobCatBuf;
        if (chunks.length < n) {
            int cap = Math.max(n, chunks.length * 2);
            fabrolia$mobChunkBuf = chunks = new long[cap];
            fabrolia$mobCatBuf = cats = new int[cap];
        }
        for (int i = 0; i < n; i++) {
            MobCapEntry e = initialMobs.get(i);
            chunks[i] = e.chunkPos().pack();
            cats[i] = e.category().ordinal();
        }
        long h;
        try {
            h = p.buildArena(level, chunks, cats, n);
        } catch (Throwable t) {
            LocalCapBridge.disable(t);
            return false;
        }
        if (h == 0L) {
            return false;
        }
        FabroliaLocalCapView view = (FabroliaLocalCapView) (Object) localMobCap;
        view.fabrolia$setCapHandle(h);
        fabrolia$lastArena.put(level, new CapArena(h, view));
        // SHADOW-SEED the Java maps off-main (task #32 cod runaway, root cause 2):
        // this cookie outlives its arena — the handle is detached at the NEXT
        // tick's build, while async-island gate-1 spawn arms legitimately consume
        // the cookie one-or-more ticks late. Post-detach canSpawn/addMob fall
        // back to the Java maps; empty maps mean vanilla canSpawn approves every
        // near player (no counts entry ⇒ "under cap") — the per-player LOCAL cap
        // silently vanished for island-armed chunks and the whole level's
        // per-category budget concentrated around single players. The shadow
        // replay costs ~µs on the pool (the 4.1%-of-main replay cost this arena
        // exists to avoid stays off main); the SEED_BYPASS flag keeps it out of
        // the native routing (the arena already holds these counts). Intra-tick
        // addMob traffic still routes native-only, so a mid-life detach sees
        // seed-exact counts (missing at most the current tick's own spawns —
        // bounded, self-correcting at the next build).
        long[] seedChunks = java.util.Arrays.copyOf(chunks, n);
        int[] seedCats = java.util.Arrays.copyOf(cats, n);
        Runnable shadowSeed = () -> {
            try {
                view.fabrolia$seedJavaShadow(seedChunks, seedCats, seedChunks.length);
            } catch (Throwable t) {
                FabroliaConfig.LOGGER.warn("[Fabrolia] local-cap Java shadow seed failed", t);
            }
        };
        var seedExec = ParallelProcessor.executor;
        if (seedExec != null && !seedExec.isShutdown()) {
            try {
                seedExec.execute(shadowSeed);
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                shadowSeed.run();
            }
        } else {
            shadowSeed.run();
        }
        if (!fabrolia$localCapLogged) {
            fabrolia$localCapLogged = true;
            FabroliaConfig.LOGGER.info("[Fabrolia] Local mob-cap arena ACTIVE (provider) — handle built, {} initial mobs seeded natively (+Java shadow)", n);
        }
        return true;
    }

    /** Free and drop the level's arena handle (level unload / shutdown). Safe ordering: unload
     *  fires on the server thread between ticks, when no spawn worker can hold the handle
     *  (the spawn future is pumped to completion within the level tick that built it). */
    public static void fabrolia$freeLocalCapHandle(ServerLevel level) {
        CapArena prev = fabrolia$lastArena.remove(level);
        if (prev != null) {
            prev.view().fabrolia$setCapHandle(0L);
            LocalCapBridge.Provider p = LocalCapBridge.provider();
            if (p != null) {
                try { p.free(prev.handle()); } catch (Throwable ignored) {}
            }
        }
    }

    public static final class BatchResult {
        final int[] counts = new int[CATEGORY_COUNT];
        final List<ChargeEntry> charges;
        final List<MobCapEntry> mobCaps;
        /** Per-batch biome cache keyed on packed (qx, qy, qz). Dense farms reuse the same QuartPos
         *  for hundreds of entities; `chunk.getNoiseBiome` lookup is cheap but not free, and
         *  this skips it for every cache hit. Per-batch (not shared) — no concurrency overhead. */
        final Long2ObjectOpenHashMap<Biome> biomeCache;

        BatchResult(int sizeHint) {
            this.charges = new ArrayList<>(Math.max(16, sizeHint >> 2));
            this.mobCaps = new ArrayList<>(Math.max(16, sizeHint >> 1));
            this.biomeCache = new Long2ObjectOpenHashMap<>(Math.max(8, sizeHint >> 4));
        }

        void absorb(BatchResult other) {
            for (int i = 0; i < CATEGORY_COUNT; i++) this.counts[i] += other.counts[i];
            this.charges.addAll(other.charges);
            this.mobCaps.addAll(other.mobCaps);
        }
    }

    public record ChargeEntry(BlockPos pos, double charge) {}
    public record MobCapEntry(ChunkPos chunkPos, MobCategory category) {}

    /** Tick-head precompute slot: heavy collect() built on the pool while main runs
     *  distance updates / the servercore list build. Valid only if SpawnIndex.version
     *  is unchanged at consume time (no entity membership change since submit) —
     *  positions are stable in that window (post-packets, pre-entity-phase), so the
     *  result is bit-identical to computing at the createState point. */
    private record Pre(long version, java.util.concurrent.CompletableFuture<BatchResult> future) {}

    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, Pre> PRECOMPUTE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void precompute(ServerLevel level, NaturalSpawner.ChunkGetter getter) {
        var exec = ParallelProcessor.executor;
        if (getter == null || exec == null || exec.isShutdown()) {
            return;
        }
        Collection<Entity> idx = SpawnIndex.getEntities(level);
        if (idx.isEmpty()) {
            return;
        }
        long v = SpawnIndex.version();
        try {
            PRECOMPUTE.put(level, new Pre(v, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                // local buffer — the static entityBuf belongs to main's inline path
                Entity[] arr = new Entity[idx.size() + 64];
                int n = 0;
                for (Entity e : idx) {
                    if (e == null || e.isRemoved() || fabrolia$deadCorpse(level, e)
                            || !fabrolia$inSectionsTolerant(level, e)) continue;
                    if (n >= arr.length) {
                        Entity[] grown = new Entity[arr.length * 2];
                        System.arraycopy(arr, 0, grown, 0, n);
                        arr = grown;
                    }
                    arr[n++] = e;
                }
                return collect(arr, n, getter);
            }, exec)));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            PRECOMPUTE.remove(level);
        }
    }

    /** The serial tail shared by both paths: potential charges + per-chunk mob caps. */
    private static NaturalSpawner.SpawnState finishState(ServerLevel level,
                                                         int spawnableChunkCount,
                                                         BatchResult merged,
                                                         LocalMobCapCalculator localMobCap) {
        PotentialCalculator potential = new PotentialCalculator();
        for (int i = 0, m = merged.charges.size(); i < m; i++) {
            ChargeEntry c = merged.charges.get(i);
            potential.addCharge(c.pos(), c.charge());
        }
        // Arena path: the provider seeds the local counts from merged.mobCaps in ONE native
        // pass during build (bit-exact addMob equivalent — same near-player predicate). The
        // Java replay below runs ONLY when the arena declined (no provider / disabled /
        // build failure) — running both would double-count and halve the local cap.
        boolean arenaSeeded = fabrolia$buildLocalCapArena(level, localMobCap, merged.mobCaps);
        if (!arenaSeeded) {
            for (int i = 0, m = merged.mobCaps.size(); i < m; i++) {
                MobCapEntry m2 = merged.mobCaps.get(i);
                localMobCap.addMob(m2.chunkPos(), m2.category());
            }
        }
        Object2IntOpenHashMap<MobCategory> countsMap = new Object2IntOpenHashMap<>(CATEGORY_COUNT);
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            int c = merged.counts[i];
            if (c != 0) countsMap.put(CATEGORIES[i], c);
        }
        return new NaturalSpawner.SpawnState(spawnableChunkCount, countsMap, potential, localMobCap);
    }

    /** Full per-level teardown (level unload, both loaders): precompute slot,
     *  spawn index, and the native arena — each retained the ServerLevel (and
     *  the arena its off-heap allocation) forever with dynamic dimensions. */
    public static void onLevelUnload(ServerLevel level) {
        PRECOMPUTE.remove(level);
        SpawnCensus.clearLevel(level);
        com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex.clearLevel(level);
        com.axalotl.fabrolia.common.parallelised.utils.HardColliderIndex.onLevelUnload(level);
        com.axalotl.fabrolia.common.parallelised.utils.PlayerChunkIndex.onLevelUnload(level);
        fabrolia$freeLocalCapHandle(level);
        // Audit 2026-07-26: two more per-level registries retained the unloaded
        // ServerLevel for the life of the process. ChunkDelivery.dropLevelCache
        // existed for exactly this and had NO callers — its cache carries a
        // 96 MB byte budget of chunk payloads plus the level key. The portal
        // registries hold the level inside their value records.
        com.axalotl.fabrolia.common.regions.ChunkDelivery.dropLevelCache(level);
        com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager.onLevelUnload(level);
    }

    public static NaturalSpawner.SpawnState buildState(int spawnableChunkCount, Iterable<Entity> entities, NaturalSpawner.ChunkGetter chunkGetter, LocalMobCapCalculator localMobCap) {
        // Prefer the incremental SpawnIndex when a level is currently surfaced — bypasses
        // the c2me-notickvd-filtered section walk (4.14% of server thread in R5FaKJnHJg).
        // chunkGetter.query inside computeRange still filters out non-ticking chunks at
        // worker time, so the extra entries we feed through are O(hashmap-lookup) each —
        // measured against the iteration overhead we skip, net is ~3-4% server thread saved.
        ServerLevel surfacedLevel = FabroliaSpawnContext.currentLevel;

        // INCREMENTAL CENSUS (task #21): O(occupied chunks × categories) reduce
        // over event-maintained counts — no entity traversal, no pool submission,
        // no main-thread wait. The scan below remains as the config-gated legacy path.
        if (surfacedLevel != null && FabroliaConfig.fabroliaIncrementalSpawnCensus) {
            return fabrolia$buildStateIncremental(surfacedLevel, spawnableChunkCount, chunkGetter, localMobCap);
        }

        // The LOCAL-cap arena (provider, e.g. Oxidizium) is built inside finishState — it
        // needs the MERGED initial-mob list to seed the counts in one native pass. All
        // canSpawn/addMob traffic starts after finishState attaches the handle.

        // Tick-head precompute: bit-identical when the membership version held.
        if (surfacedLevel != null) {
            Pre pre = PRECOMPUTE.remove(surfacedLevel);
            // Every 256 ticks force the inline path: it PURGES stale index
            // entries (removed / section-absent) that the precompute only
            // filters — without this cadence the set's strong refs grow for
            // the whole session under region mode (precompute serves ~every
            // tick there).
            if (pre != null && (surfacedLevel.getGameTime() & 255) != 0
                    && pre.version() == SpawnIndex.version()) {
                ParallelProcessor.pumpUntilDone(pre.future());
                BatchResult merged = pre.future().getNow(null);
                // RE-VALIDATE after the pump (2026-07-17 audit): pumpUntilDone
                // deliberately runs island work + main-sync entity ticks on
                // this thread while waiting — the pump itself can mutate the
                // index. A snapshot invalidated by our own pump falls through
                // to the inline census below.
                if (merged != null && pre.version() == SpawnIndex.version()) {
                    SpawnCensusDiag.CENSUS_PRE.incrementAndGet();
                    return finishState(surfacedLevel, spawnableChunkCount, merged, localMobCap);
                }
            }
        }

        // Ground-truth resync at the forced-inline tick (task #32): re-add any
        // accessible live entity the index lost (missed attach seams, race
        // evictions) BEFORE counting, so the census below sees the healed set.
        if (surfacedLevel != null && (surfacedLevel.getGameTime() & 255) == 0) {
            try {
                var storage = ((TickingChunksAccess)
                        ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) surfacedLevel)
                                .fabrolia$entityManager()).fabrolia$sectionStorage();
                int healed = SpawnIndex.reconcile(surfacedLevel, storage);
                if (healed > 0) {
                    SpawnCensusDiag.RECONCILE_HEALED.addAndGet(healed);
                }
            } catch (Throwable ignored) {
            }
        }

        Collection<Entity> indexEntities = surfacedLevel != null
                ? com.axalotl.fabrolia.common.parallelised.utils.SpawnIndex.getEntities(surfacedLevel)
                : null;

        int n = 0;
        try {
            if (indexEntities != null && !indexEntities.isEmpty()) {
                SpawnCensusDiag.CENSUS_INLINE.incrementAndGet();
                n = fillBufferFromIndex(surfacedLevel, indexEntities);
            } else {
                n = fillBuffer(entities);
            }
            BatchResult merged = collect(entityBuf, n, chunkGetter);
            // (a parallel playersNearChunk pre-warm was tried and reverted 2026-06-12 —
            // dispatch overhead exceeded the addMob saving; the arena seed made it moot)
            return finishState(surfacedLevel, spawnableChunkCount, merged, localMobCap);
        } finally {
            // encloses the FILL too — a fill-time throw must not strand up to
            // ~13k strong Entity refs in the static buffer (crash-day leak).
            Entity[] b = entityBuf;
            for (int i = 0, lim = Math.min(n, b.length); i < lim; i++) b[i] = null;
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
        if ((level.getGameTime() & 255) == 0) {
            // ground-truth audit: two-strike purge + reconcile + zero-vector
            // sweep, OFF-MAIN (the inline reconcile this replaces was a main
            // section walk). Fire-and-forget — corrections are per-entity
            // ledger updates, safe against the concurrent census.
            var exec = ParallelProcessor.executor;
            if (exec != null && !exec.isShutdown()) {
                try {
                    exec.execute(() -> SpawnCensus.audit(level));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                }
            }
        }
        SpawnCensus.Reduced red = SpawnCensus.reduce(level, chunkGetter);
        NaturalSpawner.SpawnState probe = new NaturalSpawner.SpawnState(
                spawnableChunkCount, red.counts(), new PotentialCalculator(), localMobCap);
        java.util.List<MobCategory> spawnable =
                NaturalSpawner.getFilteredSpawningCategories(probe, true, true);
        if (spawnable.isEmpty()) {
            fabrolia$seedJavaCountWeighted(localMobCap, red.mobSeed());
            SpawnCensusDiag.CENSUS_FAST.incrementAndGet();
            SpawnCensusDiag.CENSUS_SKIP_ALL.incrementAndGet();
            SpawnCensusDiag.addReduceNanos(System.nanoTime() - t0);
            return probe;
        }
        // Potential charges: only cost-bearing types can contribute; evaluate
        // their exact position/biome now (vanilla-identical), tiny N.
        PotentialCalculator potential = new PotentialCalculator();
        for (Entity e : SpawnCensus.census(level).costBearing) {
            if (e == null || e.isRemoved()) continue;
            if (e instanceof Mob mob
                    && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) continue;
            if (e.getType().getCategory() == MobCategory.MISC) continue;
            if (fabrolia$deadCorpse(level, e)) continue;
            BlockPos pos = e.blockPosition();
            chunkGetter.query(ChunkPos.pack(pos), chunk -> {
                MobSpawnSettings.MobSpawnCost cost = chunk
                        .getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                                QuartPos.fromBlock(pos.getZ()))
                        .value().getMobSettings().getMobSpawnCost(e.getType());
                if (cost != null) potential.addCharge(pos, cost.charge());
            });
        }
        // Local caps: expand the per-chunk vectors into the arena's per-mob
        // entry ABI (bit-equivalent seed, no Rust changes); Java fallback and
        // shadow seed stay count-weighted.
        java.util.ArrayList<MobCapEntry> expanded = new java.util.ArrayList<>(Math.max(16, red.totalMobs()));
        for (var it = red.mobSeed().long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            ChunkPos cp = ChunkPos.unpack(entry.getLongKey());
            int[] mobs = entry.getValue();
            for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
                for (int i = 0; i < mobs[cat]; i++) {
                    expanded.add(new MobCapEntry(cp, CATEGORIES[cat]));
                }
            }
        }
        boolean arenaSeeded = fabrolia$buildLocalCapArena(level, localMobCap, expanded);
        if (!arenaSeeded) {
            fabrolia$seedJavaCountWeighted(localMobCap, red.mobSeed());
        }
        SpawnCensusDiag.CENSUS_FAST.incrementAndGet();
        SpawnCensusDiag.addReduceNanos(System.nanoTime() - t0);
        return new NaturalSpawner.SpawnState(spawnableChunkCount, red.counts(), potential, localMobCap);
    }

    private static void fabrolia$seedJavaCountWeighted(LocalMobCapCalculator calc,
            Long2ObjectOpenHashMap<int[]> mobSeed) {
        FabroliaLocalCapView view = (FabroliaLocalCapView) (Object) calc;
        for (var it = mobSeed.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            int[] mobs = entry.getValue();
            for (int cat = 0; cat < CATEGORY_COUNT; cat++) {
                if (mobs[cat] > 0) {
                    view.fabrolia$addMobs(entry.getLongKey(), cat, mobs[cat]);
                }
            }
        }
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
    /** Dead-but-unremoved mobs (health 0, tickDeath pending) never leave a
     *  chunk that stopped entity-ticking — vanilla's removal needs ~20 ticks
     *  of tickDeath, so an admin-kill into a lazy mob switch leaves frozen
     *  corpses that hold the cap until someone walks close (a vanilla edge
     *  artifact, invisible in practice because looking = ticking). Vanilla
     *  steady-state census semantics count a corpse for at most 20 transient
     *  ticks; skipping them entirely is observably identical and makes cap
     *  release independent of the owning chunk ever ticking again. */
    /** REFINED 2026-07-21 (refill-cadence parity): vanilla's census counts a
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

    /** RACE-TOLERANT membership check (task #32 cod runaway): the raw check
     *  races entity section moves on island workers — the mover's position is
     *  updated BEFORE its section transfer lands (lookup hits the new, not-yet-
     *  populated section), and the per-section entity list is a plain ArrayList
     *  whose concurrent mutation the identity scan can misread (silent misses
     *  or CME/AIOOBE-class throws — no happens-before on any of it). A false
     *  negative here is catastrophic downstream (permanent census invisibility
     *  → cap under-fill → unbounded spawning), so: retry once after the mover's
     *  µs-scale settle window, and treat ANY throw as present — uncertainty
     *  must never purge; the next census re-checks a genuinely-gone entry. */
    static boolean fabrolia$inSectionsTolerant(ServerLevel level, Entity e) {
        try {
            if (fabrolia$inSections(level, e)) return true;
            Thread.onSpinWait();
            return fabrolia$inSections(level, e);
        } catch (Throwable t) {
            return true;
        }
    }

    private static int fillBufferFromIndex(ServerLevel level, Collection<Entity> entities) {
        // size() of the LIVE concurrent set is a capacity HINT, never a bound
        // (2026-07-17 production AIOOBE: buffer sized exactly to a stale
        // size(), weakly-consistent iteration observed a concurrent island
        // arrival, idx ran past the array). Growth is handled in-loop; the
        // slack absorbs the common few-arrivals case without reallocating.
        int size = entities.size();
        Entity[] buf = entityBuf;
        if (buf.length < size + 64) {
            buf = new Entity[Math.max(size + 64, buf.length * 2)];
            entityBuf = buf;
        }
        int idx = 0;
        boolean purged = false;
        for (java.util.Iterator<Entity> it = entities.iterator(); it.hasNext(); ) {
            Entity e = it.next();
            if (e == null) continue;
            int purgeReason = e.isRemoved() ? 1 : fabrolia$deadCorpse(level, e) ? 2
                    : !fabrolia$inSectionsTolerant(level, e) ? 3 : 0;
            if (purgeReason == 3 && !SpawnIndex.strikeForPurge(level, e)) {
                SpawnCensusDiag.SECT_STRIKE1.incrementAndGet();
                // FIRST failed sighting: keep the entry AND count the entity this
                // census. A genuinely-gone entity contributes nothing anyway
                // (chunkGetter.query no-ops for non-ticking chunks) and purges on
                // the next census; a live mover misread by the racy check must
                // never be dropped — dropped entries have no re-entry seam while
                // continuously visible, and each invisible mob permanently
                // under-fills the caps (task #32 cod runaway ratchet).
                purgeReason = 0;
            } else if (purgeReason == 0) {
                SpawnIndex.clearStrike(level, e);
            }
            if (purgeReason != 0) {
                // Self-healing census, AUTHORITATIVE (2026-07-18 mob-switch
                // release): eligibility = being the level lookup's CURRENT
                // object for this id — exactly vanilla's census source
                // (byId.values() iterates those objects and no others). The
                // c2me chunk system unloads entities WITHOUT firing the
                // per-entity removal seams we mirror (objects dropped with
                // isRemoved still false), so killed/unloaded switch mobs
                // stayed indexed and held the mob cap forever. The identity
                // check is O(1), runs at census time on whatever thread reads
                // the index, and self-heals every leak class — kill, discard,
                // unload, dimension transfer, island retirement — without
                // depending on any island ticking or any unload path firing.
                it.remove();
                purged = true;
                SpawnIndex.clearStrike(level, e);
                switch (purgeReason) {
                    case 1 -> SpawnCensusDiag.onPurgeRemoved(e);
                    case 2 -> SpawnCensusDiag.onPurgeCorpse(e);
                    default -> SpawnCensusDiag.onPurgeSections(e);
                }
                continue;
            }
            if (idx == buf.length) {
                buf = java.util.Arrays.copyOf(buf, buf.length + (buf.length >> 1) + 64);
                entityBuf = buf;
            }
            buf[idx++] = e;
        }
        if (purged) SpawnIndex.bumpVersion();
        return idx;
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
        Entity[] buf = entityBuf;
        if (entities instanceof java.util.Collection<?> c) {
            int hint = c.size() + 64;
            if (buf.length < hint) {
                buf = new Entity[Math.max(hint, buf.length * 2)];
                entityBuf = buf;
            }
        }
        int idx = 0;
        for (Entity e : entities) {
            if (e == null || e.isRemoved()) continue;
            if (idx == buf.length) {
                buf = java.util.Arrays.copyOf(buf, buf.length + (buf.length >> 1) + 64);
                entityBuf = buf;
            }
            buf[idx++] = e;
        }
        return idx;
    }

    private static BatchResult collect(Entity[] entities, int n, NaturalSpawner.ChunkGetter chunkGetter) {
        if (n == 0) return new BatchResult(0);
        if (n <= SPAWN_GRAIN || !(ParallelProcessor.executor instanceof ForkJoinPool fjp)) {
            BatchResult r = new BatchResult(n);
            computeRange(entities, chunkGetter, 0, n, r);
            return r;
        }
        int pool = Math.max(1, ParallelProcessor.getPoolSize());
        int batchSize = Math.max(64, (n + pool - 1) / pool);
        int batchCount = (n + batchSize - 1) / batchSize;
        BatchResult[] parts = new BatchResult[batchCount];
        ForkJoinTask<?>[] tasks = new ForkJoinTask[batchCount];
        for (int b = 0, i = 0; i < n; i += batchSize, b++) {
            int from = i;
            int to = Math.min(i + batchSize, n);
            parts[b] = new BatchResult(to - from);
            BatchResult out = parts[b];
            tasks[b] = fjp.submit(() -> computeRange(entities, chunkGetter, from, to, out));
        }
        for (ForkJoinTask<?> t : tasks) t.join();
        BatchResult merged = new BatchResult(n);
        for (BatchResult p : parts) merged.absorb(p);
        return merged;
    }

    private static void computeRange(Entity[] entities, NaturalSpawner.ChunkGetter chunkGetter, int from, int to, BatchResult out) {
        for (int i = from; i < to; i++) {
            Entity e = entities[i];
            if (e == null) continue;
            if (e instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) continue;
            MobCategory cat = e.getType().getCategory();
            int ord = cat.ordinal();
            if (ord == MISC_ORDINAL) continue;
            BlockPos pos = e.blockPosition();
            chunkGetter.query(ChunkPos.pack(pos), chunk -> {
                Biome biome = cachedRoughBiome(pos, chunk, out);
                MobSpawnSettings.MobSpawnCost cost = biome.getMobSettings().getMobSpawnCost(e.getType());
                if (cost != null) out.charges.add(new ChargeEntry(pos, cost.charge()));
                if (e instanceof Mob) out.mobCaps.add(new MobCapEntry(chunk.getPos(), cat));
                out.counts[ord]++;
            });
        }
    }

    private static Biome cachedRoughBiome(BlockPos pos, ChunkAccess chunk, BatchResult out) {
        int qx = QuartPos.fromBlock(pos.getX());
        int qy = QuartPos.fromBlock(pos.getY());
        int qz = QuartPos.fromBlock(pos.getZ());
        // 24/24/12 packing — covers vanilla world bounds (±16M xz, ±2048 y) without collisions.
        long key = ((long)(qx & 0xFFFFFF))
                 | ((long)(qz & 0xFFFFFF) << 24)
                 | ((long)(qy & 0xFFF) << 48);
        Biome cached = out.biomeCache.get(key);
        if (cached != null) return cached;
        Biome biome = chunk.getNoiseBiome(qx, qy, qz).value();
        out.biomeCache.put(key, biome);
        return biome;
    }
}
