package com.axalotl.fabrolia.common.mixin.server;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.locks.LockSupport;
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
            if (rs.mainHoldsAllClaims()) {
                break; // save-all/rebuild window already exclusive
            }
            IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
            if (owner == null) {
                break;
            }
            if (IslandCommands.mainHolds(owner)) {
                return original.call(chunkPos, onRemove); // already exclusive
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
            // tick. Main never blocks and never re-enters the iterator.
            if (!owner.unitClaim.compareAndSet(false, true)) {
                return false;
            }
            owner.noteClaim("entitySave");
            try {
                return original.call(chunkPos, onRemove);
            } finally {
                owner.clearClaim();
                owner.unitClaim.set(false);
            }
        }
        return original.call(chunkPos, onRemove);
    }
}
