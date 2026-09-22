package com.axalotl.fabrolia.common.mixin.entity.sensor;

import com.axalotl.fabrolia.common.regions.NativeSensorTargetingAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps native sensor range updates with their source when island workers change. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySensorStateMixin implements NativeSensorTargetingAccess {
    @Unique
    private TargetingConditions[] fabrolia$sensorConditions;

    @Override
    public final TargetingConditions fabrolia$sensorCondition(int slot, TargetingConditions prototype) {
        // The caller already owns this source; this storage grants no target-read permission.
        TargetingConditions[] conditions = this.fabrolia$sensorConditions;
        if (conditions == null) {
            conditions = new TargetingConditions[6];
            this.fabrolia$sensorConditions = conditions;
        }
        TargetingConditions condition = conditions[slot];
        if (condition == null) {
            condition = prototype.copy();
            conditions[slot] = condition;
        }
        return condition;
    }

    @Override
    public final TargetingConditions fabrolia$existingSensorCondition(int slot, TargetingConditions prototype) {
        TargetingConditions[] conditions = this.fabrolia$sensorConditions;
        return conditions == null || conditions[slot] == null ? prototype : conditions[slot];
    }
}
