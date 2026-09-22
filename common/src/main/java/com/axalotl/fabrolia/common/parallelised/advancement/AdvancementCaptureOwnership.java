package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.axalotl.fabrolia.common.regions.WorldGenEntityConstruction;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/** Assertion-only observation authority. Never waits, promotes or replays a producer. */
public final class AdvancementCaptureOwnership {
    // Native resource replacement alone cannot prove that all dynamically added
    // listeners were cleared. Never turn this conservative invalidation back off.
    private static volatile boolean unindexedListenerObserved;

    private AdvancementCaptureOwnership() {}

    /** Aggregate resource diagnostic; never an island-unit admission policy. */
    public static boolean requiresGlobalProducerEnvelope() {
        return requirements().global();
    }

    /** Installed-resource prefilter for native producers whose victims cannot yet be bounded. */
    public static boolean mayRequireGlobalEntityInteractionEnvelope() {
        MinecraftServer server = ParallelProcessor.getServer();
        if (unindexedListenerObserved || server == null
                || !(server.getAdvancements() instanceof AdvancementRequirements.Provider provider)
                || !provider.fabrolia$tracksListenerInsertions()) return true;
        AdvancementRequirements requirements = provider.fabrolia$advancementRequirements();
        return requirements == null || requirements.mayRequireGlobalEntityInteraction();
    }

    /** Generic native mutation may reach any active criterion of this exact player. */
    public static boolean requiresGlobalProducerEnvelope(ServerPlayer player) {
        requireTriggerEvaluationOwnership(player);
        return requirements().requiresGlobal(player.getAdvancements(), AdvancementRequirements.Producer.ANY);
    }

    /** Player E/L may omit only proven native dimension and inactive item-use triggers. */
    public static boolean requiresGlobalPlayerTickEnvelope(ServerPlayer player, boolean activeUse) {
        requireTriggerEvaluationOwnership(player);
        return requirements().requiresGlobal(player.getAdvancements(), activeUse
                ? AdvancementRequirements.Producer.PLAYER_USE
                : AdvancementRequirements.Producer.IDLE_PLAYER_OR_ENTITY_INTERACTION);
    }

    /** Native non-player interactions retain inventory, effects, damage, breeding and pickup. */
    public static boolean requiresGlobalEntityInteractionEnvelope(ServerPlayer player) {
        requireTriggerEvaluationOwnership(player);
        return requirements().requiresGlobal(player.getAdvancements(),
                AdvancementRequirements.Producer.IDLE_PLAYER_OR_ENTITY_INTERACTION);
    }

    /** Use may consume, place, craft, damage, or change inventory/effects. */
    public static boolean requiresGlobalPlayerUseEnvelope(ServerPlayer player) {
        requireTriggerEvaluationOwnership(player);
        return requirements().requiresGlobal(player.getAdvancements(), AdvancementRequirements.Producer.PLAYER_USE);
    }

    /** Called before any listener-map publication while the player's monitor is held. */
    public static AdvancementRequirements observeListenerInsertion(
            ServerPlayer player, CriterionTrigger<?> trigger, CriterionTriggerInstance instance) {
        requireGlobalProducerEnvelope();
        AdvancementRequirements requirements = requirements(player.level().getServer());
        if (!requirements.contains(trigger, instance)) {
            unindexedListenerObserved = true;
        }
        return requirements;
    }

    private static AdvancementRequirements requirements() {
        return requirements(ParallelProcessor.getServer());
    }

    private static AdvancementRequirements requirements(MinecraftServer server) {
        if (server == null || !(server.getAdvancements() instanceof AdvancementRequirements.Provider provider)) {
            return AdvancementRequirements.UNKNOWN;
        }
        AdvancementRequirements requirements = provider.fabrolia$advancementRequirements();
        return requirements == null ? AdvancementRequirements.UNKNOWN : requirements;
    }

    /** Global reward/serialization authority is deliberately stronger than target ownership. */
    public static boolean ownsGlobalProducerEnvelope() {
        if (RegionScheduler.ownsExclusiveGameplay()) return true;
        MinecraftServer server = ParallelProcessor.getServer();
        if (server == null) return false;
        return RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)
                && !RegionScheduler.hasLiveGameplayWorkers();
    }

    public static void requireGlobalProducerEnvelope() {
        if (!ownsGlobalProducerEnvelope()) {
            throw new IllegalStateException("advancement operation requires a pre-established global producer envelope");
        }
    }

    public static void requireTriggerEvaluationOwnership(ServerPlayer target) {
        Objects.requireNonNull(target, "target");
        if (ownsGlobalProducerEnvelope()) return;
        requireEntityObservationOwnership(target);
        // Progress/context ownership does not imply that any particular trigger runs.
    }

    /** Exact native trigger identity, asserted before native eager contexts/matching. */
    public static void requireTriggerEvaluationOwnership(ServerPlayer target, CriterionTrigger<?> trigger) {
        requireTriggerEvaluationOwnership(target);
        if (!ownsGlobalProducerEnvelope() && requirements().requiresGlobal(target.getAdvancements(), trigger)) {
            requireGlobalProducerEnvelope();
        }
    }

    public static void requireContextConstructionOwnership(ServerPlayer origin, Entity target) {
        requireTriggerEvaluationOwnership(origin);
        requireEntityObservationOwnership(target);
    }

    public static void requireEntityObservationOwnership(Entity target) {
        if (target == null || ownsGlobalProducerEnvelope()) return;
        if (target instanceof ServerPlayer player) {
            if (AsyncIslandPlayerTicks.ownsAdvancementObservation(player)) return;
            throw new IllegalStateException("advancement observation requires the exact player turn or claim");
        }
        if (WorldGenEntityConstruction.owns(target)) return;
        if (target.level() instanceof ServerLevel level
                && RegionScheduler.ownsCurrentContext(level, target.blockPosition())) return;
        throw new IllegalStateException("advancement observation requires the exact entity owner");
    }

    /** Full native saves can follow remote pearls and other references, not merely the root. */
    public static void requireEntitySerializationOwnership(Entity target) {
        Objects.requireNonNull(target, "target");
        if (ownsGlobalProducerEnvelope() || WorldGenEntityConstruction.owns(target)) return;
        throw new IllegalStateException("full entity NBT requires a pre-established global producer envelope");
    }
}
