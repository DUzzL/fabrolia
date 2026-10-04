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
 * <p>Membership candidates are the exact tick-ownership sets: an island
 * partition is the {@link Region#entities} tick sets of the regions carrying
 * the island's domain; the Main remainder is the global-domain regions of
 * async levels plus every entity of non-async levels. Entities in transfer
 * (arrival-pending, portal-pending) belong to nobody. A {@link Pass} is opened
 * once per head: it keeps exactly the candidates whose {@link FunctionPassMark}
 * it wins, so no entity is passed twice between two of its ticks whatever
 * partition it belongs to. Order: entities by id (vanilla's EntityLookup
 * insertion order), players by their PlayerList position.
 */
public abstract class FunctionPartition {

    private static final Comparator<Entity> BY_ID = Comparator.comparingInt(Entity::getId);

    /** Candidate entities (players included), any order. */
    protected abstract List<Entity> candidates();

    /** Whether {@code entity} belongs to this partition (guards). */
    public abstract boolean owns(Entity entity);

    /** Whether chunk {@code (cx,cz)} of {@code level} belongs to this partition (guards). */
    public abstract boolean ownsChunk(ServerLevel level, int cx, int cz);

    public abstract String describe();

    /** Claim this entity's pass (overridden by model tests without mixins). */
    protected FunctionPassMark.Claim claim(Entity entity, long passId) {
        return ((FunctionPassMark) entity).fabrolia$claimFunctionPass(passId);
    }

    /** The claimed members of one head pass, in vanilla relative order. */
    public static final class Pass {
        final FunctionPartition partition;
        final List<Entity> entities;
        final List<ServerPlayer> players;

        Pass(FunctionPartition partition, List<Entity> entities, List<ServerPlayer> players) {
            this.partition = partition;
            this.entities = entities;
            this.players = players;
        }

        public boolean isEmpty() {
            return entities.isEmpty();
        }

        /**
         * Vanilla {@code EntitySelector.findEntities} restricted to this pass:
         * the selector's own predicate (options, type, aabb, distance, feature
         * flags), world limit and permission check, over the claimed members in
         * vanilla relative order. Only limit/sort-free selectors reach here.
         */
        public List<Entity> select(EntitySelector selector, CommandSourceStack source)
                throws CommandSyntaxException {
            selector.checkPermissions(source);
            Vec3 pos = selector.position.apply(source.getPosition());
            AABB box = selector.getAbsoluteAabb(pos);
            ServerLevel level = source.getLevel();
            boolean worldLimited = selector.isWorldLimited();
            ArrayList<Entity> out = new ArrayList<>();
            if (!selector.includesEntities()) {
                Predicate<Entity> predicate = selector.getPredicate(pos, box, null);
                for (ServerPlayer player : players) {
                    if (player.isRemoved() || (worldLimited && player.level() != level)) continue;
                    if (predicate.test(player)) out.add(player);
                }
                return out;
            }
            Predicate<Entity> predicate = selector.getPredicate(pos, box, source.enabledFeatures());
            for (Entity e : entities) {
                if (e.isRemoved() || (worldLimited && e.level() != level)) continue;
                if (selector.type.tryCast(e) == null) continue;
                if (predicate.test(e)) out.add(e);
            }
            return out;
        }
    }

    /**
     * Members of pass {@code passId}: candidates still owed a pass (claimed
     * now) and, when {@code acceptSame}, those this exact pass already holds
     * (later roots of the same Main pass).
     */
    public final Pass open(long passId, boolean acceptSame) {
        List<Entity> candidates = candidates();
        ArrayList<Entity> members = new ArrayList<>(candidates.size());
        for (Entity e : candidates) {
            FunctionPassMark.Claim c = claim(e, passId);
            if (c == FunctionPassMark.Claim.NEW || (acceptSame && c == FunctionPassMark.Claim.SAME)) {
                members.add(e);
            }
        }
        members.sort(BY_ID);
        ArrayList<ServerPlayer> players = new ArrayList<>();
        for (Entity e : members) {
            if (e instanceof ServerPlayer p) players.add(p);
        }
        FunctionPlayerSnapshot.sortByPlayerList(players);
        return new Pass(this, members, players);
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

        @Override
        protected List<Entity> candidates() {
            ThreadedRegionizer regionizer = rs.regionizer();
            ArrayList<Entity> out = new ArrayList<>();
            EpochDomain d = island.domain;
            for (LongIterator it = island.cells.iterator(); it.hasNext(); ) {
                addRegion(out, regionizer.peekByGridKey(it.nextLong()), d);
            }
            for (LongIterator it = island.haloCells.iterator(); it.hasNext(); ) {
                addRegion(out, regionizer.peekByGridKey(it.nextLong()), d);
            }
            return out;
        }

        private static void addRegion(List<Entity> out, Region r, EpochDomain d) {
            if (r == null || r.domain != d || r.state() != RegionState.ACTIVE) return;
            for (Entity e : r.entities) {
                if (live(e) && tickOwnedByCurrentThread(e)) out.add(e);
            }
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
    // Main remainder partition (physical Main)
    // ------------------------------------------------------------------

    static MainPartition main(MinecraftServer server) {
        return new MainPartition(server, null);
    }

    /** Main remainder restricted to one level (async OFF catch-up). */
    static MainPartition mainLevel(MinecraftServer server, ServerLevel level) {
        return new MainPartition(server, level);
    }

    static final class MainPartition extends FunctionPartition {
        final MinecraftServer server;
        final ServerLevel only;

        MainPartition(MinecraftServer server, ServerLevel only) {
            this.server = server;
            this.only = only;
        }

        static boolean partitioned(RegionizedServer rs) {
            return rs != null && IslandCommands.requiresMainFence(rs);
        }

        /**
         * Some claimed member lives in a level whose islands may be ticking
         * (async, arrival owners or a still-entered unit): its body must not
         * overlap an island tick.
         */
        boolean hasAsyncLevelMember(Pass pass) {
            for (Entity e : pass.entities) {
                if (e.level() instanceof ServerLevel level && partitioned(RegionScheduler.forLevel(level))) {
                    return true;
                }
            }
            return false;
        }

        private Iterable<ServerLevel> levels() {
            return only != null ? List.of(only) : server.getAllLevels();
        }

        @Override
        protected List<Entity> candidates() {
            ArrayList<Entity> out = new ArrayList<>();
            for (ServerLevel level : levels()) {
                RegionizedServer rs = RegionScheduler.forLevel(level);
                if (only != null || !partitioned(rs)) {
                    // Tick-list members only: the pass mark alternates with
                    // ticks, so an entity that never ticks would be passed
                    // once and then never again.
                    level.entityTickList.forEach(e -> {
                        if (live(e)) out.add(e);
                    });
                } else {
                    // Copy-free walk; an empty region (exact entityCount) is
                    // skipped before a skip-list iterator is built. The Main
                    // remainder of an async level is almost always empty, and
                    // this runs once per partitioned root/loop callback.
                    EpochDomain global = rs.globalDomain;
                    rs.regionizer().forEachRegion(r -> {
                        if (r.domain != global || r.entityCount.get() == 0L) return;
                        for (Entity e : r.entities) {
                            if (live(e)) out.add(e);
                        }
                    });
                }
            }
            return out;
        }

        @Override
        public boolean owns(Entity entity) {
            if (!(entity.level() instanceof ServerLevel level)) return false;
            if (only != null) return level == only;
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (!partitioned(rs)) return true;
            long key = ((RegionKeyed) entity).fabrolia$getRegionKey();
            if (key == RegionKeyed.NO_REGION) return false;
            Region r = rs.regionizer().peekByGridKey(key);
            return r != null && r.domain == rs.globalDomain && r.entities.contains(entity);
        }

        @Override
        public boolean ownsChunk(ServerLevel level, int cx, int cz) {
            if (only != null) return level == only;
            RegionizedServer rs = RegionScheduler.forLevel(level);
            return !partitioned(rs)
                    || rs.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(cx, cz)) == null;
        }

        @Override
        public String describe() {
            return only != null ? "main/" + only.dimension().identifier() : "main";
        }
    }

    /**
     * Island head membership: only entities whose tick this coordinator owns. A
     * player Main owns (A5 MAIN phase, fallbacks, MAIN_SYNC lane) is never run
     * on the island — strict C1 would reject its award/revoke — and is served
     * by its Main-sync body instead (IslandFunctionHead.runMainSyncEntityPass).
     */
    public static boolean tickOwnedByCurrentThread(Entity e) {
        return !(e instanceof net.minecraft.server.level.ServerPlayer p)
                || PlayerTurnOwnership.currentThreadOwns(p);
    }

    private static boolean live(Entity e) {
        if (e.isRemoved()) return false;
        ParallelProcessor.TickGuard guard = (ParallelProcessor.TickGuard) e;
        return !guard.fabrolia$tickLease().isArrivalPending() && !guard.fabrolia$isPortalPending();
    }

    // ------------------------------------------------------------------
    // One Main-sync entity tree (physical Main, inside its MAIN_SYNC body)
    // ------------------------------------------------------------------

    static FunctionPartition mainSyncTree(Entity root, IslandRegistry.PIsland heldIsland) {
        return new MainSyncTreePartition(root, heldIsland);
    }

    /**
     * The entity tree (root and its passengers) whose tick body physical Main
     * is about to run under the claim of {@code heldIsland} (null: the global
     * lane). A ridden vehicle's MAIN_SYNC body ticks its player passengers, so
     * the tree is the pass. Chunks are owned under a held global fence or all
     * claims of their level, or while they belong to {@code heldIsland}.
     */
    static final class MainSyncTreePartition extends FunctionPartition {
        final Entity root;
        final IslandRegistry.PIsland heldIsland;

        MainSyncTreePartition(Entity root, IslandRegistry.PIsland heldIsland) {
            this.root = root;
            this.heldIsland = heldIsland;
        }

        @Override
        protected List<Entity> candidates() {
            ArrayList<Entity> out = new ArrayList<>(2);
            root.getSelfAndPassengers().forEach(e -> {
                if (live(e)) out.add(e);
            });
            return out;
        }

        @Override
        public boolean owns(Entity e) {
            return e == root || e.getRootVehicle() == root;
        }

        @Override
        public boolean ownsChunk(ServerLevel level, int cx, int cz) {
            if (IslandCommands.fenceWindowHeld()) return true;
            RegionizedServer rs = RegionScheduler.forLevel(level);
            if (rs == null || rs.currentThreadHoldsAllClaims()) return true;
            IslandRegistry.PIsland owner =
                    rs.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(cx, cz));
            return owner == heldIsland && (owner == null || IslandCommands.mainHolds(owner));
        }

        @Override
        public String describe() {
            return "main-sync " + root;
        }
    }
}
