package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Iterator;

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
 * DURING a unit's processing land in the shared set under the same lock; the
 * unit re-extracts its own until none remain (vanilla's drain-until-empty,
 * island-scoped). Events an island posts that vanilla mechanics cannot
 * produce (nothing crosses the gap) stay in the set for the next tick's
 * partition — never lost, never run on the wrong timeline.
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
            UNIT_LOCAL.get().add(ev); // set semantics: vanilla same-tick dedup
        } else {
            // island's own later-phase post (entity/BE segment, any member
            // worker): vanilla next-tick in ISLAND time — next unit's drain,
            // never via main's stamp (a lagged island would hold it extra
            // units: the production BE-finalization bank-B path).
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
        if (!local.isEmpty()) {
            BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
            synchronized (acc.fabrolia$blockEventLock()) {
                acc.fabrolia$blockEvents().addAll(local);
            }
            local.clear();
        }
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
        BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
        Object lock = acc.fabrolia$blockEventLock();
        ObjectLinkedOpenHashSet<BlockEventData> set = acc.fabrolia$blockEvents();
        ArrayList<BlockEventData> resched = null;
        while (true) {
            // Partition the current wave in insertion order: island-owned →
            // that island's stash (processed by its unit later this tick),
            // global-domain / regionless → processed here, vanilla-style.
            ArrayList<BlockEventData> globals = null;
            synchronized (lock) {
                if (set.isEmpty()) {
                    break;
                }
                Iterator<BlockEventData> it = set.iterator();
                while (it.hasNext()) {
                    BlockEventData ev = it.next();
                    IslandRegistry.PIsland owner = ownerIsland(rs, ev);
                    it.remove();
                    if (owner != null) {
                        owner.stashBlockEvent(ev, level.getGameTime());
                    } else {
                        if (globals == null) globals = new ArrayList<>();
                        globals.add(ev);
                    }
                }
            }
            if (globals == null) {
                break; // wave was island-only: nothing here can post new events
            }
            boolean ranAny = false;
            for (int i = 0, n = globals.size(); i < n; i++) {
                BlockEventData ev = globals.get(i);
                if (level.shouldTickBlocksAt(ev.pos())) {
                    ranAny = true;
                    if (level.doBlockEvent(ev)) {
                        broadcast(level, ev);
                    }
                } else {
                    if (resched == null) resched = new ArrayList<>();
                    resched.add(ev); // vanilla: retry next tick
                }
            }
            if (!ranAny) {
                break; // nothing executed → nothing new was posted
            }
        }
        if (resched != null && !resched.isEmpty()) {
            synchronized (lock) {
                set.addAll(resched);
            }
        }
        return true;
    }

    /**
     * Unit coordinator, before the island's entity phase: the island's stashed
     * events, then its own follow-ups from the shared set until none remain.
     */
    static void runIslandEvents(RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain d) {
        ServerLevel level = rs.level;
        // GENERATION-PRESERVING DRAIN (2026-07-16, production bisect
        // islandevents ON=broken / OFF=working): execute exactly ONE stash
        // generation (one vanilla tick's worth of main-partitioned events) +
        // this unit's OWN posts (captured unit-locally during the scheduled
        // segment) + same-drain recursion (posts made BY these executions on
        // this thread — vanilla drain-until-empty). The old loop re-scanned
        // the SHARED set mid-drain, swallowing (a) NEWER stash generations
        // when the island lagged main (two piston banks' ticks merged into
        // one drain) and (b) concurrent posts from other contexts (executed
        // up to a tick early) — both collapse vanilla's inter-bank tick
        // boundary; the machine's fallers then miss the portal geometrically.
        ArrayList<IslandRegistry.PIsland.StampedEvent> stamped = island.drainDueEvents(d.unitGameTime);
        // vanilla parity: the level queue is an insertion-ordered SET — one
        // execution per distinct event per drain, however many routes posted
        // it (main partition + island later-phase can race the same event;
        // the ArrayList merge double-fired pistons = self-break class).
        ObjectLinkedOpenHashSet<BlockEventData> merged = new ObjectLinkedOpenHashSet<>();
        if (stamped != null) {
            for (int i = 0; i < stamped.size(); i++) {
                merged.add(stamped.get(i).ev());
            }
        }
        ArrayList<BlockEventData> nextUnit = island.drainNextUnitEvents();
        if (nextUnit != null) {
            merged.addAll(nextUnit);
        }
        var local = UNIT_LOCAL.get();
        if (!local.isEmpty()) {
            merged.addAll(local);
            local.clear();
        }
        ArrayList<BlockEventData> mine = merged.isEmpty() ? null : new ArrayList<>(merged);
        Long2ByteOpenHashMap tickable = new Long2ByteOpenHashMap(16);
        ArrayList<BlockEventData> resched = null;
        int executed = 0;
        while (mine != null && !mine.isEmpty()) {
            for (int i = 0, n = mine.size(); i < n; i++) {
                BlockEventData ev = mine.get(i);
                long chunkKey = ChunkPos.pack(ev.pos());
                byte gate = tickable.get(chunkKey); // 0 unknown, 1 tick, 2 skip
                if (gate == 0) {
                    gate = level.shouldTickBlocksAt(ev.pos()) ? (byte) 1 : (byte) 2;
                    tickable.put(chunkKey, gate);
                }
                if (gate == 1) {
                    executed++;
                    if (level.doBlockEvent(ev)) {
                        broadcast(level, ev);
                    }
                } else {
                    if (resched == null) resched = new ArrayList<>();
                    resched.add(ev);
                }
            }
            // vanilla drain-until-empty: events posted BY those executions on
            // THIS thread were captured unit-locally — run them in the same
            // drain. Foreign concurrent posts stay in the shared set for the
            // next main partition (their own tick's phase).
            if (local.isEmpty()) {
                break;
            }
            mine = new ArrayList<>(local);
            local.clear();
        }
        if (resched != null && !resched.isEmpty()) {
            // vanilla parity: non-tickable events retry next phase, keeping
            // their own generation so they can never merge into a newer one.
            long retryGen = island.genForNextUnit(d.unitGameTime); // next unit under the map
            for (int i = 0; i < resched.size(); i++) {
                island.restashBlockEvent(resched.get(i), retryGen);
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
            FlightRecorder.dump("block-event burst (" + executed + ") on island#" + island.id);
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
