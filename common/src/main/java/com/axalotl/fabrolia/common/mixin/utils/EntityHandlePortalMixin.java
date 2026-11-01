package com.axalotl.fabrolia.common.mixin.utils;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.axalotl.fabrolia.common.parallelised.utils.PlayerDimensionTransition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    public abstract Level level();

    @WrapMethod(method = "handlePortal")
    protected void fabrolia$handlePortal(Operation<Void> original) {
        Level level = this.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        this.processPortalCooldown();

        Entity self = (Entity) (Object) this;

        PortalProcessor process = this.portalProcess;
        if (process == null) return;

        if (process.processPortalTeleportation(serverLevel, self, this.canUsePortal(false))) {
            Portal portal = process.portal;

            if (portal instanceof NetherPortalBlock) {
                boolean executedNow = NetherPortalTransfers.submit(self, portal, process.getEntryPosition(), serverLevel);
                if (executedNow) {
                    this.portalProcess = null;
                }
                // The exact source remains reserved through its complete source
                // unit, including owed player late work. No tick is unwound;
                // the whole transfer runs later between owned worker units.
            } else {
                if (self instanceof ServerPlayer player
                        && PlayerDimensionTransition.deferPortal(player, serverLevel, process)) {
                    // The rest of this source turn still applies block contacts.
                    // Close re-entry now, before deferred Main destination work.
                    this.setPortalCooldown();
                    this.portalProcess = null;
                    return;
                }
                ProfilerFiller profiler = Profiler.get();
                profiler.push("portal");
                this.setPortalCooldown();

                TeleportTransition transition = process.getPortalDestination(serverLevel, self);
                if (transition != null) {
                    self.teleportToPortalDestination(serverLevel, transition);
                }
                profiler.pop();
            }
        } else if (process.hasExpired()) {
            this.portalProcess = null;
        }
    }
}