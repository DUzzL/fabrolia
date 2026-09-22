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
            NetherPortalTransfers.notePortalActive(self, serverLevel);
        }

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