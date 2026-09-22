package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.axalotl.fabrolia.common.regions.NativeSensorTargetingAccess;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Isolates all six mutable Sensor prototypes without replacing native sensor methods.
 * Each field has one range-writer read and one targetability-reader read. Both select
 * the actual source's persistent copy; the original range writes and tests still run.
 *
 * <p>A ThreadLocal would lose the last native range update when a source migrates to
 * another worker and a retained NearestVisibleLivingEntities predicate runs before
 * the next due sensor scan. Source ownership preserves that update cadence without
 * extra attribute reads, callback changes, or locks around native calls.
 */
@Mixin(value = Sensor.class, priority = 1500)
public abstract class SensorTargetingConditionsMixin {
    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityTargetable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceTargetConditions(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(0, prototype);
    }

    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityTargetable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceTargetConditionsIgnoringInvisibility(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(1, prototype);
    }

    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityAttackable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceAttackConditions(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(2, prototype);
    }

    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityAttackable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceAttackConditionsIgnoringInvisibility(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(3, prototype);
    }

    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityAttackableIgnoringLineOfSight(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceAttackConditionsIgnoringLineOfSight(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(4, prototype);
    }

    @ModifyExpressionValue(
            method = {
                    "updateTargetingConditionRanges(Lnet/minecraft/world/entity/LivingEntity;)V",
                    "isEntityAttackableIgnoringLineOfSight(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;)Z"
            },
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/world/entity/ai/sensing/Sensor;ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT:Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"),
            require = 2,
            allow = 2)
    private static TargetingConditions fabrolia$sourceAttackConditionsIgnoringInvisibilityAndLineOfSight(
            TargetingConditions prototype, @Local(argsOnly = true, ordinal = 0) LivingEntity source) {
        return ((NativeSensorTargetingAccess) source).fabrolia$sensorCondition(5, prototype);
    }
}
