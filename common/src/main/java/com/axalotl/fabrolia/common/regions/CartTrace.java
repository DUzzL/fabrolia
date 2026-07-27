package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * task #26 async-divergence hunt: per-tick minecart trajectory sampler
 * ({@code -Dfabrolia.debug.cartTrace}). Emits one line per minecart per tick at
 * the fixed pre-movement point (setOldPosAndRot head): wall game-time, owning
 * island L, executing thread, pose, velocity, portal cooldown/process, and the
 * rail/block under the cart. Diff an async-off (vanilla control) log against an
 * async-on log to locate the FIRST cycle divergence the operator asked for —
 * a freeze, a missed or doubled tick, a cadence drift, or a rail-state change
 * around the cross-dimension handoff.
 */
public final class CartTrace {

    public static final boolean ON = Boolean.getBoolean("fabrolia.debug.cartTrace");
    private static final Logger LOG = LoggerFactory.getLogger("Fabrolia-CartTrace");

    private CartTrace() {}

    // ---- Always-on per-minecart tick history (task #26 arrival-physics audit) ----
    // The last N pre-movement states of every minecart, so the auto-capture can
    // show the exact ticks where horizontal speed collapsed and whether a rail was
    // ever engaged. Cheap (one small record per cart per tick); pruned on removal
    // and by age. Independent of the cartTrace log flag.
    public record TickState(long wall, long islandL, double x, double y, double z,
                            double vx, double vy, double vz, boolean insidePortal,
                            int cooldown, boolean onRail, String here, String below,
                            long nanos) {
        String fmt() {
            return "w=" + wall + " L=" + islandL
                    + String.format(" pos=(%.3f,%.3f,%.3f) vel=(%.4f,%.4f,%.4f) spd=%.4f",
                        x, y, z, vx, vy, vz, Math.sqrt(vx * vx + vy * vy + vz * vz))
                    + " inPortal=" + insidePortal + " cd=" + cooldown
                    + " onRail=" + onRail + " here=" + here + " below=" + below;
        }
    }

    private static final int RING = 10;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, TickState[]> HIST =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer> HIST_IDX =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Record one minecart's pre-movement state (called every minecart tick,
     *  unconditionally — the ring is the auto-capture's evidence). */
    public static void recordTick(Entity e) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        java.util.UUID id = e.getUUID();
        BlockPos at = e.blockPosition();
        BlockState here = safeState(sl, at);
        BlockState below = safeState(sl, at.below());
        boolean onRail = isRail(here) || isRail(below);
        Vec3 p = e.position();
        Vec3 v = e.getDeltaMovement();
        boolean inPortal = here != null && here.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
        int cd = e instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard
                ? ((com.axalotl.fabrolia.common.ParallelProcessor.TickGuard) e).fabrolia$isPortalPending() ? -1 : safeCd(e) : safeCd(e);
        TickState st = new TickState(IslandClock.wallTime(sl), IslandClock.contextTime(),
                p.x, p.y, p.z, v.x, v.y, v.z, inPortal, cd, onRail,
                here == null ? "?" : blockName(here), below == null ? "?" : blockName(below),
                System.nanoTime());
        TickState[] ring = HIST.computeIfAbsent(id, k -> new TickState[RING]);
        int idx = HIST_IDX.merge(id, 1, (a, b) -> (a + 1) % (RING * 1_000_000)) % RING;
        ring[idx] = st;
        if (HIST.size() > 512) pruneHist();
    }

    private static int safeCd(Entity e) {
        try { return e.getPortalCooldown(); } catch (Throwable t) { return -2; }
    }

    /** Chronological last-N states for the auto-capture. */
    public static String dumpHistory(java.util.UUID id) {
        TickState[] ring = HIST.get(id);
        if (ring == null) return "  (no tick history)";
        java.util.ArrayList<TickState> live = new java.util.ArrayList<>(RING);
        for (TickState s : ring) if (s != null) live.add(s);
        live.sort(java.util.Comparator.comparingLong(TickState::nanos));
        StringBuilder sb = new StringBuilder(256);
        sb.append("  last ").append(live.size()).append(" ticks (oldest first):");
        for (TickState s : live) sb.append("\n    ").append(s.fmt());
        return sb.toString();
    }

    public static void onRemove(java.util.UUID id) {
        HIST.remove(id);
        HIST_IDX.remove(id);
    }

    private static void pruneHist() {
        long now = System.nanoTime();
        HIST.entrySet().removeIf(en -> {
            TickState[] r = en.getValue();
            long newest = 0;
            for (TickState s : r) if (s != null && s.nanos > newest) newest = s.nanos;
            boolean stale = now - newest > 30_000_000_000L;
            if (stale) HIST_IDX.remove(en.getKey());
            return stale;
        });
    }

    private static boolean isRail(BlockState st) {
        return st != null && (st.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock);
    }

    /** Rail/collision block scan around a position (auto-capture: is a rail
     *  reachable, and is it powered?). */
    public static String railScan(ServerLevel sl, BlockPos at) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("rail scan @ ").append(at.toShortString()).append(":");
        int[][] offs = {{0,0,0},{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{1,-1,0},{-1,-1,0},{0,-1,1},{0,-1,-1}};
        for (int[] o : offs) {
            BlockPos p = at.offset(o[0], o[1], o[2]);
            BlockState st = safeState(sl, p);
            if (st == null || st.isAir()) continue;
            sb.append("\n      ").append(o[0]).append(',').append(o[1]).append(',').append(o[2])
              .append(" = ").append(blockName(st));
            if (isRail(st)) sb.append("  <RAIL>");
        }
        return sb.toString();
    }

    public static void sample(Entity e) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        Vec3 p = e.position();
        Vec3 v = e.getDeltaMovement();
        BlockPos at = e.blockPosition();
        BlockState hereState = safeState(sl, at);
        BlockState belowState = safeState(sl, at.below());
        String here = hereState == null ? "?" : blockName(hereState);
        String below = belowState == null ? "?" : blockName(belowState);
        long islandL = IslandClock.contextTime();
        long wall = IslandClock.wallTime(sl);
        String portal = e instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard tg
                ? tg.fabrolia$portalStateInfo() : "?";
        // Topology fields (task #26 player leave/return transition audit): island
        // id/gen, region domain owner + state, retired flag, chunk entity-ticking
        // gate, portal-ticket level, and how many players are within sim range of
        // this chunk (near/away discriminator). Captures merge/split/retire around
        // the first failure.
        String topo = topology(sl, e.chunkPosition().pack());
        LOG.info("[cart] id={} {} wall={} L={} thr={} pos=({},{},{}) vel=({},{},{}) speed={} {} here={} below={} {}",
                e.getId(), sl.dimension().identifier(), wall, islandL,
                Thread.currentThread().getName(),
                f(p.x), f(p.y), f(p.z), g(v.x), g(v.y), g(v.z), g(v.length()),
                portal, here, below, topo);
    }

    private static String topology(ServerLevel sl, long chunkKey) {
        try {
            RegionizedServer rs = RegionScheduler.forLevel(sl);
            if (rs == null) return "topo:rs=null";
            StringBuilder sb = new StringBuilder(96);
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkKey);
            sb.append("island=").append(owner == null ? "none"
                    : ("#" + owner.id + "/g" + owner.gen + (owner.retired ? "/RETIRED" : "")
                       + "/L" + owner.logicalTick));
            Region reg = rs.regionFor(chunkKey);
            sb.append(" region=").append(reg == null ? "null"
                    : (Long.toHexString(reg.gridKey) + "/" + reg.state() + "/"
                       + (reg.domain == null ? "nullDom"
                       : (reg.domain.owner == null ? "global"
                       : "isl#" + reg.domain.owner.id + "g" + reg.domain.owner.gen))
                       + "/inSet=" + (reg != null && reg.entities != null)));
            var cs = sl.getChunkSource();
            var dm = cs.chunkMap.getDistanceManager();
            var holder = cs.getVisibleChunkIfPresent(chunkKey);
            sb.append(" gate=").append(dm.inEntityTickingRange(chunkKey))
              .append(" ticketLvl=").append(holder == null ? "unloaded" : holder.getTicketLevel())
              .append(" tickingChunk=").append(holder != null && holder.getTickingChunk() != null);
            return sb.toString();
        } catch (Throwable t) {
            return "topo:err=" + t;
        }
    }

    private static BlockState safeState(ServerLevel sl, BlockPos pos) {
        try {
            return sl.getBlockState(pos);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String blockName(BlockState st) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(st.getBlock()).getPath();
        // Include the powered flag for rails so a de-powered rail is visible.
        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
            path += "[powered=" + st.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED) + "]";
        }
        return path;
    }

    private static String f(double d) { return String.format("%.3f", d); }
    private static String g(double d) { return String.format("%.4f", d); }
}
