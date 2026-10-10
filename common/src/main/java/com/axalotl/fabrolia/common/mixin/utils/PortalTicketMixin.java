package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PLACE_PORTAL_TICKET off-main hazard (2026-07-16 audit): a cross-dimension
 * teleport's post-transition hook mutates the DESTINATION level's ticket
 * structures ({@code TicketStorage.addTicket} → plain Long2ObjectOpenHashMap,
 * then a synchronous {@code SimulationChunkTracker} edge update whose
 * sync-all'd monitor main holds for entire consolidation fixpoint waves).
 * From a region worker that is (a) an unserialized map write — the same
 * class of race that crashed 2026-06-25 — and (b) a mid-entity-phase park
 * of unbounded length, the measured hundreds-of-ms wall between two halves
 * of an End-portal sand cohort. Defer the ticket to main: single writer,
 * no monitor wait; the destination chunk is already loaded (the arrival
 * cohort holds it), so a one-slot-later PORTAL ticket changes nothing
 * observable.
 */
@Mixin(Entity.class)
public abstract class PortalTicketMixin {

    @Inject(method = "placePortalTicket", at = @At("HEAD"), cancellable = true)
    private void fabrolia$deferPortalTicket(BlockPos pos, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel destination)) return;
        var server = destination.getServer();
        if (RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            return; // physical Main / vanilla context: unchanged
        }
        ChunkPos chunk = ChunkPos.containing(pos);
        // The request belongs to this destination now. Pickup, merging or a
        // later teleport may remove/change the entity before Main drains it;
        // vanilla has already placed the ticket by then.
        RegionScheduler.enqueueGlobalMainOnly(() -> destination.getChunkSource()
                .addTicketWithRadius(TicketType.PORTAL, chunk, 3));
        ci.cancel();
    }
}
