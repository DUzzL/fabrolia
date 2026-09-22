package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementListenerMap;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Capture owns the exact player; reward/resource mutation owns the global envelope. */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin implements AdvancementRequirements.ActiveListeners {
    @Shadow private ServerPlayer player;
    @Shadow @Final private Map<CriterionTrigger<?>,
            Map<PlayerAdvancements.TriggerInstanceKey, ? extends CriterionTriggerInstance>> activeTriggers;
    @Unique private final AdvancementRequirements.ListenerIndex fabrolia$listenerIndex =
            new AdvancementRequirements.ListenerIndex();

    @Override
    public Map<CriterionTrigger<?>, Map<PlayerAdvancements.TriggerInstanceKey, ? extends CriterionTriggerInstance>>
            fabrolia$activeTriggers() {
        return activeTriggers;
    }

    @Override
    public boolean fabrolia$hasUnindexedListeners(AdvancementRequirements requirements) {
        return fabrolia$listenerIndex.hasUnindexedListeners(requirements, activeTriggers);
    }

    @WrapMethod(method = "addListener")
    private void fabrolia$indexInsertion(Criterion<?> criterion, PlayerAdvancements.TriggerInstanceKey key,
                                         Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        synchronized (this) {
            CriterionTrigger<?> trigger = criterion.trigger();
            var listeners = activeTriggers.get(trigger);
            if (!(listeners instanceof AdvancementListenerMap<?>)) {
                var tracked = new AdvancementListenerMap<CriterionTriggerInstance>(this,
                        instance -> fabrolia$observeInsertion(trigger, instance));
                if (listeners != null) tracked.putAll(listeners);
                // Native getOrCreateTriggerMapForType must never publish a raw HashMap.
                activeTriggers.put(trigger, tracked);
            }
            original.call(criterion, key);
        }
    }

    @Unique
    private void fabrolia$observeInsertion(CriterionTrigger<?> trigger, CriterionTriggerInstance instance) {
        var requirements = AdvancementCaptureOwnership.observeListenerInsertion(player, trigger, instance);
        fabrolia$listenerIndex.observeInsertion(requirements, trigger, instance);
    }

    @WrapMethod(method = "registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V")
    private void fabrolia$lockRegisterAll(ServerAdvancementManager manager, Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        synchronized (this) {
            original.call(manager);
        }
    }

    @WrapMethod(method = "registerListeners(Lnet/minecraft/advancements/AdvancementHolder;)V")
    private void fabrolia$lockRegister(AdvancementHolder holder, Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        synchronized (this) {
            original.call(holder);
        }
    }

    @WrapMethod(method = "unregisterListeners(Lnet/minecraft/advancements/AdvancementHolder;)V")
    private void fabrolia$lockUnregister(AdvancementHolder holder, Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        synchronized (this) {
            original.call(holder);
        }
    }

    @WrapMethod(method = "clearTriggers()V")
    private void fabrolia$lockClear(Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        synchronized (this) {
            original.call();
            fabrolia$listenerIndex.clearIfEmpty(activeTriggers);
        }
    }

    @WrapMethod(method = "reload")
    private void fabrolia$ownedReload(ServerAdvancementManager manager, Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        original.call(manager);
    }

    @WrapMethod(method = "setPlayer")
    private void fabrolia$ownedRebind(ServerPlayer replacement, Operation<Void> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        original.call(replacement);
    }

    @WrapMethod(method = "getOrStartProgress")
    private AdvancementProgress fabrolia$ownedProgress(AdvancementHolder holder,
                                                      Operation<AdvancementProgress> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player);
        return original.call(holder);
    }

    @WrapMethod(method = "flushDirty(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    private void fabrolia$ownedFlush(ServerPlayer recipient, boolean force, Operation<Void> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player);
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(recipient);
        original.call(recipient, force);
    }


    @WrapMethod(method = "save")
    private void fabrolia$ownedSave(Operation<Void> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player);
        original.call();
    }

    @WrapMethod(method = "setSelectedTab")
    private void fabrolia$ownedSelectedTab(AdvancementHolder holder, Operation<Void> original) {
        AdvancementCaptureOwnership.requireTriggerEvaluationOwnership(player);
        original.call(holder);
    }
    @WrapMethod(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$ownedAward(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        // This is a synchronous API. Refusal is before body, never enqueue + false.
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        return original.call(holder, criterion);
    }

    @WrapMethod(method = "revoke(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean fabrolia$ownedRevoke(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        AdvancementCaptureOwnership.requireGlobalProducerEnvelope();
        return original.call(holder, criterion);
    }
}
