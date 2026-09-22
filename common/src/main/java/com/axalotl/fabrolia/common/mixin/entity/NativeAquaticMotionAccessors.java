package com.axalotl.fabrolia.common.mixin.entity;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Source-owned raw state only; these views never resolve an entity or run a controller. */
public final class NativeAquaticMotionAccessors {
    private NativeAquaticMotionAccessors() {}

    @Mixin(Entity.class)
    public interface Geometry {
        @Accessor("dimensions") EntityDimensions fabrolia$aquaticDimensions();
        @Accessor("eyeHeight") float fabrolia$aquaticEyeHeight();
        @Accessor("fluidInteraction") EntityFluidInteraction fabrolia$aquaticFluids();
        @Accessor("insideEffectCollector") InsideBlockEffectApplier.StepBasedCollector fabrolia$aquaticEffects();
        @Accessor("wasTouchingWater") boolean fabrolia$aquaticWasTouchingWater();
        @Accessor("firstTick") boolean fabrolia$aquaticFirstTick();
        @Accessor("nextStep") float fabrolia$aquaticNextStep();
    }

    @Mixin(LivingEntity.class)
    public interface Living {
        @Accessor("autoSpinAttackTicks") int fabrolia$aquaticSpinTicks();
        @Accessor("equipment") EntityEquipment fabrolia$aquaticEquipment();
    }

    @Mixin(Mob.class)
    public interface Body {
        @Accessor("bodyRotationControl") BodyRotationControl fabrolia$aquaticBodyControl();
    }

    @Mixin(MoveControl.class)
    public interface Move {
        @Accessor("mob") Mob fabrolia$aquaticMoveSource();
        @Accessor("speedModifier") double fabrolia$aquaticMoveSpeed();
        @Accessor("wantedX") double fabrolia$aquaticWantedX();
        @Accessor("wantedY") double fabrolia$aquaticWantedY();
        @Accessor("wantedZ") double fabrolia$aquaticWantedZ();
    }

    @Mixin(SmoothSwimmingMoveControl.class)
    public interface Swimming {
        @Accessor("inWaterSpeedModifier") float fabrolia$aquaticWaterSpeed();
        @Accessor("outsideWaterSpeedModifier") float fabrolia$aquaticLandSpeed();
    }

    @Mixin(LookControl.class)
    public interface Look {
        @Accessor("mob") Mob fabrolia$aquaticLookSource();
    }

    @Mixin(JumpControl.class)
    public interface Jump {
        @Accessor("mob") Mob fabrolia$aquaticJumpSource();
        @Accessor("jump") boolean fabrolia$aquaticPendingJump();
    }

    @Mixin(BodyRotationControl.class)
    public interface Rotation {
        @Accessor("mob") Mob fabrolia$aquaticRotationSource();
    }

    @Mixin(PathNavigation.class)
    public interface Navigation {
        @Accessor("mob") Mob fabrolia$aquaticNavigationSource();
        @Accessor("level") Level fabrolia$aquaticNavigationLevel();
        @Accessor("speedModifier") double fabrolia$aquaticNavigationSpeed();
        @Accessor("nodeEvaluator") NodeEvaluator fabrolia$aquaticEvaluator();
        @Accessor("pathFinder") PathFinder fabrolia$aquaticPathFinder();
    }

    @Mixin(PathFinder.class)
    public interface Finder {
        @Accessor("nodeEvaluator") NodeEvaluator fabrolia$aquaticFinderEvaluator();
        @Accessor("captureDebug") BooleanSupplier fabrolia$aquaticCaptureDebug();
    }

    @Mixin(Path.class)
    public interface Nodes {
        @Accessor("nodes") List<Node> fabrolia$aquaticNodes();
    }

    @Mixin(AvoidEntityGoal.class)
    public interface Avoid {
        @Accessor("walkSpeedModifier") double fabrolia$aquaticWalkSpeed();
        @Accessor("sprintSpeedModifier") double fabrolia$aquaticSprintSpeed();
        @Accessor("pathNav") PathNavigation fabrolia$aquaticAvoidNavigation();
        @Accessor("path") Path fabrolia$aquaticAvoidPath();
    }

    @Mixin({PanicGoal.class, RandomStrollGoal.class})
    public interface GoalSpeed {
        @Accessor("speedModifier") double fabrolia$aquaticGoalSpeed();
    }

    @Mixin(MoveToTargetSink.class)
    public interface SinkSpeed {
        @Accessor("speedModifier") float fabrolia$aquaticSinkSpeed();
    }

    @Mixin(BlockBehaviour.BlockStateBase.class)
    public interface WaterState {
        @Accessor("isSuffocating") BlockBehaviour.StatePredicate fabrolia$aquaticSuffocation();
    }

    @Mixin(BlockBehaviour.Properties.class)
    public interface WaterProperties {
        @Accessor("isSuffocating") BlockBehaviour.StatePredicate fabrolia$aquaticDefaultSuffocation();
    }

    @Mixin(Squid.class)
    public interface SquidState {
        @Accessor("movementVector") Vec3 fabrolia$aquaticSquidVector();
    }
}
