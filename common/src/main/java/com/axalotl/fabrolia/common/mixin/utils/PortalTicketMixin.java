package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
        var server = self.level().getServer();
        if (server == null
                || RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            return; // physical Main / vanilla context: unchanged
        }
        BlockPos immutable = pos.immutable();
        RegionScheduler.enqueueGlobalMainOnly(() -> {
            if (!self.isRemoved()) {
                self.placePortalTicket(immutable);
            }
        });
        ci.cancel();
    }
}
