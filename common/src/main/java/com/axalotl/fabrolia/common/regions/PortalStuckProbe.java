package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * TRAPPED-IN-PORTAL probe ({@code /fabrolia regions debug portalstuck}).
 *
 * <p>Inspects the CURRENT world state: finds every entity sitting in (or held
 * on cooldown by) a portal and prints, per entity, the failure discriminators
 * so a preserved failed state is fully characterized:
 *
 * <ol>
 *   <li>still ticking? — ticks since its last {@code setOldPosAndRot}</li>
 *   <li>in the level entity lookup?</li>
 *   <li>in the region tick set + chunk entity-ticking?</li>
 *   <li>exact position, velocity, portal cooldown, portalProcess entry/expiry</li>
 *   <li>owning island active / retired / player-empty (L vs wall, deadline)</li>
 *   <li>chunk load/ticking level + ticket ranges holding it</li>
 *   <li>overdue scheduled block ticks near it (rail/redstone)</li>
 * </ol>
 *
 * Read-only; main thread (command context). Always available, so a field
 * failure is capturable immediately.
 */
public final class PortalStuckProbe {

    private PortalStuckProbe() {}

    /** Velocity magnitude below which an in-portal entity is treated as at rest
     *  (a bouncing cart carries ≈0.4 b/t; this is two orders down). */
    private static final double AT_REST = 0.02;

    public static String scan(MinecraftServer server) {
        StringBuilder sb = new StringBuilder(256);
        int found = 0, trapped = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof net.minecraft.server.level.ServerPlayer) continue;
                boolean insidePortal = inPortal(level, e);
                boolean onCooldown = e instanceof ParallelProcessor.TickGuard
                        && ((ParallelProcessor.TickGuard) e).fabrolia$isPortalPending();
                int cooldown = e.getPortalCooldown();
                boolean hasProcess = portalProcessNonNull(e);
                // Report anything actually interacting with a portal right now:
                // inside portal blocks, mid-cooldown, or with a live process.
                if (!insidePortal && cooldown == 0 && !hasProcess) continue;
                found++;
                Vec3 v = e.getDeltaMovement();
                double speed = v.length();
                boolean isTrapped = insidePortal && speed < AT_REST;
                if (isTrapped) trapped++;
                sb.append(isTrapped ? "\n  ** TRAPPED " : "\n  portal-entity ")
                  .append(e.getType().toString()).append(' ').append(e.getUUID())
                  .append(" id=").append(e.getId())
                  .append(" in ").append(level.dimension().identifier());
                appendDiscriminators(sb, level, e, speed, insidePortal);
            }
        }
        String header = "portalstuck: scanned all levels — portalEntities=" + found
                + " trapped=" + trapped
                + (found == 0 ? " (no entity is inside a portal or on portal cooldown)" : "");
        return header + sb;
    }

    private static void appendDiscriminators(StringBuilder sb, ServerLevel level, Entity e,
                                             double speed, boolean insidePortal) {
        // (4) pose + portal-entry state
        sb.append("\n      pos=(").append(fmt(e.getX())).append(',').append(fmt(e.getY()))
          .append(',').append(fmt(e.getZ())).append(") vel=(")
          .append(fmt3(e.getDeltaMovement().x)).append(',').append(fmt3(e.getDeltaMovement().y))
          .append(',').append(fmt3(e.getDeltaMovement().z)).append(") speed=").append(fmt3(speed))
          .append(" insidePortalBlock=").append(insidePortal);
        if (e instanceof ParallelProcessor.TickGuard tg) {
            sb.append("\n      ").append(tg.fabrolia$portalStateInfo())
              .append(" portalPending=").append(tg.fabrolia$isPortalPending());
            // (1) still ticking? Wall-nanos delta — clock-independent, so an
            // island-driven cart trailing wall does not read as stalled. At
            // 20 TPS a live entity ticks every ~50 ms; seconds = not ticking.
            long lastNanos = tg.fabrolia$lastTickNanos();
            sb.append("\n      lastTick=").append(lastNanos == 0L ? "never"
                    : ((System.nanoTime() - lastNanos) / 1_000_000L) + "ms ago");
        }
        // (2) in the level entity lookup?
        boolean inLookup = level.getEntity(e.getUUID()) == e;
        long chunkKey = e.chunkPosition().pack();
        RegionizedServer rs = RegionScheduler.forLevel(level);
        // (3) region tick set + region domain
        String regionInfo = "n/a";
        boolean inRegionSet = false;
        if (rs != null) {
            Region reg = rs.regionFor(chunkKey);
            if (reg != null) {
                inRegionSet = reg.entities.contains(e);
                regionInfo = Long.toHexString(reg.gridKey) + "/" + reg.state() + "/"
                        + (reg.domain == null ? "nullDomain"
                        : (reg.domain.owner == null ? "global"
                        : "island#" + reg.domain.owner.id + "g" + reg.domain.owner.gen));
            }
        }
        sb.append("\n      inLookup=").append(inLookup)
          .append(" inRegionSet=").append(inRegionSet)
          .append(" region=").append(regionInfo)
          .append(" regionKeyField=").append(Long.toHexString(((RegionKeyed) e).fabrolia$getRegionKey()));
        // (3/6) chunk ticking + ticket state
        appendChunkLoad(sb, level, chunkKey);
        // (5) owning island drive state
        if (rs != null) {
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkKey);
            if (owner == null) {
                sb.append("\n      island: <none — cell unowned/global-driven>");
            } else {
                sb.append("\n      island#").append(owner.id).append("/g").append(owner.gen)
                  .append(" retired=").append(owner.retired)
                  .append(" L=").append(owner.logicalTick)
                  .append(" wall=").append(IslandClock.wallTime(level))
                  .append(" nextDeadlineIn=").append(owner.nextDeadlineNanos == 0 ? "unscheduled"
                        : ((owner.nextDeadlineNanos - System.nanoTime()) / 1_000_000L) + "ms")
                  .append(" pendingArrivals=").append(owner.pendingArrivals.size())
                  .append(" claim=").append(owner.unitClaim.get());
            }
        }
        // (7) overdue scheduled ticks nearby (rail/redstone clock-domain link)
        appendScheduledTicks(sb, level, e);
    }

    private static void appendChunkLoad(StringBuilder sb, ServerLevel level, long chunkKey) {
        try {
            ServerChunkCache cs = level.getChunkSource();
            var dm = cs.chunkMap.getDistanceManager();
            var holder = cs.getVisibleChunkIfPresent(chunkKey);
            sb.append("\n      chunk=(").append(ChunkPos.getX(chunkKey)).append(',')
              .append(ChunkPos.getZ(chunkKey)).append(") entityTickingRange=")
              .append(dm.inEntityTickingRange(chunkKey))
              .append(" ticketLevel=").append(holder == null ? "unloaded" : holder.getTicketLevel())
              .append(" tickingChunk=").append(holder != null && holder.getTickingChunk() != null);
        } catch (Throwable t) {
            sb.append("\n      chunkLoad: probe-failed:").append(t);
        }
    }

    /** Scan a small box around the entity for blocks with a PENDING scheduled
     *  block tick; flag any whose target game-time is already in the past
     *  (overdue = a scheduled rail/redstone tick that a clock-domain handoff
     *  may have stranded — the #27 cross-link the operator called out). */
    private static void appendScheduledTicks(StringBuilder sb, ServerLevel level, Entity e) {
        try {
            BlockPos center = e.blockPosition();
            var blockTicks = level.getBlockTicks();
            StringBuilder hits = new StringBuilder(48);
            int pending = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(p);
                        if (st.isAir()) continue;
                        if (blockTicks.hasScheduledTick(p, st.getBlock())) {
                            pending++;
                            hits.append(' ').append(p.toShortString()).append('=')
                                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
                        }
                    }
                }
            }
            // A powered-rail/redstone scheduled tick that stays pending here
            // across probes (esp. after an island retire/refound) is the #27
            // clock-domain-handoff signal — the loader's rail losing power.
            sb.append("\n      scheduledBlockTicksNearby=").append(pending);
            if (pending > 0) sb.append(" at:").append(hits);
        } catch (Throwable t) {
            sb.append("\n      scheduledTicks: probe-failed:").append(t);
        }
    }

    private static boolean inPortal(ServerLevel level, Entity e) {
        return isPortal(level, e.blockPosition())
                || isPortal(level, BlockPos.containing(e.getX(), e.getY(), e.getZ()));
    }

    private static boolean isPortal(ServerLevel level, BlockPos pos) {
        try {
            return level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean portalProcessNonNull(Entity e) {
        String info = e instanceof ParallelProcessor.TickGuard
                ? ((ParallelProcessor.TickGuard) e).fabrolia$portalStateInfo() : "";
        return info.contains("portalProcess{");
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
    private static String fmt3(double d) { return String.format("%.4f", d); }
}
