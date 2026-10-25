package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
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

    /** Membership and the census ledger's {@code tracked} flag change as ONE
     *  per-entity transaction under the entity's census lock (the lock
     *  {@link SpawnCensus#update} already takes; monitors are reentrant). The
     *  2026-10-24 review reproduced the split version: an audit purge removed
     *  the entity, a worker's section-add re-inserted it with tracked=true,
     *  then the purge's update(false) cleared the flag — a live index member
     *  without a census contribution, which no later add/move repairs. */
    private static Object transactionLock(Entity entity) {
        return entity instanceof SpawnCensus.Ledger led ? led.fabrolia$censusLock() : entity;
    }

    /** Entity entered an accessible section in {@code level}. Mirrors
     *  {@code PersistentEntitySectionManager.startTracking}. */
    public static void onTrackingStart(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        synchronized (transactionLock(entity)) {
            set.add(entity);
            SpawnCensus.update(level, entity, true);
        }
    }

    /** Entity left an accessible section in {@code level} (chunk hidden, entity died,
     *  or section transition). Mirrors {@code PersistentEntitySectionManager.stopTracking}.
     *  A pending purge strike ends with the membership it was taken against: the
     *  strike sites only walk the visible set, so a struck entity that leaves it
     *  would otherwise stay referenced by the strike ledger until level unload. */
    public static void onTrackingEnd(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
        synchronized (transactionLock(entity)) {
            if (set != null) {
                set.remove(entity);
            }
            clearStrike(level, entity);
            SpawnCensus.update(level, entity, false);
        }
    }

    /** Census purge (two-strike section miss or removed entity):
     *  re-checks {@code stillGone} under the transaction lock, then removes the
     *  entity and its census contribution together. A worker's
     *  {@link #ensurePresent} either completes before (and the re-check sees
     *  the section) or re-adds after; it can no longer land between the two.
     *
     *  @return true when the entity was purged */
    public static boolean purge(ServerLevel level, Entity entity,
                                java.util.function.Predicate<Entity> stillGone) {
        Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
        synchronized (transactionLock(entity)) {
            clearStrike(level, entity);
            if (set == null || !set.contains(entity) || !stillGone.test(entity)) {
                return false;
            }
            set.remove(entity);
            SpawnCensus.update(level, entity, false);
            return true;
        }
    }

    /** Two-strike purge ledger (task #32 cod runaway): entities whose census
     *  section-membership check failed ONCE. The check races entity section
     *  moves on island workers (position updates before the section add lands;
     *  the per-section entity list is a plain ArrayList mutated mid-scan), so a
     *  single failure must never purge — a falsely-purged LIVE entity has no
     *  seam that re-adds it while it stays continuously visible, and every
     *  invisible mob permanently under-fills the spawn caps. Real leaks
     *  (c2me unloads, frozen corpses) are STABLY absent and still purge on the
     *  second consecutive sighting, one audit interval later. */
    private static final Map<ServerLevel, Set<Entity>> PENDING_PURGE = new ConcurrentHashMap<>();

    /** @return true when this is the SECOND consecutive failed sighting — purge now. */
    public static boolean strikeForPurge(ServerLevel level, Entity entity) {
        Set<Entity> s = PENDING_PURGE.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        if (s.add(entity)) {
            // The census walk may hold an entity a worker's onTrackingEnd removed
            // (and cleared strikes for) just before this add. Strike first, then
            // re-read membership; onTrackingEnd removes membership first, then
            // clears: one of the two sees the other, so no strike outlives it.
            Set<Entity> visible = VISIBLE_PER_LEVEL.get(level);
            if (visible == null || !visible.contains(entity)) {
                s.remove(entity);
            }
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
     *  other membership hole) re-enters here. The overwhelmingly-common
     *  already-present path returns after one membership lookup. */
    public static void ensurePresent(ServerLevel level, Entity entity) {
        Set<Entity> set = VISIBLE_PER_LEVEL.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        if (set.contains(entity)) {
            return;
        }
        synchronized (transactionLock(entity)) {
            if (set.contains(entity)) {
                return;
            }
            set.add(entity);
            SpawnCensus.update(level, entity, true);
        }
    }

    /** GROUND-TRUTH RECONCILIATION (task #32): the index is a derived cache of
     *  "entities in accessible sections"; every missed add event (whatever the
     *  cause — a mod's bespoke attach path, a future seam change) diverges it
     *  FOREVER because nothing re-fires for a continuously-visible entity. This
     *  sweep walks section storage and re-adds any accessible live entity the
     *  index lost, bounding ANY membership hole to one sweep interval. Runs on
     *  main during the census audit (every 256 gt — the same cadence
     *  as the purge hygiene it mirrors); cost is one section walk, amortized
     *  ~0.02% of a tick. A failed section walk is a failed census obligation
     *  and propagates unchanged; it must not be converted into a partial heal. */
    public static int reconcile(ServerLevel level,
            net.minecraft.world.level.entity.EntitySectionStorage<net.minecraft.world.level.entity.EntityAccess> storage) {
        int[] healed = new int[1];
        var chunkKeys = storage.getAllChunksWithExistingSections();
        for (var itc = chunkKeys.iterator(); itc.hasNext(); ) {
            long chunkKey = itc.nextLong();
            // One occupied chunk is admitted preparation; every entity heal is
            // additionally cut on its own so W between entries prevents the next
            // derived-census mutation instead of letting the section drain run on.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                for (var section : storage.getExistingSectionsInChunk(chunkKey).toList()) {
                    boolean accessible = section.getStatus().isAccessible();
                    section.getEntities().forEach(ea ->
                            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                                if (!(ea instanceof Entity e) || e.isRemoved()) return;
                                if (!accessible && !ea.isAlwaysTicking()) return;
                                Set<Entity> set = VISIBLE_PER_LEVEL.get(level);
                                if (set == null || !set.contains(e)) {
                                    ensurePresent(level, e);
                                    healed[0]++;
                                }
                            }));
                }
            });
        }
        RegionScheduler.commitSuccessIfHealthy(() -> { });
        return healed[0];
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
