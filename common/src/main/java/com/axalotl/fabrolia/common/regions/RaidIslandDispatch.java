package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Island-owned raid ticking — unit-driven (2026-08-13 stacked-raid-farm
 * cadence fix, second iteration).
 *
 * <p>First iteration dispatched one raid-tick task per MAIN level tick
 * through a coalescing queue: under sprint (or any main/island rate
 * divergence) the coalescing collapsed dispatches, so the raid ticked at a
 * FRACTION of the farm's cadence (the farm's timer runs on the island's
 * scheduled ticks — one per unit). Vanilla ticks a raid once per level tick,
 * strictly 1:1 with the scheduled-tick phase it reads — a stacked farm keys
 * its create/destroy-the-village window on that exact ratio, and a raid
 * running at half cadence checks the village inside the designed dead
 * window and dies by the two-strike rule.
 *
 * <p>Newly created raids are assigned immediately; main's {@code Raids.tick}
 * refreshes ownership. The island ticks every assigned
 * raid at the vanilla slot (after scheduled ticks, before block events), once
 * per UNIT in lockstep with its scheduled ticks, whatever main's rate is.
 * Stopped raids are dropped; a surviving raid whose center migrated is handed
 * directly to the new owner's set.
 */
public final class RaidIslandDispatch {

    private RaidIslandDispatch() {}

    /** Publish an owner assignment without waiting for Main's next level tick. */
    public static void assign(IslandRegistry.PIsland owner, Raid raid) {
        owner.assignedRaids.add(raid);
    }

    public static void assignCreated(ServerLevel level, Raid raid) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        if (rs == null || !rs.asyncActive) return;
        RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(view.center()));
        if (owner != null && !owner.retired) assign(owner, raid);
        // Do not add raid tickets. Vanilla's existing player/portal/forceload
        // tickets decide which chunks are loaded and eligible for wave spawning.
    }

    /**
     * Main, {@code Raids.tick} slot. An island-owned raid is handed to its island;
     * an unowned raid whose vanilla step is a no-op is skipped.
     *
     * @return whether Main must tick this raid under the all-island fence
     *         ({@link #tickOnMainFenced}, once for every such raid of the level tick)
     */
    public static boolean dispatch(ServerLevel level, Raid raid) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
        if (rs != null && rs.asyncActive) {
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(view.center()));
            if (owner != null && !owner.retired) {
                assign(owner, raid);
                return false;
            }
        }
        // No island owns the center. A raid whose step only re-reads whether its
        // center chunk is loaded (a Main-safe read) needs no all-island fence.
        return !isInertWhileCenterUnloaded(level, raid);
    }

    /** One fence for every unowned raid of this level tick, in map order. */
    public static void tickOnMainFenced(ServerLevel level, java.util.List<Raid> raids) {
        RaidOwnership.runMainFenced(() -> {
            for (int i = 0, n = raids.size(); i < n; i++) {
                Raid raid = raids.get(i);
                RegionScheduler.runSuccessBodyIfHealthy(() -> tickOwned(level, raid));
            }
        });
    }

    /**
     * Whether vanilla's level step for this raid ({@code Raids.tick}: gamerule check,
     * stopped-raid removal, {@code Raid.tick}) is a no-op: an ongoing, already
     * inactive raid whose center chunk is not loaded only recomputes
     * {@code active = hasChunkAt(center)} (still false, so no boss-bar visibility
     * change) and returns. Such raids never expire in vanilla ({@code ticksActive}
     * advances only while active), so a raid left in an unloaded area would otherwise
     * take the all-island fence every tick forever. Physical Main only, for a raid no
     * island owns: its status, active flag and center are written only by its tick,
     * {@code stop()} and {@code setCenter}, which for such a raid run only on Main.
     */
    static boolean isInertWhileCenterUnloaded(ServerLevel level, Raid raid) {
        return level.getGameRules().get(GameRules.RAIDS)
                && level.getDifficulty() != Difficulty.PEACEFUL
                && !raid.isStopped() && !raid.isOver()
                && !raid.isActive()
                && !level.hasChunkAt(raid.getCenter());
    }

    /** Rebuild fence held: move subscriptions without adding an extra raid tick. */
    static void reassignAfterTopology(RegionizedServer runtime,
                                      java.util.List<IslandRegistry.PIsland> oldOwners) {
        java.util.HashSet<Raid> raids = null;
        for (IslandRegistry.PIsland owner : oldOwners) {
            if (owner.assignedRaids.isEmpty()) continue;
            if (raids == null) raids = new java.util.HashSet<>();
            raids.addAll(owner.assignedRaids);
            owner.assignedRaids.clear();
        }
        if (raids == null || !runtime.asyncActive) return;
        for (Raid raid : raids) {
            RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
            if (view.stopped()) continue;
            IslandRegistry.PIsland owner =
                    runtime.islandOwnerOfChunk(ChunkPos.pack(view.center()));
            if (owner != null && !owner.retired) assign(owner, raid);
        }
    }

    private static void tickOwned(ServerLevel level, Raid raid) {
        if (!level.getGameRules().get(GameRules.RAIDS)) raid.stop();
        if (raid.isStopped()) {
            ((RaidRegistryAccess) level.getRaids()).fabrolia$removeStoppedRaid(raid);
        } else {
            raid.tick(level);
            if (((RaidOwnerAccess) raid).fabrolia$raidView().stopped()) {
                ((RaidRegistryAccess) level.getRaids()).fabrolia$removeStoppedRaid(raid);
            }
        }
    }

    /** Coordinator thread, island claim held, vanilla raid slot. Ticks every
     *  assigned raid ONCE per unit; drops stopped or migrated-away raids
     *  (ownership re-resolved against the live topology). */
    public static void service(RegionizedServer rs, IslandRegistry.PIsland island, ServerLevel level) {
        for (Raid raid : island.assignedRaids) {
            // One admitted raid owns its vanilla tick and stale-set cleanup.
            // The receipt surfaces peer W before the iterator may reach a
            // successor raid; no tick is skipped and no body is replayed.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> serviceOneRaidEntered(rs, island, level, raid));
        }
        // The empty iteration and the gap after its last receipt still need a
        // normal-return linearization point before the caller enters its next
        // vanilla phase.
        RegionScheduler.commitSuccessIfHealthy(() -> {});
    }

    private static void serviceOneRaidEntered(
            RegionizedServer rs, IslandRegistry.PIsland island,
            ServerLevel level, Raid raid) {
        RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(view.center()));
        if (owner == island && !island.retired) {
            tickOwned(level, raid);
            view = ((RaidOwnerAccess) raid).fabrolia$raidView();
            owner = rs.islandOwnerOfChunk(ChunkPos.pack(view.center()));
        }
        if (view.stopped() || owner != island || island.retired) {
            island.assignedRaids.remove(raid);
            if (!view.stopped() && owner != null && !owner.retired && owner != island) {
                assign(owner, raid);
            }
        }
    }
}
