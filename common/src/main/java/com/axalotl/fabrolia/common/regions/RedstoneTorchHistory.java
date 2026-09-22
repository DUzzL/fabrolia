package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Burnout history uses each position's simulation clock, never a foreign island's L. */
public final class RedstoneTorchHistory {
    private static final Map<Level, History> LEVELS = new WeakHashMap<>();

    private RedstoneTorchHistory() {}

    private static synchronized History history(Level level) {
        return LEVELS.computeIfAbsent(level, ignored -> new History());
    }

    public static void purge(ServerLevel level) {
        history(level).purge(pos -> ownerNow(level, pos));
    }

    public static boolean toggled(Level level, BlockPos pos, boolean add) {
        long now = level instanceof ServerLevel server ? ownerNow(server, pos) : level.getGameTime();
        return history(level).toggled(pos, now, add);
    }

    private static long ownerNow(ServerLevel level, BlockPos pos) {
        return ownerNow(RegionScheduler.forLevel(level), level, pos);
    }

    private static long ownerNow(RegionizedServer rs, ServerLevel level, BlockPos pos) {
        if (rs != null) {
            Region region = rs.regionizer().peekByGridKey(
                    rs.regionizer().grid.keyFor(pos.getX() >> 4, pos.getZ() >> 4));
            EpochDomain domain = region == null ? null : region.domain;
            if (domain != null && domain.owner != null) {
                return Math.max(domain.owner.logicalTick, domain.unitGameTime);
            }
        }
        return IslandClock.wallTime(level);
    }

    /** Exclude foreign-region purges until both stamps and their owner clock
     * are published. The caller already owns the closed, drained region cut. */
    static void rebaseAndCommit(Region region, EpochDomain target) {
        History history = history(region.level);
        synchronized (history) {
            EpochDomain source = region.domain;
            long wall = IslandClock.wallTime(region.level);
            long oldNow = !history.collapsedToWall && source != null && source.owner != null
                    ? Math.max(source.owner.logicalTick, source.unitGameTime) : wall;
            long newNow = target.owner != null ? target.owner.logicalTick : wall;
            RegionizedServer rs = RegionScheduler.forLevel(region.level);
            if (rs == null) throw new IllegalStateException("Torch history rebase has no level runtime");
            RegionGrid grid = rs.regionizer().grid;
            if (oldNow != newNow) {
                history.rebase(pos -> grid.keyFor(pos.getX() >> 4, pos.getZ() >> 4) == region.gridKey,
                        newNow - oldNow);
            }
            region.commitDomainRebaseSeal(target, target.epoch - 1);
            history.collapsedToWall = false;
        }
    }

    /** Workers are quiescent; retain remaining local age before runtime lookup goes dark. */
    static void collapseAllToWall(java.util.List<RegionizedServer> levels) {
        for (RegionizedServer rs : levels) {
            History history;
            synchronized (RedstoneTorchHistory.class) {
                history = LEVELS.get(rs.level);
            }
            if (history == null) continue;
            long wall = IslandClock.wallTime(rs.level);
            synchronized (history) {
                if (history.collapsedToWall) continue;
                history.positions.forEach((pos, window) -> {
                    long delta = wall - ownerNow(rs, rs.level, pos);
                    for (int i = 0; i < window.count; i++) window.times[i] += delta;
                });
                history.collapsedToWall = true;
            }
        }
    }

    static final class History {
        private final HashMap<BlockPos, Window> positions = new HashMap<>();
        private boolean collapsedToWall;

        synchronized void purge(ToLongFunction<BlockPos> now) {
            positions.entrySet().removeIf(entry -> {
                entry.getValue().purge(now.applyAsLong(entry.getKey()));
                return entry.getValue().count == 0;
            });
        }

        synchronized boolean toggled(BlockPos pos, long now, boolean add) {
            Window window = positions.get(pos);
            if (window == null) {
                if (!add) return false;
                window = new Window();
                positions.put(pos.immutable(), window);
            }
            window.purge(now);
            if (add) window.add(now);
            return window.count == 8;
        }

        synchronized void rebase(Predicate<BlockPos> affected, long delta) {
            positions.forEach((pos, window) -> {
                if (affected.test(pos)) {
                    for (int i = 0; i < window.count; i++) window.times[i] += delta;
                }
            });
        }
    }

    /** Only the newest eight stamps can affect vanilla's eight-toggle threshold. */
    private static final class Window {
        final long[] times = new long[8];
        int count;

        void purge(long now) {
            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (now - times[i] <= 60L) times[kept++] = times[i];
            }
            count = kept;
        }

        void add(long now) {
            if (count == times.length) {
                System.arraycopy(times, 1, times, 0, times.length - 1);
                count--;
            }
            times[count++] = now;
        }
    }
}
