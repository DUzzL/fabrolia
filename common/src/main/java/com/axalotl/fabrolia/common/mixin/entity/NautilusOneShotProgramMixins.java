package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeNautilusProgram;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.RandomStroll;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromLookTarget;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Factory-branch witnesses, not an approval of arbitrary generated OneShots. */
public final class NautilusOneShotProgramMixins {
    private NautilusOneShotProgramMixins() {}

    @Mixin(StartAttacking.class)
    public abstract static class Attacking {
        @WrapOperation(method = "create(Lnet/minecraft/world/entity/ai/behavior/StartAttacking$TargetFinder;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;",
                at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/StartAttacking;create(Lnet/minecraft/world/entity/ai/behavior/StartAttacking$StartAttackingCondition;Lnet/minecraft/world/entity/ai/behavior/StartAttacking$TargetFinder;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
        private static <E extends Mob> BehaviorControl<E> fabrolia$defaultCondition(StartAttacking.StartAttackingCondition<E> condition,
                StartAttacking.TargetFinder<E> finder, Operation<BehaviorControl<E>> original) {
            BehaviorControl<E> result = original.call(condition, finder);
            NativeNautilusProgram.startFactory(result, condition, finder);
            return result;
        }
    }

    @Mixin(RandomStroll.class)
    public abstract static class Swimming {
        @WrapOperation(method = "swim", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/ai/behavior/RandomStroll;strollFlyOrSwim(FLjava/util/function/Function;Ljava/util/function/Predicate;)Lnet/minecraft/world/entity/ai/behavior/OneShot;"))
        private static OneShot<PathfinderMob> fabrolia$swimBranch(float speed, Function<PathfinderMob, Vec3> position,
                Predicate<PathfinderMob> condition, Operation<OneShot<PathfinderMob>> original) {
            OneShot<PathfinderMob> result = original.call(speed, position, condition);
            NativeNautilusProgram.swimFactory(result, speed, position, condition);
            return result;
        }
    }

    @Mixin(SetWalkTargetFromLookTarget.class)
    public abstract static class Walking {
        @WrapOperation(method = "create(FI)Lnet/minecraft/world/entity/ai/behavior/OneShot;", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/ai/behavior/SetWalkTargetFromLookTarget;create(Ljava/util/function/Predicate;Ljava/util/function/Function;I)Lnet/minecraft/world/entity/ai/behavior/OneShot;"))
        private static OneShot<LivingEntity> fabrolia$defaultWalking(Predicate<LivingEntity> condition,
                Function<LivingEntity, Float> speed, int distance, Operation<OneShot<LivingEntity>> original) {
            OneShot<LivingEntity> result = original.call(condition, speed, distance);
            NativeNautilusProgram.walkFactory(result, condition, speed, distance);
            return result;
        }
    }
}
