package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@link ServerLevel} set of <em>visible</em> entities — those in sections whose
 * chunk visibility is {@code TRACKED} or {@code TICKING} ({@code Visibility.isAccessible()}).
 * Mirrors vanilla {@code PersistentEntitySectionManager.visibleEntityStorage} exactly: the
 * same {@code startTracking}/{@code stopTracking} seams that gate vanilla's
 * {@code EntityLookup.byId} feed this index too.
 *
 * <p><b>Why this matters for spawn caps:</b> vanilla {@code level.getAllEntities()}
 * — the entity source vanilla feeds to {@code NaturalSpawner.createState} — is
 * {@code EntityLookup.byId.values()}, i.e. only entities in <em>accessible</em>
 * sections. Earlier {@code SpawnIndex} versions tracked every {@code addEntity}
 * return regardless of section visibility, so mobs in chunks held loaded by tickets
 * but with no players in tracking range (stasis-pearl chunkloaders, c2me
 * non-ticking-vd, force-loaded farms, etc.) accumulated forever in the index and
 * inflated the {@code mobCategoryCounts} that gates natural-spawn. The leak grew
 * with uptime and reset only on server restart — matching the "mob spawn rate
 * deteriorates over hours" complaint.
 *
 * <p>Hook seams (in {@code PersistentEntitySectionManager}):
 * <ul>
 *   <li>{@code startTracking(entity)} — called from {@code addEntity} when initial
 *       section is accessible, from {@code updateChunkStatus} when a chunk gains
 *       accessibility, and from {@code Callback.updateStatus} on section transitions
 *       to a more visible state.</li>
 *   <li>{@code stopTracking(entity)} — called from {@code updateChunkStatus} when
 *       a chunk loses accessibility, from {@code Callback.onRemove} when an entity
 *       dies in an accessible section, and from {@code Callback.updateStatus} on
 *       section transitions to a less visible state.</li>
 * </ul>
 * Both vanilla call sites guard with {@code Visibility.isAccessible()} (with
 * {@code isAlwaysTicking()} forcing {@code TICKING} effective status — players,
 * persisted mobs treated by override) — see PESM:90, 158-170, 391-420, 423-444.
 *
 * <p>Cross-dimension teleport: the old entity is {@code setRemoved(CHANGED_DIMENSION)}
 * before the new entity is added in the target level, so the old PESM's
 * {@code stopTracking} fires before the new PESM's {@code startTracking} — clean
 * even with the level-keyed set.
 */
public final class SpawnIndex {

    private SpawnIndex() {}

    private static final Map<ServerLevel, Set<Entity>> VISIBLE_PER_LEVEL = new ConcurrentHashMap<>();

    /** Bumped on every visibility-set membership change — the createState
     *  precompute uses it as its validity guard: snapshot built at tick head
     *  is only used if no membership change happened before the real
     *  createState point. */
    private static final java.util.concurrent.atomic.AtomicLong VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    public static long version() {
        return VERSION.get();
    }

    /** Entity entered an accessible section in {@code level}. Mirrors
     *  {@code PersistentEntitySectionManager.startTracking}. */
    public static void onTrackingStart(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        // Bump BEFORE the mutation (2026-07-17 audit): the precompute guard
        // reads version-then-snapshot; mutate-then-bump let a committed add be
        // observed by the snapshot while the bump landed after the check —
        // the guard falsely passed. Bump-first can only invalidate spuriously.
        VERSION.incrementAndGet();
        set.add(entity);
        SpawnCensus.update(level, entity, true);
    }

    /** Entity left an accessible section in {@code level} (chunk hidden, entity died,
     *  or section transition). Mirrors {@code PersistentEntitySectionManager.stopTracking}. */
    public static void onTrackingEnd(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
        if (set != null) {
            VERSION.incrementAndGet();
            set.remove(entity);
        }
        SpawnCensus.update(level, entity, false);
    }

    /** Census-side purge notification (removed-entry self-heal in
     *  ParallelSpawnHelper.fillBufferFromIndex bypasses onTrackingEnd). */
    public static void bumpVersion() {
        VERSION.incrementAndGet();
    }

    /** Two-strike purge ledger (task #32 cod runaway): entities whose census
     *  section-membership check failed ONCE. The check races entity section
     *  moves on island workers (position updates before the section add lands;
     *  the per-section entity list is a plain ArrayList mutated mid-scan), so a
     *  single failure must never purge — a falsely-purged LIVE entity has no
     *  seam that re-adds it while it stays continuously visible, and every
     *  invisible mob permanently under-fills the spawn caps. Real leaks
     *  (c2me unloads, frozen corpses) are STABLY absent and still purge on the
     *  second consecutive sighting, one census later. */
    private static final Map<ServerLevel, Set<Entity>> PENDING_PURGE = new ConcurrentHashMap<>();

    /** @return true when this is the SECOND consecutive failed sighting — purge now. */
    public static boolean strikeForPurge(ServerLevel level, Entity entity) {
        Set<Entity> s = PENDING_PURGE.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        if (s.add(entity)) {
            return false;
        }
        s.remove(entity);
        return true;
    }

    /** Entity passed the membership check — clear any pending strike. */
    public static void clearStrike(ServerLevel level, Entity entity) {
        Set<Entity> s = PENDING_PURGE.get(level);
        if (s != null && !s.isEmpty()) {
            s.remove(entity);
        }
    }

    /** Census self-heal (task #32): a live entity that crossed into an
     *  accessible section but is missing from the index (false purge, or any
     *  other membership hole) re-enters here. Idempotent; version bumps only
     *  on an actual heal so the precompute guard is undisturbed on the
     *  overwhelmingly-common already-present path. */
    public static void ensurePresent(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        if (set.contains(entity)) {
            return;
        }
        VERSION.incrementAndGet();
        set.add(entity);
        SpawnCensus.update(level, entity, true);
    }

    /** GROUND-TRUTH RECONCILIATION (task #32): the index is a derived cache of
     *  "entities in accessible sections"; every missed add event (whatever the
     *  cause — a mod's bespoke attach path, a future seam change) diverges it
     *  FOREVER because nothing re-fires for a continuously-visible entity. This
     *  sweep walks section storage and re-adds any accessible live entity the
     *  index lost, bounding ANY membership hole to one sweep interval. Runs on
     *  main at the forced-inline census tick (every 256 gt — the same cadence
     *  as the purge hygiene it mirrors); cost is one section walk, amortized
     *  ~0.02% of a tick. Concurrent section-list mutation is tolerated per
     *  section (racy misses self-correct next sweep). */
    public static int reconcile(ServerLevel level,
            net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess> storage) {
        int healed = 0;
        var chunkKeys = storage.getAllChunksWithExistingSections();
        for (var itc = chunkKeys.iterator(); itc.hasNext(); ) {
            long chunkKey = itc.nextLong();
            try {
                for (var section : storage.getExistingSectionsInChunk(chunkKey).toList()) {
                    boolean accessible = section.getStatus().isAccessible();
                    final int[] add = {0};
                    section.getEntities().forEach(ea -> {
                        if (!(ea instanceof Entity e) || e.isRemoved()) return;
                        if (!accessible && !ea.isAlwaysTicking()) return;
                        Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
                        if (set == null || !set.contains(e)) {
                            ensurePresent(level, e);
                            add[0]++;
                        }
                    });
                    healed += add[0];
                }
            } catch (Throwable ignored) {
                // racy list scan — self-corrects next sweep
            }
        }
        return healed;
    }

    /**
     * @return live set of currently visible entities for {@code level}, or an empty
     *         collection if the level is unknown. Caller MUST treat the iteration as
     *         weakly consistent (CHM newKeySet semantics) — entities added during
     *         iteration may or may not be observed, removals during iteration won't
     *         throw CME.
     */
    public static Collection<Entity> getEntities(ServerLevel level) {
        Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
        return set != null ? set : Collections.emptySet();
    }

    /**
     * Clear all entries for a level. Called when the level unloads — the per-entity
     * stopTracking callbacks fire during chunk unloads, but a level-shutdown wipe
     * guards against any straggler entries (e.g. always-ticking entities whose
     * stopTracking is bypassed by {@code getEffectiveStatus}).
     */
    public static void clearLevel(ServerLevel level) {
        VISIBLE_PER_LEVEL.remove(level);
        PENDING_PURGE.remove(level);
    }
}
