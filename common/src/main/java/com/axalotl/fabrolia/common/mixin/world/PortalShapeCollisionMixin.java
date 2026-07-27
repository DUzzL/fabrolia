package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * task #26 (2026-07-24) — portal-minecart arrival-placement audit.
 *
 * <p>The production trap: a cross-dimension minecart arrives at the portal FLOOR
 * (Y at the portal bottom) instead of on the rail, then vanilla's
 * gravity-before-rail-check ({@link net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior})
 * drops it below the rail and into the redstone block behind the portal before it
 * can engage — off-rail, cooldown re-armed, trapped.
 *
 * <p>{@link PortalShape#findCollisionFreePosition} returns
 * {@code collisionFreeBottomCenter.orElse(bottomCenter)} — i.e. when its
 * collision-free search FAILS it falls back to {@code bottomCenter}, the portal
 * floor. Under Fabrolia's async deferred teleport this search runs on MAIN during
 * the drain, against a destination island that is ticking concurrently, so it can
 * fail (find a collision) where vanilla's synchronous placement succeeds — the
 * async-only arrival-placement difference behind the trap.
 *
 * <p>This logs, gated by {@code -Dfabrolia.debug.portalTrace}, exactly when the
 * search FELL BACK to the floor (result == the raw bottomCenter) so the next
 * field capture shows whether the trapping crossing hit the fallback, and where.
 * Read-only; no behaviour change.
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeCollisionMixin {

    private static final Logger FAB_LOG = LoggerFactory.getLogger("Fabrolia-PortalPlace");

    @Inject(method = "findCollisionFreePosition", at = @At("RETURN"))
    private static void fabrolia$logFallback(Vec3 bottomCenter, ServerLevel level, Entity entity,
                                             EntityDimensions dimensions,
                                             CallbackInfoReturnable<Vec3> cir) {
        if (!com.axalotl.fabrolia.common.regions.PortalXferTrace.ON) return;
        Vec3 result = cir.getReturnValue();
        // orElse(bottomCenter): an EXACT match means the collision-free search
        // found nothing and fell back to the portal floor — the off-rail arrival.
        if (result != null && result.equals(bottomCenter)) {
            // Dump what's occupying the portal area — the entity/block that made
            // the collision-free search fail (and thus forced the off-rail floor
            // placement). This is the exact thing to eliminate for the fix.
            StringBuilder occ = new StringBuilder(96);
            try {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        bottomCenter.x - 1.5, bottomCenter.y - 0.5, bottomCenter.z - 1.5,
                        bottomCenter.x + 1.5, bottomCenter.y + 3.0, bottomCenter.z + 1.5);
                for (Entity o : level.getEntities(entity, box)) {
                    occ.append(' ').append(o.getType().toString()).append('@')
                       .append(String.format("(%.2f,%.2f,%.2f)", o.getX(), o.getY(), o.getZ()))
                       .append(o.isRemoved() ? "[removed]" : "");
                }
            } catch (Throwable t) {
                occ.append(" <scan-failed:").append(t).append('>');
            }
            FAB_LOG.warn("[portalplace] COLLISION-FREE-SEARCH FAILED for {} at portal floor {} in {} "
                    + "thread={} — arrival placed at the portal FLOOR (off-rail), the trap-inducing "
                    + "placement. A concurrently-ticking destination under the async drain can fail "
                    + "this search where vanilla's synchronous placement succeeds. "
                    + "Other entities in the portal box:{}",
                    entity.getType(), bottomCenter, level.dimension().identifier(),
                    Thread.currentThread().getName(),
                    occ.length() == 0 ? " (none — collision was a BLOCK, not an entity)" : occ);
        }
    }
}
