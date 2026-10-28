package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Predicate;

/**
 * Runs {@code SimpleCriterionTrigger.trigger(ServerPlayer, Predicate)} through
 * {@link AdvancementBuffer#trigger}: vanilla's evaluation and awards on the player's
 * owner thread at fire time, with only the work the owner may not do routed to the
 * fenced Main lane.
 *
 * <p>26.2 model: Mojang removed {@code SimpleCriterionTrigger.players} (the
 * {@code Map<PlayerAdvancements, Set<Listener>>} we used to {@code @Shadow}) and moved the
 * listener-set onto each {@link PlayerAdvancements} ({@link PlayerAdvancements#getTriggerMapForType
 * getTriggerMapForType} → {@code Map<TriggerInstanceKey, SimpleInstance>}). Grants go
 * through {@link PlayerAdvancements#award(net.minecraft.advancements.AdvancementHolder, String)
 * award}.
 *
 * <p><b>Vanish integration:</b> when the Vanish mod is present, its
 * {@code me.drex.vanish.mixin.interaction.SimpleCriterionTriggerMixin} adds a second
 * {@code @WrapMethod} layer on this same vanilla method to skip advancement progress for
 * vanished/spectator players. That extra mixin layer was profiled at ~6.9% of main-thread
 * time on a 26-player live event (mixinextras {@code Operation.call} dispatch + varargs
 * array per trigger × ~25k trigger calls per tick). We absorb the check into this single
 * wrap and cancel Vanish's mixin via {@code MixinCanceller}, saving one layer of
 * wrap-operation overhead per trigger.
 *
 * <p>Soft dependency: VanishAPI is resolved via MethodHandle at class-init. When Vanish is
 * absent, the lookup falls back to a no-op constant; the JIT inlines the volatile field
 * check as a single load. When present, {@code isVanished} runs as a direct MH invoke —
 * cheaper than {@code Class.forName + reflective Method.invoke} on each call.
 */
@Mixin(SimpleCriterionTrigger.class)
public abstract class SimpleCriterionTriggerMixin {

    /** Resolved at class-load. Null only when Vanish is absent. */
    @Unique
    private static final MethodHandle fabrolia$VANISH_IS_VANISHED;

    /** Mirrors {@code VanishConfig.interaction.advancementProgress} read once per call. */
    @Unique
    private static final MethodHandle fabrolia$VANISH_CONFIG_ADV_FLAG;

    /** Mirrors {@code VanishConfig.interaction.spectatorAdvancementProgress}. */
    @Unique
    private static final MethodHandle fabrolia$VANISH_CONFIG_SPEC_FLAG;

    static {
        MethodHandle isVan = null, advFlag = null, specFlag = null;
        Class<?> api = null;
        try {
            api = Class.forName("me.drex.vanish.api.VanishAPI");
        } catch (ClassNotFoundException absent) {
            // Optional dependency is not installed.  This is the only supported fallback.
        }
        if (api != null) {
            try {
                isVan = MethodHandles.lookup().findStatic(
                        api, "isVanished",
                        MethodType.methodType(boolean.class, net.minecraft.world.entity.Entity.class));
                // Config is fetched via static ConfigManager.vanish() → VanishConfig instance.
                // We inline as two MH chains so each call is a single MH.invokeExact returning bool.
                Class<?> mgr = Class.forName("me.drex.vanish.config.ConfigManager");
                Class<?> cfg = Class.forName("me.drex.vanish.config.VanishConfig");
                Class<?> inter = Class.forName("me.drex.vanish.config.VanishConfig$Interaction");
                MethodHandle vanishCall = MethodHandles.lookup().findStatic(
                        mgr, "vanish", MethodType.methodType(cfg));
                MethodHandle interactionGetter = MethodHandles.lookup().findGetter(cfg, "interaction", inter);
                MethodHandle advGetter = MethodHandles.lookup().findGetter(inter, "advancementProgress", boolean.class);
                MethodHandle specGetter = MethodHandles.lookup().findGetter(inter, "spectatorAdvancementProgress", boolean.class);
                // Compose: () -> bool  via filterReturnValue chains.
                advFlag = MethodHandles.filterReturnValue(
                        MethodHandles.filterReturnValue(vanishCall, interactionGetter), advGetter);
                specFlag = MethodHandles.filterReturnValue(
                        MethodHandles.filterReturnValue(vanishCall, interactionGetter), specGetter);
            } catch (Throwable linkageFailure) {
                // Vanish's own trigger mixin is cancelled when this bridge is selected.
                // An installed-but-incompatible API therefore has no semantics-preserving
                // fallback: fail class initialisation with the exact linkage Throwable.
                FailStops.<RuntimeException>rethrow(linkageFailure);
            }
        }
        fabrolia$VANISH_IS_VANISHED = isVan;
        fabrolia$VANISH_CONFIG_ADV_FLAG = advFlag;
        fabrolia$VANISH_CONFIG_SPEC_FLAG = specFlag;
    }

    @WrapMethod(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Predicate;)V")
    private void fabrolia$triggerOnOwner(ServerPlayer player, Predicate<?> predicate, Operation<Void> original) {
        // Vanish skip-check (inlined from me.drex.vanish.mixin.interaction.SimpleCriterionTriggerMixin —
        // their mixin is disabled via MixinCanceller when we ship). Same semantics: skip when player
        // is vanished AND the config flag is on, or spectator AND the spectator flag is on.
        if (fabrolia$VANISH_IS_VANISHED != null) {
            try {
                if ((boolean) fabrolia$VANISH_CONFIG_ADV_FLAG.invokeExact()
                        && (boolean) fabrolia$VANISH_IS_VANISHED.invokeExact((net.minecraft.world.entity.Entity) player)) {
                    return;
                }
                if ((boolean) fabrolia$VANISH_CONFIG_SPEC_FLAG.invokeExact() && player.isSpectator()) {
                    return;
                }
            } catch (Throwable failure) {
                // MethodHandle invocation requires a checked Throwable boundary.  It is
                // propagation-only: never turn a failed Vanish decision into an award.
                FailStops.<RuntimeException>rethrow(failure);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        CriterionTrigger<?> selfTrigger = (CriterionTrigger) (Object) this;
        AdvancementBuffer.trigger(player, predicate, selfTrigger);
    }
}
