package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.IslandUnitAdmission;
import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Consumer;

/**
 * Production audit (2026-07-12) — ENTITY-SECTION SAVE TEARING (priority 1).
 * {@code storeChunkSections} serializes LIVE entity NBT on main for chunk
 * unloads AND autosave. Under the deadline drive an island unit may be
 * mid-tick over those exact entities (the 1-tick rebuild-lag window after a
 * full-status drop still leaves the cell island-owned; autosave touches ALL
 * loaded chunks) — torn inventories/positions on disk. Take the owning
 * island's unit claim for the serialization, blocking with the same
 * routed-batch-aware spin as player saves (a parked waiter's island cannot
 * finish until main runs the batch; claims held by main's own quiesce
 * windows are skipped via the reentrancy flag).
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntitySectionSaveMixin {

    @WrapMethod(method = "saveAll")
    private void fabrolia$fullEntitySave(Operation<Void> original) {
        IslandUnitAdmission.runFullEntitySave(this, () -> original.call());
    }

    @WrapMethod(method = "storeChunkSections")
    private boolean fabrolia$ownerSafeEntitySave(long chunkPos, Consumer<?> onRemove,
                                                 Operation<Boolean> original) {
        // Resolve the level via any async rs that owns this manager's chunk —
        // the manager is per-level but carries no level ref; check all async
        // levels' owners for this chunk key (cheap: ≤3 levels, one map read).
        for (RegionizedServer rs : RegionScheduler.allAsync()) {
            if (((Object) this) != rs.entityManagerHandle()) {
                continue;
            }
            if (rs.currentThreadHoldsAllClaims()) {
                return fabrolia$runEntitySaveBody(chunkPos, onRemove, original);
            }
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
            if (owner == null) {
                break;
            }
            if (IslandCommands.mainHolds(owner)) {
                return fabrolia$runOptionalEntitySave(chunkPos, onRemove, original);
            }
            // TRY-OR-DEFER (2026-07-14 harness NPE + production wedge class):
            // this runs inside processUnloads' chunksToUnload.removeIf. The
            // old blocking mainClaim (a) drained main chunk services, which
            // re-entered chunksToUnload.add DURING its own iteration —
            // fastutil iterator corruption, "wrapped is null" NPE crash —
            // and (b) parked main indefinitely behind a busy/retained island
            // claim (the frontier-walk watchdog on the production jar).
            // One-shot CAS instead: if the owner is mid-unit, report "not
            // stored" — vanilla keeps the chunk queued and retries next
            // tick. A queued-but-not-yet-claimed unit normally has the same
            // priority: this opportunistic serializer must not steal its
            // queue→claim window (2026-08-22 c2me incremental-save race).
            // PersistentEntityUnloadRetryMixin may attach one bounded, aged,
            // exact-manager+chunk lease after repeated ownership yields. That
            // lease only permits this CAS contention; the vanilla body still
            // cannot run without unitClaim. Main never blocks or re-enters the
            // iterator.
            if (!IslandUnitAdmission.entityUnloadSaveMayClaim(
                    this, chunkPos, owner.submitQueued.get())) {
                IslandUnitAdmission.noteEntityUnloadOwnershipYield(this, chunkPos);
                return false;
            }
            if (!owner.tryClaimAutonomousWork()) {
                IslandUnitAdmission.noteEntityUnloadOwnershipYield(this, chunkPos);
                return false;
            }
            if (!IslandUnitAdmission.entityUnloadSaveMayClaim(
                    this, chunkPos, owner.submitQueued.get())) {
                owner.unitClaim.set(false);
                IslandUnitAdmission.noteEntityUnloadOwnershipYield(this, chunkPos);
                return false;
            }
            boolean saved = false;
            Throwable failure = null;
            try {
                owner.noteClaim("entitySave");
                saved = fabrolia$runOptionalEntitySave(chunkPos, onRemove, original);
            } catch (Throwable thrown) {
                failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
            }
            try {
                owner.clearClaim();
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        FailStops.mergeFailure(failure, cleanup));
            }
            try {
                owner.unitClaim.set(false);
            } catch (Throwable cleanup) {
                failure = RegionScheduler.publishFatalAndSelectWinner(
                        FailStops.mergeFailure(failure, cleanup));
            }
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable peerFailure) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
                }
            }
            if (failure != null) IslandCommands.publishAndPropagate(failure);
            return saved;
        }
        return fabrolia$runOptionalEntitySave(chunkPos, onRemove, original);
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$runOptionalEntitySave(
            long chunkPos, Consumer<?> onRemove, Operation<Boolean> original) {
        try (var admission = NetherPortalPlayerAccess.tryBeginEntitySave(this, chunkPos)) {
            if (admission == null) return false;
            return fabrolia$runEntitySaveBody(chunkPos, onRemove, original);
        } catch (Throwable failure) {
            IslandCommands.publishAndPropagate(failure);
            return false; // unreachable
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$runEntitySaveBody(
            long chunkPos, Consumer<?> onRemove, Operation<Boolean> original) {
        try {
            return RegionScheduler.callSuccessBooleanIfHealthy(
                    () -> original.call(chunkPos, onRemove));
        } catch (Throwable failure) {
            IslandCommands.publishAndPropagate(failure);
            return false; // unreachable: publishAndPropagate always exact-rethrows
        }
    }
}
