package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placement-decision forensics for the End-portal falling-block scatter.
 *
 * <p>Enabled with {@code -Dfabrolia.debug.fbePlace=true}; a single static-final
 * boolean when off, so there is no cost and no added ordering on the hot path.
 *
 * <p>The backlog theory (audit4) was rejected in the field, so this instruments
 * the DECISION rather than the transport. Vanilla
 * {@code FallingBlockEntity.tick():159+} places a block only when
 *
 * <pre>
 *   (isAlive() || forceTickAfterTeleportToDuplicate)   // continuation gate
 *   &amp;&amp; (onGround() || isStuckInWater)                  // landing gate
 *   &amp;&amp; !currentState.is(MOVING_PISTON) &amp;&amp; !cancelDrop
 *   &amp;&amp; mayReplace &amp;&amp; wouldSurvive                      // replace gate
 * </pre>
 *
 * and otherwise drops the block as an item ("scatter"). Every input to those
 * gates is captured here at the exact instant the decision is taken, for both
 * outcomes, so a bad placement can be diffed against a good one from the same
 * duper cycle.
 */
public final class FallingBlockPlacementTrace {

    /**
     * DEFAULT ON (audit6). Every diagnostic in this mod was gated on a JVM system
     * property, and the audit5 field test proved the property does not reach the
     * JVM on the operator's launcher: the mixin provably applied (mixin.json sets
     * defaultRequire=1, so a failed injection would have crashed startup, and the
     * class loaded fine because blocks were placed), which leaves "ON was false"
     * as the only explanation. That means portalTrace, fbeTrace and cartTrace have
     * been silently dead for every field test in this investigation.
     *
     * <p>So this one defaults to ON and is switched OFF explicitly:
     * {@code -Dfabrolia.debug.fbePlace=false}. Output is bounded (see THROTTLE) so
     * an always-on trace cannot flood latest.log even on a running duper.
     */
    public static final boolean ON =
            !"false".equalsIgnoreCase(System.getProperty("fabrolia.debug.fbePlace", "true"));

    /** First N lines uncapped (that is the interesting burst), then &le;20/s. */
    private static final int BURST = 500;
    private static final java.util.concurrent.atomic.AtomicInteger EMITTED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_START =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger IN_WINDOW =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.LongAdder SUPPRESSED =
            new java.util.concurrent.atomic.LongAdder();

    private static boolean allow() {
        if (EMITTED.incrementAndGet() <= BURST) return true;
        long now = System.nanoTime();
        long start = WINDOW_START.get();
        if (now - start > 1_000_000_000L) {
            if (WINDOW_START.compareAndSet(start, now)) IN_WINDOW.set(0);
        }
        if (IN_WINDOW.incrementAndGet() <= 20) return true;
        SUPPRESSED.increment();
        return false;
    }

    private FallingBlockPlacementTrace() {}

    /** uuid -&gt; wall nanos at which a destination COPY was published. Presence
     *  also answers "is this the arrival copy or the source original?". */
    private static final ConcurrentHashMap<UUID, long[]> ARRIVALS = new ConcurrentHashMap<>();
    /** uuid -&gt; wall nanos of the copy's first destination tick. */
    private static final ConcurrentHashMap<UUID, Long> FIRST_TICK = new ConcurrentHashMap<>();
    /** destination level -&gt; wall nanos of the most recent End-platform regen. */
    private static final ConcurrentHashMap<ServerLevel, Long> LAST_REGEN = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED = 4096;

    /** Called when a staged cross-dimension arrival is published. */
    public static void noteArrival(Entity entity, ServerLevel dest, long srcTick, long srcDomain) {
        if (!ON || !(entity instanceof FallingBlockEntity)) return;
        if (ARRIVALS.size() > MAX_TRACKED) ARRIVALS.clear();
        ARRIVALS.put(entity.getUUID(), new long[]{System.nanoTime(), srcTick, srcDomain,
                IslandClock.wallTime(dest)});
    }

    /** Called when an End platform is regenerated in {@code level}. */
    public static void noteRegen(ServerLevel level) {
        if (!ON) return;
        LAST_REGEN.put(level, System.nanoTime());
    }

    /** Called on a falling block's first tick in its destination level. */
    public static void noteFirstTick(Entity entity) {
        if (!ON || !ARRIVALS.containsKey(entity.getUUID())) return;
        if (FIRST_TICK.size() > MAX_TRACKED) FIRST_TICK.clear();
        FIRST_TICK.putIfAbsent(entity.getUUID(), System.nanoTime());
    }

    /**
     * audit8: DEFERRED eviction. This is called from {@code Entity.remove(reason)},
     * and vanilla's falling-block drop path is {@code discard(); spawnAtLocation(...)}
     * — discard runs FIRST, so an immediate remove() here wiped the arrival record
     * before the DROP outcome hook could read it. That is why audit6/audit7 reported
     * copy=false, srcTick=-1 and publishedNsAgo=-1 on all eleven DROP lines while the
     * PLACE line (which wraps setBlock, before discard) kept its record. That missing
     * data was an artifact of this method, not a transport defect.
     *
     * <p>Removal now only ENQUEUES the id; the entry stays readable until the outcome
     * hook has run. Eviction happens in bulk, far enough behind that every enqueued
     * entity's tick has long since returned.
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<UUID> PENDING_EVICT =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int EVICT_LAG = 1024;

    public static void forget(UUID id) {
        if (!ON) return;
        PENDING_EVICT.add(id);
        if (PENDING_COUNT.incrementAndGet() > EVICT_LAG) {
            for (int i = 0; i < EVICT_LAG / 2; i++) {
                UUID old = PENDING_EVICT.poll();
                if (old == null) break;
                PENDING_COUNT.decrementAndGet();
                ARRIVALS.remove(old);
                FIRST_TICK.remove(old);
            }
        }
    }

    /** packed BlockPos -&gt; {nanos, srcTick, srcDomain, uuidHi, uuidLo} of the last PLACE there. */
    private static final ConcurrentHashMap<Long, long[]> LAST_PLACE_AT = new ConcurrentHashMap<>();

    /**
     * audit8 — one line per End-platform regeneration, reading the duper's placement
     * column immediately before and immediately after the clear.
     *
     * <p>Distinguishes the three candidate orderings:
     * <ol>
     *   <li>{@code before=orange_concrete_powder after=orange_concrete_powder}
     *       (cleared=false) — the regen saw the block and failed to remove it;</li>
     *   <li>{@code before=air} with {@code regenAfterLastPlaceNs} large or
     *       {@code never} — the regen ran before the previous cohort placed, so it
     *       had nothing to clear and the placement lands behind it;</li>
     *   <li>{@code before=orange_concrete_powder after=air} (cleared=true) — the
     *       regen cleared correctly and the next cohort starts at y=50 for some
     *       other reason.</li>
     * </ol>
     */
    public static void regen(ServerLevel level, BlockPos center, boolean dropResources,
                             java.util.function.Supplier<BlockState> readTarget,
                             Runnable doClear) {
        if (!ON) { doClear.run(); return; }
        BlockState before;
        long now;
        try {
            before = readTarget.get();
            now = System.nanoTime();
        } catch (Throwable t) {
            doClear.run();
            LAST_REGEN.put(level, System.nanoTime());
            return;
        }
        doClear.run();
        LAST_REGEN.put(level, System.nanoTime());
        try {
            BlockState after = readTarget.get();
            if (!allow()) return;
            emitRegen(level, center, dropResources, before, after, now);
        } catch (Throwable ignored) {
            // forensics must never influence the path being diagnosed
        }
    }

    private static void emitRegen(ServerLevel level, BlockPos center, boolean dropResources,
                                  BlockState before, BlockState after, long now) {
        BlockPos target = TARGET;
        int dx = target.getX() - center.getX();
        int dy = target.getY() - center.getY();
        int dz = target.getZ() - center.getZ();
        // EndPlatformFeature lays a 5x5 obsidian floor and clears the layers above it,
        // all relative to `center`. before/after are the ground truth; this only says
        // whether the loop was even supposed to touch the column.
        boolean inLoop = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && dy >= -1 && dy <= 2;

        long[] lp = LAST_PLACE_AT.get(target.asLong());
        String placeUuid = lp == null ? "none" : new UUID(lp[3], lp[4]).toString();
        String placeCohort = lp == null ? "-1@-1" : (lp[1] + "@" + lp[2]);

        String island = "?";
        try {
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (rs != null) {
                Region r = rs.regionizer().peekByGridKey(rs.regionizer().grid.keyFor(
                        net.minecraft.world.level.ChunkPos.pack(
                                center.getX() >> 4, center.getZ() >> 4)));
                island = (r != null && r.domain != null && r.domain.owner != null)
                        ? "#" + r.domain.owner.id + "g" + r.domain.owner.gen : "global";
            }
        } catch (Throwable ignored) {
            // island identity is best-effort
        }

        LoggerFactory.getLogger("Fabrolia-FbeRegen").info(
                "[fberegen] dim={} island={} center={} target={} inClearLoop={} "
                        + "before={} after={} cleared={} dropResources={} L={} wall={} "
                        + "lastPlaceUuid={} lastPlaceCohort={} regenAfterLastPlaceNs={} thread={}{}",
                level.dimension().identifier(), island, center.toShortString(),
                target.toShortString(), inLoop,
                before, after, !before.equals(after), dropResources,
                IslandClock.contextTime(), IslandClock.wallTime(level),
                placeUuid, placeCohort,
                lp == null ? "never" : Long.toString(now - lp[0]),
                Thread.currentThread().getName(),
                SUPPRESSED.sum() > 0 ? " suppressed=" + SUPPRESSED.sumThenReset() : "");
    }

    /** The duper's placement column, as identified by the audit6/audit7 captures. */
    private static final BlockPos TARGET = new BlockPos(100, 49, 0);

    /**
     * The decision itself. {@code outcome} is {@code PLACE} (became a block) or
     * {@code DROP} (scattered as an item).
     */
    public static void decision(FallingBlockEntity fbe, String outcome, BlockPos pos,
                                BlockState carried, boolean cancelDrop) {
        if (!ON || !allow()) return;
        try {
            emit(fbe, outcome, pos, carried, cancelDrop);
        } catch (Throwable ignored) {
            // forensics must never influence the path being diagnosed
        }
    }

    private static void emit(FallingBlockEntity fbe, String outcome, BlockPos pos,
                             BlockState carried, boolean cancelDrop) {
        UUID id = fbe.getUUID();
        long now = System.nanoTime();
        long[] arrival = ARRIVALS.get(id);
        Long firstTick = FIRST_TICK.get(id);

        String levelId = fbe.level() instanceof ServerLevel sl
                ? sl.dimension().identifier().toString() : "client?";
        long wall = fbe.level() instanceof ServerLevel sl ? IslandClock.wallTime(sl) : -1;
        Long regen = fbe.level() instanceof ServerLevel sl ? LAST_REGEN.get(sl) : null;

        BlockState below = fbe.level().getBlockState(pos.below());
        BlockState here = fbe.level().getBlockState(pos);

        // Fabrolia's EFFECTIVE removed state (isRemoved is wrapped for the portal
        // handoff) vs the REAL one, which is exactly the removal reason.
        boolean effectiveRemoved = fbe.isRemoved();
        boolean realRemoved = fbe.getRemovalReason() != null;

        if ("PLACE".equals(outcome)) {
            if (LAST_PLACE_AT.size() > MAX_TRACKED) LAST_PLACE_AT.clear();
            LAST_PLACE_AT.put(pos.asLong(), new long[]{now,
                    arrival != null ? arrival[1] : -1,
                    arrival != null ? arrival[2] : -1,
                    id.getMostSignificantBits(), id.getLeastSignificantBits()});
        }

        StringBuilder cohort = new StringBuilder(48);
        if (arrival != null && fbe.level() instanceof ServerLevel sl) {
            int siblings = 0;
            for (var e : sl.getEntities(net.minecraft.world.level.entity.EntityTypeTest
                            .forClass(FallingBlockEntity.class),
                    fbe.getBoundingBox().inflate(8.0), o -> o != fbe)) {
                long[] a = ARRIVALS.get(e.getUUID());
                if (a != null && a[1] == arrival[1] && a[2] == arrival[2]) siblings++;
            }
            cohort.append(" cohortSiblingsWithin8=").append(siblings);
        }

        LoggerFactory.getLogger("Fabrolia-FbePlace").info(
                "[fbeplace] {} uuid={} id={} dim={} copy={} pos={} exactPos=({},{},{}) vel={} "
                        + "time={} fallDist={} onGround={} state={} here={} below={} belowFree={} "
                        + "canSurvive={} mayReplaceHere={} isAlive={} effectiveRemoved={} "
                        + "realRemoved={} removalReason={} forceTickDup={} cancelDrop={} "
                        + "L={} wall={} srcTick={} srcDomain={} destWallAtArrival={} "
                        + "publishedNsAgo={} firstTickNsAgo={} lastRegenNsAgo={} releaseRule={}{}",
                outcome, id, fbe.getId(), levelId, arrival != null, pos,
                fbe.getX(), fbe.getY(), fbe.getZ(), fbe.getDeltaMovement(),
                fbe.time, fbe.fallDistance, fbe.onGround(),
                carried, here, below,
                net.minecraft.world.level.block.FallingBlock.isFree(below),
                carried.canSurvive(fbe.level(), pos),
                here.canBeReplaced(),
                fbe.isAlive(), effectiveRemoved, realRemoved, fbe.getRemovalReason(),
                fbe.forceTickAfterTeleportToDuplicate, cancelDrop,
                IslandClock.contextTime(), wall,
                arrival != null ? arrival[1] : -1,
                arrival != null ? arrival[2] : -1,
                arrival != null ? arrival[3] : -1,
                arrival != null ? (now - arrival[0]) : -1,
                firstTick != null ? (now - firstTick) : -1,
                regen != null ? (now - regen) : -1,
                "causal", // the causal-prefix rule is the only release mode now (flag retired)
                cohort + (SUPPRESSED.sum() > 0 ? " suppressed=" + SUPPRESSED.sumThenReset() : ""));
    }
}
