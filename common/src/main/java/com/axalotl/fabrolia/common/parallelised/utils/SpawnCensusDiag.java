package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * SPAWNCENSUS diagnostics (task #32 cod overpopulation): live comparison of the
 * {@link SpawnIndex} membership against section-storage ground truth, plus counters
 * for every census-time purge {@link ParallelSpawnHelper#fillBufferFromIndex} performs.
 *
 * <p>The number that matters is <b>invis</b> per category: entities that ARE present in
 * an accessible section (gameplay-visible — selectors/kills resolve through sections)
 * and WOULD be censused (non-persistent mob, not MISC, not a corpse) but are ABSENT
 * from the SpawnIndex. Those are invisible to the spawn caps: every such entity
 * permanently under-fills {@code mobCategoryCounts}, so natural spawning keeps
 * refilling the category — the runaway-population ratchet. A healthy census keeps
 * invis at 0 (transients of a tick are fine); a growing invis is the bug signature.
 *
 * <p>Read-only, best-effort under concurrency (section lists mutate while we walk —
 * per-chunk Throwable containment; the report is a diagnostic snapshot, not a barrier).
 */
public final class SpawnCensusDiag {

    private SpawnCensusDiag() {}

    private static final MobCategory[] CATS = MobCategory.values();

    /** Census purges by reason (fillBufferFromIndex it.remove() sites). */
    public static final AtomicLong PURGE_REMOVED = new AtomicLong();
    public static final AtomicLong PURGE_CORPSE = new AtomicLong();
    public static final AtomicLong PURGE_SECTIONS = new AtomicLong();
    /** How buildState resolved each census: purging inline walk vs precompute snapshot. */
    public static final AtomicLong CENSUS_INLINE = new AtomicLong();
    public static final AtomicLong CENSUS_PRE = new AtomicLong();
    /** First-failed-sighting events on the section check (near-miss rate of the
     *  purge race; with the two-strike fix these are held, not purged). */
    public static final AtomicLong SECT_STRIKE1 = new AtomicLong();
    /** Entities re-added by the ground-truth reconciliation sweep. */
    public static final AtomicLong RECONCILE_HEALED = new AtomicLong();
    /** Incremental census (task #21): fast-path builds, all-saturated skips,
     *  audit failures, and reduce cost. */
    public static final AtomicLong CENSUS_FAST = new AtomicLong();
    public static final AtomicLong CENSUS_SKIP_ALL = new AtomicLong();
    public static final AtomicLong AUDIT_ERRORS = new AtomicLong();
    public static final AtomicLong EV_ADD = new AtomicLong();
    public static final AtomicLong EV_REMOVE = new AtomicLong();
    public static final java.util.concurrent.atomic.LongAdder REDUCE_NANOS =
            new java.util.concurrent.atomic.LongAdder();
    public static final AtomicLong REDUCE_NANOS_MAX = new AtomicLong();

    public static void addReduceNanos(long nanos) {
        REDUCE_NANOS.add(nanos);
        REDUCE_NANOS_MAX.accumulateAndGet(nanos, Math::max);
    }
    /** Per-category count of section-absence purges — the suspect reason. */
    private static final AtomicLongArray PURGE_SECTIONS_BY_CAT = new AtomicLongArray(CATS.length);

    public static void onPurgeRemoved(Entity e) {
        PURGE_REMOVED.incrementAndGet();
    }

    public static void onPurgeCorpse(Entity e) {
        PURGE_CORPSE.incrementAndGet();
    }

    public static void onPurgeSections(Entity e) {
        PURGE_SECTIONS.incrementAndGet();
        PURGE_SECTIONS_BY_CAT.incrementAndGet(e.getType().getCategory().ordinal());
    }

    /** Would this entity be counted by the census (computeRange semantics)?
     *  Corpse rule mirrors ParallelSpawnHelper: dying mobs in TICKING chunks
     *  count (vanilla's ~20-tick death window); frozen corpses don't. */
    private static boolean censusable(ServerLevel level, Entity e) {
        if (e.isRemoved()) return false;
        if (e instanceof net.minecraft.world.entity.LivingEntity le && le.isDeadOrDying()) {
            var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                    .fabrolia$entityManager();
            var ticking = ((TickingChunksAccess) em).fabrolia$tickingChunks();
            if (ticking.get(e.chunkPosition().pack()) == 0) return false;
        }
        if (e instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return false;
        return e.getType().getCategory() != MobCategory.MISC;
    }

    public static String report(ServerLevel level) {
        var storage = ((TickingChunksAccess)
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager()).fabrolia$sectionStorage();

        Collection<Entity> idx = SpawnIndex.getEntities(level);
        Set<Entity> indexed = java.util.Collections.newSetFromMap(new IdentityHashMap<>(Math.max(16, idx.size() * 2)));
        indexed.addAll(idx);

        long[] truthAll = new long[CATS.length];
        long[] ledgered = new long[CATS.length];
        long[] truthCensus = new long[CATS.length];
        long[] invis = new long[CATS.length];
        long[] idxCensus = new long[CATS.length];
        List<String> samples = new ArrayList<>(8);
        long sectionsWalked = 0, sectionsFailed = 0, matched = 0;

        for (Entity e : idx) {
            if (e != null && censusable(level, e)) idxCensus[e.getType().getCategory().ordinal()]++;
        }

        var chunkKeys = storage.getAllChunksWithExistingSections();
        for (var itc = chunkKeys.iterator(); itc.hasNext(); ) {
            long chunkKey = itc.nextLong();
            try {
                for (var section : storage.getExistingSectionsInChunk(chunkKey).toList()) {
                    sectionsWalked++;
                    if (!section.getStatus().isAccessible()) continue;
                    List<Entity> snap = new ArrayList<>();
                    section.getEntities().forEach(ea -> {
                        if (ea instanceof Entity e) snap.add(e);
                    });
                    for (Entity e : snap) {
                        int ord = e.getType().getCategory().ordinal();
                        truthAll[ord]++;
                        boolean inIdx = indexed.contains(e);
                        if (inIdx) matched++;
                        if (!censusable(level, e)) continue;
                        truthCensus[ord]++;
                        if (e instanceof SpawnCensus.Ledger led && led.fabrolia$censusState() != 0) {
                            ledgered[ord]++;
                        }
                        if (!inIdx) {
                            invis[ord]++;
                            if (samples.size() < 8) {
                                samples.add(e.getType().builtInRegistryHolder().key().identifier()
                                        + "@" + e.blockPosition().toShortString());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                sectionsFailed++;
            }
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("SPAWNCENSUS idx=").append(idx.size())
          .append(" v=").append(SpawnIndex.version())
          .append(" sections=").append(sectionsWalked);
        if (sectionsFailed > 0) sb.append(" failed=").append(sectionsFailed);
        sb.append(" ghost=").append(Math.max(0, idx.size() - matched));
        sb.append(" purges[rem=").append(PURGE_REMOVED.get())
          .append(" corpse=").append(PURGE_CORPSE.get())
          .append(" sect=").append(PURGE_SECTIONS.get())
          .append(" strike1=").append(SECT_STRIKE1.get()).append(']');
        sb.append(" census[inline=").append(CENSUS_INLINE.get())
          .append(" pre=").append(CENSUS_PRE.get())
          .append(" fast=").append(CENSUS_FAST.get())
          .append(" skipAll=").append(CENSUS_SKIP_ALL.get())
          .append(" reduceAvgUs=").append(CENSUS_FAST.get() == 0 ? 0
                  : REDUCE_NANOS.sum() / Math.max(1, CENSUS_FAST.get()) / 1000)
          .append(" reduceMaxUs=").append(REDUCE_NANOS_MAX.get() / 1000)
          .append(" auditErr=").append(AUDIT_ERRORS.get())
          .append(" evAdd=").append(EV_ADD.get())
          .append(" evRem=").append(EV_REMOVE.get())
          .append(" healed=").append(RECONCILE_HEALED.get()).append(']');
        long[] incTotals;
        String incErr = null;
        try {
            incTotals = SpawnCensus.debugTotals(level);
        } catch (Throwable t) {
            incTotals = new long[CATS.length];
            incErr = t.toString();
        }
        long incDrift = 0;
        for (int i = 0; i < CATS.length; i++) {
            incDrift += Math.abs(incTotals[i] - truthCensus[i]);
        }
        sb.append(" incDrift=").append(incDrift);
        if (incErr != null) {
            sb.append(" incTotalsErr=").append(incErr);
        }
        final long[] incRef = incTotals;
        for (int i = 0; i < CATS.length; i++) {
            if (truthCensus[i] == 0 && idxCensus[i] == 0 && invis[i] == 0) continue;
            sb.append('\n').append(CATS[i].getName())
              .append(" idx=").append(idxCensus[i])
              .append(" truth=").append(truthCensus[i])
              .append(" inc=").append(incRef[i])
              .append(" led=").append(ledgered[i])
              .append(" invis=").append(invis[i]);
            long ps = PURGE_SECTIONS_BY_CAT.get(i);
            if (ps > 0) sb.append(" sectPurges=").append(ps);
        }
        if (!samples.isEmpty()) {
            sb.append("\ninvis samples: ").append(String.join(", ", samples));
        }
        try {
            sb.append("\ninc-maps: ").append(SpawnCensus.debugDump(level));
        } catch (Throwable t) {
            sb.append("\ninc-maps: dump failed ").append(t);
        }
        return sb.toString();
    }
}
