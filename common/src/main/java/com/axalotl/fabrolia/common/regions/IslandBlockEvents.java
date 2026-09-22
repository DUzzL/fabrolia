package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
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
 * posts retain their shared-partition/next-unit eligibility boundary. One
 * level-wide pending identity spans every queue until the native dequeue;
 * ordering eligible inputs never makes an external input eligible early.
 *
 * <p>The chunk gate is memoized per chunk per pass (same rationale as the BE
 * slices: main's pump mutates tickets concurrently; one coherent gate read
 * per chunk restores vanilla's whole-phase coherence).
 */
public final class IslandBlockEvents {

    private IslandBlockEvents() {}

    /** All methods use the level's existing block-event lock. Queue moves retain identity. */
    public static final class Publications {
        private long nextOrder = 1L;
        private final Object2LongOpenHashMap<BlockEventData> orders = new Object2LongOpenHashMap<>();
        private final Object2LongOpenHashMap<BlockEventData> carriedGens = new Object2LongOpenHashMap<>();

        long reserve(BlockEventData ev) {
            if (orders.containsKey(ev)) return 0L;
            long order = nextOrder++;
            orders.put(ev, order);
            return order;
        }

        public boolean add(ObjectLinkedOpenHashSet<BlockEventData> events, BlockEventData ev) {
            if (reserve(ev) == 0L) return false;
            if (!events.add(ev)) throw new IllegalStateException("shared block event bypassed publication");
            return true;
        }

        public void remove(BlockEventData ev) {
            orders.removeLong(ev);
            carriedGens.removeLong(ev);
        }

        private void requirePending(BlockEventData ev, long order) {
            if (order == 0L || orders.getLong(ev) != order) {
                throw new IllegalStateException("block event lost its exact pending publication");
            }
        }

        private void cancel(BlockEventData ev, long order) {
            if (orders.getLong(ev) == order) remove(ev);
        }

        private IslandRegistry.PIsland.StampedEvent transfer(BlockEventData ev, long mainGen) {
            long order = orders.getLong(ev);
            requirePending(ev, order);
            long gen = carriedGens.containsKey(ev) ? carriedGens.removeLong(ev) : mainGen;
            return new IslandRegistry.PIsland.StampedEvent(ev, gen, order, false);
        }
    }

    /** One exclusively owned capture object, also borrowed by routed Main callbacks. */
    static final class OrderedEvents {
        private ObjectLinkedOpenHashSet<BlockEventData> events = new ObjectLinkedOpenHashSet<>();
        private Object2LongOpenHashMap<BlockEventData> orders = new Object2LongOpenHashMap<>();

        private void add(BlockEventData ev, long order) {
            if (events.add(ev)) orders.put(ev, order);
        }

        boolean isEmpty() { return events.isEmpty(); }

        private BlockEventData removeFirst() {
            BlockEventData ev = events.removeFirst();
            orders.removeLong(ev);
            return ev;
        }

        private void cancel(Publications publications) {
            for (BlockEventData ev : events) publications.cancel(ev, orders.getLong(ev));
            events.clear();
            orders.clear();
        }

        /** Both inputs are ordered; preserve this object's identity for routed borrowers. */
        private void merge(ArrayList<IslandRegistry.PIsland.StampedEvent> pending) {
            if (pending == null || pending.isEmpty()) return;
            if (events.isEmpty()) {
                for (var event : pending) add(event.ev(), event.order());
                return;
            }
            OrderedEvents merged = new OrderedEvents();
            var iterator = events.iterator();
            BlockEventData local = iterator.hasNext() ? iterator.next() : null;
            for (var event : pending) {
                while (local != null && orders.getLong(local) < event.order()) {
                    merged.add(local, orders.getLong(local));
                    local = iterator.hasNext() ? iterator.next() : null;
                }
                merged.add(event.ev(), event.order());
            }
            while (local != null) {
                merged.add(local, orders.getLong(local));
                local = iterator.hasNext() ? iterator.next() : null;
            }
            events = merged.events;
            orders = merged.orders;
        }
    }

    public static boolean publishShared(ServerLevel level, BlockEventData ev) {
        BlockEventQueueAccess access = (BlockEventQueueAccess) level;
        synchronized (access.fabrolia$blockEventLock()) {
            return access.fabrolia$blockEventPublications().add(access.fabrolia$blockEvents(), ev);
        }
    }

    private static void publishCapture(ServerLevel level, OrderedEvents capture, BlockEventData ev) {
        BlockEventQueueAccess access = (BlockEventQueueAccess) level;
        synchronized (access.fabrolia$blockEventLock()) {
            long order = access.fabrolia$blockEventPublications().reserve(ev);
            if (order != 0L) capture.add(ev, order);
        }
    }

    /** Cold topology/abort transfer: neither eligibility nor pending identity is reissued. */
    public static void restoreShared(ServerLevel level, IslandRegistry.PIsland.StampedEvent event) {
        BlockEventQueueAccess access = (BlockEventQueueAccess) level;
        synchronized (access.fabrolia$blockEventLock()) {
            var events = access.fabrolia$blockEvents();
            var publications = access.fabrolia$blockEventPublications();
            BlockEventData ev = event.ev();
            publications.requirePending(ev, event.order());
            if (events.contains(ev)) return;
            if (event.stampOnCarry()) publications.carriedGens.removeLong(ev);
            else publications.carriedGens.put(ev, event.gen());
            if (events.isEmpty() || publications.orders.getLong(events.last()) < event.order()) {
                events.add(ev);
                return;
            }
            var ordered = new ObjectLinkedOpenHashSet<BlockEventData>(events.size() + 1);
            boolean inserted = false;
            for (BlockEventData pending : events) {
                if (!inserted && publications.orders.getLong(pending) > event.order()) {
                    ordered.add(ev);
                    inserted = true;
                }
                ordered.add(pending);
            }
            if (!inserted) ordered.add(ev);
            events.clear();
            events.addAll(ordered);
        }
    }

    /** Unit-local capture (2026-07-16 generation fix): while a unit's
     *  scheduled+event segments run on the coordinator, its OWN posts land
     *  here (same-drain recursion = vanilla drain-until-empty) instead of
     *  the shared set — the old mid-drain shared-set re-extraction also
     *  swallowed events posted CONCURRENTLY by other contexts, executing
     *  them up to a tick early (production: two piston banks collapsed
     *  into one apparent motion → geometric portal miss). Posts from other
     *  threads/segments take the vanilla shared-set path → next partition. */
    static final ThreadLocal<OrderedEvents> UNIT_LOCAL = ThreadLocal.withInitial(OrderedEvents::new);
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
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland ctx = null;
        if (CAPTURING.get()) {
            ctx = CAPTURING_ISLAND.get();
        } else if (t instanceof RegionWorkerThread w) {
            EpochDomain d = w.currentRegion != null && w.currentRegion.domain != null
                    ? w.currentRegion.domain : w.currentDomain;
            ctx = d != null ? d.owner : null;
        } else if (rs != null) {
            ctx = IslandCommands.routedUnitSourceOnMain(rs);
        }
        if (ctx == null || ctx.retired) {
            return false; // main/global context: shared set → partition → stamp
        }
        // POSITION ownership (vanilla partition semantics): only this
        // island's own positions may bypass the shared set. Cross-island
        // posts (seam machines) go through main's partition to the true
        // owner — routing by posting-thread context sent a caravan's
        // seam events to the wrong island's queue (train stall).
        if (rs == null || ownerIsland(rs, ev) != ctx) {
            return false;
        }
        if (CAPTURING.get()) {
            publishCapture(level, UNIT_LOCAL.get(), ev);
        } else {
            // A routed command keeps its existing scheduled/event same-drain
            // capture. Later entity/BE posts wait for the source island's next
            // unit, never Main's stamp, which could delay a lagged island again.
            var sourceCapture = IslandCommands.routedUnitEventCaptureOnMain(rs);
            if (sourceCapture != null) publishCapture(level, sourceCapture, ev);
            else ctx.stashNextUnitEvent(ev);
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
        while (!local.isEmpty()) {
            BlockEventData ev = local.events.first();
            long order = local.orders.getLong(ev);
            local.removeFirst();
            // Aborted captures acquire their Main stamp at partition, not on this worker.
            restoreShared(level, new IslandRegistry.PIsland.StampedEvent(ev, 0L, order, true));
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
        // An island-only wave is queue metadata, not a global native producer.
        // If any residual event exists, leave the entire wave in its real queue
        // until global admission succeeds; a refused fence cannot orphan a detach.
        boolean needsGlobal = RegionScheduler.callSuccessBooleanIfHealthy(
                () -> stashOwnedWaveOrRequireGlobal(level, rs));
        if (needsGlobal) {
            RegionScheduler.runGlobalMainBody(level.getServer(),
                    () -> RegionScheduler.runSuccessBodyIfHealthy(
                            () -> runPartitionedEntered(level, rs)));
        }
        return true;
    }

    private static boolean stashOwnedWaveOrRequireGlobal(
            ServerLevel level, RegionizedServer rs) {
        BlockEventQueueAccess access = (BlockEventQueueAccess) level;
        synchronized (access.fabrolia$blockEventLock()) {
            ObjectLinkedOpenHashSet<BlockEventData> events = access.fabrolia$blockEvents();
            for (BlockEventData event : events) {
                if (ownerIsland(rs, event) == null) return true;
            }
            // Topology publication is Main-owned. No callback runs between this
            // classification and the metadata transfer of this exact locked wave.
            var iterator = events.iterator();
            while (iterator.hasNext()) {
                BlockEventData event = iterator.next();
                ownerIsland(rs, event).stashStamped(
                        access.fabrolia$blockEventPublications().transfer(event, level.getGameTime()));
                iterator.remove();
            }
        }
        return false;
    }

    /** Caller owns the sole W-vs-entry cut for this complete Main wave drain. */
    private static void runPartitionedEntered(ServerLevel level, RegionizedServer rs) {
        BlockEventQueueAccess acc = (BlockEventQueueAccess) level;
        Object lock = acc.fabrolia$blockEventLock();
        ObjectLinkedOpenHashSet<BlockEventData> set = acc.fabrolia$blockEvents();
        Publications publications = acc.fabrolia$blockEventPublications();
        ArrayList<BlockEventData> resched = new ArrayList<>();
        while (true) {
            long waveEnd;
            synchronized (lock) {
                if (set.isEmpty()) break;
                waveEnd = publications.orders.getLong(set.last());
                // Partition before native callbacks, but retain the pending global suffix.
                var iterator = set.iterator();
                while (iterator.hasNext()) {
                    BlockEventData ev = iterator.next();
                    IslandRegistry.PIsland owner = ownerIsland(rs, ev);
                    if (owner != null) {
                        owner.stashStamped(publications.transfer(ev, level.getGameTime()));
                        iterator.remove();
                    }
                }
            }
            boolean ranAny = false;
            try {
                while (true) {
                    BlockEventData ev;
                    synchronized (lock) {
                        if (set.isEmpty() || publications.orders.getLong(set.first()) > waveEnd) break;
                        ev = set.removeFirst();
                        publications.remove(ev);
                    }
                    if (level.shouldTickBlocksAt(ev.pos())) {
                        ranAny = true;
                        if (level.doBlockEvent(ev)) broadcast(level, ev);
                    } else {
                        resched.add(ev);
                    }
                }
            } catch (Throwable primary) {
                RegionScheduler.publishFatalAndSelectWinner(primary);
                try {
                    synchronized (lock) {
                        var iterator = set.iterator();
                        while (iterator.hasNext()) {
                            BlockEventData ev = iterator.next();
                            if (publications.orders.getLong(ev) <= waveEnd) {
                                publications.remove(ev);
                                iterator.remove();
                            }
                        }
                    }
                } catch (Throwable cleanup) {
                    RegionScheduler.publishFatalAndSelectWinner(cleanup);
                }
                throw primary;
            }
            if (!ranAny) break;
        }
        for (BlockEventData ev : resched) publishShared(level, ev);
    }

    /**
     * Unit coordinator: freeze external eligibility once, then drain the ordered
     * capture through recursive callbacks without detaching its pending suffix.
     */
    static void runIslandEvents(RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain d) {
        // The island stash, next-unit queue and unit-local recursion are three
        // sources of the same vanilla block-event wave.  Admit their detach and
        // the complete event body once; per-member admissions after copy+clear
        // can strand the unvisited suffix when a peer W wins.
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            try {
                runIslandEventsEntered(rs, island, d);
            } catch (Throwable primary) {
                RegionScheduler.publishFatalAndSelectWinner(primary);
                try {
                    BlockEventQueueAccess access = (BlockEventQueueAccess) rs.level;
                    synchronized (access.fabrolia$blockEventLock()) {
                        UNIT_LOCAL.get().cancel(access.fabrolia$blockEventPublications());
                    }
                } catch (Throwable cleanup) {
                    RegionScheduler.publishFatalAndSelectWinner(cleanup);
                }
                throw primary;
            }
        });
    }

    /** Caller owns the sole W-vs-entry cut for this complete island wave drain. */
    private static void runIslandEventsEntered(
            RegionizedServer rs, IslandRegistry.PIsland island, EpochDomain d) {
        ServerLevel level = rs.level;
        var local = UNIT_LOCAL.get();
        local.merge(island.drainEventsEntered());
        if (local.isEmpty()) return;
        BlockEventQueueAccess access = (BlockEventQueueAccess) level;
        Long2ByteOpenHashMap tickable = new Long2ByteOpenHashMap(16);
        ArrayList<BlockEventData> resched = new ArrayList<>();
        int executed = 0;
        while (!local.isEmpty()) {
            BlockEventData ev;
            synchronized (access.fabrolia$blockEventLock()) {
                ev = local.events.first();
                Publications publications = access.fabrolia$blockEventPublications();
                publications.requirePending(ev, local.orders.getLong(ev));
                local.removeFirst();
                publications.remove(ev);
            }
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
        if (!resched.isEmpty()) {
            // A native retry is a new insertion after the complete current drain.
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
