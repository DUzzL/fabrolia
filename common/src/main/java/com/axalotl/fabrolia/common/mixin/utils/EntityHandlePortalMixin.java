package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.parallelised.utils.PortalTeleportationManager;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Entity.class)
public abstract class EntityHandlePortalMixin {

    @Shadow
    public PortalProcessor portalProcess;

    @Shadow
    protected abstract void processPortalCooldown();

    @Shadow
    public abstract boolean canUsePortal(boolean ignorePassenger);

    @Shadow
    public abstract void setPortalCooldown();

    @Shadow
    public abstract boolean isOnPortalCooldown();

    @Shadow
    public abstract boolean canTeleport(Level from, Level _to);

    @Shadow
    public abstract Entity teleport(TeleportTransition transition);

    @Shadow
    public abstract Level level();

    @WrapMethod(method = "handlePortal")
    protected void fabrolia$handlePortal(Operation<Void> original) {
        Level level = this.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        this.processPortalCooldown();

        Entity self = (Entity) (Object) this;

        // task #26 async-island portal-loader fix: record this entity's chunk as
        // PORTAL-ACTIVE whenever it is inside a portal OR still on cooldown from a
        // recent crossing, so the main-thread lease re-arm can hold the chunk
        // loaded on WALL cadence — decoupling the loader's chunk lifetime from its
        // (possibly scheduling-starved) island's tick rate. Runs BEFORE the
        // process==null early-out so on-cooldown-but-not-in-portal carts (mid
        // rail, between crossings) are covered too.
        if (this.portalProcess != null || this.isOnPortalCooldown()) {
            PortalTeleportationManager.notePortalActive(self, serverLevel);
        }

        PortalProcessor process = this.portalProcess;
        if (process == null) return;

        if (process.processPortalTeleportation(serverLevel, self, this.canUsePortal(false))) {
            Portal portal = process.portal;

            if (portal instanceof NetherPortalBlock) {
                this.setPortalCooldown();
                boolean executedNow = PortalTeleportationManager.submit(self, portal, process.getEntryPosition(), serverLevel);
                if (executedNow) {
                    this.portalProcess = null;
                }
                // DEFERRED: main now owns the transfer. The source must look REMOVED
                // for the rest of this tick (EntityMixin wraps isRemoved) rather than
                // having its tick aborted — see the note there for why the difference
                // is load-bearing.
            } else {
                ProfilerFiller profiler = Profiler.get();
                profiler.push("portal");
                this.setPortalCooldown();

                TeleportTransition transition = process.getPortalDestination(serverLevel, self);
                if (transition != null) {
                    ServerLevel newLevel = transition.newLevel();
                    if (serverLevel.isAllowedToEnterPortal(newLevel)
                            && (newLevel.dimension() == serverLevel.dimension()
                            || this.canTeleport(serverLevel, newLevel))) {
                        this.teleport(transition);
                    }
                }
                profiler.pop();
            }
        } else if (process.hasExpired()) {
            this.portalProcess = null;
        }
    }
}