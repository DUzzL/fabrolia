package com.axalotl.fabrolia.common.mixin.advancement;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Trigger-map mutation monitor (2026-07-13 production CME): vanilla confines
 * {@code activeTriggers} (and its inner per-trigger maps) to the server thread —
 * criteria (un)register as advancements progress, and triggers fire on main.
 * Under Fabrolia, triggers ALSO fire from region workers (a bot pushed by a
 * region task fired ENTER_BLOCK inside the entity phase), and the worker-side
 * buffer snapshot ({@code AdvancementBuffer.buildPending}) iterates the same
 * plain {@code HashMap} main is mutating — live CME, fixmix3 23:03.
 *
 * <p>Every mutator below takes the instance monitor; the snapshot iteration in
 * {@code buildPending} takes the same one. Sections are tiny (map put/remove,
 * a short filtered copy), per-player, and nothing inside them takes another
 * lock — uncontended in practice, safe by pairing. {@code getTriggerMapForType}
 * (the read that only fetches the map reference) stays lock-free; the DEEP read
 * (iteration) is what synchronizes.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

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

    /** Same ownership rule for the flush itself: a mod calling flushDirty
     *  off-main (or the vanilla in-tick call under regional player ticking —
     *  also routed by ServerPlayerMixin) queues for the main batch-end drain. */
    @WrapMethod(method = "flushDirty(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    private void fabrolia$mainOwnFlush(net.minecraft.server.level.ServerPlayer player, boolean force,
                                       Operation<Void> original) {
        if (fabrolia$offMain()) {
            com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer.queueFlush(player);
            return;
        }
        original.call(player, force);
    }

    @WrapMethod(method = "clearTriggers()V")
    private void fabrolia$lockClear(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.atomic.AtomicLong fabrolia$lastBeltWarn =
            new java.util.concurrent.atomic.AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private static void fabrolia$beltWarn(String what, Object id) {
        long now = System.nanoTime();
        long last = fabrolia$lastBeltWarn.get();
        if (now - last > 5_000_000_000L && fabrolia$lastBeltWarn.compareAndSet(last, now)) {
            org.slf4j.LoggerFactory.getLogger("Fabrolia-Advancement").warn(
                    "[async-islands] direct {} for '{}' arrived on a region worker "
                    + "(a mod bypassing the trigger path) — deferred to the main "
                    + "advancement drain (rate-limited warning)", what, id);
        }
    }

    /**
     * Worker belt (2026-07-17 trigger-path redesign audit): award() mutates
     * progress, (un)registers listeners, and runs REWARDS — loot, recipes,
     * experience, and the reward FUNCTION through the command fence. That
     * chain is main-owned; the buffer routes all trigger-raised awards to
     * main drains, but a mod may call player.getAdvancements().award()
     * directly from an island-ticked context. Defer the call itself to the
     * main drain (FIFO with the trigger-raised awards). Returns false to the
     * worker-side caller ("not newly awarded in this call") — the award
     * applies on main within the tick.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$offMain() {
        // task #15 widen (2026-07-22): the original belt caught only region
        // workers — a scarpet async task / mod thread running /advancement
        // grant executed award() on ITS OWN thread, mutating progressChanged
        // and rootsToUpdate against main's batch-end flush (stress rig CME on
        // the main-side iterator). Advancement state is main-owned, full stop:
        // ANY off-main caller defers.
        var s = com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        return s != null && !s.isSameThread();
    }

    @WrapMethod(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$mainOwnAward(AdvancementHolder holder, String criterion,
                                          Operation<Boolean> original) {
        if (fabrolia$offMain()) {
            PlayerAdvancements self = (PlayerAdvancements) (Object) this;
            fabrolia$beltWarn("award", holder.id());
            com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer
                    .deferDirect(() -> self.award(holder, criterion));
            return false;
        }
        return original.call(holder, criterion);
    }

    /** Same belt for revoke — progress mutation + listener re-registration
     *  are main-owned state. */
    @WrapMethod(method = "revoke(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$mainOwnRevoke(AdvancementHolder holder, String criterion,
                                           Operation<Boolean> original) {
        if (fabrolia$offMain()) {
            PlayerAdvancements self = (PlayerAdvancements) (Object) this;
            fabrolia$beltWarn("revoke", holder.id());
            com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer
                    .deferDirect(() -> self.revoke(holder, criterion));
            return false;
        }
        return original.call(holder, criterion);
    }
}
