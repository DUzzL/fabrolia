package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;

/**
 * A2 increment 3 — island-partitioned block events (docs/ASYNC-ISLANDS.md).
 *
 * <p>Vanilla runs ONE queue in its block-event slot: drain in insertion order,
 * gate each event on {@code shouldTickBlocksAt}, execute + broadcast, keep
 * non-tickable events for next tick, and keep draining until empty so events
 * posted by events run the same tick. With async islands, the slot's main
 * drain handles only GLOBAL-domain events; events owned by an island are
 * stashed onto it (in queue order) and processed by its unit coordinator
 * BEFORE its entity phase — the vanilla island-relative order
 * ([scheduled → events → entities → BEs]) holds because only the island's own
 * state can observe the difference (separation invariant). Events posted
 * DURING a unit's scheduled/event window stay in its ordered capture. Other
 * posts retain their shared-partition/next-unit eligibility boundary.
 *
 * <p>The chunk gate is memoized per chunk per pass (same rationale as the BE
 * slices: main's pump mutates tickets concurrently; one coherent gate read
 * per chunk restores vanilla's whole-phase coherence).
 */
public final class IslandBlockEvents {

    private IslandBlockEvents() {}

    /** Unit-local capture (2026-07-16 generation fix): while a unit's
     *  scheduled+event segments run on the coordinator, its OWN posts land
     *  here (same-drain recursion = vanilla drain-until-empty) instead of
     *  the shared set — the old mid-drain shared-set re-extraction also
     *  swallowed events posted CONCURRENTLY by other contexts, executing
     *  them up to a tick early (production: two piston banks collapsed
     *  into one apparent motion → geometric portal miss). Posts from other
     *  threads/segments take the vanilla shared-set path → next partition. */
    static final ThreadLocal<ObjectLinkedOpenHashSet<BlockEventData>> UNIT_LOCAL =
            ThreadLocal.withInitial(ObjectLinkedOpenHashSet::new);
    static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);
    /** The island whose unit is capturing on this thread — ownership gate:
     *  vanilla partitions BY POSITION, so only posts for THIS island's own
     *  positions may join its drain (a seam machine's cross-island posts
     *  must take the shared set → partition → the true owner's stash). */
    static final ThreadLocal<IslandRegistry.PIsland> CAPTURING_ISLAND = new ThreadLocal<>();

    private static ArrayList<BlockEventData> drainUnitLocalEntered(
            ObjectLinkedOpenHashSet<BlockEventData> local) {
        if (local.isEmpty()) return null;
        ArrayList<BlockEventData> drained = new ArrayList<>(local);
        local.clear();
        return drained;
    }

    /** blockEvent add hook: true = captured (either unit-locally for the
     *  coordinator's scheduled/event window = same-drain vanilla recursion,
     *  or island next-unit for the island's own later-phase posts = vanilla
     *  next-tick in ISLAND time, never via main's partition/stamp). */
    public static boolean captureLocal(ServerLevel level, BlockEventData ev) {
        Thread t = Thread.currentThread();
        IslandRegistry.PIsland ctx = null;
        if (CAPTURING.get()) {
            ctx = CAPTURING_ISLAND.get();
        } else if (t instanceof RegionWorkerThread w) {
            EpochDomain d = w.currentRegion != null && w.currentRegion.domain != null
                    ? w.currentRegion.domain : w.currentDomain;
            ctx = d != null ? d.owner : null;
        }
        if (ctx == null || ctx.retired) {
            return false; // main/global context: shared set → partition → stamp
        }
        // POSITION ownership (vanilla partition semantics): only this
        // island's own positions may bypass the shared set. Cross-island
        // posts (seam machines) go through main's partition to the true
        // owner — routing by posting-thread context sent a caravan's
        // seam events to the wrong island's queue (train stall).
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || ownerIsland(rs, ev) != ctx) {
            return false;
        }
        if (CAPTURING.get()) {
            UNIT_LOCAL.get().add(ev);
        } else {
            ctx.stashNextUnitEvent(ev);
        }
        return true;
    }

    public static void beginUnitCapture(IslandRegistry.PIsland island) {
        CAPTURING.set(Boolean.TRUE);
        CAPTURING_ISLAND.set(island);
    }

    /** End capture; any leftovers (unit aborted before its event segment)
     *  flush to the shared set so a reused coordinator thread can never
     *  leak one island's events into another island's drain. */
    public static void endUnitCapture(ServerLevel level) {
        CAPTURING.set(Boolean.FALSE);
        CAPTURING_ISLAND.remove();
        var local = UNIT_LOCAL.get();
        if (local.isEmpty()) return;
        BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
        synchronized (acc.fabrolia$blockEventLock()) {
            acc.fabrolia$blockEvents().addAll(local);
        }
        local.clear();
    }

    /**
     * Main thread, the vanilla block-event slot. @return true — handled
     * (global events processed, island events stashed to their units);
     * false — async off, caller runs the vanilla body.
     */
    public static boolean runPartitioned(ServerLevel level) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) {
            return false;
        }
        RegionScheduler.runSuccessBodyIfHealthy(() -> runPartitionedEntered(level, rs));
        return true;
    }

    private static void runPartitionedEntered(ServerLevel level, RegionizedServer rs) {
        BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
        Object lock = acc.fabrolia$blockEventLock();
        ObjectLinkedOpenHashSet<BlockEventData> set = acc.fabrolia$blockEvents();
        ArrayList<BlockEventData> resched = new ArrayList<>();
        boolean ranAny;
        do {
            ArrayList<BlockEventData> wave;
            synchronized (lock) {
                if (set.isEmpty()) {
                    wave = null;
                } else {
                    wave = new ArrayList<>(set);
                    set.clear();
                }
            }
            if (wave == null) break;
            ArrayList<BlockEventData> globals = new ArrayList<>();
            for (int i = 0, n = wave.size(); i < n; i++) {
                BlockEventData ev = wave.get(i);
                IslandRegistry.PIsland owner = ownerIsland(rs, ev);
                if (owner != null) {
                    owner.stashBlockEvent(ev, level.getGameTime());
                } else {
                    globals.add(ev);
                }
            }
            if (globals.isEmpty()) break;
            ranAny = false;
            for (int i = 0, n = globals.size(); i < n; i++) {
                BlockEventData ev = globals.get(i);
                if (level.shouldTickBlocksAt(ev.pos())) {
                    ranAny = true;
                    if (level.doBlockEvent(ev)) broadcast(level, ev);
                } else {
                    resched.add(ev);
                }
            }
        } while (ranAny);
        for (int i = 0, n = resched.size(); i < n; i++) {
            BlockEventData ev = resched.get(i);
            synchronized (lock) {
                set.add(ev);
            }
        }
    }

    static void runIslandEvents(RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain d) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> runIslandEventsEntered(rs, island, d));
    }

    private static void runIslandEventsEntered(
            RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain d) {
        ServerLevel level = rs.level;
        ArrayList<IslandRegistry.PIsland.StampedEvent> stamped = island.drainDueEventsEntered(d.unitGameTime);
        ObjectLinkedOpenHashSet<BlockEventData> merged = new ObjectLinkedOpenHashSet<>();
        if (stamped != null) {
            for (int i = 0; i < stamped.size(); i++) {
                merged.add(stamped.get(i).ev());
            }
        }
        ArrayList<BlockEventData> nextUnit = island.drainNextUnitEventsEntered();
        if (nextUnit != null) {
            for (int i = 0; i < nextUnit.size(); i++) {
                merged.add(nextUnit.get(i));
            }
        }
        var local = UNIT_LOCAL.get();
        ArrayList<BlockEventData> captured = drainUnitLocalEntered(local);
        if (captured != null) {
            for (int i = 0; i < captured.size(); i++) {
                merged.add(captured.get(i));
            }
        }
        ArrayList<BlockEventData> mine = merged.isEmpty() ? null : new ArrayList<>(merged);
        Long2ByteOpenHashMap tickable = new Long2ByteOpenHashMap(16);
        ArrayList<BlockEventData> resched = new ArrayList<>();
        int executed = 0;
        while (mine != null && !mine.isEmpty()) {
            for (int i = 0, n = mine.size(); i < n; i++) {
                BlockEventData ev = mine.get(i);
                long chunkKey = ChunkPos.pack(ev.pos());
                byte gate = tickable.get(chunkKey);
                if (gate == 0) {
                    gate = level.shouldTickBlocksAt(ev.pos()) ? (byte) 1 : (byte) 2;
                    tickable.put(chunkKey, gate);
                }
                if (gate == 1) {
                    boolean shouldBroadcast = level.doBlockEvent(ev);
                    executed++;
                    if (shouldBroadcast) broadcast(level, ev);
                } else {
                    resched.add(ev);
                }
            }
            mine = drainUnitLocalEntered(local);
        }
        if (!resched.isEmpty()) {
            long retryGen = island.genForNextUnit(d.unitGameTime);
            for (int i = 0; i < resched.size(); i++) {
                BlockEventData ev = resched.get(i);
                island.restashBlockEvent(ev, retryGen);
            }
        }
        // DIAGNOSTIC (debug-gated): an abnormal pile of block events in one
        // unit = compressed pulses. Threshold 512 — a large TNT flying
        // machine legitimately runs 68-80 events per unit, every unit
        // (production 2026-07-16: the old 64 threshold flooded the log with
        // one full ring dump per minute during normal machine operation).
        if (FlightRecorder.DEBUG && executed > 512) {
            FlightRecorder.record("EVENT-BURST island#" + island.id
                    + " L=" + d.unitGameTime + " executed=" + executed);
            FlightRecorder.dump("block-event burst (" + executed
                    + ") on island#" + island.id);
        }
    }

    private static IslandRegistry.PIsland ownerIsland(RegionizedServer rs, BlockEventData ev) {
        Region r = regionFor(rs, ev);
        EpochDomain d = r != null ? r.domain : null;
        return d != null ? d.owner : null;
    }

    private static Region regionFor(RegionizedServer rs, BlockEventData ev) {
        ThreadedRegionizer reg = rs.regionizer();
        return reg.peekByGridKey(reg.grid.keyFor(ev.pos().getX() >> 4, ev.pos().getZ() >> 4));
    }

    private static void broadcast(ServerLevel level, BlockEventData ev) {
        level.getServer().getPlayerList().broadcast(null,
                ev.pos().getX(), ev.pos().getY(), ev.pos().getZ(), 64.0,
                level.dimension(),
                new ClientboundBlockEventPacket(ev.pos(), ev.block(), ev.paramA(), ev.paramB()));
    }
}
