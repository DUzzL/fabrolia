package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.IslandRegistry;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionizedServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk-loader stall grace (2026-07-18 production hardening). Vanilla
 * decrements ticket timeouts once per MAIN tick (purgeStaleTickets), while
 * the machine that RENEWS a loader ticket (an entity cycling a portal, a
 * hopper line refreshing) runs at its owning ISLAND's cadence. A temporary
 * island stall therefore froze renewal but not expiry: main burned the
 * timeout down and a perfectly healthy loader unloaded mid-stall. (A main
 * stall is inherently safe in vanilla and here — no ticks, no decrements.)
 *
 * <p>Repair: while a chunk's owner island is BEHIND the wall clock, its
 * tickets do not decrement — timeout and renewal then count the same clock
 * (island ticks), the exact rule every other relative mechanic follows under
 * the L-clock model. A healthy island with a genuinely dead loader expires
 * normally (L keeps pace, decrements run). Leak bound: an island more than
 * {@link #STALL_GRACE_CAP_TICKS} behind is not a lag spike — decrements
 * resume so a permanently wedged island cannot pin tickets forever.
 */
@Mixin(TicketStorage.class)
public abstract class TicketStorageMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final long STALL_GRACE_CAP_TICKS = 6000;

    @Inject(method = "canTicketExpire", at = @At("RETURN"), cancellable = true)
    private void fabrolia$stallGrace(ChunkMap chunkMap, Ticket ticket, long chunkPos,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (!(chunkMap instanceof com.axalotl.fabrolia.common.regions.LevelAccess la)) {
            return;
        }
        RegionizedServer rs = RegionScheduler.forLevel(la.fabrolia$level());
        if (rs == null || !rs.asyncActive) {
            return;
        }
        IslandRegistry.PIsland owner = rs.islandOwnerOfChunk(chunkPos);
        if (owner == null) {
            return;
        }
        long wall = com.axalotl.fabrolia.common.regions.IslandClock.wallTime(la.fabrolia$level());
        long behind = wall - owner.logicalTick;
        if (behind <= 0 || behind >= STALL_GRACE_CAP_TICKS) {
            return; // caught up (vanilla expiry) / wedged beyond the leak bound
        }
        // Expiry counts ISLAND ticks, not wall ticks: decrement only on
        // passes where the owner's L advanced since the last purge. A stall
        // (L frozen) freezes expiry exactly as long as it freezes renewal; a
        // deliberately RATED island expires proportionally slower — matching
        // its renewal cadence — instead of freezing forever (v1 froze any
        // behind island, which leaked tickets on permanently-rated islands).
        if (owner.purgeSeenWall != wall) {
            owner.purgeAdvanced = owner.logicalTick != owner.purgeSeenL;
            owner.purgeSeenL = owner.logicalTick;
            owner.purgeSeenWall = wall;
        }
        if (!owner.purgeAdvanced) {
            cir.setReturnValue(false);
        }
    }
}
