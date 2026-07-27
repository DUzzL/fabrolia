package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task #32 — production-path coverage of the per-player packet lane
 * ({@link RegionalPlayerTicks}): strict arrival-order application across
 * venues, MAIN-only sequencing, the routed-apply reentrancy guard, owner
 * migration (retired island → packets re-drain at the new venue, never lost,
 * never doubled) and logout teardown. Real {@code routePacket} /
 * {@code drainLane} / {@code scheduleDrain} / island-lane plumbing; the
 * player is a constructor-bypassed {@link ServerPlayer} overriding only what
 * the lane reads (the TickDomainsProductionPathTest harness idiom).
 */
class PacketLaneProductionPathTest {

    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    static class LaneLevel extends ServerLevel {
        TickRateManager fabricatedTickRate;

        private LaneLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static LaneLevel alloc() {
            try {
                LaneLevel l = (LaneLevel) UNSAFE.allocateInstance(LaneLevel.class);
                l.fabricatedTickRate = new TickRateManager();
                return l;
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public TickRateManager tickRateManager() { return fabricatedTickRate; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public long getGameTime() { return 1_000L; }
    }

    static class LanePlayer extends ServerPlayer {
        LaneLevel fabricatedLevel;
        // NOTE: Unsafe.allocateInstance skips field initializers — set in alloc().
        volatile boolean fabricatedAlive;

        private LanePlayer() {
            super(null, null, null, null);
            throw new AssertionError("never constructed — allocated via Unsafe");
        }

        static LanePlayer alloc(LaneLevel level) throws ReflectiveOperationException {
            LanePlayer p;
            try {
                p = (LanePlayer) UNSAFE.allocateInstance(LanePlayer.class);
            } catch (InstantiationException e) {
                throw new IllegalStateException(e);
            }
            p.fabricatedLevel = level;
            p.fabricatedAlive = true;
            setEntityField(p, "uuid", UUID.randomUUID());
            setEntityField(p, "type", net.minecraft.world.entity.EntityTypes.PLAYER);
            setEntityField(p, "chunkPosition", new ChunkPos(5, 5));
            ServerPlayerGameMode gm = (ServerPlayerGameMode)
                    UNSAFE.allocateInstance(ServerPlayerGameMode.class);
            Field gmField = ServerPlayerGameMode.class.getDeclaredField("gameModeForPlayer");
            gmField.setAccessible(true);
            gmField.set(gm, GameType.SURVIVAL);
            Field pgm = ServerPlayer.class.getDeclaredField("gameMode");
            pgm.setAccessible(true);
            pgm.set(p, gm);
            return p;
        }

        /** isRemoved() is final — drive it through the real removalReason field. */
        void markRemoved() throws ReflectiveOperationException {
            setEntityField(this, "removalReason",
                    net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        }

        private static void setEntityField(Object target, String name, Object value)
                throws ReflectiveOperationException {
            Field f = net.minecraft.world.entity.Entity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }

        @Override public ServerLevel level() { return fabricatedLevel; }
        @Override public boolean isAlive() { return fabricatedAlive; }
    }

    private static void setStatic(Class<?> owner, String name, Object value)
            throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStatic(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private LaneLevel level;
    private LanePlayer player;
    private RegionizedServer rs;
    private final List<Runnable> mainQueue = new ArrayList<>();
    private Consumer<Runnable> savedMainExec;

    @BeforeEach
    void setUp() throws Exception {
        level = LaneLevel.alloc();
        player = LanePlayer.alloc(level);

        rs = (RegionizedServer) UNSAFE.allocateInstance(RegionizedServer.class);
        rs.asyncActive = true;
        // owner left null on purpose: regionForGridKey's doneEpoch stamping
        // consults rs.domainForCreation, which needs a fully built server —
        // the lane tests assign region domains by hand instead.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
        Field rf = RegionizedServer.class.getDeclaredField("regionizer");
        rf.setAccessible(true);
        rf.set(rs, regionizer);

        setStatic(RegionScheduler.class, "active", true);
        PacketLaneProductionPathTest.<java.util.Map<ServerLevel, RegionizedServer>>getStatic(
                RegionScheduler.class, "PER_LEVEL").put(level, rs);

        FabroliaConfig.regionalPlayerTicksExperimental = true;
        savedMainExec = RegionalPlayerTicks.MAIN_EXEC;
        RegionalPlayerTicks.MAIN_EXEC = mainQueue::add;
    }

    @AfterEach
    void tearDown() throws Exception {
        RegionalPlayerTicks.MAIN_EXEC = savedMainExec;
        FabroliaConfig.regionalPlayerTicksExperimental = false;
        RegionalPlayerTicks.forget(player);
        PacketLaneProductionPathTest.<java.util.Map<ServerLevel, RegionizedServer>>getStatic(
                RegionScheduler.class, "PER_LEVEL").clear();
        setStatic(RegionScheduler.class, "active", false);
        mainQueue.clear();
    }

    private void runMainQueue() {
        // scheduleDrain coalesces poorly on purpose here — run everything;
        // drainLane's single-consumer flag makes extra runs no-ops.
        List<Runnable> tasks = new ArrayList<>(mainQueue);
        mainQueue.clear();
        tasks.forEach(Runnable::run);
    }

    private IslandRegistry.PIsland islandAtPlayerChunk() {
        ThreadedRegionizer regionizer = rs.regionizer();
        Region r = regionizer.regionForGridKey(
                regionizer.grid.keyFor(player.chunkPosition().pack()));
        EpochDomain d = new EpochDomain();
        d.owner = new IslandRegistry.PIsland(31, 0);
        d.unitGameTime = 1;
        r.domain = d;
        return d.owner;
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    /** Requirement #1: for one player, simulation packets apply strictly in
     *  arrival order — including MAIN-only (container) items sequenced
     *  through the same lane. */
    @Test
    void packetsApplyInArrivalOrderOnMainVenue() {
        List<String> applied = new ArrayList<>();
        assertTrue(RegionalPlayerTicks.routePacket(player, "move", () -> applied.add("move")));
        assertTrue(RegionalPlayerTicks.routePacket(player, "useItemOn", () -> applied.add("useItemOn")));
        assertTrue(RegionalPlayerTicks.routePacket(player, "containerClick", true, () -> applied.add("containerClick")));
        assertTrue(RegionalPlayerTicks.routePacket(player, "attack", () -> applied.add("attack")));

        runMainQueue();
        assertEquals(List.of("move", "useItemOn", "containerClick", "attack"), applied,
                "lane must preserve arrival order across region and MAIN-only items");
    }

    /** A MAIN-only item met on a worker stops the worker drain and replays
     *  the REMAINDER on main — order intact, nothing doubled. */
    @Test
    void mainOnlyItemReschedulesRemainderPreservingOrder() throws Exception {
        List<String> applied = new ArrayList<>();
        assertTrue(RegionalPlayerTicks.routePacket(player, "move",
                () -> applied.add("move@" + venue())));
        assertTrue(RegionalPlayerTicks.routePacket(player, "containerClick", true,
                () -> applied.add("click@" + venue())));
        assertTrue(RegionalPlayerTicks.routePacket(player, "useItem",
                () -> applied.add("useItem@" + venue())));
        mainQueue.clear(); // discard the pre-scheduled drains; drive by hand

        RegionalPlayerTicks.Sim sim = RegionalPlayerTicks.sim(player);
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            try {
                RegionalPlayerTicks.drainLane(player, sim);
            } catch (Throwable t) {
                err.set(t);
            }
        }, "fabrolia-test-lane-worker");
        worker.start();
        worker.join(10_000);
        assertFalse(worker.isAlive());
        if (err.get() != null) throw new AssertionError(err.get());

        assertEquals(List.of("move@worker"), applied,
                "worker venue must stop at the MAIN-only item");
        assertEquals(1, mainQueue.size(), "remainder must be rescheduled on main");
        runMainQueue();
        assertEquals(List.of("move@worker", "click@main", "useItem@main"), applied,
                "MAIN-only item and the remainder apply on main, order intact");
    }

    private static String venue() {
        return Thread.currentThread() instanceof RegionWorkerThread ? "worker" : "main";
    }

    /** Task #32 reentrancy guard: a handler re-entered DURING a routed apply
     *  (main-venue drain replays through the mixin head) must run inline —
     *  re-routing would move it to the lane tail, reordering it behind
     *  later-arrived packets. */
    @Test
    void handlerReenteredDuringRoutedApplyRunsInline() {
        List<String> applied = new ArrayList<>();
        assertTrue(RegionalPlayerTicks.routePacket(player, "useItemOn", () -> {
            applied.add("useItemOn");
            // the main-venue replay hits the routing mixin head again:
            boolean rerouted = RegionalPlayerTicks.routePacket(player, "nested", () -> applied.add("nested"));
            assertFalse(rerouted, "routePacket inside a routed apply must decline (inline)");
            applied.add("nested-inline");
        }));
        assertTrue(RegionalPlayerTicks.routePacket(player, "move", () -> applied.add("move")));

        runMainQueue();
        assertEquals(List.of("useItemOn", "nested-inline", "move"), applied,
                "re-entered handler must not be pushed behind later packets");
    }

    // ------------------------------------------------------------------
    // Owner migration / logout
    // ------------------------------------------------------------------

    /** Requirement #2: queued packets survive an owner change. The island
     *  drain task dies with the retired island, but the LANE keeps the items
     *  and the next schedule re-resolves the venue — exactly once, in order. */
    @Test
    void queuedPacketsSurviveOwnerRetirement() throws Exception {
        IslandRegistry.PIsland owner = islandAtPlayerChunk();
        List<String> applied = new ArrayList<>();

        assertTrue(RegionalPlayerTicks.routePacket(player, "useItemOn", () -> applied.add("useItemOn")));
        synchronized (owner.pendingIslandTasks) {
            assertEquals(1, owner.pendingIslandTasks.size(),
                    "drain must be scheduled on the owner island's lane");
        }
        assertEquals(0, mainQueue.size());

        // Topology change: the island retires with the drain task queued.
        owner.retired = true;

        // The next packet re-resolves the owner (retired → main fallback).
        assertTrue(RegionalPlayerTicks.routePacket(player, "attack", () -> applied.add("attack")));
        assertEquals(1, mainQueue.size(), "retired owner must fall back to main");
        runMainQueue();
        assertEquals(List.of("useItemOn", "attack"), applied,
                "both queued packets re-drain at the new venue, in order");

        // The stale island task must be harmless (single-consumer lane).
        AtomicReference<Throwable> err = new AtomicReference<>();
        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            try {
                CommandShards.serviceIslandTasks(owner);
            } catch (Throwable t) {
                err.set(t);
            }
        }, "fabrolia-test-stale-coordinator");
        worker.currentDomain = null;
        worker.start();
        worker.join(10_000);
        if (err.get() != null) throw new AssertionError(err.get());
        assertEquals(List.of("useItemOn", "attack"), applied,
                "stale island drain must not double-apply");
    }

    /** Requirement: logout with queued packets — the drain stops, disconnect
     *  is the one terminal drop (vanilla semantics), teardown forgets state. */
    @Test
    void logoutStopsDrainAndForgetDropsState() throws Exception {
        List<String> applied = new ArrayList<>();
        assertTrue(RegionalPlayerTicks.routePacket(player, "useItemOn", () -> applied.add("useItemOn")));
        mainQueue.clear();

        player.markRemoved();
        player.fabricatedAlive = false;
        RegionalPlayerTicks.drainLane(player, RegionalPlayerTicks.sim(player));
        assertEquals(List.of(), applied, "no application after removal");

        RegionalPlayerTicks.forget(player);
        java.util.Map<UUID, ?> sims = getStatic(RegionalPlayerTicks.class, "SIMS");
        assertFalse(sims.containsKey(player.getUUID()), "forget must drop the sim record");
    }
}
