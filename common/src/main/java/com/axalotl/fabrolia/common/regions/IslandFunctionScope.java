package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Runtime ownership envelope of an owner-local function body (island head
 * partition pass, Main remainder pass, inline owner-local function on a
 * worker). The planner proves these bodies owner-local; the guards in this
 * scope turn any miss of that proof into a fail-stop instead of a race:
 * foreign entities/chunks, TimerQueue, command storage writes, team changes,
 * score creation for non-owned holders, unbounded selectors and live
 * PlayerList access.
 *
 * <p>The scope lives in a plain field of the executing thread (region worker)
 * or, for the one non-worker executor (physical Main), in a static field
 * owned by that thread: hot guards (chunk access) read no shared cache line.
 */
public final class IslandFunctionScope {

    /** Scope of the single non-worker executor (physical Main). */
    private static FunctionPartition nonWorkerScope;
    private static Thread nonWorkerThread;

    private IslandFunctionScope() {}

    /** The partition whose owner-local body runs on this thread, or null. */
    public static FunctionPartition current() {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) return w.functionScope;
        return t == nonWorkerThread ? nonWorkerScope : null;
    }

    public static void run(FunctionPartition partition, Runnable body) {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            FunctionPartition previous = w.functionScope;
            w.functionScope = partition;
            try {
                body.run();
            } finally {
                w.functionScope = previous;
            }
            return;
        }
        Thread previousThread = nonWorkerThread;
        if (previousThread != null && previousThread != t) {
            throw new IllegalStateException("owner-local function scope entered on " + t.getName()
                    + " while " + previousThread.getName() + " holds the non-worker scope");
        }
        FunctionPartition previous = nonWorkerScope;
        nonWorkerThread = t;
        nonWorkerScope = partition;
        try {
            body.run();
        } finally {
            nonWorkerScope = previous;
            nonWorkerThread = previous == null ? null : t;
        }
    }

    /** Leave the scope for a body that is NOT owner-local (fenced Main escalation). */
    public static void runUnscoped(Runnable body) {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            FunctionPartition previous = w.functionScope;
            w.functionScope = null;
            try {
                body.run();
            } finally {
                w.functionScope = previous;
            }
            return;
        }
        if (nonWorkerThread != t) {
            body.run();
            return;
        }
        FunctionPartition previous = nonWorkerScope;
        nonWorkerScope = null;
        try {
            body.run();
        } finally {
            nonWorkerScope = previous;
        }
    }

    /**
     * C2 inline owner-local reward: the same proof as C3 (exact object of the
     * active plan, current library object, read lease across the body), run
     * inside the enclosing pass's scope or the venue's own partition, which
     * must own {@code player}.
     *
     * @return false when not proven or not owned here — the caller routes it
     */
    public static boolean runOwnerLocalReward(MinecraftServer server,
                                              net.minecraft.server.level.ServerPlayer player,
                                              net.minecraft.commands.functions.CommandFunction<
                                                      net.minecraft.commands.CommandSourceStack> function,
                                              Runnable body) {
        return FunctionAnalyzer.withExactOwnerLocalProof(server.getFunctions(), function, proven -> {
            if (!proven) return false;
            FunctionPartition partition = current();
            if (partition == null) {
                partition = Thread.currentThread() instanceof RegionWorkerThread
                        ? partitionOfCurrentWorker(server) : mainPartitionFor(server, player);
            }
            if (!partition.owns(player)) return false;
            run(partition, body);
            return true;
        });
    }

    /**
     * Main's partition for {@code player}: its island while Main holds that
     * island's claim (MAIN_SYNC bucket, routed body), else Main's remainder.
     */
    private static FunctionPartition mainPartitionFor(MinecraftServer server,
                                                      net.minecraft.server.level.ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            RegionizedServer rs = RegionScheduler.forLevel(level);
            IslandRegistry.PIsland island = rs == null ? null
                    : rs.islandOwnerOfChunk(player.chunkPosition().pack());
            if (island != null && IslandCommands.mainHolds(island)) {
                return FunctionPartition.island(rs, island);
            }
        }
        return FunctionPartition.main(server);
    }

    /**
     * Partition of the entity context this region worker currently executes
     * for: its island (coordinator or claimed island region) or the Main
     * remainder (a global-domain region of Main's entity phase).
     */
    public static FunctionPartition partitionOfCurrentWorker(MinecraftServer server) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            throw new IllegalStateException("not a region worker: " + Thread.currentThread().getName());
        }
        Region region = w.currentRegion;
        EpochDomain domain = region != null ? region.domain : w.currentDomain;
        IslandRegistry.PIsland owner = domain != null ? domain.owner : null;
        if (owner == null) return FunctionPartition.main(server);
        RegionizedServer rs = RegionScheduler.forLevel(owner.domainLevel());
        if (rs == null) {
            throw new IllegalStateException("island#" + owner.id + " has no live level runtime");
        }
        return FunctionPartition.island(rs, owner);
    }

    /** Publish a fail-stop and throw: the owner-local proof was violated. */
    public static RuntimeException violation(FunctionPartition partition, String what) {
        IllegalStateException failure = new IllegalStateException(
                "owner-local function body (" + partition.describe() + ") attempted " + what
                        + "; the function plan proved this impossible");
        Throwable winner = RegionScheduler.publishFatalAndSelectWinner(failure);
        if (winner instanceof RuntimeException re) throw re;
        if (winner instanceof Error err) throw err;
        throw failure;
    }

    public static void checkEntity(Entity entity, String what) {
        FunctionPartition p = current();
        if (p != null && !p.owns(entity)) {
            throw violation(p, what + " on non-owned entity " + entity);
        }
    }

    public static void checkChunk(ServerLevel level, int cx, int cz) {
        FunctionPartition p = current();
        if (p != null && !p.ownsChunk(level, cx, cz)) {
            throw violation(p, "chunk access " + level.dimension().identifier() + " [" + cx + "," + cz + "]");
        }
    }

    public static void forbid(String what) {
        FunctionPartition p = current();
        if (p != null) throw violation(p, what);
    }
}
