package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.NativeNautilusProgram;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.behavior.AnimalPanic;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.ChargeAttack;
import net.minecraft.world.entity.ai.behavior.CountDownCooldownTicks;
import net.minecraft.world.entity.ai.behavior.FollowTemptation;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.nautilus.Nautilus;
import net.minecraft.world.entity.animal.nautilus.NautilusAi;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only these actual NautilusAi callsites publish root identities. */
@Mixin(NautilusAi.class)
public abstract class NautilusAiProgramMixin {
    @WrapOperation(method = "initCoreActivity", at = @At(value = "NEW", target = "(F)Lnet/minecraft/world/entity/ai/behavior/AnimalPanic;"))
    private static AnimalPanic<Nautilus> fabrolia$panic(float speed, Operation<AnimalPanic<Nautilus>> original) {
        return NativeNautilusProgram.root(0, original.call(speed));
    }

    @WrapOperation(method = "initCoreActivity", at = @At(value = "NEW", target = "(II)Lnet/minecraft/world/entity/ai/behavior/LookAtTargetSink;"))
    private static LookAtTargetSink fabrolia$look(int min, int max, Operation<LookAtTargetSink> original) {
        return NativeNautilusProgram.root(1, original.call(min, max));
    }

    @WrapOperation(method = "initCoreActivity", at = @At(value = "NEW", target = "()Lnet/minecraft/world/entity/ai/behavior/MoveToTargetSink;"))
    private static MoveToTargetSink fabrolia$move(Operation<MoveToTargetSink> original) {
        return NativeNautilusProgram.root(2, original.call());
    }

    @WrapOperation(method = "initCoreActivity", at = @At(value = "NEW", target = "(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)Lnet/minecraft/world/entity/ai/behavior/CountDownCooldownTicks;"))
    private static CountDownCooldownTicks fabrolia$cooldown(MemoryModuleType<Integer> memory, Operation<CountDownCooldownTicks> original) {
        int slot = memory == MemoryModuleType.TEMPTATION_COOLDOWN_TICKS ? 3
                : memory == MemoryModuleType.CHARGE_COOLDOWN_TICKS ? 4
                : memory == MemoryModuleType.ATTACK_TARGET_COOLDOWN ? 5 : -1;
        return NativeNautilusProgram.root(slot, original.call(memory));
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "NEW", target = "(Lnet/minecraft/world/entity/EntityType;FI)Lnet/minecraft/world/entity/ai/behavior/AnimalMakeLove;"))
    private static AnimalMakeLove fabrolia$breeding(EntityType<? extends Animal> type, float speed, int distance, Operation<AnimalMakeLove> original) {
        return NativeNautilusProgram.root(6, original.call(type, speed, distance));
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "NEW", target = "(Ljava/util/function/Function;Ljava/util/function/Function;)Lnet/minecraft/world/entity/ai/behavior/FollowTemptation;"))
    private static FollowTemptation fabrolia$following(Function<LivingEntity, Float> speed,
            Function<LivingEntity, Double> distance, Operation<FollowTemptation> original) {
        FollowTemptation result = original.call(speed, distance);
        NativeNautilusProgram.following(result, speed, distance);
        return result;
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/StartAttacking;create(Lnet/minecraft/world/entity/ai/behavior/StartAttacking$TargetFinder;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<Nautilus> fabrolia$attacking(StartAttacking.TargetFinder<Nautilus> finder,
            Operation<BehaviorControl<Nautilus>> original) {
        BehaviorControl<Nautilus> result = original.call(finder);
        NativeNautilusProgram.attacking(result, finder);
        return result;
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/RandomStroll;swim(F)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"))
    private static BehaviorControl<PathfinderMob> fabrolia$swimming(float speed, Operation<BehaviorControl<PathfinderMob>> original) {
        BehaviorControl<PathfinderMob> result = original.call(speed);
        NativeNautilusProgram.swimming(result, speed);
        return result;
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/SetWalkTargetFromLookTarget;create(FI)Lnet/minecraft/world/entity/ai/behavior/OneShot;"))
    private static OneShot<LivingEntity> fabrolia$walking(float speed, int distance, Operation<OneShot<LivingEntity>> original) {
        OneShot<LivingEntity> result = original.call(speed, distance);
        NativeNautilusProgram.walking(result, speed, distance);
        return result;
    }

    @WrapOperation(method = "initIdleActivity", at = @At(value = "NEW",
            target = "(Ljava/util/Map;Ljava/util/Set;Lnet/minecraft/world/entity/ai/behavior/GateBehavior$OrderPolicy;Lnet/minecraft/world/entity/ai/behavior/GateBehavior$RunningPolicy;Ljava/util/List;)Lnet/minecraft/world/entity/ai/behavior/GateBehavior;"))
    private static GateBehavior<Nautilus> fabrolia$gate(Map<MemoryModuleType<?>, MemoryStatus> entry,
            Set<MemoryModuleType<?>> exit, GateBehavior.OrderPolicy order, GateBehavior.RunningPolicy running,
            List<Pair<? extends BehaviorControl<? super Nautilus>, Integer>> children, Operation<GateBehavior<Nautilus>> original) {
        return NativeNautilusProgram.root(9, original.call(entry, exit, order, running, children));
    }

    @WrapOperation(method = "initFightActivity", at = @At(value = "NEW",
            target = "(ILnet/minecraft/world/entity/ai/targeting/TargetingConditions;FFDDLnet/minecraft/sounds/SoundEvent;)Lnet/minecraft/world/entity/ai/behavior/ChargeAttack;"))
    private static ChargeAttack fabrolia$charge(int interval, TargetingConditions targeting, float speed, float knockback,
            double chargeDistance, double detectionDistance, SoundEvent sound, Operation<ChargeAttack> original) {
        return NativeNautilusProgram.root(10, original.call(interval, targeting, speed, knockback, chargeDistance, detectionDistance, sound));
    }

    @WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;selector(Lnet/minecraft/world/entity/ai/targeting/TargetingConditions$Selector;)Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"))
    private static TargetingConditions fabrolia$chargeSelector(TargetingConditions targeting,
            TargetingConditions.Selector selector, Operation<TargetingConditions> original) {
        TargetingConditions result = original.call(targeting, selector);
        NativeNautilusProgram.chargeConditions(result, selector);
        return result;
    }

    @Inject(method = "getActivities", at = @At("RETURN"))
    private static void fabrolia$activities(CallbackInfoReturnable<List<ActivityData<Nautilus>>> cir) {
        NativeNautilusProgram.activities(cir.getReturnValue());
    }

    @Inject(method = "getTemptations", at = @At("RETURN"))
    private static void fabrolia$food(CallbackInfoReturnable<Predicate<ItemStack>> cir) {
        NativeNautilusProgram.food(cir.getReturnValue());
    }
}
