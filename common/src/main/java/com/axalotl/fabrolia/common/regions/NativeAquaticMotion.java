package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.EntityMovementAccessor;
import com.axalotl.fabrolia.common.mixin.entity.NativeAquaticMotionAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeEntityDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeGoalDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeNautilusProgramAccessors;
import com.axalotl.fabrolia.common.mixin.entity.PlayerMotionLivingAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FollowFlockLeaderGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.animal.fish.Cod;
import net.minecraft.world.entity.animal.fish.Salmon;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.nautilus.Nautilus;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A source-program proof, not a movement predictor or permission to query a recipient.
 * The caller retains source/program ownership, the live mod-callback envelope and
 * every goal/navigation query proof. It must also census collision/contact recipients
 * over the returned envelope, including a proof against cramming damage callbacks.
 * Unknown programs and relocation prefixes keep the original complete Main E.
 *
 * Native 26.2: AbstractFish.travelInWater performs one move after moveRelative(0.01F).
 * FishMoveControl adds 0.005 buoyancy and speed * (yd / length) * 0.1 vertically.
 * AbstractNautilus instead moveRelative(getSpeed()), after its exact smooth control.
 * Squid/GlowSquid travel ignores input entirely; movementVector is applied only
 * after super.aiStep has travelled. All supported families ignore fluid push,
 * but EntityFluidInteraction still refreshes water membership.
 * The owned source-water corridor below proves that refresh and excludes dry travel,
 * bubble/portal/terrain callbacks; it is not inferred from stale isInWater().
 */
final class NativeAquaticMotion {
    private NativeAquaticMotion() {}

    private static final double CONTACT = 1.0E-7;
    private static final double STEP_DOWN = (double) 1.0E-5F;
    private static final Class<?> FISH_MOVE = nativeType("net.minecraft.world.entity.animal.fish.AbstractFish$FishMoveControl");
    private static final Class<?> FISH_SWIM = nativeType("net.minecraft.world.entity.animal.fish.AbstractFish$FishSwimGoal");
    private static final Class<?> SQUID_RANDOM = nativeType("net.minecraft.world.entity.animal.squid.Squid$SquidRandomMovementGoal");
    private static final Class<?> SQUID_FLEE = nativeType("net.minecraft.world.entity.animal.squid.Squid$SquidFleeGoal");
    private static final ThreadLocal<BlockPos.MutableBlockPos> POSITION =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final Class<?> SUFFOCATION = defaultSuffocation();
    private static final Field MOVE_OPERATION = moveOperation();

    private static Class<?> defaultSuffocation() {
        Object predicate = ((NativeAquaticMotionAccessors.WaterProperties) BlockBehaviour.Properties.of())
                .fabrolia$aquaticDefaultSuffocation();
        Class<?> type = predicate.getClass();
        return type.isHidden() && type.getNestHost() == BlockBehaviour.class
                && type.getDeclaredFields().length == 0 && nativeClass(BlockBehaviour.class) ? type : null;
    }

    private static Field moveOperation() {
        try {
            Field field = MoveControl.class.getDeclaredField("operation");
            return field.getType().isEnum() && field.getType().getEnclosingClass() == MoveControl.class
                    && field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            return null;
        }
    }

    private static boolean nonStrafing(MoveControl control) {
        if (MOVE_OPERATION == null) return false;
        try {
            Object operation = MOVE_OPERATION.get(control);
            return operation instanceof Enum<?> value && value.getDeclaringClass() == MOVE_OPERATION.getType()
                    && (value.name().equals("WAIT") || value.name().equals("MOVE_TO") || value.name().equals("JUMPING"));
        } catch (IllegalAccessException incompatible) {
            return false;
        }
    }

    private static Class<?> nativeType(String name) {
        try {
            Class<?> type = Class.forName(name, false, Entity.class.getClassLoader());
            return nativeClass(type) ? type : null;
        } catch (ClassNotFoundException | LinkageError incompatible) {
            return null;
        }
    }

    private static boolean nativeClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        var nativeSource = Entity.class.getProtectionDomain().getCodeSource();
        return source != null && nativeSource != null && source.equals(nativeSource)
                && type.getClassLoader() == Entity.class.getClassLoader();
    }

    /* PathNavigation has exactly one lambda in 26.2: its constructor's debug
       subscriber predicate. Validate its actual captured receiver, never execute it. */
    private static final ClassValue<Field[]> DEBUG_CAPTURE = new ClassValue<>() {
        @Override protected Field[] computeValue(Class<?> type) {
            if (!type.isHidden() || type.getNestHost() != PathNavigation.class
                    || !nativeClass(PathNavigation.class)) return new Field[0];
            Field[] fields = type.getDeclaredFields();
            if (fields.length != 1 || Modifier.isStatic(fields[0].getModifiers())
                    || !Modifier.isFinal(fields[0].getModifiers()) || !fields[0].trySetAccessible()) return new Field[0];
            return fields;
        }
    };

    static boolean bounds(Entity entity, NativeEntityMotion.Access access, NativeEntityMotion.Bounds out) {
        Class<?> type = entity.getClass();
        boolean fish = type == Cod.class || type == Salmon.class || type == TropicalFish.class;
        boolean squid = type == Squid.class || type == GlowSquid.class;
        if (!fish && !squid && type != Nautilus.class || !nativeClass(type)) return false;
        Mob source = (Mob) entity;
        if (source.level() != access.level()
                || type == Cod.class && source.getType() != EntityTypes.COD
                || type == Salmon.class && source.getType() != EntityTypes.SALMON
                || type == TropicalFish.class && source.getType() != EntityTypes.TROPICAL_FISH
                || type == Nautilus.class && source.getType() != EntityTypes.NAUTILUS
                || type == Squid.class && source.getType() != EntityTypes.SQUID
                || type == GlowSquid.class && source.getType() != EntityTypes.GLOW_SQUID
                || !prefix(source, squid) || !geometry(source) || !controls(source, fish, squid, access)) return false;

        double inputScale;
        double verticalControl;
        double buoyancy;
        if (squid) {
            if (!squidProgram((Squid) source)
                    || !(source.getBbHeight() - 0.002 > source.getFluidJumpThreshold())) return false;
            // The certified source-water refresh fills the deflated fluid box.
            // Thus even a retained/pending/base-control jump uses jumpInLiquid,
            // never jumpFromGround. Squid.travel ignores all speed/input fields.
            inputScale = 0.0;
            verticalControl = (double) 0.04F;
            buoyancy = 0.0;
        } else {
            var move = (NativeAquaticMotionAccessors.Move) source.getMoveControl();
            var navigation = (NativeAquaticMotionAccessors.Navigation) source.getNavigation();
            double modifier = Math.max(move.fabrolia$aquaticMoveSpeed(), navigation.fabrolia$aquaticNavigationSpeed());
            modifier = fish ? fishProgram(source, modifier) : nautilusProgram((Nautilus) source, modifier);
            double attribute = source.getAttributeValue(Attributes.MOVEMENT_SPEED);
            float retainedSpeed = source.getSpeed();
            if (!nonnegative(modifier) || !nonnegative(attribute) || !nonnegative(retainedSpeed)) return false;

            // Both swimming controllers use this actual float target. Outward
            // rounding and twice the maximum enclose the native float lerp,
            // without imposing a speed cap or assuming modifier <= 1.6.
            float target = Math.nextUp((float) Math.nextUp(attribute * modifier));
            double speed = Math.max(retainedSpeed, target);
            if (!(speed <= Float.MAX_VALUE / 4.0)) return false;
            speed = Math.nextUp(speed * 2.0);
            inputScale = (double) 0.01F;
            verticalControl = multiply(0.2, speed);
            buoyancy = 0.005;
            if (!fish) {
                var smooth = (NativeAquaticMotionAccessors.Swimming) source.getMoveControl();
                float waterScale = smooth.fabrolia$aquaticWaterSpeed();
                if (!nonnegative(waterScale) || !Float.isFinite(smooth.fabrolia$aquaticLandSpeed())) return false;
                // The close-target branch retains getSpeed(); MOVE_TO sets the
                // actual float target times the actual in-water multiplier.
                float swimmingSpeed = Math.nextUp(target * waterScale);
                if (!nonnegative(swimmingSpeed)) return false;
                inputScale = Math.nextUp(Math.max(retainedSpeed, swimmingSpeed));
                verticalControl = 0.0;
            }
        }

        Vec3 velocity = source.getDeltaMovement();
        // Native normalization's finite components are bounded by two. Rotation
        // uses two products in X/Z. Nonfinite candidate velocities are ignored by
        // Entity.setDeltaMovement, leaving the already bounded preceding value.
        double dx = add(Math.abs(velocity.x), multiply(4.0, inputScale));
        double dy = add(add(Math.abs(velocity.y), buoyancy), add(verticalControl, multiply(2.0, inputScale)));
        double dz = add(Math.abs(velocity.z), multiply(4.0, inputScale));
        double step = (float) source.getAttributeValue(Attributes.STEP_HEIGHT);
        if (!nonnegative(step)) return false;
        dx = add(dx, 2.0 * CONTACT);
        dz = add(dz, 2.0 * CONTACT);
        dy = add(dy, add(step, add(STEP_DOWN, 2.0 * CONTACT)));
        if (!nonnegative(dx) || !nonnegative(dy) || !nonnegative(dz)) return false;

        var box = source.getBoundingBox();
        double halfWidth = source.getBbWidth() / 2.0F;
        // Include the fallback oldPosition -> position inside-block sweep. The
        // normal server wrapper resets old position before E, but that ordering
        // is not required for this certificate, and no stale history is ignored.
        double minX = Math.min(Math.nextDown(box.minX - dx), Math.nextDown(source.xOld - halfWidth));
        double minY = Math.min(Math.nextDown(box.minY - dy), Math.nextDown(source.yOld));
        double minZ = Math.min(Math.nextDown(box.minZ - dz), Math.nextDown(source.zOld - halfWidth));
        double maxX = Math.max(Math.nextUp(box.maxX + dx), Math.nextUp(source.xOld + halfWidth));
        double maxY = Math.max(Math.nextUp(box.maxY + dy), Math.nextUp(source.yOld + source.getBbHeight()));
        double maxZ = Math.max(Math.nextUp(box.maxZ + dz), Math.nextUp(source.zOld + halfWidth));
        // setPos rebuilds geometry as (position + movement) +/- dimensions,
        // whereas collision scans translate the cached box. Enclose both actual
        // floating-point evaluation orders, including at large coordinates.
        minX = Math.min(minX, Math.nextDown(Math.nextDown(source.getX() - dx) - halfWidth));
        minZ = Math.min(minZ, Math.nextDown(Math.nextDown(source.getZ() - dz) - halfWidth));
        maxX = Math.max(maxX, Math.nextUp(Math.nextUp(source.getX() + dx) + halfWidth));
        maxY = Math.max(maxY, Math.nextUp(Math.nextUp(source.getY() + dy) + source.getBbHeight()));
        maxZ = Math.max(maxZ, Math.nextUp(Math.nextUp(source.getZ() + dz) + halfWidth));
        if (!sourceWater(access, minX, minY, minZ, maxX, maxY, maxZ)) return false;
        out.set(minX, minY, minZ, maxX, maxY, maxZ);
        var raw = (NativeAquaticMotionAccessors.Geometry) source;
        boolean splash = !raw.fabrolia$aquaticWasTouchingWater() && !raw.fabrolia$aquaticFirstTick();
        // SWIM/STEP/SPLASH originate at source.position(); native registration
        // gives radius 16, but retain the actual registered notification radius.
        // Empty live registries prove callback absence without predicting delivery.
        if (!splash && noMovementEvent(source, dx, dz)) return true;
        int radius = Math.max(GameEvent.SWIM.value().notificationRadius(), GameEvent.STEP.value().notificationRadius());
        if (splash) radius = Math.max(radius, GameEvent.SPLASH.value().notificationRadius());
        return radius >= 0 && access.hasNoGameEventListeners(out, radius);
    }

    private static boolean prefix(Mob source, boolean squid) {
        var geometry = (NativeAquaticMotionAccessors.Geometry) source;
        var living = (NativeAquaticMotionAccessors.Living) source;
        var items = (PlayerMotionLivingAccessor) source;
        var movement = (EntityMovementAccessor) source;
        Vec3 velocity = source.getDeltaMovement();
        Vec3 stuck = movement.fabrolia$stuckSpeedMultiplier();
        if (velocity.getClass() != Vec3.class || stuck.getClass() != Vec3.class
                || source.position().getClass() != Vec3.class || source.getBoundingBox().getClass() != AABB.class) return false;
        if (!NativeGroundMotion.sourceAttributes(source)) return false;
        if (source.isRemoved() || source.isDeadOrDying() || source.isPassenger() || source.isVehicle()
                || source.isSleeping() || source.getSleepingPos().isPresent() || source.getPose() != Pose.STANDING
                || source.isUsingItem() || !source.getUseItem().isEmpty() || !source.getActiveEffects().isEmpty()
                || source.portalProcess != null || source.getLeashData() != null
                || source.noPhysics || source.isFallFlying() || source.isAutoSpinAttack()
                || living.fabrolia$aquaticSpinTicks() != 0 || source.canPickUpLoot()
                || source.isInPowderSnow || source.wasInPowderSnow || source.getTicksFrozen() != 0
                || source.getRemainingFireTicks() > 0 || !source.canBreatheUnderwater()
                || source.getAttributeValue(Attributes.BOUNCINESS) != 0.0
                || source.getInterpolation().hasActiveInterpolation() || !squid && items.fabrolia$motionJumping()
                || stuck.x != 0.0 || stuck.y != 0.0 || stuck.z != 0.0
                || !movement.fabrolia$movementThisTick().isEmpty() || source.mainSupportingBlockPos.isPresent()
                || geometry.fabrolia$aquaticFluids().getClass() != EntityFluidInteraction.class
                || living.fabrolia$aquaticEquipment().getClass() != EntityEquipment.class
                || !Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)
                || !Double.isFinite(source.xOld) || !Double.isFinite(source.yOld) || !Double.isFinite(source.zOld)
                || !Float.isFinite(source.xxa) || !Float.isFinite(source.yya) || !Float.isFinite(source.zza)
                || !Float.isFinite(source.getXRot()) || !Float.isFinite(source.getYRot())) return false;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            var previous = items.fabrolia$motionLastEquipment().get(slot);
            if (!source.getItemBySlot(slot).isEmpty() || previous == null || !previous.isEmpty()) return false;
        }
        for (var effects : items.fabrolia$motionLocationEnchantments().values()) {
            if (!(effects instanceof Map<?, ?> map) || !map.isEmpty()) return false;
        }
        return NativeGroundMotion.emptyBlockEffects(geometry.fabrolia$aquaticEffects());
    }

    private static boolean geometry(Mob source) {
        if (source.getAttributes().getClass() != AttributeMap.class) return false;
        for (var attribute : source.getAttributes().getAttributesToUpdate()) {
            if (attribute.getClass() != AttributeInstance.class || attribute.getAttribute().is(Attributes.SCALE)) return false;
        }
        if (source instanceof Nautilus nautilus && (nautilus.isBaby() || nautilus.getAge() < 0)) return false;
        if (source instanceof Squid squid && squid.getAge() == -1 && !squid.isAgeLocked()) return false;
        EntityDimensions base = source instanceof Squid squid && squid.isBaby()
                ? squid.getDefaultDimensions(Pose.STANDING) : source.getType().getDimensions();
        float width = base.width(), height = base.height(), eye = base.eyeHeight();
        if (!base.fixed()) {
            // Salmon alone scales its default dimensions from the synchronized
            // variant. Native E has no variant writer; other exact fish have no
            // dimension override. Adult Nautilus has no age-boundary refresh.
            if (source instanceof Salmon salmon) {
                float variantScale = salmon.getSalmonScale();
                width *= variantScale;
                height *= variantScale;
                eye *= variantScale;
            }
            float scale = source.getScale();
            width *= scale;
            height *= scale;
            eye *= scale;
        }
        var raw = (NativeAquaticMotionAccessors.Geometry) source;
        var cached = raw.fabrolia$aquaticDimensions();
        var box = source.getBoundingBox();
        float half = width / 2.0F;
        return Float.isFinite(width) && Float.isFinite(height) && Float.isFinite(eye)
                && width > 0.002 && height > 0.002 && eye >= 0.0F && eye <= height
                && cached.width() == width && cached.height() == height && cached.eyeHeight() == eye
                && raw.fabrolia$aquaticEyeHeight() == eye
                && Double.isFinite(source.getX()) && Double.isFinite(source.getY()) && Double.isFinite(source.getZ())
                && box.minX == source.getX() - half && box.maxX == source.getX() + half
                && box.minY == source.getY() && box.maxY == source.getY() + height
                && box.minZ == source.getZ() - half && box.maxZ == source.getZ() + half;
    }

    private static boolean controls(Mob source, boolean fish, boolean squid, NativeEntityMotion.Access access) {
        var move = source.getMoveControl();
        var look = source.getLookControl();
        var jump = source.getJumpControl();
        var body = ((NativeAquaticMotionAccessors.Body) source).fabrolia$aquaticBodyControl();
        if (move.getClass() != (squid ? MoveControl.class : fish ? FISH_MOVE : SmoothSwimmingMoveControl.class)
                || look.getClass() != (fish || squid ? LookControl.class : SmoothSwimmingLookControl.class)
                || jump.getClass() != JumpControl.class || body.getClass() != BodyRotationControl.class) return false;
        // Base STRAFE queries source + speed-scaled offsets before travel.
        // Squid travel ignores input, so its displacement bound cannot close it.
        if (squid && !nonStrafing(move)) return false;
        var moving = (NativeAquaticMotionAccessors.Move) move;
        if (moving.fabrolia$aquaticMoveSource() != source
                || ((NativeAquaticMotionAccessors.Look) look).fabrolia$aquaticLookSource() != source
                || ((NativeAquaticMotionAccessors.Jump) jump).fabrolia$aquaticJumpSource() != source
                || !squid && ((NativeAquaticMotionAccessors.Jump) jump).fabrolia$aquaticPendingJump()
                || ((NativeAquaticMotionAccessors.Rotation) body).fabrolia$aquaticRotationSource() != source
                || !nonnegative(moving.fabrolia$aquaticMoveSpeed())
                || !Double.isFinite(moving.fabrolia$aquaticWantedX())
                || !Double.isFinite(moving.fabrolia$aquaticWantedY())
                || !Double.isFinite(moving.fabrolia$aquaticWantedZ())) return false;
        var navigation = source.getNavigation();
        if (navigation.getClass() != (squid ? GroundPathNavigation.class : WaterBoundPathNavigation.class)) return false;
        var navigating = (NativeAquaticMotionAccessors.Navigation) navigation;
        var evaluator = navigating.fabrolia$aquaticEvaluator();
        var finder = navigating.fabrolia$aquaticPathFinder();
        if (navigating.fabrolia$aquaticNavigationSource() != source
                || navigating.fabrolia$aquaticNavigationLevel() != access.level()
                || !nonnegative(navigating.fabrolia$aquaticNavigationSpeed())
                || evaluator == null || evaluator.getClass() != (squid ? WalkNodeEvaluator.class : SwimNodeEvaluator.class)
                || finder == null || finder.getClass() != PathFinder.class
                || ((NativeAquaticMotionAccessors.Finder) finder).fabrolia$aquaticFinderEvaluator() != evaluator
                || !path(navigation.getPath())) return false;
        Object callback = ((NativeAquaticMotionAccessors.Finder) finder).fabrolia$aquaticCaptureDebug();
        if (callback == null) return false;
        Field[] fields = DEBUG_CAPTURE.get(callback.getClass());
        try {
            return fields.length == 1 && fields[0].get(callback) == access.level().getServer().debugSubscribers();
        } catch (IllegalAccessException incompatible) {
            return false;
        }
    }

    private static boolean squidProgram(Squid source) {
        var vector = ((NativeAquaticMotionAccessors.SquidState) source).fabrolia$aquaticSquidVector();
        if (vector == null || vector.getClass() != Vec3.class
                || !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) return false;
        var selector = source.getGoalSelector();
        var target = ((NativeEntityDependencyAccessors.MobTarget) source).fabrolia$targetSelector();
        if (selector.getClass() != GoalSelector.class || target.getClass() != GoalSelector.class
                || !target.getAvailableGoals().isEmpty()) return false;
        int found = 0;
        for (var wrapped : selector.getAvailableGoals()) {
            if (wrapped.getClass() != WrappedGoal.class) return false;
            var goal = wrapped.getGoal();
            int bit;
            if (goal.getClass() == SQUID_RANDOM) {
                bit = 1;
                if (((NativeGoalDependencyAccessors.SquidSource) goal).fabrolia$goalSquid() != source) return false;
            } else if (goal.getClass() == SQUID_FLEE) {
                bit = 2;
                if (((NativeGoalDependencyAccessors.SquidFleeSource) goal).fabrolia$fleeingSquid() != source) return false;
            } else {
                return false;
            }
            if ((found & bit) != 0) return false;
            found |= bit;
        }
        // Both goals can run. They write movementVector, not deltaMovement.
        // Squid.aiStep copies that vector only after super.aiStep has completed
        // travel, pushes and AgeableMob's stable-age tail. GlowSquid then updates
        // its dark-tick datum and sends a particle; neither tail moves the body.
        return found == 3;
    }

    private static double fishProgram(Mob source, double speed) {
        var selector = source.getGoalSelector();
        var target = ((NativeEntityDependencyAccessors.MobTarget) source).fabrolia$targetSelector();
        if (selector.getClass() != GoalSelector.class || target.getClass() != GoalSelector.class
                || !target.getAvailableGoals().isEmpty()) return Double.NaN;
        int found = 0;
        speed = Math.max(speed, 1.0); // AbstractSchoolingFish.pathToLeader literal.
        for (var wrapped : selector.getAvailableGoals()) {
            if (wrapped.getClass() != WrappedGoal.class) return Double.NaN;
            var goal = wrapped.getGoal();
            Class<?> type = goal.getClass();
            int bit;
            if (type == PanicGoal.class || type == FISH_SWIM) {
                bit = type == PanicGoal.class ? 1 : 2;
                if (((NativeGoalDependencyAccessors.PathfinderSource) goal).fabrolia$goalPathfinder() != source
                        || type == FISH_SWIM && ((NativeGoalDependencyAccessors.FishSource) goal).fabrolia$swimmingFish() != source) return Double.NaN;
                double producer = ((NativeAquaticMotionAccessors.GoalSpeed) goal).fabrolia$aquaticGoalSpeed();
                if (!nonnegative(producer)) return Double.NaN;
                speed = Math.max(speed, producer);
            } else if (type == AvoidEntityGoal.class) {
                bit = 4;
                var avoid = (NativeAquaticMotionAccessors.Avoid) goal;
                if (((NativeGoalDependencyAccessors.AvoidQuery) goal).fabrolia$avoidingMob() != source
                        || avoid.fabrolia$aquaticAvoidNavigation() != source.getNavigation()
                        || !path(avoid.fabrolia$aquaticAvoidPath())
                        || !nonnegative(avoid.fabrolia$aquaticWalkSpeed())
                        || !nonnegative(avoid.fabrolia$aquaticSprintSpeed())) return Double.NaN;
                speed = Math.max(speed, Math.max(avoid.fabrolia$aquaticWalkSpeed(), avoid.fabrolia$aquaticSprintSpeed()));
            } else if (type == FollowFlockLeaderGoal.class) {
                bit = 8;
                if (((NativeGoalDependencyAccessors.FlockSource) goal).fabrolia$flockingFish() != source) return Double.NaN;
            } else {
                return Double.NaN;
            }
            if ((found & bit) != 0) return Double.NaN;
            found |= bit;
        }
        // No school-size, leader, running-state or countdown condition: every
        // installed native speed producer is covered regardless of RNG/goal choice.
        return found == 15 ? speed : Double.NaN;
    }

    private static double nautilusProgram(Nautilus source, double speed) {
        if (source.getGoalSelector().getClass() != GoalSelector.class
                || !source.getGoalSelector().getAvailableGoals().isEmpty()) return Double.NaN;
        var target = ((NativeEntityDependencyAccessors.MobTarget) source).fabrolia$targetSelector();
        if (target.getClass() != GoalSelector.class || !target.getAvailableGoals().isEmpty()) return Double.NaN;
        var program = NativeNautilusProgram.match(source);
        if (program == null || !program.idleAndChargeStopped()) return Double.NaN;
        // The source-bound witness pins every installed producer: panic 1.6F,
        // temptation 1.3F, breeding 0.4F, stroll/look target 1.0F. It also proves
        // the actual live lambdas. updateActivity occurs only after Brain.tick;
        // an initially IDLE, STOPPED Charge cannot start in this E.
        speed = Math.max(speed, (double) 1.6F);
        var sink = program.movement();
        if (!path(((NativeNautilusProgramAccessors.Movement) sink).fabrolia$nautilusPath())) return Double.NaN;
        double retained = ((NativeAquaticMotionAccessors.SinkSpeed) sink).fabrolia$aquaticSinkSpeed();
        if (!nonnegative(retained)) return Double.NaN;
        speed = Math.max(speed, retained);
        var memories = ((NativeEntityDependencyAccessors.BrainMemories) source.getBrain()).fabrolia$memories();
        var slot = memories.get(MemoryModuleType.WALK_TARGET);
        if (slot != null && slot.value() != null) {
            Object value = slot.value();
            if (value.getClass() != WalkTarget.class) return Double.NaN;
            double walking = ((WalkTarget) value).getSpeedModifier();
            if (!nonnegative(walking)) return Double.NaN;
            speed = Math.max(speed, walking);
        }
        var retainedPath = memories.get(MemoryModuleType.PATH);
        if (retainedPath != null && retainedPath.value() != null
                && (!(retainedPath.value() instanceof Path path) || !path(path))) return Double.NaN;
        return speed;
    }

    private static boolean path(Path path) {
        if (path == null) return true;
        if (path.getClass() != Path.class || path.getTarget() == null
                || path.getTarget().getClass() != BlockPos.class) return false;
        var nodes = ((NativeAquaticMotionAccessors.Nodes) (Object) path).fabrolia$aquaticNodes();
        if (nodes == null || nodes.getClass() != ArrayList.class) return false;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node == null || node.getClass() != Node.class) return false;
        }
        return true;
    }

    /** Bound the actual nextStep branch instead of granting arbitrary live listeners. */
    private static boolean noMovementEvent(Mob source, double dx, double dz) {
        float next = ((NativeAquaticMotionAccessors.Geometry) source).fabrolia$aquaticNextStep();
        if (!Float.isFinite(source.moveDist) || !Float.isFinite(next)) return false;
        float distance = Math.nextUp((float) multiply(add(dx, dz), (double) 0.6F));
        float accumulated = Math.nextUp(source.moveDist + distance);
        return Float.isFinite(accumulated) && accumulated <= next;
    }

    private static boolean sourceWater(NativeEntityMotion.Access access,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        // Native BlockCollisions visits floor(min - 1e-7)-1 through
        // floor(max + 1e-7)+1. This also owns the fluid-refresh column apron,
        // step/support sweeps, and water getHeight/getCollisionShape above reads.
        if (!(minX > Integer.MIN_VALUE + 2.0 && maxX < Integer.MAX_VALUE - 2.0
                && minZ > Integer.MIN_VALUE + 2.0 && maxZ < Integer.MAX_VALUE - 2.0
                && minY > Integer.MIN_VALUE + 2.0 && maxY < Integer.MAX_VALUE - 2.0)) return false;
        int x0 = (int) Math.floor(Math.nextDown(minX - CONTACT)) - 1;
        int y0 = (int) Math.floor(Math.nextDown(minY - CONTACT)) - 1;
        int z0 = (int) Math.floor(Math.nextDown(minZ - CONTACT)) - 1;
        int x1 = (int) Math.floor(Math.nextUp(maxX + CONTACT)) + 1;
        int y1 = (int) Math.floor(Math.nextUp(maxY + CONTACT)) + 1;
        int z1 = (int) Math.floor(Math.nextUp(maxZ + CONTACT)) + 1;
        if (y0 < access.level().getMinY() || y1 >= access.level().getMaxY()
                || !access.readyColumns(x0, z0, x1, z1)) return false;
        if (Blocks.WATER.getClass() != LiquidBlock.class || Fluids.WATER.getClass() != WaterFluid.Source.class
                || !Fluids.WATER.is(FluidTags.WATER) || Fluids.WATER.is(FluidTags.LAVA)
                || Blocks.WATER.getBounceRestitution() != 0.0F) return false;
        var water = Blocks.WATER.defaultBlockState();
        Object suffocation = ((NativeAquaticMotionAccessors.WaterState) water).fabrolia$aquaticSuffocation();
        if (water.is(BlockTags.CLIMBABLE) || water.hasOffsetFunction()
                || suffocation == null || suffocation.getClass() != SUFFOCATION) return false;
        var pos = POSITION.get();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                var chunk = access.level().getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                for (int y = y0; y <= y1; y++) {
                    pos.set(x, y, z);
                    if (chunk.getBlockState(pos) != water) return false;
                }
            }
        }
        return true;
    }

    private static boolean nonnegative(double value) {
        return value >= 0.0 && Double.isFinite(value);
    }

    private static double add(double a, double b) {
        return Math.nextUp(a + b);
    }

    private static double multiply(double a, double b) {
        return Math.nextUp(a * b);
    }
}
