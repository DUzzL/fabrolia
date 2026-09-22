package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NetherPortalTransfers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(NewMinecartBehavior.class)
public abstract class NewMinecartBehaviorPortalReservationMixin {
    /**
     * A pending cart's passenger is still independently pushable and indexed.
     * A no-op Entity.push guard alone is insufficient here: pushEntities counts
     * every selected candidate as pushed, which can suppress the movement retry
     * after a successful pickup. Exclude the departed tree before that decision.
     */
    @WrapOperation(
            method = {"pickupEntities", "pushEntities"},
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> fabrolia$excludePortalReservedCandidates(
            Level level, Entity source, AABB bounds, Predicate<? super Entity> predicate,
            Operation<List<Entity>> original) {
        List<Entity> entities = original.call(level, source, bounds, predicate);
        for (int first = 0; first < entities.size(); first++) {
            if (!fabrolia$isPortalReservedCandidate(entities.get(first))) continue;
            // Keep the ordinary query allocation-free beyond vanilla. A mod may
            // supply an immutable/shared list, so never mutate the original.
            List<Entity> remaining = new ArrayList<>(entities.size() - 1);
            for (int i = 0; i < first; i++) remaining.add(entities.get(i));
            for (int i = first + 1; i < entities.size(); i++) {
                Entity entity = entities.get(i);
                if (!fabrolia$isPortalReservedCandidate(entity)) remaining.add(entity);
            }
            return remaining;
        }
        return entities;
    }

    @Unique
    private static boolean fabrolia$isPortalReservedCandidate(Entity entity) {
        return NetherPortalTransfers.isSourceMutationBlocked(entity)
                || NetherPortalTransfers.isSourceMutationBlocked(entity.getRootVehicle());
    }
}
