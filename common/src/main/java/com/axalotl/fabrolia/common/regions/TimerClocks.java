package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.timer.BehaviorTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.BrainTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.CopperGolemTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.GateBehaviorTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.LivingEntityTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.NearestBedSensorTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.TrialSpawnerStateDataTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.VillagerTimerAccessor;
import com.axalotl.fabrolia.common.mixin.timer.WorkAtPoiTimerAccessor;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.ai.sensing.NearestBedSensor;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;

/**
 * Clock-domain ownership of ABSOLUTE TIMERS in entities and block entities
 * (timer clock fix, 2026-11-01) — the entity/block-entity sibling of the
 * scheduled-tick rebase ({@link TickDomains}).
 *
 * <p>THE DEFECT. Vanilla stores some timers as an absolute game time: trial
 * spawner {@code cooldown_ends_at}, neutral-mob {@code anger_end_time},
 * villager {@code last_slept} and restock times, brain behaviour end times and
 * the brain's schedule check. One
 * clock, so "remaining = stamp - now" is stable. Under async islands each
 * island reads its own clock L, and an area changes clock when islands merge
 * (the survivor keeps its own L), when it loads again into a new island (born
 * at the wall time W) or into another island, and when an entity is copied to
 * another dimension. Only scheduled ticks and torch history were rebased, so
 * every such timer ended early or late by the clock gap (measured: a 60 s
 * trial-spawner cooldown ended after 125 s on a merge into an island 65 s
 * behind, and after 2.6 s on a reload; docs/FARM-CLOCK-REVIEW-2026-11-01.md).
 *
 * <p>THE FIX. Every entity and the stamped block entities carry the clock
 * their timers are expressed in (the "basis": an island, or null for W).
 * <ul>
 *   <li>Saved data is in W, as vanilla's saves are: {@code saveWithoutId} /
 *       {@code saveAdditional} shift the timers to W for the write and back
 *       (the owner or a claim holder saves, so the island cannot observe it);
 *       a load sets the basis to W.</li>
 *   <li>A region that changes domain moves its timers with its scheduled
 *       ticks, in the same safe point and by the same "now" values
 *       ({@link #rebaseRegion}).</li>
 *   <li>Before an entity ticks (and before a stamped block entity reads its
 *       timers) the basis is compared with the clock this thread reads
 *       ({@link #currentBasis}, the rule of {@link IslandClock#contextTime});
 *       on a mismatch the timers move to it. This covers entities that entered
 *       a region under another clock and ticks Fabrolia runs on Main.</li>
 * </ul>
 * A shift keeps "remaining = stamp - now" exactly; unset sentinels never move,
 * and a shift into the past stops just above the sentinel (an expired timer
 * stays expired).
 *
 * <p>Deviations (release notes): saves made before this fix hold island-clock
 * values, which load as W once (those timers end early once); an island-head
 * datapack that compares {@code /data} timers (W) with {@code time query
 * gametime} (island clock) is off by the gap; a retired island in a basis is read as its
 * final L plus the wall time since it retired; an entity read by a neighbour
 * between a clock change and its own first tick there (outside a region
 * rebase) reads the old clock once. Throttles held in vanilla lambdas and goal
 * fields out of reach (AcquirePoi, SetClosestHomeAsWalkTarget, the raider
 * banner cache) are not moved: a stamp further ahead than any legal one is
 * treated as due, and one moved into the past only makes the next check come
 * early once. Potent sulfur's eruption time is read only on clients. Vault, brushable blocks, Lithium
 * hopper sleep, vibrations, warden warnings and torch history keep their own
 * fixes and are not touched here; players are not stamped.
 */
public final class TimerClocks {

    /** Duck on entities and stamped block entities: the clock their timers are in. */
    public interface Stamped {
        IslandRegistry.PIsland fabrolia$timerBasis();

        void fabrolia$setTimerBasis(IslandRegistry.PIsland basis);

        /** Move every timer by {@code delta}; {@code clamp}: a timer moved into
         *  the past stops just above its sentinel. */
        void fabrolia$shiftTimers(long delta, boolean clamp);
    }

    /** Memories that hold an absolute game time (a holder: entities are built
     *  before the memory registry is bootstrapped). */
    private static final class TimeMemories {
        static final List<MemoryModuleType<Long>> ALL = List.of(
                MemoryModuleType.HEARD_BELL_TIME,
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                MemoryModuleType.LAST_SLEPT,
                MemoryModuleType.LAST_WOKEN,
                MemoryModuleType.LAST_WORKED_AT_POI);
    }

    /** {@code Brain.lastScheduleUpdate} before the first schedule check. */
    static final long BRAIN_SCHEDULE_UNSET = -9999L;

    /** Never a sentinel: every value is a time. */
    static final long NO_SENTINEL = Long.MIN_VALUE;

    /** Largest lead a legal AcquirePoi start stamp has over "now"
     *  ({@code timestamp + 20 + nextInt(20)}, first stamp {@code now + nextInt(20)}). */
    public static final long ACQUIRE_POI_MAX_LEAD = 40L;
    /** Largest lead of a legal AcquirePoi retry ({@code currentDelay <= 400}). */
    public static final long ACQUIRE_POI_RETRY_MAX_LEAD = 400L;
    /** SetClosestHomeAsWalkTarget: throttle {@code now + nextInt(20)}, cache
     *  entries {@code throttle + 40}. */
    public static final long HOME_SEARCH_MAX_LEAD = 20L;
    public static final long HOME_SEARCH_CACHE_MAX_LEAD = 60L;
    /** Raider banner pickup: unreachable until {@code now + 600}. */
    public static final long BANNER_RETRY_MAX_LEAD = 600L;

    /** Retired-island bases resolved (diagnostic; see the class deviations). */
    public static final LongAdder RETIRED_BASIS_READS = new LongAdder();

    private TimerClocks() {}

    // ------------------------------------------------------------ clocks

    /**
     * The clock {@code getGameTime()} answers on this thread: the island whose
     * unit time {@link IslandClock#contextTime} returns, or null (W) for Main,
     * chunk/IO threads, the global domain and domains without a unit yet.
     */
    public static IslandRegistry.PIsland currentBasis() {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) return null;
        Region r = w.currentRegion;
        EpochDomain d = r != null ? r.domain : w.currentDomain;
        if (d == null || d.unitGameTime == 0L) return null;
        return d.owner;
    }

    /** What {@code basis} reads now; {@code wall} is the raw counter. */
    static long now(IslandRegistry.PIsland basis, long wall) {
        if (basis == null) return wall;
        if (basis.retired) {
            RETIRED_BASIS_READS.increment();
            return basis.logicalTick + Math.max(0L, wall - basis.retiredWallTime);
        }
        return VibrationDomains.domainNow(basis.logicalTick, basis.domain.unitGameTime);
    }

    /** Ticks to add to a timer in {@code from} to express it in {@code to}. */
    static long delta(IslandRegistry.PIsland from, IslandRegistry.PIsland to, long wall) {
        return from == to ? 0L : now(to, wall) - now(from, wall);
    }

    /** Move {@code s} onto the clock this thread reads. */
    public static void ensureCurrent(Stamped s, ServerLevel level) {
        IslandRegistry.PIsland target = currentBasis();
        IslandRegistry.PIsland basis = s.fabrolia$timerBasis();
        if (basis == target) return;
        moveTo(s, target, IslandClock.wallTime(level));
    }

    static void moveTo(Stamped s, IslandRegistry.PIsland target, long wall) {
        long d = delta(s.fabrolia$timerBasis(), target, wall);
        if (d != 0L) s.fabrolia$shiftTimers(d, true);
        s.fabrolia$setTimerBasis(target);
    }

    /**
     * Ticks a save adds to express {@code s}'s timers in W. Never negative: a
     * mid-unit island reads one tick ahead of W at most, and a negative save
     * shift could land a timer on its sentinel and break the exact restore.
     */
    public static long toWallForSave(Stamped s, ServerLevel level) {
        IslandRegistry.PIsland basis = s.fabrolia$timerBasis();
        if (basis == null) return 0L;
        long wall = IslandClock.wallTime(level);
        return Math.max(0L, wall - now(basis, wall));
    }

    // ------------------------------------------------------------ rules

    /** A timer moved by {@code d}: sentinels ({@code v <= floor}) stay; with
     *  {@code clamp} a result at or below the floor stops at {@code floor + 1}. */
    public static long shift(long v, long d, long floor, boolean clamp) {
        if (v <= floor) return v;
        long r = v + d;
        return clamp && r <= floor ? floor + 1 : r;
    }

    /** Camel pose tick: the sign says "sitting", 0 is the default. */
    public static long shiftSigned(long v, long d, boolean clamp) {
        if (v == 0L) return 0L;
        if (v > 0L) return shift(v, d, 0L, clamp);
        return -shift(-v, d, 0L, clamp);
    }

    /** A start stamp further ahead than any legal one is cross-clock residue. */
    public static boolean isFutureResidue(long stamp, long now, long maxLead) {
        return stamp - now > maxLead;
    }

    // ------------------------------------------------------------ stores

    /** All timers of {@code e} (players carry none). */
    public static void shiftEntity(Entity e, long d, boolean clamp) {
        if (e instanceof Player) return;
        if (e instanceof LivingEntity le) {
            LivingEntityTimerAccessor a = (LivingEntityTimerAccessor) (Object) le;
            if (a.fabrolia$lastDamageSource() != null) {
                a.fabrolia$setLastDamageStamp(shift(a.fabrolia$lastDamageStamp(), d, NO_SENTINEL, clamp));
            }
            shiftBrain(le.getBrain(), d, clamp);
        }
        if (e instanceof NeutralMob m) {
            m.setPersistentAngerEndTime(shift(m.getPersistentAngerEndTime(), d, 0L, clamp));
        }
        if (e instanceof Villager v) {
            VillagerTimerAccessor a = (VillagerTimerAccessor) (Object) v;
            a.fabrolia$setLastRestockGameTime(shift(a.fabrolia$lastRestockGameTime(), d, 0L, clamp));
            a.fabrolia$setLastGossipDecayTime(shift(a.fabrolia$lastGossipDecayTime(), d, 0L, clamp));
            a.fabrolia$setLastGossipTime(shift(a.fabrolia$lastGossipTime(), d, 0L, clamp));
        }
        if (e instanceof Camel c) {
            long pose = c.getEntityData().get(Camel.LAST_POSE_CHANGE_TICK);
            long moved = shiftSigned(pose, d, clamp);
            if (moved != pose) c.getEntityData().set(Camel.LAST_POSE_CHANGE_TICK, moved);
        }
        if (e instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
            com.axalotl.fabrolia.common.mixin.timer.ArmorStandTimerAccessor a =
                    (com.axalotl.fabrolia.common.mixin.timer.ArmorStandTimerAccessor) (Object) stand;
            a.fabrolia$setLastHit(shift(a.fabrolia$lastHit(), d, 0L, clamp));
        }
        if (e instanceof CopperGolem g) {
            CopperGolemTimerAccessor a = (CopperGolemTimerAccessor) (Object) g;
            a.fabrolia$setNextWeatheringTick(shift(a.fabrolia$nextWeatheringTick(), d, 0L, clamp));
        }
    }

    private static void shiftBrain(Brain<?> brain, long d, boolean clamp) {
        BrainTimerAccessor a = (BrainTimerAccessor) (Object) brain;
        // The schedule check runs when more than 20 ticks passed; -9999 = never.
        a.fabrolia$setLastScheduleUpdate(shift(a.fabrolia$lastScheduleUpdate(), d, BRAIN_SCHEDULE_UNSET, clamp));
        Map<MemoryModuleType<?>, MemorySlot<?>> memories = a.fabrolia$memories();
        for (MemoryModuleType<Long> type : TimeMemories.ALL) {
            @SuppressWarnings("unchecked")
            MemorySlot<Long> slot = (MemorySlot<Long>) memories.get(type);
            if (slot != null && slot.hasValue()) {
                slot.set(shift(slot.value(), d, NO_SENTINEL, clamp), slot.timeToLive());
            }
        }
        for (Map<?, ? extends Set<? extends BehaviorControl<?>>> byActivity
                : a.fabrolia$availableBehaviorsByPriority().values()) {
            for (Set<? extends BehaviorControl<?>> behaviors : byActivity.values()) {
                for (BehaviorControl<?> behavior : behaviors) {
                    shiftBehavior(behavior, d, clamp);
                }
            }
        }
        for (Sensor<?> sensor : a.fabrolia$sensors().values()) {
            if (sensor instanceof NearestBedSensor bed) {
                NearestBedSensorTimerAccessor b = (NearestBedSensorTimerAccessor) (Object) bed;
                b.fabrolia$setLastUpdate(shift(b.fabrolia$lastUpdate(), d, 0L, clamp));
                for (Long2LongMap.Entry entry : b.fabrolia$batchCache().long2LongEntrySet()) {
                    entry.setValue(shift(entry.getLongValue(), d, NO_SENTINEL, clamp));
                }
            }
        }
    }

    private static void shiftBehavior(BehaviorControl<?> behavior, long d, boolean clamp) {
        if (behavior instanceof Behavior<?> b) {
            BehaviorTimerAccessor a = (BehaviorTimerAccessor) (Object) b;
            a.fabrolia$setEndTimestamp(shift(a.fabrolia$endTimestamp(), d, NO_SENTINEL, clamp));
        }
        if (behavior instanceof WorkAtPoi w) {
            WorkAtPoiTimerAccessor a = (WorkAtPoiTimerAccessor) (Object) w;
            a.fabrolia$setLastCheck(shift(a.fabrolia$lastCheck(), d, 0L, clamp));
        }
        if (behavior instanceof GateBehavior<?> g) {
            for (BehaviorControl<?> inner : ((GateBehaviorTimerAccessor) (Object) g).fabrolia$behaviors()) {
                shiftBehavior(inner, d, clamp);
            }
        }
    }

    /** Trial spawner state: cooldown end and next spawn (0 = unset). */
    public static void shiftTrialSpawner(TrialSpawner spawner, long d, boolean clamp) {
        TrialSpawnerStateDataTimerAccessor a = (TrialSpawnerStateDataTimerAccessor) (Object) spawner.getStateData();
        a.fabrolia$setCooldownEndsAt(shift(a.fabrolia$cooldownEndsAt(), d, 0L, clamp));
        a.fabrolia$setNextMobSpawnsAt(shift(a.fabrolia$nextMobSpawnsAt(), d, 0L, clamp));
    }

    // ------------------------------------------------------------ region rebase

    /**
     * Region {@code r} moves to {@code d} (Main, both domains quiescent, beside
     * the scheduled-tick rebase): every stamped entity and trial spawner in its
     * chunks moves from its own basis to {@code d}'s clock.
     */
    static int rebaseRegion(Region r, EpochDomain d) {
        ServerLevel level = r.level;
        if (r.fullChunks.isEmpty()
                || !((Object) level instanceof com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor ema)) {
            return 0;
        }
        IslandRegistry.PIsland target = d.owner;
        long wall = IslandClock.wallTime(level);
        var em = ema.fabrolia$entityManager();
        var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) em)
                .fabrolia$sectionStorage();
        int[] moved = new int[1];
        it.unimi.dsi.fastutil.longs.LongIterator it = r.fullChunks.iterator();
        while (it.hasNext()) {
            long chunkKey = it.nextLong();
            for (var section : storage.getExistingSectionsInChunk(chunkKey).toList()) {
                section.getEntities().forEach(ea -> {
                    if (ea instanceof Entity e && !(e instanceof Player) && (Object) e instanceof Stamped s
                            && s.fabrolia$timerBasis() != target) {
                        moveTo(s, target, wall);
                        moved[0]++;
                    }
                });
            }
            var chunk = level.getChunkSource().getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(chunkKey),
                    net.minecraft.world.level.ChunkPos.getZ(chunkKey));
            if (chunk == null) continue;
            for (var be : chunk.getBlockEntities().values()) {
                if (be instanceof net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity t
                        && (Object) t.getTrialSpawner() instanceof Stamped s
                        && s.fabrolia$timerBasis() != target) {
                    moveTo(s, target, wall);
                    moved[0]++;
                }
            }
        }
        return moved[0];
    }
}
