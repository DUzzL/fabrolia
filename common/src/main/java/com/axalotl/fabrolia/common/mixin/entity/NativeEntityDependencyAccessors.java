package com.axalotl.fabrolia.common.mixin.entity;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.entity.UniquelyIdentifyable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw, non-resolving views used only while the source entity is owned. */
public final class NativeEntityDependencyAccessors {
    private NativeEntityDependencyAccessors() {}

    @Mixin(EntityReference.class)
    public interface Reference {
        @Accessor("entity") Either<UUID, ? extends UniquelyIdentifyable> fabrolia$dependencyValue();
    }

    @Mixin(LivingEntity.class)
    public interface Living {
        @Accessor("lastHurtByPlayer") EntityReference<Player> fabrolia$lastHurtByPlayer();
        @Accessor("lastHurtByMob") EntityReference<LivingEntity> fabrolia$lastHurtByMob();
        @Accessor("lastHurtMob") LivingEntity fabrolia$lastHurtMob();
        @Accessor("lastDamageSource") DamageSource fabrolia$lastDamageSource();
        @Accessor("recentKineticEnemies") Object2LongMap<Entity> fabrolia$recentKineticEnemies();
        @Accessor("lastEquipmentItems") Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> fabrolia$lastEquipmentItems();
    }

    @Mixin(CombatTracker.class)
    public interface Combat {
        @Accessor("entries") List<CombatEntry> fabrolia$combatEntries();
    }

    @Mixin(Mob.class)
    public interface MobTarget {
        @Accessor("target") LivingEntity fabrolia$rawTarget();
        @Accessor("targetSelector") net.minecraft.world.entity.ai.goal.GoalSelector fabrolia$targetSelector();
    }

    @Mixin({AreaEffectCloud.class, PrimedTnt.class, EvokerFangs.class})
    public interface TraceableOwner {
        @Accessor("owner") EntityReference<LivingEntity> fabrolia$traceableOwner();
    }

    @Mixin(ItemEntity.class)
    public interface ItemThrower {
        @Accessor("thrower") EntityReference<Entity> fabrolia$itemThrower();
    }

    @Mixin(Projectile.class)
    public interface ProjectileDeflector {
        @Accessor("lastDeflectedBy") Entity fabrolia$lastDeflectedBy();
    }

    @Mixin(FishingHook.class)
    public interface HookTarget {
        @Accessor("hookedIn") Entity fabrolia$hookedIn();
    }

    @Mixin(FireworkRocketEntity.class)
    public interface FireworkTarget {
        @Accessor("attachedToEntity") LivingEntity fabrolia$attachedToEntity();
    }

    @Mixin(ShulkerBullet.class)
    public interface BulletTarget {
        @Accessor("finalTarget") EntityReference<Entity> fabrolia$finalTarget();
    }

    @Mixin(AbstractArrow.class)
    public interface ArrowVictims {
        @Accessor("piercedAndKilledEntities") List<Entity> fabrolia$piercedAndKilledEntities();
    }

    @Mixin(Animal.class)
    public interface BreedingPlayer {
        @Accessor("loveCause") EntityReference<net.minecraft.server.level.ServerPlayer> fabrolia$loveCause();
    }

    @Mixin(Brain.class)
    public interface BrainMemories {
        @Accessor("memories") Map<MemoryModuleType<?>, MemorySlot<?>> fabrolia$memories();
        @Accessor("sensors") Map<net.minecraft.world.entity.ai.sensing.SensorType<?>, net.minecraft.world.entity.ai.sensing.Sensor<?>> fabrolia$sensors();
    }

    @Mixin(net.minecraft.world.entity.ExperienceOrb.class)
    public interface OrbPlayer {
        @Accessor("followingPlayer") Player fabrolia$followingPlayer();
    }

    @Mixin(net.minecraft.world.entity.LightningBolt.class)
    public interface LightningTargets {
        @Accessor("cause") net.minecraft.server.level.ServerPlayer fabrolia$cause();
        @Accessor("hitEntities") java.util.Set<Entity> fabrolia$hitEntities();
    }

    @Mixin(net.minecraft.world.entity.animal.equine.Llama.class)
    public interface Caravan {
        @Accessor("caravanHead") net.minecraft.world.entity.animal.equine.Llama fabrolia$caravanHead();
        @Accessor("caravanTail") net.minecraft.world.entity.animal.equine.Llama fabrolia$caravanTail();
    }

    @Mixin(net.minecraft.world.entity.animal.fish.AbstractSchoolingFish.class)
    public interface SchoolLeader {
        @Accessor("leader") net.minecraft.world.entity.animal.fish.AbstractSchoolingFish fabrolia$schoolLeader();
        @Accessor("schoolSize") int fabrolia$schoolSize();
    }

    @Mixin(net.minecraft.world.entity.monster.illager.Evoker.class)
    public interface WololoTarget {
        @Accessor("wololoTarget") net.minecraft.world.entity.animal.sheep.Sheep fabrolia$wololoTarget();
    }

    @Mixin(net.minecraft.world.entity.npc.villager.Villager.class)
    public interface LastTradingPlayer {
        @Accessor("lastTradedPlayer") Player fabrolia$lastTradedPlayer();
    }

    @Mixin(net.minecraft.world.entity.ambient.Bat.class)
    public interface BatQuery {
        @Accessor("targetPosition") net.minecraft.core.BlockPos fabrolia$flightTarget();
        @Accessor("BAT_RESTING_TARGETING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$restingTargeting() {
            throw new AssertionError();
        }
    }
    @Mixin(net.minecraft.world.entity.ai.sensing.TemptingSensor.class)
    public interface TemptingQuery {
        @Accessor("TEMPT_TARGETING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$temptingTargeting() {
            throw new AssertionError();
        }
    }
    @Mixin(net.minecraft.world.level.block.state.BlockBehaviour.class)
    public interface BlockCollision {
        @Accessor("hasCollision") boolean fabrolia$hasCollision();
    }
    @Mixin(net.minecraft.world.entity.ai.sensing.Sensor.class)
    public interface SensorQueries {
        @Accessor("TARGET_CONDITIONS")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$targetConditions() { throw new AssertionError(); }
        @Accessor("TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$visibleTargetConditions() { throw new AssertionError(); }
        @Accessor("ATTACK_TARGET_CONDITIONS")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$attackConditions() { throw new AssertionError(); }
        @Accessor("ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$visibleAttackConditions() { throw new AssertionError(); }
        @Accessor("ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$unseenAttackConditions() { throw new AssertionError(); }
        @Accessor("ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$visibleUnseenAttackConditions() { throw new AssertionError(); }
    }
    @Mixin(net.minecraft.server.level.ServerLevel.class)
    public interface EventLevel {
        @Accessor("gameEventDispatcher")
        net.minecraft.world.level.gameevent.GameEventDispatcher fabrolia$gameEventDispatcher();
    }

    @Mixin(net.minecraft.world.level.gameevent.GameEventDispatcher.class)
    public interface EventDispatcher {
        @Accessor("level") net.minecraft.server.level.ServerLevel fabrolia$eventLevel();
    }

    @Mixin(net.minecraft.world.level.chunk.LevelChunk.class)
    public interface EventChunk {
        @Accessor("gameEventListenerRegistrySections")
        it.unimi.dsi.fastutil.ints.Int2ObjectMap<net.minecraft.world.level.gameevent.GameEventListenerRegistry> fabrolia$eventRegistries();
    }

    @Mixin(net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry.class)
    public interface EventRegistry {
        @Accessor("level") net.minecraft.server.level.ServerLevel fabrolia$registryLevel();
        @Accessor("sectionY") int fabrolia$registrySection();
        @Accessor("processing") boolean fabrolia$processingEvents();
        @Accessor("listeners") java.util.List<net.minecraft.world.level.gameevent.GameEventListener> fabrolia$eventListeners();
        @Accessor("listenersToAdd") java.util.List<net.minecraft.world.level.gameevent.GameEventListener> fabrolia$addedEventListeners();
        @Accessor("listenersToRemove") java.util.Set<net.minecraft.world.level.gameevent.GameEventListener> fabrolia$removedEventListeners();
    }





    @Mixin(net.minecraft.world.entity.ai.navigation.PathNavigation.class)
    public interface Navigation {
        @Accessor("mob") Mob fabrolia$navigationMob();
        @Accessor("level") net.minecraft.world.level.Level fabrolia$navigationLevel();
        @Accessor("path") net.minecraft.world.level.pathfinder.Path fabrolia$navigationPath();
        @Accessor("hasDelayedRecomputation") boolean fabrolia$hasDelayedRecomputation();
    }

    @Mixin(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class)
    public interface Conductor {
        @Accessor("isRedstoneConductor")
        net.minecraft.world.level.block.state.BlockBehaviour.StatePredicate fabrolia$conductorPredicate();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.GoalSelector.class)
    public interface GoalLocks {
        @Accessor("lockedFlags")
        java.util.Map<net.minecraft.world.entity.ai.goal.Goal.Flag,
                net.minecraft.world.entity.ai.goal.WrappedGoal> fabrolia$lockedGoals();
    }

}
