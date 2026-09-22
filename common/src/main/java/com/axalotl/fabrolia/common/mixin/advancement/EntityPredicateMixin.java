package com.axalotl.fabrolia.common.mixin.advancement;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityPredicate.class)
public abstract class EntityPredicateMixin {
    @WrapMethod(method = "createContext")
    private static LootContext fabrolia$ownedContext(ServerPlayer origin, Entity target,
                                                    Operation<LootContext> original) {
        AdvancementCaptureOwnership.requireContextConstructionOwnership(origin, target);
        return original.call(origin, target);
    }

    @WrapMethod(method = "matches(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean fabrolia$ownedPlayerMatch(ServerPlayer origin, Entity target, Operation<Boolean> original) {
        AdvancementCaptureOwnership.requireContextConstructionOwnership(origin, target);
        return original.call(origin, target);
    }

    @WrapMethod(method = "matches(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean fabrolia$ownedEntityMatch(ServerLevel level, Vec3 origin, Entity target,
                                             Operation<Boolean> original) {
        AdvancementCaptureOwnership.requireEntityObservationOwnership(target);
        return original.call(level, origin, target);
    }
}
