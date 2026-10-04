package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The entities one per-tick function pass may fan out over.
 *
 * <p>Membership is the exact tick ownership: an island partition is the
 * {@link Region#entities} tick sets of the regions carrying the island's domain
 * (the same sets its entity phase ticks, so a head body never runs on an
 * entity another thread ticks); the Main partition is the global-domain
 * regions of async levels plus every entity of non-async levels. Entities in
 * transfer (arrival-pending, portal-pending) belong to nobody this pass.
 * Order: entities by id (vanilla's EntityLookup insertion order), players by
 * their PlayerList position.
 */
public abstract class FunctionPartition {

    private static final Comparator<Entity> BY_ID = Comparator.comparingInt(Entity::getId);

    /** Candidate entities (players included), vanilla relative order. */
    protected abstract List<Entity> entities();

    /** Candidate players, PlayerList relative order. */
    protected abstract List<ServerPlayer> players();

    /** Whether {@code entity} belongs to this partition (guards). */
    public abstract boolean owns(Entity entity);

    /** Whether chunk {@code (cx,cz)} of {@code level} belongs to this partition (guards). */
    public abstract boolean ownsChunk(ServerLevel level, int cx, int cz);

    public abstract String describe();

    /**
     * Vanilla {@code EntitySelector.findEntities} restricted to this partition:
     * the selector's own predicate (options, type, aabb, distance, feature
     * flags), world limit and permission check, over this partition's
     * candidates in vanilla relative order. Only limit/sort-free selectors
     * reach here (the planner proves it), so no global sort is needed.
     */
    public final List<Entity> select(EntitySelector selector, CommandSourceStack source)
            throws CommandSyntaxException {
        selector.checkPermissions(source);
        Vec3 pos = selector.position.apply(source.getPosition());
        AABB box = selector.getAbsoluteAabb(pos);
        ServerLevel level = source.getLevel();
        boolean worldLimited = selector.isWorldLimited();
        ArrayList<Entity> out = new ArrayList<>();
        if (!selector.includesEntities()) {
            Predicate<Entity> predicate = selector.getPredicate(pos, box, null);
            for (ServerPlayer player : players()) {
                if (worldLimited && player.level() != level) continue;
                if (predicate.test(player)) out.add(player);
            }
            return out;
        }
        Predicate<Entity> predicate = selector.getPredicate(pos, box, source.enabledFeatures());
        for (Entity e : entities()) {
            if (worldLimited && e.level() != level) continue;
            if (selector.type.tryCast(e) == null) continue;
            if (predicate.test(e)) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Island partition (coordinator at the unit head, or Main while parked)
    // ------------------------------------------------------------------

    static FunctionPartition island(RegionizedServer rs, IslandRegistry.PIsland island) {
        return new IslandPartition(rs, island);
    }

    static final class IslandPartition extends FunctionPartition {
        final RegionizedServer rs;
        final IslandRegistry.PIsland island;

        IslandPartition(RegionizedServer rs, IslandRegistry.PIsland island) {
            this.rs = rs;
            this.island = island;
        }

        private ArrayList<Region> regions() {
            ThreadedRegionizer regionizer = rs.regionizer();
            ArrayList<Region> out = new ArrayList<>(island.cells.size() + island.haloCells.size());
            EpochDomain d = island.domain;
            for (LongIterator it = island.cells.iterator(); it.hasNext(); ) {
                Region r = regionizer.peekByGridKey(it.nextLong());
                if (r != null && r.domain == d && r.state() == RegionState.ACTIVE) out.add(r);
            }
            for (LongIterator it = island.haloCells.iterator(); it.hasNext(); ) {
                Region r = regionizer.peekByGridKey(it.nextLong());
                if (r != null && r.domain == d && r.state() == RegionState.ACTIVE) out.add(r);
            }
            return out;
        }

        @Override
        protected List<Entity> entities() {
            ArrayList<Entity> out = new ArrayList<>();
            for (Region r : regions()) {
                for (Entity e : r.entities) {
                    if (live(e)) out.add(e);
                }
            }
            out.sort(BY_ID);
            return out;
        }

        @Override
        protected List<ServerPlayer> players() {
            ArrayList<ServerPlayer> out = new ArrayList<>();
            for (Region r : regions()) {
                for (Entity e : r.entities) {
                    if (e instanceof ServerPlayer p && live(p)) out.add(p);
                }
            }
            FunctionPlayerSnapshot.sortByPlayerList(out);
            return out;
        }

        @Override
        public boolean owns(Entity entity) {
            if (entity.level() != rs.level) return false;
            long key = ((RegionKeyed) entity).fabrolia$getRegionKey();
            if (key == RegionKeyed.NO_REGION) return false;
            Region r = rs.regionizer().peekByGridKey(key);
            return r != null && r.domain == island.domain && r.entities.contains(entity);
        }

        @Override
        public boolean ownsChunk(ServerLevel level, int cx, int cz) {
            return level == rs.level
                    && rs.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(cx, cz)) == island;
        }

        @Override
        public String describe() {
            return "island#" + island.id + "/g" + island.gen;
        }
    }

    // ------------------------------------------------------------------
    // Main remainder partition (physical Main, at the vanilla function point)
    // ------------------------------------------------------------------

    static FunctionPartition main(MinecraftServer server) {
        return new MainPartition(server);
    }

    static final class MainPartition extends FunctionPartition {
        final MinecraftServer server;

        MainPartition(MinecraftServer server) {
            this.server = server;
        }

        private static boolean partitioned(RegionizedServer rs) {
            return rs != null && IslandCommands.requiresMainFence(rs);
        }

        @Override
        protected List<Entity> entities() {
            ArrayList<Entity> out = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                int from = out.size();
                RegionizedServer rs = RegionScheduler.forLevel(level);
                if (!partitioned(rs)) {
                    for (Entity e : level.getAllEntities()) {
                        if (e != null && live(e)) out.add(e);
                    }
                } else {
                    for (Region r : rs.regionizer().snapshot()) {
                        if (r.domain != rs.globalDomain) continue;
                        for (Entity e : r.entities) {
                            if (live(e)) out.add(e);
                        }
                    }
                }
                out.subList(from, out.size()).sort(BY_ID);
            }
            return out;
        }

        @Override
        protected List<ServerPlayer> players() {
            ArrayList<ServerPlayer> out = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (live(p) && owns(p)) out.add(p);
            }
            return out;
        }

        @Override
        public boolean owns(Entity entity) {
            if (!(entity.level() instanceof ServerLevel level)) return false;
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (!partitioned(rs)) return true;
            long key = ((RegionKeyed) entity).fabrolia$getRegionKey();
            if (key == RegionKeyed.NO_REGION) return false;
            Region r = rs.regionizer().peekByGridKey(key);
            return r != null && r.domain == rs.globalDomain && r.entities.contains(entity);
        }

        @Override
        public boolean ownsChunk(ServerLevel level, int cx, int cz) {
            RegionizedServer rs = RegionScheduler.forLevel(level);
            return !partitioned(rs)
                    || rs.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(cx, cz)) == null;
        }

        @Override
        public String describe() {
            return "main";
        }
    }

    private static boolean live(Entity e) {
        if (e.isRemoved()) return false;
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) e;
        return !guard.fabrolia$tickLease().isArrivalPending() && !guard.fabrolia$isPortalPending();
    }
}
