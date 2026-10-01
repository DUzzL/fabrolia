package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.common.parallelised.utils.FabroliaPushableEntityQuery;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/**
 * Routes vanilla's single {@code getEntities(pusher, box, pushableBy(pusher))}
 * call inside {@code getPushableEntities} through the optional class-group
 * index. Only that call is substituted: the method body, its selector and every
 * other mod's injections into {@code getPushableEntities} stay intact.
 */
@Mixin(value = Level.class, priority = 1500)
public abstract class LevelPushableEntitiesMixin {

    @WrapOperation(method = "getPushableEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntities("
                            + "Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/world/phys/AABB;"
                            + "Ljava/util/function/Predicate;)Ljava/util/List;"),
            require = 1,
            allow = 1)
    private List<Entity> fabrolia$indexedPushCandidates(
            Level level, Entity pusher, AABB boundingBox, Predicate<? super Entity> selector,
            Operation<List<Entity>> original) {
        if (level instanceof FabroliaPushableEntityQuery indexed) {
            List<Entity> candidates =
                    indexed.fabrolia$getPushableEntitiesOrNull(pusher, boundingBox, selector);
            if (candidates != null) return candidates;
        }
        return original.call(level, pusher, boundingBox, selector);
    }
}
