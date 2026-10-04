package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementOwnership;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRewardRouting;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Contract C1: award, revoke and flushDirty run synchronously on the player's owner
 * thread with vanilla's return value; anywhere else they are rejected loudly (no
 * deferral, no fabricated {@code false}). The listener/progress monitors keep an
 * off-owner trigger's listener snapshot consistent against the owner's mutations.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow private ServerPlayer player;

    @WrapMethod(method = "registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V")
    private void fabrolia$lockRegisterAll(ServerAdvancementManager manager, Operation<Void> original) {
        synchronized (this) {
            original.call(manager);
        }
    }

    @WrapMethod(method = "registerListeners(Lnet/minecraft/advancements/AdvancementHolder;)V")
    private void fabrolia$lockRegister(AdvancementHolder holder, Operation<Void> original) {
        synchronized (this) {
            original.call(holder);
        }
    }

    @WrapMethod(method = "unregisterListeners(Lnet/minecraft/advancements/AdvancementHolder;)V")
    private void fabrolia$lockUnregister(AdvancementHolder holder, Operation<Void> original) {
        synchronized (this) {
            original.call(holder);
        }
    }

    /**
     * {@code PlayerPredicate.advancements} reads through this insert-on-miss accessor,
     * also for another player's progress (a vehicle/passenger sub-predicate evaluated
     * by an off-owner producer); the monitor keeps that plain-map insert exclusive.
     */
    @WrapMethod(method = "getOrStartProgress(Lnet/minecraft/advancements/AdvancementHolder;)Lnet/minecraft/advancements/AdvancementProgress;")
    private net.minecraft.advancements.AdvancementProgress fabrolia$lockProgressStart(
            AdvancementHolder holder, Operation<net.minecraft.advancements.AdvancementProgress> original) {
        synchronized (this) {
            return original.call(holder);
        }
    }

    @WrapMethod(method = "clearTriggers()V")
    private void fabrolia$lockClear(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    /** Reload clears every listener and progress map: only a quiescent Main may do it. */
    @WrapMethod(method = "reload(Lnet/minecraft/server/ServerAdvancementManager;)V")
    private void fabrolia$quiescentReload(ServerAdvancementManager manager, Operation<Void> original) {
        if (!AdvancementOwnership.quiescentContext()) {
            throw new IllegalStateException("PlayerAdvancements.reload outside quiescent Main ownership");
        }
        original.call(manager);
    }

    @WrapMethod(method = "flushDirty(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    private void fabrolia$ownerFlush(ServerPlayer player, boolean force, Operation<Void> original) {
        if (!AdvancementOwnership.ownsPlayer(player)) {
            throw AdvancementOwnership.offOwnerMutation("flushDirty", player, force);
        }
        original.call(player, force);
    }

    @WrapMethod(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$ownerAward(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        return AdvancementOwnership.mutateOnOwner("award", this.player, holder.id(),
                () -> original.call(holder, criterion));
    }

    @WrapMethod(method = "revoke(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$ownerRevoke(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        return AdvancementOwnership.mutateOnOwner("revoke", this.player, holder.id(),
                () -> original.call(holder, criterion));
    }

    /** The completion announcement in {@code award}: Main owns the player list. */
    @WrapOperation(
            method = "lambda$award$0(Lnet/minecraft/advancements/AdvancementHolder;Lnet/minecraft/advancements/DisplayInfo;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 1, allow = 1)
    private void fabrolia$routeAnnouncement(PlayerList playerList, Component message, boolean overlay,
                                            Operation<Void> original) {
        if (!AdvancementRewardRouting.routeAnnouncement(playerList, message, overlay, this.player)) {
            original.call(playerList, message, overlay);
        }
    }
}
