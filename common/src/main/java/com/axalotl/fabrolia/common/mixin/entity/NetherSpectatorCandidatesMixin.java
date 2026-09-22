package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalPlayerAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.List;

/** Retain vanilla source-list order without probing unrelated players' mutable cameras. */
@Mixin(Entity.class)
public abstract class NetherSpectatorCandidatesMixin {
    @WrapOperation(method = "teleportSpectators",
            at = @At(value = "INVOKE", target = "Ljava/util/List;copyOf(Ljava/util/Collection;)Ljava/util/List;"),
            require = 1, allow = 1)
    private List<ServerPlayer> fabrolia$capturedSpectatorCandidates(
            Collection<? extends ServerPlayer> players, Operation<List<ServerPlayer>> original) {
        return NetherPortalPlayerAccess.spectatorCandidates(original.call(players));
    }
}
