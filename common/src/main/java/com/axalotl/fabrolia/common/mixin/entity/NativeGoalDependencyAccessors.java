package com.axalotl.fabrolia.common.mixin.entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native goal-owned references, including inactive goals consulted by canUse. */
public final class NativeGoalDependencyAccessors {
    private NativeGoalDependencyAccessors() {}

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.BegGoal", "net.minecraft.world.entity.ai.goal.TemptGoal", "net.minecraft.world.entity.animal.dolphin.Dolphin$DolphinSwimWithPlayerGoal"})
    public interface Player {
        @Accessor("player") net.minecraft.world.entity.player.Player fabrolia$player();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.BreedGoal"})
    public interface Partner {
        @Accessor("partner") net.minecraft.world.entity.animal.Animal fabrolia$partner();
        @Accessor("animal") net.minecraft.world.entity.animal.Animal fabrolia$breedingAnimal();
        @Accessor("partnerClass") Class<? extends net.minecraft.world.entity.animal.Animal> fabrolia$partnerClass();
        @Accessor("level") net.minecraft.server.level.ServerLevel fabrolia$breedingLevel();
        @Accessor("PARTNER_TARGETING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$partnerTargeting() {
            throw new AssertionError();
        }
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.FollowMobGoal"})
    public interface FollowingMob {
        @Accessor("followingMob") net.minecraft.world.entity.Mob fabrolia$followingMob();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.FollowOwnerGoal"})
    public interface Owner {
        @Accessor("owner") net.minecraft.world.entity.LivingEntity fabrolia$owner();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.FollowParentGoal"})
    public interface Parent {
        @Accessor("parent") net.minecraft.world.entity.animal.Animal fabrolia$parent();
        @Accessor("animal") net.minecraft.world.entity.animal.Animal fabrolia$followingAnimal();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.FollowPlayerRiddenEntityGoal"})
    public interface Following {
        @Accessor("following") net.minecraft.world.entity.player.Player fabrolia$following();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.LeapAtTargetGoal", "net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal", "net.minecraft.world.entity.ai.goal.OcelotAttackGoal", "net.minecraft.world.entity.ai.goal.RangedAttackGoal", "net.minecraft.world.entity.ai.goal.SwellGoal", "net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal", "net.minecraft.world.entity.monster.EnderMan$EndermanFreezeWhenLookedAt"})
    public interface Target {
        @Accessor("target") net.minecraft.world.entity.LivingEntity fabrolia$target();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.LookAtPlayerGoal"})
    public interface LookAt {
        @Accessor("lookAt") net.minecraft.world.entity.Entity fabrolia$lookAt();
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$lookingMob();
        @Accessor("lookDistance") float fabrolia$lookDistance();
        @Accessor("lookAtType") Class<? extends net.minecraft.world.entity.LivingEntity> fabrolia$lookAtType();
        @Accessor("lookAtContext") net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$lookAtContext();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.OfferFlowerGoal"})
    public interface Entity {
        @Accessor("entity") net.minecraft.world.entity.LivingEntity fabrolia$entity();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.target.DefendVillageTargetGoal"})
    public interface PotentialTarget {
        @Accessor("potentialTarget") net.minecraft.world.entity.LivingEntity fabrolia$potentialTarget();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal", "net.minecraft.world.entity.animal.equine.TraderLlama$TraderLlamaDefendWanderingTraderGoal"})
    public interface OwnerLastHurtBy {
        @Accessor("ownerLastHurtBy") net.minecraft.world.entity.LivingEntity fabrolia$ownerLastHurtBy();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal"})
    public interface OwnerLastHurt {
        @Accessor("ownerLastHurt") net.minecraft.world.entity.LivingEntity fabrolia$ownerLastHurt();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.target.TargetGoal"})
    public interface TargetMob {
        @Accessor("targetMob") net.minecraft.world.entity.LivingEntity fabrolia$targetMob();
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$targetingMob();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.feline.Cat$CatRelaxOnOwnerGoal"})
    public interface OwnerPlayer {
        @Accessor("ownerPlayer") net.minecraft.world.entity.player.Player fabrolia$ownerPlayer();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.feline.Cat$CatTemptGoal"})
    public interface SelectedPlayer {
        @Accessor("selectedPlayer") net.minecraft.world.entity.player.Player fabrolia$selectedPlayer();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.fox.Fox$DefendTrustedTargetGoal"})
    public interface TrustedLastHurtBy {
        @Accessor("trustedLastHurtBy") net.minecraft.world.entity.LivingEntity fabrolia$trustedLastHurtBy();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.fox.Fox$DefendTrustedTargetGoal"})
    public interface TrustedLastHurt {
        @Accessor("trustedLastHurt") net.minecraft.world.entity.LivingEntity fabrolia$trustedLastHurt();
    }

    @Mixin(targets = {"net.minecraft.world.entity.monster.EnderMan$EndermanLookForPlayerGoal"})
    public interface PendingTarget {
        @Accessor("pendingTarget") net.minecraft.world.entity.player.Player fabrolia$pendingTarget();
    }

    @Mixin(targets = {"net.minecraft.world.entity.monster.cubemob.SulfurCube$SulfurCubeSearchForItemsGoal"})
    public interface TargetItem {
        @Accessor("targetItem") net.minecraft.world.entity.item.ItemEntity fabrolia$targetItem();
    }

    @Mixin(targets = {"net.minecraft.world.entity.raid.Raider$ObtainRaidLeaderBannerGoal"})
    public interface PursuedBannerItemEntity {
        @Accessor("pursuedBannerItemEntity") net.minecraft.world.entity.item.ItemEntity fabrolia$pursuedBannerItemEntity();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.AvoidEntityGoal"})
    public interface ToAvoid {
        @Accessor("toAvoid") net.minecraft.world.entity.LivingEntity fabrolia$toAvoid();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.BegGoal.class)
    public interface BegQuery {
        @Accessor("wolf") net.minecraft.world.entity.animal.wolf.Wolf fabrolia$beggingWolf();
        @Accessor("begTargeting") net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$begTargeting();
        @Accessor("level") net.minecraft.server.level.ServerLevel fabrolia$begLevel();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.TemptGoal.class)
    public interface TemptQuery {
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$temptedMob();
        @Accessor("items") java.util.function.Predicate<net.minecraft.world.item.ItemStack> fabrolia$temptItems();
        @Accessor("targetingConditions") net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$temptTargeting();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal.class)
    public interface NearestTargetQuery {
        @Accessor("targetType") Class<? extends net.minecraft.world.entity.LivingEntity> fabrolia$targetType();
        @Accessor("targetConditions") net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$targetConditions();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.AvoidEntityGoal.class)
    public interface AvoidQuery {
        @Accessor("mob") net.minecraft.world.entity.PathfinderMob fabrolia$avoidingMob();
        @Accessor("maxDist") float fabrolia$avoidDistance();
        @Accessor("avoidClass") Class<? extends net.minecraft.world.entity.LivingEntity> fabrolia$avoidClass();
        @Accessor("avoidPredicate") java.util.function.Predicate<?> fabrolia$avoidPredicate();
        @Accessor("predicateOnAvoidEntity") java.util.function.Predicate<?> fabrolia$predicateOnAvoidEntity();
        @Accessor("avoidEntityTargeting") net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$avoidTargeting();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.PanicGoal.class)
    public interface PanicQuery {
        @Accessor("panicCausingDamageTypes")
        java.util.function.Function<net.minecraft.world.entity.PathfinderMob,
                net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType>> fabrolia$panicCausingDamageTypes();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal.class)
    public interface VillageQuery {
        @Accessor("mob") net.minecraft.world.entity.PathfinderMob fabrolia$villageMob();
        @Accessor("canDealWithDoors") java.util.function.BooleanSupplier fabrolia$canDealWithDoors();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.FloatGoal", "net.minecraft.world.entity.ai.goal.EatBlockGoal", "net.minecraft.world.entity.ai.goal.RandomLookAroundGoal", "net.minecraft.world.entity.ai.goal.LeapAtTargetGoal"})
    public interface MobSource {
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$goalMob();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.PanicGoal", "net.minecraft.world.entity.ai.goal.RandomStrollGoal", "net.minecraft.world.entity.ai.goal.RestrictSunGoal", "net.minecraft.world.entity.ai.goal.FleeSunGoal", "net.minecraft.world.entity.ai.goal.MeleeAttackGoal", "net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal", "net.minecraft.world.entity.ai.goal.MoveToBlockGoal"})
    public interface PathfinderSource {
        @Accessor("mob") net.minecraft.world.entity.PathfinderMob fabrolia$goalPathfinder();
    }

    @Mixin({net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal.class,
            net.minecraft.world.entity.ai.goal.RandomStandGoal.class})
    public interface HorseSource {
        @Accessor("horse") net.minecraft.world.entity.animal.equine.AbstractHorse fabrolia$goalHorse();
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.equine.AbstractHorse$MountPanicGoal")
    public interface MountPanicSource {
        @Accessor("this$0") net.minecraft.world.entity.animal.equine.AbstractHorse fabrolia$panickingHorse();
    }

    @Mixin(targets = {"net.minecraft.world.entity.ai.goal.SpearUseGoal", "net.minecraft.world.entity.ai.goal.RangedBowAttackGoal"})
    public interface MonsterSource {
        @Accessor("mob") net.minecraft.world.entity.monster.Monster fabrolia$goalMonster();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.SwellGoal.class)
    public interface CreeperSource {
        @Accessor("creeper") net.minecraft.world.entity.monster.Creeper fabrolia$goalCreeper();
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.dolphin.Dolphin$DolphinSwimWithPlayerGoal")
    public interface DolphinSource {
        @Accessor("dolphin") net.minecraft.world.entity.animal.dolphin.Dolphin fabrolia$goalDolphin();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitPanicGoal", "net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitAvoidEntityGoal", "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal"})
    public interface RabbitSource {
        @Accessor("rabbit") net.minecraft.world.entity.animal.rabbit.Rabbit fabrolia$goalRabbit();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearMeleeAttackGoal", "net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearHurtByTargetGoal", "net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearAttackPlayersGoal"})
    public interface PolarBearSource {
        @Accessor("this$0") net.minecraft.world.entity.animal.polarbear.PolarBear fabrolia$goalPolarBear();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.ClimbOnTopOfPowderSnowGoal.class)
    public interface PowderSnowSource {
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$climbingMob();
        @Accessor("level") net.minecraft.world.level.Level fabrolia$climbingLevel();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.MoveToBlockGoal.class)
    public interface BlockTarget {
        @Accessor("blockPos") net.minecraft.core.BlockPos fabrolia$goalBlockPos();
        @Accessor("searchRange") int fabrolia$blockSearchRange();
        @Accessor("verticalSearchRange") int fabrolia$verticalSearchRange();
        @Accessor("verticalSearchStart") int fabrolia$verticalSearchStart();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal.class)
    public interface ResetAngerSource {
        @Accessor("mob") net.minecraft.world.entity.Mob fabrolia$angerMob();
        @Accessor("alertOthersOfSameType") boolean fabrolia$alertOthersOfSameType();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal.class)
    public interface HurtByAlert {
        @Accessor("alertSameType") boolean fabrolia$alertSameType();
        @Accessor("HURT_BY_TARGETING")
        static net.minecraft.world.entity.ai.targeting.TargetingConditions fabrolia$hurtByTargeting() {
            throw new AssertionError();
        }
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.fish.AbstractFish$FishSwimGoal")
    public interface FishSource {
        @Accessor("fish") net.minecraft.world.entity.animal.fish.AbstractFish fabrolia$swimmingFish();
    }

    @Mixin(net.minecraft.world.entity.ai.goal.FollowFlockLeaderGoal.class)
    public interface FlockSource {
        @Accessor("mob") net.minecraft.world.entity.animal.fish.AbstractSchoolingFish fabrolia$flockingFish();
        @Accessor("nextStartTick") int fabrolia$flockNextStartTick();
    }

    @Mixin(targets = {"net.minecraft.world.entity.animal.turtle.Turtle$TurtleBreedGoal", "net.minecraft.world.entity.animal.turtle.Turtle$TurtleLayEggGoal", "net.minecraft.world.entity.animal.turtle.Turtle$TurtleGoToWaterGoal", "net.minecraft.world.entity.animal.turtle.Turtle$TurtleGoHomeGoal", "net.minecraft.world.entity.animal.turtle.Turtle$TurtleTravelGoal", "net.minecraft.world.entity.animal.turtle.Turtle$TurtleRandomStrollGoal"})
    public interface TurtleSource {
        @Accessor("turtle") net.minecraft.world.entity.animal.turtle.Turtle fabrolia$goalTurtle();
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.squid.Squid$SquidRandomMovementGoal")
    public interface SquidSource {
        @Accessor("squid") net.minecraft.world.entity.animal.squid.Squid fabrolia$goalSquid();
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.squid.Squid$SquidFleeGoal")
    public interface SquidFleeSource {
        @Accessor("this$0") net.minecraft.world.entity.animal.squid.Squid fabrolia$fleeingSquid();
    }

    @Mixin(net.minecraft.world.entity.ai.targeting.TargetingConditions.class)
    public interface Targeting {
        @Accessor("checkLineOfSight") boolean fabrolia$queryLineOfSight();
        @Accessor("range") double fabrolia$queryRange();
        @Accessor("testInvisible") boolean fabrolia$queryTestInvisible();
        @Accessor("isCombat") boolean fabrolia$queryCombat();
        @Accessor("selector") net.minecraft.world.entity.ai.targeting.TargetingConditions.Selector fabrolia$querySelector();
    }
}
