package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator diagnostics + surgical repair for scheduled-tick state
 * (task #31 — /fabrolia regions ticks …). All operations run on main under
 * the command fence (islands quiescent), read the CANONICAL per-chunk
 * containers as the only source of truth, and never delete anything except a
 * specifically listed entry at a specifically named position after an
 * explicit confirm. Far-future entries are REPORTED, never auto-clamped —
 * modded delays may be legitimately long; the operator decides.
 */
public final class TickInspector {

    private TickInspector() {}

    /** Beyond this many ticks in the future an entry is FLAGGED as a suspected
     *  stale blocker in reports (vanilla delays top out around 600; the
     *  corruption class measured in the field is 10^4–10^6). Reporting
     *  threshold only — removal always names exact entries. */
    // (report threshold only — clear-stale still names exact entries and
    // requires confirm; vanilla delays top out ~600, so 6000 flags the
    // corruption class while leaving modded long delays untouched)
    public static final long SUSPECT_FUTURE = 6_000;

    public record Entry(boolean block, String type, BlockPos pos, long trigger,
                        TickPriority priority, long subTick, long domainNow,
                        long rel, String domain) {}

    private static LevelChunkTicks<?> container(ServerLevel level, boolean block, long chunkKey) {
        LevelTicksIslandAccess<?> access = (LevelTicksIslandAccess<?>)
                (block ? level.getBlockTicks() : level.getFluidTicks());
        Object o = access.fabrolia$allContainers().get(chunkKey);
        return o instanceof LevelChunkTicks<?> c ? c : null;
    }

    private static String domainOf(LevelChunkTicks<?> c) {
        long id = ((FabroliaTickDomain) (Object) c).fabrolia$domainIsland();
        return id == FabroliaTickDomain.WALL ? "WALL"
                : "island#" + id + "g" + ((FabroliaTickDomain) (Object) c).fabrolia$domainGen();
    }

    /** Every canonical entry at {@code pos} (both lanes) with tag-resolved
     *  relative time. Loaded chunks only (unloaded = NBT, use status). */
    public static List<Entry> entriesAt(ServerLevel level, BlockPos pos) {
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        List<Entry> out = new ArrayList<>(4);
        for (boolean block : new boolean[]{true, false}) {
            LevelChunkTicks<?> c = container(level, block, chunkKey);
            if (c == null) continue;
            long now = TickDomains.resolveNow(level, c);
            String dom = domainOf(c);
            c.getAll().filter(t -> t.pos().equals(pos)).forEach(t -> {
                String type = block
                        ? BuiltInRegistries.BLOCK.getKey((Block) t.type()).toString()
                        : BuiltInRegistries.FLUID.getKey((Fluid) t.type()).toString();
                out.add(new Entry(block, type, t.pos(), t.triggerTick(), t.priority(),
                        t.subTickOrder(), now, t.triggerTick() - now, dom));
            });
        }
        return out;
    }

    public static String inspect(ServerLevel level, BlockPos pos) {
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        StringBuilder sb = new StringBuilder(256);
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland owner = rs != null ? rs.islandOwnerOfChunk(chunkKey) : null;
        sb.append("ticks @ ").append(pos.toShortString())
          .append(" chunk[").append(pos.getX() >> 4).append(',').append(pos.getZ() >> 4).append(']')
          .append(" owner=").append(owner == null ? "global/none"
                  : "island#" + owner.id + "g" + owner.gen + " L=" + owner.logicalTick)
          .append(" wall=").append(TickDomains.wallNow(level))
          .append(" block=").append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
        List<Entry> es = entriesAt(level, pos);
        if (es.isEmpty()) {
            sb.append("\n no canonical entries at this position (loaded containers)");
        }
        for (Entry e : es) {
            sb.append("\n ").append(e.block() ? "BLOCK" : "FLUID").append(' ').append(e.type())
              .append(" trigger=").append(e.trigger())
              .append(" (").append(e.rel() >= 0 ? "+" : "").append(e.rel())
              .append(" vs ").append(e.domain()).append(" now=").append(e.domainNow()).append(')')
              .append(" prio=").append(e.priority()).append(" sub=").append(e.subTick());
            if (e.rel() > SUSPECT_FUTURE) sb.append("  <== SUSPECT STALE BLOCKER (far future; dedup rejects new schedules here)");
            if (e.rel() < -SUSPECT_FUTURE) sb.append("  <== deeply overdue (will burst when tickable)");
        }
        // dedup probe: would a fresh schedule for the current block be accepted?
        Block cur = level.getBlockState(pos).getBlock();
        boolean blocked = level.getBlockTicks().hasScheduledTick(pos, cur);
        sb.append("\n dedup: hasScheduledTick(current block)=").append(blocked)
          .append(blocked ? "  (new schedules for this block type are REJECTED at this pos)" : "");
        return sb.toString();
    }

    /** Area sweep (task #32 follow-up tooling): list every loaded chunk in the
     *  square radius whose containers hold SUSPECT entries — far-future
     *  triggers (stale dedup blockers, the shutdown-corruption signature) or
     *  deeply-overdue backlogs. Read-only; feed hits to inspect/clear-stale. */
    public static String scan(ServerLevel level, int cx, int cz, int radius) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("tick scan center=[").append(cx).append(',').append(cz).append("] r=").append(radius)
          .append(" wall=").append(TickDomains.wallNow(level));
        int hits = 0, scanned = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long chunkKey = ChunkPos.pack(cx + dx, cz + dz);
                for (boolean block : new boolean[]{true, false}) {
                    LevelChunkTicks<?> c = container(level, block, chunkKey);
                    if (c == null) continue;
                    scanned++;
                    long now = TickDomains.resolveNow(level, c);
                    int[] n = {0}, farFuture = {0}, overdue = {0};
                    c.getAll().forEach(t -> {
                        n[0]++;
                        long rel = t.triggerTick() - now;
                        if (rel > SUSPECT_FUTURE) farFuture[0]++;
                        if (rel < -SUSPECT_FUTURE) overdue[0]++;
                    });
                    if (farFuture[0] > 0 || overdue[0] > 0) {
                        hits++;
                        sb.append("\n [").append(cx + dx).append(',').append(cz + dz).append("] ")
                          .append(block ? "block" : "fluid")
                          .append(" domain=").append(domainOf(c))
                          .append(" count=").append(n[0])
                          .append(" suspectFuture=").append(farFuture[0])
                          .append(" deepOverdue=").append(overdue[0]);
                    }
                }
            }
        }
        sb.append("\n containers=").append(scanned).append(" suspectChunks=").append(hits)
          .append(hits > 0 ? " — run ticks inspect/clear-stale/reschedule on listed chunks"
                           : " — clean");
        return sb.toString();
    }

    /** Chunk-level summary: counts, earliest, overdue/future extremes, tag vs
     *  owner agreement. */
    public static String status(ServerLevel level, int cx, int cz) {
        long chunkKey = ChunkPos.pack(cx, cz);
        StringBuilder sb = new StringBuilder(192);
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland owner = rs != null ? rs.islandOwnerOfChunk(chunkKey) : null;
        sb.append("chunk[").append(cx).append(',').append(cz).append("] owner=")
          .append(owner == null ? "global/none" : "island#" + owner.id + "g" + owner.gen + " L=" + owner.logicalTick)
          .append(" wall=").append(TickDomains.wallNow(level))
          .append(" domainStats: loadRebases=").append(TickDomains.LOAD_REBASES.get())
          .append(" unresolved=").append(TickDomains.UNRESOLVED_TAGS.get())
          .append(" consumerHeals=").append(TickDomains.CONSUMER_HEALS.get());
        for (boolean block : new boolean[]{true, false}) {
            LevelChunkTicks<?> c = container(level, block, chunkKey);
            sb.append("\n ").append(block ? "block" : "fluid").append(": ");
            if (c == null) { sb.append("no loaded container"); continue; }
            long now = TickDomains.resolveNow(level, c);
            long[] minT = {Long.MAX_VALUE}, maxT = {Long.MIN_VALUE};
            int[] n = {0}, farFuture = {0}, overdue = {0};
            c.getAll().forEach(t -> {
                n[0]++;
                minT[0] = Math.min(minT[0], t.triggerTick());
                maxT[0] = Math.max(maxT[0], t.triggerTick());
                long rel = t.triggerTick() - now;
                if (rel > SUSPECT_FUTURE) farFuture[0]++;
                if (rel < -32) overdue[0]++;
            });
            sb.append("count=").append(n[0]).append(" domain=").append(domainOf(c))
              .append(" now=").append(now);
            if (n[0] > 0) {
                sb.append(" earliest=").append(minT[0]).append(" (").append(minT[0] - now).append(")")
                  .append(" latest=").append(maxT[0]).append(" (").append(maxT[0] - now).append(")")
                  .append(" overdue=").append(overdue[0]).append(" suspectFuture=").append(farFuture[0]);
                if (farFuture[0] > 0) {
                    int[] listed = {0};
                    boolean blk = block;
                    c.getAll().forEach(t -> {
                        long rel = t.triggerTick() - now;
                        if (rel > SUSPECT_FUTURE && listed[0]++ < 8) {
                            String type = blk
                                    ? BuiltInRegistries.BLOCK.getKey((Block) t.type()).toString()
                                    : BuiltInRegistries.FLUID.getKey((Fluid) t.type()).toString();
                            sb.append("\n   SUSPECT ").append(type).append(" @ ")
                              .append(t.pos().toShortString())
                              .append(" trigger=").append(t.triggerTick()).append(" (+").append(rel).append(")");
                        }
                    });
                }
            }
            boolean tagMatchesOwner = (owner == null
                    && ((FabroliaTickDomain) (Object) c).fabrolia$domainIsland() == FabroliaTickDomain.WALL)
                    || (owner != null
                    && ((FabroliaTickDomain) (Object) c).fabrolia$domainIsland() == owner.id);
            if (!tagMatchesOwner) sb.append("  <== tag/owner MISMATCH (run rebuild)");
        }
        return sb.toString();
    }

    /** Derived-state rebuild for one chunk: rebase both containers into the
     *  CURRENT owner's domain (tag-driven, delay-preserving, idempotent) and
     *  refresh the runner's next-tick index from the canonical head. Never
     *  executes or drops a tick. */
    public static String rebuild(ServerLevel level, int cx, int cz) {
        long chunkKey = ChunkPos.pack(cx, cz);
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland owner = rs != null ? rs.islandOwnerOfChunk(chunkKey) : null;
        long targetId = owner != null ? owner.id : FabroliaTickDomain.WALL;
        long targetGen = owner != null ? owner.gen : -1;
        long targetNow = owner != null ? owner.logicalTick : TickDomains.wallNow(level);
        int moved = 0;
        for (boolean block : new boolean[]{true, false}) {
            LevelChunkTicks<?> c = container(level, block, chunkKey);
            if (c == null) continue;
            moved += TickDomains.rebaseTo(level, c, targetId, targetGen, targetNow);
            LevelTicksIslandAccess<?> access = (LevelTicksIslandAccess<?>)
                    (block ? level.getBlockTicks() : level.getFluidTicks());
            ScheduledTick<?> head = c.peek();
            if (head != null) {
                access.fabrolia$nextTickForContainer().put(chunkKey, head.triggerTick());
            }
        }
        return "rebuild chunk[" + cx + "," + cz + "]: rebased " + moved
                + " tick(s) into " + (owner == null ? "WALL" : "island#" + owner.id)
                + "; next-tick index refreshed";
    }

    /** WHOLE-CHUNK stale-blocker repair (operator fast path, 2026-07-21):
     *  removes every suspect-stale scheduled tick (trigger > now + SUSPECT_FUTURE)
     *  in BOTH lanes of one chunk, then refreshes the derived next-tick index
     *  from the container heads. minecraft:fire / soul_fire entries are skipped
     *  by default — long netherrack fire ticks are legitimate and flooded the
     *  repair workflow with false positives. Idempotent: a repaired chunk
     *  reports zero on re-run. Every removal is listed and logged. */
    public static String clearStaleChunk(ServerLevel level, int cx, int cz,
                                         boolean includeFire, boolean dryRun) {
        long chunkKey = ChunkPos.pack(cx, cz);
        StringBuilder sb = new StringBuilder(192);
        sb.append(dryRun ? "DRY-RUN (nothing removed — append 'confirm' to apply) " : "")
          .append("clear-stale chunk[").append(cx).append(',')
          .append(cz).append("] ").append(level.dimension().identifier());
        int removedBlock = 0, removedFluid = 0, skippedFire = 0;
        for (boolean block : new boolean[]{true, false}) {
            LevelChunkTicks<?> c = container(level, block, chunkKey);
            if (c == null) continue;
            long now = TickDomains.resolveNow(level, c, chunkKey, block ? "block" : "fluid");
            final long fnow = now;
            List<ScheduledTick<?>> stale = new java.util.ArrayList<>();
            c.getAll().forEach(t -> {
                if (t.triggerTick() - fnow > SUSPECT_FUTURE) stale.add(t);
            });
            // Corruption-signature discrimination (2026-07-27 audit): a
            // clock-offset mistranslation shifts EVERY pending entry of the
            // container by the same (W − L), so its suspects cluster within
            // the span of ordinary short delays (≤ ~600). A legitimately long
            // modded delay is an independent per-entry value. Annotation
            // only — removal below still lists exact entries and the
            // operator decides; nothing is cleared automatically anywhere.
            if (!stale.isEmpty()) {
                long minRel = Long.MAX_VALUE, maxRel = Long.MIN_VALUE;
                for (ScheduledTick<?> t : stale) {
                    long rel = t.triggerTick() - fnow;
                    minRel = Math.min(minRel, rel);
                    maxRel = Math.max(maxRel, rel);
                }
                sb.append("\n ").append(block ? "block" : "fluid").append(" signature: ");
                if (stale.size() == 1) {
                    sb.append("SINGLE far-future entry (+").append(minRel)
                      .append(") — could be a legitimate long modded delay; verify with 'ticks inspect' before clearing");
                } else if (maxRel - minRel <= 600) {
                    sb.append(stale.size()).append(" entries share a common offset ≈+").append(minRel)
                      .append(" (spread ").append(maxRel - minRel)
                      .append(") — clock-offset corruption signature");
                } else {
                    sb.append(stale.size()).append(" entries with DIVERGING offsets (+").append(minRel)
                      .append("...+").append(maxRel)
                      .append(") — may include legitimate long modded delays; inspect each before clearing");
                }
            }
            for (ScheduledTick<?> t : stale) {
                String type = block
                        ? BuiltInRegistries.BLOCK.getKey((Block) t.type()).toString()
                        : BuiltInRegistries.FLUID.getKey((Fluid) t.type()).toString();
                if (!includeFire && (type.equals("minecraft:fire") || type.equals("minecraft:soul_fire"))) {
                    skippedFire++;
                    continue;
                }
                sb.append("\n ").append(block ? "BLOCK" : "FLUID").append(' ').append(type)
                  .append(" @").append(t.pos().toShortString())
                  .append(" trigger=").append(t.triggerTick())
                  .append(" (+").append(t.triggerTick() - fnow).append(')');
                if (!dryRun) {
                    int removed = TickDomains.drainRemoveIfErased(c, x -> x.pos().equals(t.pos())
                            && x.triggerTick() == t.triggerTick()
                            && x.subTickOrder() == t.subTickOrder() && x.type() == t.type());
                    if (removed == 0) continue;
                    TickRepair.markDirty(level, chunkKey);
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-TickDomains").warn(
                            "[ticks clear-stale chunkhere] removed {} {} at {} trigger={} (+{}) chunk=[{},{}] {}",
                            block ? "block" : "fluid", type, t.pos().toShortString(),
                            t.triggerTick(), t.triggerTick() - fnow, cx, cz,
                            level.dimension().identifier());
                    if (block) removedBlock++; else removedFluid++;
                } else {
                    if (block) removedBlock++; else removedFluid++;
                }
            }
            if (!dryRun) {
                LevelTicksIslandAccess<?> access = (LevelTicksIslandAccess<?>)
                        (block ? level.getBlockTicks() : level.getFluidTicks());
                ScheduledTick<?> head = c.peek();
                if (head != null) {
                    access.fabrolia$nextTickForContainer().put(chunkKey, head.triggerTick());
                } else {
                    access.fabrolia$nextTickForContainer().remove(chunkKey);
                }
            }
        }
        sb.append("\n ").append(dryRun ? "would remove" : "removed").append(": block=")
          .append(removedBlock).append(" fluid=").append(removedFluid);
        if (skippedFire > 0) {
            sb.append(" (skipped ").append(skippedFire)
              .append(" fire entr").append(skippedFire == 1 ? "y" : "ies")
              .append(" — add 'fire' to include)");
        }
        if (removedBlock + removedFluid + skippedFire == 0) {
            sb.append(" — clean");
        }
        return sb.toString();
    }

    /** Remove ONLY the listed suspect-stale entries at one position. Dry-run
     *  unless {@code confirm}; every removal logged with full identity. */
    public static String clearStale(ServerLevel level, BlockPos pos, boolean confirm) {
        List<Entry> es = entriesAt(level, pos);
        List<Entry> stale = es.stream().filter(e -> e.rel() > SUSPECT_FUTURE).toList();
        if (stale.isEmpty()) {
            return "no suspect-stale entries at " + pos.toShortString()
                    + " (" + es.size() + " entr" + (es.size() == 1 ? "y" : "ies") + " total; see inspect)";
        }
        StringBuilder sb = new StringBuilder(confirm ? "REMOVED:" : "DRY-RUN (append 'confirm' to apply):");
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        for (Entry e : stale) {
            sb.append("\n ").append(e.block() ? "BLOCK" : "FLUID").append(' ').append(e.type())
              .append(" trigger=").append(e.trigger()).append(" (+").append(e.rel()).append(")");
            if (confirm) {
                LevelChunkTicks<?> c = container(level, e.block(), chunkKey);
                if (c != null) {
                    int removed = TickDomains.drainRemoveIfErased(c, t -> t.pos().equals(pos)
                            && t.triggerTick() == e.trigger()
                            && t.subTickOrder() == e.subTick());
                    if (removed > 0) TickRepair.markDirty(level, chunkKey);
                }
                org.slf4j.LoggerFactory.getLogger("Fabrolia-TickDomains").warn(
                        "[ticks clear-stale] operator removed {} {} at {} trigger={} (+{} vs {})",
                        e.block() ? "block" : "fluid", e.type(), pos.toShortString(),
                        e.trigger(), e.rel(), e.domain());
            }
        }
        return sb.toString();
    }

    /** Operator-requested reschedule of the CURRENT block+fluid at pos with a
     *  vanilla scheduleTick (valid recovery rule: the block decides its own
     *  behavior when its tick fires; nothing is synthesized for absent
     *  blocks). */
    public static String reschedule(ServerLevel level, BlockPos pos, int delay) {
        var state = level.getBlockState(pos);
        var fluid = level.getFluidState(pos);
        StringBuilder sb = new StringBuilder(96);
        long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (!state.isAir()) {
            boolean pending = level.getBlockTicks().hasScheduledTick(pos, state.getBlock());
            level.scheduleTick(pos, state.getBlock(), delay);
            if (!pending && level.getBlockTicks().hasScheduledTick(pos, state.getBlock())) {
                TickRepair.markDirty(level, chunkKey);
            }
            sb.append("scheduled block ").append(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
              .append(" +").append(delay);
        }
        if (!fluid.isEmpty()) {
            boolean pending = level.getFluidTicks().hasScheduledTick(pos, fluid.getType());
            level.scheduleTick(pos, fluid.getType(), delay);
            if (!pending && level.getFluidTicks().hasScheduledTick(pos, fluid.getType())) {
                TickRepair.markDirty(level, chunkKey);
            }
            sb.append(sb.isEmpty() ? "" : "; ").append("scheduled fluid ")
              .append(BuiltInRegistries.FLUID.getKey(fluid.getType())).append(" +").append(delay);
        }
        if (sb.isEmpty()) sb.append("nothing to schedule (air, no fluid)");
        org.slf4j.LoggerFactory.getLogger("Fabrolia-TickDomains").warn(
                "[ticks reschedule] operator: {} at {}", sb, pos.toShortString());
        return sb.toString();
    }
}
