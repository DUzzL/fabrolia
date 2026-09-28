package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Unique private static final AtomicLong fabrolia$lastBeltWarn = new AtomicLong();

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
     * {@code PlayerPredicate.advancements} reads through this insert-on-miss accessor.
     * Parallel predicate evaluation keeps each player's own pendings in one task, but a
     * vehicle/passenger sub-predicate may evaluate ANOTHER player's progress from a
     * sibling task; the monitor keeps that plain-map insert exclusive against it.
     */
    @WrapMethod(method = "getOrStartProgress(Lnet/minecraft/advancements/AdvancementHolder;)Lnet/minecraft/advancements/AdvancementProgress;")
    private net.minecraft.advancements.AdvancementProgress fabrolia$lockProgressStart(
            AdvancementHolder holder, Operation<net.minecraft.advancements.AdvancementProgress> original) {
        synchronized (this) {
            return original.call(holder);
        }
    }

    @WrapMethod(method = "flushDirty(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    private void fabrolia$mainOwnFlush(ServerPlayer player, boolean force, Operation<Void> original) {
        if (fabrolia$offMain()) {
            AdvancementBuffer.queueFlush(player);
        } else {
            original.call(player, force);
        }
    }

    @WrapMethod(method = "clearTriggers()V")
    private void fabrolia$lockClear(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @Unique
    private static void fabrolia$beltWarn(String what, Object id) {
        long now = System.nanoTime();
        long last = fabrolia$lastBeltWarn.get();
        if (now - last > 5_000_000_000L && fabrolia$lastBeltWarn.compareAndSet(last, now)) {
            LoggerFactory.getLogger("Fabrolia-Advancement").warn(
                    "[async-islands] direct {} for '{}' arrived on a region worker (a mod bypassing the trigger path) — deferred to the main advancement drain (rate-limited warning)",
                    what, id);
        }
    }

    @Unique
    private static boolean fabrolia$offMain() {
        if (RegionalPlayerEntityTicksV2.inWorkerPlayerTick()) {
            return false;
        }
        MinecraftServer server = ParallelProcessor.getServer();
        return server != null && !server.isSameThread();
    }

    @WrapMethod(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$mainOwnAward(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        if (fabrolia$offMain()) {
            PlayerAdvancements self = (PlayerAdvancements) (Object) this;
            fabrolia$beltWarn("award", holder.id());
            AdvancementBuffer.deferDirect(() -> self.award(holder, criterion));
            return false;
        }
        return original.call(holder, criterion);
    }

    @WrapMethod(method = "revoke(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$mainOwnRevoke(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        if (fabrolia$offMain()) {
            PlayerAdvancements self = (PlayerAdvancements) (Object) this;
            fabrolia$beltWarn("revoke", holder.id());
            AdvancementBuffer.deferDirect(() -> self.revoke(holder, criterion));
            return false;
        }
        return original.call(holder, criterion);
    }
}
