package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
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
 * refreshes ownership and maintains tickets. The island ticks every assigned
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
        // Ticket storage remains Main-owned; it is not the first-tick admission.
        RegionScheduler.enqueueGlobalMainOnly(() -> RaidIslandMerge.onRaidDispatched(level, raid));
    }

    public static void dispatch(ServerLevel level, Raid raid) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        RaidOwnerAccess.View view = ((RaidOwnerAccess) raid).fabrolia$raidView();
        if (rs != null && rs.asyncActive) {
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(ChunkPos.pack(view.center()));
            if (owner != null && !owner.retired) {
                assign(owner, raid);
                RaidIslandMerge.onRaidDispatched(level, raid);
                return;
            }
        }
        RaidOwnership.runMainFenced(() -> tickOwned(level, raid));
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
