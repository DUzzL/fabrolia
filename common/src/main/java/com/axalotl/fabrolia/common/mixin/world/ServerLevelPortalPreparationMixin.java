package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.NetherPortalPreparation;
import com.axalotl.fabrolia.common.regions.PoiPublicationFence;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelPortalPreparationMixin implements NetherPortalPreparation.PoiAccess {
    @Unique
    private final PoiPublicationFence fabrolia$portalPublications =
            new PoiPublicationFence(RegionScheduler::publishFatalAndSelectWinner);

    @Override
    public PoiPublicationFence fabrolia$portalPublications() {
        return this.fabrolia$portalPublications;
    }

    /**
     * Only {@code nether_portal} POI add/remove callbacks enter the fence: the
     * portal search reads no other POI type, and vanilla itself defers every
     * POI change through {@code server.execute}. Beds, job sites, hives and
     * lodestones keep the vanilla path, so their churn cannot make a transfer
     * wait for Main.
     */
    @WrapOperation(method = {"lambda$updatePOIOnBlockStateChange$0", "lambda$updatePOIOnBlockStateChange$2"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;execute(Ljava/lang/Runnable;)V"),
            require = 2, allow = 2)
    private void fabrolia$trackPortalPublication(MinecraftServer server, Runnable callback,
                                                Operation<Void> original,
                                                @Local(argsOnly = true) Holder<PoiType> poiType) {
        if (!poiType.is(PoiTypes.NETHER_PORTAL)) {
            original.call(server, callback);
            return;
        }
        this.fabrolia$portalPublications.submit(callback, tracked -> {
            if (NetherPortalPreparation.deferGenerationPublication((ServerLevel) (Object) this)) {
                // execute may inline on Main even with older queued worldgen adds.
                // Do not let a portal replacement remove overtake its earlier add.
                server.schedule(new TickTask(server.getTickCount(), tracked));
            } else {
                original.call(server, tracked);
            }
        });
    }

    @Inject(method = "getWorldBorder", at = @At("HEAD"), cancellable = true)
    private void fabrolia$fixedPortalBorder(CallbackInfoReturnable<WorldBorder> cir) {
        WorldBorder border = NetherPortalPreparation.snapshotBorder((ServerLevel) (Object) this);
        if (border != null) cir.setReturnValue(border);
    }
}
