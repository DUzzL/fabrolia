package com.axalotl.fabrolia.common.mixin.entity;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw views for the finite Nautilus installation witness; none conveys ownership. */
public final class NativeNautilusProgramAccessors {
    private NativeNautilusProgramAccessors() {}

    @Mixin(LivingEntity.class)
    public interface Source {
        @Accessor("brain") Brain<?> fabrolia$nautilusStoredBrain();
    }

    @Mixin(Brain.class)
    public interface Program {
        @Accessor("availableBehaviorsByPriority") Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> fabrolia$nautilusBehaviors();
        @Accessor("sensors") Map<SensorType<?>, Sensor<?>> fabrolia$nautilusSensors();
        @Accessor("activityRequirements") Map<Activity, Set<Pair<MemoryModuleType<?>, MemoryStatus>>> fabrolia$nautilusRequirements();
        @Accessor("activityMemoriesToEraseWhenStopped") Map<Activity, Set<MemoryModuleType<?>>> fabrolia$nautilusExitMemories();
        @Accessor("coreActivities") Set<Activity> fabrolia$nautilusCore();
        @Accessor("activeActivities") Set<Activity> fabrolia$nautilusActive();
        @Accessor("defaultActivity") Activity fabrolia$nautilusDefault();
        @Accessor("schedule") EnvironmentAttribute<Activity> fabrolia$nautilusSchedule();
    }

    @Mixin(Behavior.class)
    public interface Timed {
        @Accessor("entryCondition") Map<MemoryModuleType<?>, MemoryStatus> fabrolia$nautilusEntry();
        @Accessor("minDuration") int fabrolia$nautilusMinDuration();
        @Accessor("maxDuration") int fabrolia$nautilusMaxDuration();
        @Accessor("status") Behavior.Status fabrolia$nautilusStatus();
    }

    @Mixin(AnimalPanic.class)
    public interface Panic {
        @Accessor("speedMultiplier") float fabrolia$nautilusPanicSpeed();
        @Accessor("panicCausingDamageTypes") Function<PathfinderMob, TagKey<DamageType>> fabrolia$nautilusPanicDamage();
        @Accessor("positionGetter") Function<?, Vec3> fabrolia$nautilusPanicPosition();
    }

    @Mixin(MoveToTargetSink.class)
    public interface Movement {
        @Accessor("path") Path fabrolia$nautilusPath();
        @Accessor("lastTargetPos") BlockPos fabrolia$nautilusLastTargetPos();
    }

    @Mixin(CountDownCooldownTicks.class)
    public interface Cooldown {
        @Accessor("cooldownTicks") MemoryModuleType<Integer> fabrolia$nautilusCooldown();
    }

    @Mixin(AnimalMakeLove.class)
    public interface Breeding {
        @Accessor("partnerType") EntityType<? extends Animal> fabrolia$nautilusPartnerType();
        @Accessor("speedModifier") float fabrolia$nautilusBreedingSpeed();
        @Accessor("closeEnoughDistance") int fabrolia$nautilusBreedingDistance();
    }

    @Mixin(FollowTemptation.class)
    public interface Following {
        @Accessor("speedModifier") Function<LivingEntity, Float> fabrolia$nautilusFollowingSpeed();
        @Accessor("closeEnoughDistance") Function<LivingEntity, Double> fabrolia$nautilusFollowingDistance();
        @Accessor("lookInTheEyes") boolean fabrolia$nautilusFollowingEyes();
    }

    @Mixin(ChargeAttack.class)
    public interface Charge {
        @Accessor("timeBetweenAttacks") int fabrolia$nautilusAttackInterval();
        @Accessor("chargeTargeting") TargetingConditions fabrolia$nautilusChargeTargeting();
        @Accessor("speed") float fabrolia$nautilusChargeSpeed();
        @Accessor("knockbackForce") float fabrolia$nautilusKnockback();
        @Accessor("maxTargetDetectionDistance") double fabrolia$nautilusDetectionDistance();
        @Accessor("maxChargeDistance") double fabrolia$nautilusChargeDistance();
        @Accessor("chargeSound") SoundEvent fabrolia$nautilusChargeSound();
        @Accessor("chargeVelocityVector") Vec3 fabrolia$nautilusChargeVelocity();
        @Accessor("startPosition") Vec3 fabrolia$nautilusChargeStart();
    }

    @Mixin(GateBehavior.class)
    public interface Gate {
        @Accessor("entryCondition") Map<MemoryModuleType<?>, MemoryStatus> fabrolia$nautilusGateEntry();
        @Accessor("exitErasedMemories") Set<MemoryModuleType<?>> fabrolia$nautilusGateExit();
        @Accessor("orderPolicy") GateBehavior.OrderPolicy fabrolia$nautilusGateOrder();
        @Accessor("runningPolicy") GateBehavior.RunningPolicy fabrolia$nautilusGateRunning();
        @Accessor("behaviors") ShufflingList<?> fabrolia$nautilusGateChildren();
    }

    @Mixin(ShufflingList.class)
    public interface Weighted {
        @Accessor("entries") List<? extends ShufflingList.WeightedEntry<?>> fabrolia$nautilusWeightedEntries();
    }

    @Mixin(Sensor.class)
    public interface Cadence {
        @Accessor("scanRate") int fabrolia$nautilusScanRate();
    }

    @Mixin(TemptingSensor.class)
    public interface Temptations {
        @Accessor("temptations") BiPredicate<PathfinderMob, ItemStack> fabrolia$nautilusTemptations();
    }

    @Mixin(NearestVisibleLivingEntities.class)
    public interface Visible {
        @Accessor("nearbyEntities") List<LivingEntity> fabrolia$nautilusVisibleList();
        @Accessor("lineOfSightTest") Predicate<LivingEntity> fabrolia$nautilusVisiblePredicate();
    }
}
