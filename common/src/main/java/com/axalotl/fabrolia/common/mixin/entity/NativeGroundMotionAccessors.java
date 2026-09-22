package com.axalotl.fabrolia.common.mixin.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Source-owned raw views. None of these methods acquire spatial authority. */
public final class NativeGroundMotionAccessors {
    private NativeGroundMotionAccessors() {}

    @Mixin(Entity.class)
    public interface Geometry {
        @Accessor("dimensions") EntityDimensions fabrolia$groundDimensions();
        @Accessor("insideEffectCollector") InsideBlockEffectApplier.StepBasedCollector fabrolia$groundEffects();
    }

    @Mixin(LivingEntity.class)
    public interface Living {
        @Accessor("autoSpinAttackTicks") int fabrolia$groundSpinTicks();
    }

    @Mixin(Sheep.class)
    public interface SheepProgram {
        @Accessor("eatBlockGoal") EatBlockGoal fabrolia$groundEatBlockGoal();
    }

    @Mixin(net.minecraft.world.entity.monster.Creeper.class)
    public interface CreeperProgram {
        @Accessor("swell") int fabrolia$groundSwell();
        @Accessor("maxSwell") int fabrolia$groundMaxSwell();
    }

    @Mixin(AttributeMap.class)
    public interface AttributeValues {
        @Accessor("attributes") Map<Holder<Attribute>, AttributeInstance> fabrolia$groundAttributes();
        @Accessor("supplier") AttributeSupplier fabrolia$groundAttributeSupplier();
    }

    @Mixin(AttributeSupplier.class)
    public interface AttributeDefaults {
        @Accessor("instances") Map<Holder<Attribute>, AttributeInstance> fabrolia$groundDefaultAttributes();
    }

    @Mixin(AttributeInstance.class)
    public interface AttributeDirty {
        @Accessor("onDirty") java.util.function.Consumer<AttributeInstance> fabrolia$groundAttributeDirty();
    }

    @Mixin(Mob.class)
    public interface Controls {
        @Accessor("bodyRotationControl") BodyRotationControl fabrolia$groundBodyControl();
    }

    @Mixin(MoveControl.class)
    public interface Move {
        @Accessor("mob") Mob fabrolia$groundMoveMob();
        @Accessor("strafeForwards") float fabrolia$groundStrafeForwards();
        @Accessor("strafeRight") float fabrolia$groundStrafeRight();
    }

    @Mixin(targets = "net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitMoveControl")
    public interface RabbitMove {
        @Accessor("nextJumpSpeed") double fabrolia$groundNextJumpSpeed();
    }

    @Mixin(net.minecraft.world.entity.animal.rabbit.Rabbit.RabbitJumpControl.class)
    public interface RabbitJump {
        @Accessor("rabbit") net.minecraft.world.entity.animal.rabbit.Rabbit fabrolia$groundJumpRabbit();
    }

    @Mixin(LookControl.class)
    public interface Look {
        @Accessor("mob") Mob fabrolia$groundLookMob();
        @Accessor("wantedX") double fabrolia$groundLookX();
        @Accessor("wantedY") double fabrolia$groundLookY();
        @Accessor("wantedZ") double fabrolia$groundLookZ();
        @Accessor("yMaxRotSpeed") float fabrolia$groundLookSpeed();
        @Accessor("xMaxRotAngle") float fabrolia$groundLookAngle();
    }

    @Mixin(JumpControl.class)
    public interface Jump {
        @Accessor("mob") Mob fabrolia$groundJumpMob();
    }

    @Mixin(BodyRotationControl.class)
    public interface Body {
        @Accessor("mob") Mob fabrolia$groundBodyMob();
    }

    @Mixin(Sensing.class)
    public interface Sense {
        @Accessor("mob") Mob fabrolia$groundSensingMob();
    }

    @Mixin(PathNavigation.class)
    public interface Navigation {
        @Accessor("speedModifier") double fabrolia$groundNavigationSpeed();
        @Accessor("pathFinder") PathFinder fabrolia$groundPathFinder();
        @Accessor("requiredPathLength") float fabrolia$groundRequiredPathLength();
        @Accessor("targetPos") BlockPos fabrolia$groundNavigationTarget();
    }

    @Mixin(net.minecraft.world.level.pathfinder.Path.class)
    public interface Nodes {
        @Accessor("nodes") List<net.minecraft.world.level.pathfinder.Node> fabrolia$groundPathNodes();
    }

    @Mixin(PathFinder.class)
    public interface Finder {
        @Accessor("nodeEvaluator") NodeEvaluator fabrolia$groundFinderEvaluator();
        @Accessor("captureDebug") java.util.function.BooleanSupplier fabrolia$groundCaptureDebug();
    }

    @Mixin(NodeEvaluator.class)
    public interface Evaluator {
        @Accessor("mob") Mob fabrolia$groundEvaluatorMob();
        @Accessor("currentContext") PathfindingContext fabrolia$groundEvaluatorContext();
    }

    @Mixin(InsideBlockEffectApplier.StepBasedCollector.class)
    public interface Effects {
        @Accessor("effectsInStep") Set<InsideBlockEffectType> fabrolia$groundEffectsInStep();
        @Accessor("beforeEffectsInStep") Map<InsideBlockEffectType, List<java.util.function.Consumer<Entity>>> fabrolia$groundBeforeEffects();
        @Accessor("afterEffectsInStep") Map<InsideBlockEffectType, List<java.util.function.Consumer<Entity>>> fabrolia$groundAfterEffects();
        @Accessor("finalEffects") List<java.util.function.Consumer<Entity>> fabrolia$groundFinalEffects();
    }

    @Mixin(BlockBehaviour.BlockStateBase.class)
    public interface Suffocation {
        @Accessor("isSuffocating") BlockBehaviour.StatePredicate fabrolia$groundSuffocation();
    }

    @Mixin(BlockBehaviour.Properties.class)
    public interface SuffocationProperties {
        @Accessor("isSuffocating") BlockBehaviour.StatePredicate fabrolia$groundDefaultSuffocation();
    }
}
