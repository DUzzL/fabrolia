package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.EntityMovementAccessor;
import com.axalotl.fabrolia.common.mixin.entity.NativeEntityDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeGroundMotionAccessors;
import com.axalotl.fabrolia.common.mixin.entity.PlayerMotionLivingAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Containment, not a simulated move. Composes with Main's retained-reference,
 * callback/known-mod and all-entity contact witnesses. The source is already
 * owned; this helper never obtains claims and never calls AI or collision.
 *
 * Audited against native 26.2 Entity.move/collide, LivingEntity.aiStep/travelInAir,
 * Mob.serverAiStep and the exact programs listed below. Fluids, relocation,
 * pending growth/scale refresh and substituted programs select whole-E Main.
 */
final class NativeGroundMotion {
    private NativeGroundMotion() {}

    // Native collision admits 1e-7 contact error; the step scan extends down by
    // 1e-5F. Their sum, rounded outward, is geometry tolerance, not a speed cap.
    private static final double CONTACT = Math.nextUp(1.0E-7 + (double)1.0E-5F);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double modifier;
        double leapHorizontal;
        double leapVertical;
        double downwardImpulse;
        double fallDistance;
        double jumpFactor;
        double searchRadius;
        long growthTicks;
    }

    private static final ClassValue<Boolean> NATIVE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            var source = type.getProtectionDomain().getCodeSource();
            var nativeSource = Entity.class.getProtectionDomain().getCodeSource();
            return source != null && source.equals(nativeSource)
                    && type.getClassLoader() == Entity.class.getClassLoader();
        }
    };
    private static final ClassValue<net.minecraft.world.entity.EntityType<?>> FAMILIES = new ClassValue<>() {
        @Override protected net.minecraft.world.entity.EntityType<?> computeValue(Class<?> type) {
            if (!NATIVE.get(type)) return null;
            return switch (type.getName()) {
                case "net.minecraft.world.entity.animal.cow.Cow" -> net.minecraft.world.entity.EntityTypes.COW;
                case "net.minecraft.world.entity.animal.pig.Pig" -> net.minecraft.world.entity.EntityTypes.PIG;
                case "net.minecraft.world.entity.animal.sheep.Sheep" -> net.minecraft.world.entity.EntityTypes.SHEEP;
                case "net.minecraft.world.entity.animal.chicken.Chicken" -> net.minecraft.world.entity.EntityTypes.CHICKEN;
                case "net.minecraft.world.entity.animal.equine.Horse" -> net.minecraft.world.entity.EntityTypes.HORSE;
                case "net.minecraft.world.entity.animal.equine.Donkey" -> net.minecraft.world.entity.EntityTypes.DONKEY;
                case "net.minecraft.world.entity.animal.equine.Mule" -> net.minecraft.world.entity.EntityTypes.MULE;
                case "net.minecraft.world.entity.animal.rabbit.Rabbit" -> net.minecraft.world.entity.EntityTypes.RABBIT;
                case "net.minecraft.world.entity.monster.Creeper" -> net.minecraft.world.entity.EntityTypes.CREEPER;
                case "net.minecraft.world.entity.monster.zombie.Zombie" -> net.minecraft.world.entity.EntityTypes.ZOMBIE;
                case "net.minecraft.world.entity.monster.skeleton.Skeleton" -> net.minecraft.world.entity.EntityTypes.SKELETON;
                case "net.minecraft.world.entity.monster.spider.Spider" -> net.minecraft.world.entity.EntityTypes.SPIDER;
                default -> null;
            };
        }
    };
    private static final ClassValue<Boolean> BLOCKS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!NATIVE.get(type)) return false;
            if (type == AirBlock.class) return true;
            return inherits(type, BlockBehaviour.class, "entityInside",
                    "getEntityInsideCollisionShape", "getFluidState", "getVisualShape", "getInteractionShape",
                    "isCollisionShapeFullBlock", "getSoundType")
                    && inherits(type, Block.class, "stepOn", "fallOn", "getFriction",
                    "getSpeedFactor", "getJumpFactor", "getBounceRestitution");
        }
    };
    private static final ClassValue<Boolean> SUFFOCATION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!NATIVE.get(type) || type.getNestHost() != BlockBehaviour.class) return false;
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) return false;
            }
            return true;
        }
    };
    private static final Object DEFAULT_SUFFOCATION =
            ((NativeGroundMotionAccessors.SuffocationProperties) (Object) BlockBehaviour.Properties.of()).fabrolia$groundDefaultSuffocation();

    private static final ClassValue<Boolean> ITEMS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return NATIVE.get(type) && inherits(type, net.minecraft.world.item.Item.class,
                    "inventoryTick", "hurtEnemy", "postHurtEnemy", "getAttackDamageBonus", "getItemDamageSource");
        }
    };

    private static boolean inherits(Class<?> type, Class<?> owner, String... names) {
        for (Class<?> cursor = type; cursor != owner; cursor = cursor.getSuperclass()) {
            if (cursor == null) return false;
            for (var method : cursor.getDeclaredMethods()) {
                for (String name : names) if (method.getName().equals(name)) return false;
            }
        }
        return true;
    }

    /** Immutable reflection schema, built once; per-E reads are raw/unboxed. */
    private record GoalProgram(Field[] sources, Field[] levels, Field[] navigations,
                               Field[] paths, Field[] speeds, Field[] positions, Field[] targets,
                               Field searchRange, double searchRadius, Field leap, boolean eats,
                               boolean egg, boolean spear) {}
    private static final GoalProgram UNKNOWN = new GoalProgram(null, null, null, null, null, null, null, null, 0, null, false, false, false);
    private static final ClassValue<GoalProgram> GOALS = new ClassValue<>() {
        @Override protected GoalProgram computeValue(Class<?> type) {
            if (!NATIVE.get(type)) return UNKNOWN;
            boolean known = switch (type.getName()) {
                case "net.minecraft.world.entity.ai.goal.FloatGoal",
                     "net.minecraft.world.entity.ai.goal.PanicGoal",
                     "net.minecraft.world.entity.ai.goal.AvoidEntityGoal",
                     "net.minecraft.world.entity.ai.goal.BreedGoal",
                     "net.minecraft.world.entity.ai.goal.TemptGoal",
                     "net.minecraft.world.entity.ai.goal.FollowParentGoal",
                     "net.minecraft.world.entity.ai.goal.RandomStrollGoal",
                     "net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal",
                     "net.minecraft.world.entity.ai.goal.LookAtPlayerGoal",
                     "net.minecraft.world.entity.ai.goal.RandomLookAroundGoal",
                     "net.minecraft.world.entity.ai.goal.EatBlockGoal",
                     "net.minecraft.world.entity.ai.goal.RestrictSunGoal",
                     "net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal",
                     "net.minecraft.world.entity.ai.goal.RandomStandGoal",
                     "net.minecraft.world.entity.ai.goal.ClimbOnTopOfPowderSnowGoal",
                     "net.minecraft.world.entity.ai.goal.FleeSunGoal",
                     "net.minecraft.world.entity.ai.goal.MeleeAttackGoal",
                     "net.minecraft.world.entity.ai.goal.ZombieAttackGoal",
                     "net.minecraft.world.entity.ai.goal.SwellGoal",
                     "net.minecraft.world.entity.ai.goal.SpearUseGoal",
                     "net.minecraft.world.entity.ai.goal.RangedBowAttackGoal",
                     "net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal",
                     "net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal",
                     "net.minecraft.world.entity.ai.goal.LeapAtTargetGoal",
                     "net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal",
                     "net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal",
                     "net.minecraft.world.entity.animal.equine.AbstractHorse$MountPanicGoal",
                     "net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitPanicGoal",
                     "net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitAvoidEntityGoal",
                     "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal",
                     "net.minecraft.world.entity.monster.skeleton.AbstractSkeleton$1",
                     "net.minecraft.world.entity.monster.spider.Spider$SpiderAttackGoal",
                     "net.minecraft.world.entity.monster.spider.Spider$SpiderTargetGoal",
                     "net.minecraft.world.entity.monster.zombie.Zombie$ZombieAttackTurtleEggGoal" -> true;
                default -> false;
            };
            if (!known) return UNKNOWN;
            var sources = new ArrayList<Field>();
            var levels = new ArrayList<Field>();
            var navigations = new ArrayList<Field>();
            var paths = new ArrayList<Field>();
            var speeds = new ArrayList<Field>();
            var positions = new ArrayList<Field>();
            var targets = new ArrayList<Field>();
            Field searchRange = null;
            Field leap = null;
            for (Class<?> cursor = type; cursor != Goal.class; cursor = cursor.getSuperclass()) {
                if (cursor == null || !NATIVE.get(cursor)) return UNKNOWN;
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    String name = field.getName();
                    ArrayList<Field> destination = null;
                    if (name.equals("mob") || name.equals("animal") || name.equals("removerMob")
                            || name.equals("horse") || name.equals("rabbit") || name.equals("creeper") || name.equals("this$0")) {
                        if (Mob.class.isAssignableFrom(field.getType())) destination = sources;
                    } else if (name.equals("level") && Level.class.isAssignableFrom(field.getType())) {
                        destination = levels;
                    } else if (name.equals("pathNav") && PathNavigation.class.isAssignableFrom(field.getType())) {
                        destination = navigations;
                    } else if (Path.class.isAssignableFrom(field.getType())) {
                        destination = paths;
                    } else if (BlockPos.class.isAssignableFrom(field.getType())) {
                        destination = positions;
                    } else if (Entity.class.isAssignableFrom(field.getType())) {
                        destination = targets;
                    } else if (name.equals("searchRange")) {
                        if (field.getType() != int.class || !field.trySetAccessible()) return UNKNOWN;
                        searchRange = field;
                    } else if (name.equals("speedModifier") || name.equals("walkSpeedModifier")
                            || name.equals("sprintSpeedModifier") || name.equals("speedModifierWhenCharging")
                            || name.equals("speedModifierWhenRepositioning")) {
                        if (field.getType() != double.class) return UNKNOWN;
                        destination = speeds;
                    } else if (name.equals("yd") && type.getName().equals("net.minecraft.world.entity.ai.goal.LeapAtTargetGoal")) {
                        if (field.getType() != float.class || !field.trySetAccessible()) return UNKNOWN;
                        leap = field;
                    }
                    if (destination != null) {
                        if (!field.trySetAccessible()) return UNKNOWN;
                        destination.add(field);
                    }
                }
            }
            if (sources.isEmpty()) return UNKNOWN;
            return new GoalProgram(sources.toArray(Field[]::new), levels.toArray(Field[]::new),
                    navigations.toArray(Field[]::new), paths.toArray(Field[]::new), speeds.toArray(Field[]::new),
                    positions.toArray(Field[]::new), targets.toArray(Field[]::new), searchRange, searchRadius(type),
                    leap, type.getName().equals("net.minecraft.world.entity.ai.goal.EatBlockGoal"),
                    type.getName().equals("net.minecraft.world.entity.monster.zombie.Zombie$ZombieAttackTurtleEggGoal"),
                    type.getName().equals("net.minecraft.world.entity.ai.goal.SpearUseGoal"));
        }
    };

    private static double searchRadius(Class<?> type) {
        // Native random candidates include home bias (horizontalRange/2), then
        // integer conversion and one path-type neighbour. Village adds its
        // ten-block POI search around each 15-block biased candidate.
        if (net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal.class.isAssignableFrom(type)) return Math.ceil(15 + 15 / 2.0 + 10 + 2);
        if (net.minecraft.world.entity.ai.goal.AvoidEntityGoal.class.isAssignableFrom(type)
                || net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal.class.isAssignableFrom(type)) return 16 + 16 / 2.0 + 2;
        if (net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal.class.isAssignableFrom(type)) return Math.ceil(15 + 15 / 2.0 + 2);
        if (net.minecraft.world.entity.ai.goal.RandomStrollGoal.class.isAssignableFrom(type)) return 10 + 10 / 2.0 + 2;
        if (net.minecraft.world.entity.ai.goal.PanicGoal.class.isAssignableFrom(type)
                || net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal.class.isAssignableFrom(type)) return Math.ceil(5 + 5 / 2.0 + 2);
        if (net.minecraft.world.entity.ai.goal.FleeSunGoal.class.isAssignableFrom(type)) return 10 + 1;
        return 0;
    }

    /** Called only after bounds/source proof; Main closes all acquisition victims. */
    static boolean hasMeleeProducer(Entity source) {
        if (!(source instanceof Mob mob)) return false;
        for (var wrapped : mob.getGoalSelector().getAvailableGoals()) {
            if (wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.MeleeAttackGoal) return true;
        }
        for (var wrapped : ((NativeEntityDependencyAccessors.MobTarget) mob).fabrolia$targetSelector().getAvailableGoals()) {
            if (wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.MeleeAttackGoal) return true;
        }
        return false;
    }

    private static double nodeMargin(Mob mob) {
        // Path.getEntityPosAtNode offsets X/Z by floor(width+1)*.5;
        // the queried floor shape and WalkNodeEvaluator add neighbours.
        return Math.ceil((double)mob.getBbWidth() + 1) + 1;
    }

    private static boolean positionColumns(BlockPos position, double margin, NativeEntityMotion.Access access) {
        if (position == null) return true;
        if (position.getClass() != BlockPos.class && position.getClass() != BlockPos.MutableBlockPos.class) return false;
        return safeColumns(access, (double)position.getX() - margin, (double)position.getZ() - margin,
                (double)position.getX() + margin, (double)position.getZ() + margin);
    }

    private static boolean pathColumns(Path path, Mob mob, NativeEntityMotion.Access access) {
        if (path == null) return true;
        if (path.getClass() != Path.class) return false;
        var nodes = ((NativeGroundMotionAccessors.Nodes) (Object) path).fabrolia$groundPathNodes();
        // Native PathFinder reconstructPath uses Lists.newArrayList. Do not
        // execute a substituted List.get or virtual Node implementation.
        if (nodes.getClass() != ArrayList.class || path.getNextNodeIndex() < 0 || path.getNextNodeIndex() > nodes.size()) return false;
        double margin = nodeMargin(mob);
        if (!positionColumns(path.getTarget(), margin, access)) return false;
        for (int index = 0; index < nodes.size(); index++) {
            var node = nodes.get(index);
            if (node == null || node.getClass() != net.minecraft.world.level.pathfinder.Node.class
                    || !safeColumns(access, (double)node.x - margin, (double)node.z - margin,
                    (double)node.x + margin, (double)node.z + margin)) return false;
        }
        return true;
    }

    private static boolean navigationColumns(Mob mob, NativeEntityMotion.Access access, double producerRadius) {
        var navigation = (NativeGroundMotionAccessors.Navigation) mob.getNavigation();
        float required = navigation.fabrolia$groundRequiredPathLength();
        double follow = attribute(mob, Attributes.FOLLOW_RANGE);
        if (!(follow >= 0) || !Double.isFinite(follow) || !Float.isFinite(required)) return false;
        // PathNavigation.createPath's largest native radiusOffset is 16.
        // Preserve float rounding, then round the truncating native radius out.
        double radius = Math.ceil(Math.max((float)follow, required) + 16.0F);
        radius = up(Math.max(radius, producerRadius) + nodeMargin(mob));
        if (!Double.isFinite(radius) || radius < 0 || radius >= Integer.MAX_VALUE) return false;
        double x = Math.floor(mob.getX()), z = Math.floor(mob.getZ());
        if (!safeColumns(access, Math.nextDown(x - radius), Math.nextDown(z - radius),
                Math.nextUp(x + radius), Math.nextUp(z + radius))) return false;
        var retained = (NativeEntityDependencyAccessors.Navigation) mob.getNavigation();
        if (retained.fabrolia$hasDelayedRecomputation()
                && !positionColumns(navigation.fabrolia$groundNavigationTarget(), nodeMargin(mob), access)) return false;
        Entity target = ((NativeEntityDependencyAccessors.MobTarget) mob).fabrolia$rawTarget();
        return target == null || targetColumns(target, nodeMargin(mob), access);
    }

    static boolean bounds(Entity source, NativeEntityMotion.Access access, NativeEntityMotion.Bounds out) {
        if (!(source instanceof Mob mob)) return false;
        var expectedType = FAMILIES.get(source.getClass());
        if (expectedType == null || source.getType() != expectedType
                || source.level() != access.level() || source.isRemoved() || mob.isDeadOrDying()
                || source.isPassenger() || source.isVehicle() || source.noPhysics
                || source.portalProcess != null || mob.getLeashData() != null
                || mob.isSleeping() || mob.getSleepingPos().isPresent() || mob.getPose() != Pose.STANDING
                || mob.isUsingItem() || !mob.getUseItem().isEmpty() || mob.canPickUpLoot()
                || mob.isFallFlying() || mob.isAutoSpinAttack()
                || ((NativeGroundMotionAccessors.Living) mob).fabrolia$groundSpinTicks() != 0
                || mob.getInterpolation().hasActiveInterpolation()
                || mob.isInWater() || mob.isInLava() || mob.isSwimming()
                || mob.isInPowderSnow || mob.wasInPowderSnow || !mob.getActiveEffects().isEmpty()
                || !Float.isFinite(mob.xxa) || !Float.isFinite(mob.yya) || !Float.isFinite(mob.zza)
                || !Float.isFinite(mob.getSpeed()) || !Float.isFinite(mob.getYRot())
                || !Float.isFinite(mob.getXRot()) || !Double.isFinite(mob.fallDistance)) return false;
        // Empty listeners/equipment do not authorize a hurt callback. Prove
        // prefix fire/void damage and post-travel retained freezing unreachable;
        // the dry corridor below excludes fresh fluid and powder-snow damage.
        int fireTicks = mob.getRemainingFireTicks();
        if (fireTicks > 0 && fireTicks % 20 == 0 && !mob.fireImmune()
                || mob.getY() < access.level().getMinY() - 64
                || Math.max(0, mob.getTicksFrozen() - 2) >= mob.getTicksRequiredToFreeze() && mob.canFreeze()) return false;
        if (!sourceAttributes(mob)) return false;
        if (mob instanceof Zombie zombie && zombie.isUnderWaterConverting()) return false;
        if (mob instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            var fuse = (NativeGroundMotionAccessors.CreeperProgram) creeper;
            // Creeper.tick tests the fuse BEFORE super.tick/AI. Match native
            // int addition (including overflow), then its zero floor. Later
            // SwellGoal/fall damage changes cannot explode until the next E.
            int nextSwell = fuse.fabrolia$groundSwell() + (creeper.isIgnited() ? 1 : creeper.getSwellDir());
            if (Math.max(0, nextSwell) >= fuse.fabrolia$groundMaxSwell()) return false;
        }
        if (mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse && horse.isBaby() && horse.isBred()) return false;
        // AgeableMob.aiStep runs after travel. Sheep.ate can grow a baby during
        // the goal pass; that separate producer is checked in goals().
        if (mob instanceof AgeableMob ageable && ageable.getAge() == -1 && !ageable.isAgeLocked()) return false;
        if (mob instanceof Sheep sheep) {
            var eat = ((NativeGroundMotionAccessors.SheepProgram) sheep).fabrolia$groundEatBlockGoal();
            if (eat == null || eat.getClass() != net.minecraft.world.entity.ai.goal.EatBlockGoal.class) return false;
            GoalProgram program = GOALS.get(eat.getClass());
            if (program == UNKNOWN) return false;
            try {
                for (Field field : program.sources) if (field.get(eat) != sheep) return false;
            } catch (IllegalAccessException failure) {
                return false;
            }
        }
        var living = (PlayerMotionLivingAccessor) mob;
        for (var value : living.fabrolia$motionLocationEnchantments().values()) {
            if (!(value instanceof java.util.Map<?, ?> map) || !map.isEmpty()) return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = mob.getItemBySlot(slot);
            if (!NativePlayerMotion.safeItem(stack)
                    || !stack.isEmpty() && !ITEMS.get(stack.getItem().getClass())
                    || stack.has(DataComponents.KINETIC_WEAPON)
                    || !ItemStack.matches(stack, living.fabrolia$motionLastEquipment().get(slot))) return false;
        }
        var movement = (EntityMovementAccessor) mob;
        var stuck = movement.fabrolia$stuckSpeedMultiplier();
        if (stuck == null || stuck.getClass() != net.minecraft.world.phys.Vec3.class
                || stuck.x != 0 || stuck.y != 0 || stuck.z != 0 || !movement.fabrolia$movementThisTick().isEmpty()) return false;
        var geometry = (NativeGroundMotionAccessors.Geometry) mob;
        if (!emptyBlockEffects(geometry.fabrolia$groundEffects())) return false;

        var box = mob.getBoundingBox();
        // AABB and Vec3 are extensible native classes. Their virtual arithmetic
        // is part of the movement program, not just six/three finite numbers.
        if (box == null || box.getClass() != net.minecraft.world.phys.AABB.class
                || mob.position().getClass() != net.minecraft.world.phys.Vec3.class) return false;
        var dimensions = geometry.fabrolia$groundDimensions();
        float halfWidth = dimensions.width() / 2.0F;
        if (!(halfWidth > 0 && Float.isFinite(halfWidth) && dimensions.height() > 0 && Float.isFinite(dimensions.height())
                && mob.getEyeHeight() >= 0 && Float.isFinite(mob.getEyeHeight()))
                || box.minX != mob.getX() - halfWidth || box.maxX != mob.getX() + halfWidth
                || box.minY != mob.getY() || box.maxY != mob.getY() + dimensions.height()
                || box.minZ != mob.getZ() - halfWidth || box.maxZ != mob.getZ() + halfWidth) return false;
        if (!Double.isFinite(mob.xOld) || !Double.isFinite(mob.yOld) || !Double.isFinite(mob.zOld)) return false;
        var velocity = mob.getDeltaMovement();
        if (velocity == null || velocity.getClass() != net.minecraft.world.phys.Vec3.class
                || !Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) return false;
        Scratch scratch = SCRATCH.get();
        if (!controls(mob, access)) return false;
        scratch.modifier = Math.max(Math.abs(mob.getMoveControl().getSpeedModifier()),
                Math.abs(((NativeGroundMotionAccessors.Navigation) mob.getNavigation()).fabrolia$groundNavigationSpeed()));
        // Native ranged strafing installs this literal, regardless of retained state.
        scratch.modifier = Math.max(scratch.modifier, 0.25);
        boolean rabbit = mob instanceof net.minecraft.world.entity.animal.rabbit.Rabbit;
        if (rabbit) {
            double retained = ((NativeGroundMotionAccessors.RabbitMove) mob.getMoveControl()).fabrolia$groundNextJumpSpeed();
            if (!Double.isFinite(retained)) return false;
            scratch.modifier = Math.max(scratch.modifier, Math.abs(retained));
        }
        scratch.leapHorizontal = 0;
        scratch.leapVertical = 0;
        scratch.downwardImpulse = 0;
        scratch.fallDistance = Math.max(0, mob.fallDistance);
        scratch.searchRadius = 0;
        scratch.growthTicks = 0;
        if (!goals(mob, mob.getGoalSelector(), access, scratch)
                || !goals(mob, ((NativeEntityDependencyAccessors.MobTarget) mob).fabrolia$targetSelector(), access, scratch)) return false;
        if (mob instanceof Sheep sheep && sheep.isBaby() && !sheep.isAgeLocked()
                && (long)sheep.getAge() + scratch.growthTicks + 1 >= 0) return false;
        if (!navigationColumns(mob, access, scratch.searchRadius)) return false;

        // Contact sampling owns its complete native collision-neighbour shell
        // independently of the navigation query proof, including eyes and support.
        if (mob.mainSupportingBlockPos.isPresent()) {
            BlockPos support = mob.mainSupportingBlockPos.get();
            if (support.getX() < Math.floor(box.minX) - 1 || support.getX() > Math.floor(box.maxX) + 1
                    || support.getZ() < Math.floor(box.minZ) - 1 || support.getZ() > Math.floor(box.maxZ) + 1
                    || support.getY() < Math.floor(box.minY) - 1 || support.getY() > Math.floor(box.maxY) + 1) return false;
        }
        // EatBlockGoal's only solid replacement is DIRT. Read its audited
        // factor too: the goal may replace current grass before jumping.
        if (!BLOCKS.get(Blocks.DIRT.getClass()) || !Float.isFinite(Blocks.DIRT.getJumpFactor())) return false;
        scratch.jumpFactor = Math.abs(Blocks.DIRT.getJumpFactor());
        // Native baby sheep eyes are above their body, not clamped to it.
        // Close the actual eye query independently of the body geometry.
        double eyeMinY = Math.nextDown(mob.getEyeY() - 1.0E-6 / 2.0);
        double eyeMaxY = Math.nextUp(mob.getEyeY() + 1.0E-6 / 2.0);
        if (!blocks(mob, access, box.minX, Math.min(box.minY, eyeMinY), box.minZ,
                box.maxX, Math.max(box.maxY, eyeMaxY), box.maxZ, scratch, true)) return false;
        if (!clearEyeBox(mob, access, scratch)) return false;
        double attributeSpeed = attribute(mob, Attributes.MOVEMENT_SPEED);
        double jumpAttribute = attribute(mob, Attributes.JUMP_STRENGTH);
        double step = attribute(mob, Attributes.STEP_HEIGHT);
        double gravity = attribute(mob, Attributes.GRAVITY);
        double friction = attribute(mob, Attributes.FRICTION_MODIFIER);
        if (!(attributeSpeed >= 0 && jumpAttribute >= 0 && step >= 0 && gravity >= 0)
                || !Double.isFinite(gravity) || !Double.isFinite(friction)) return false;
        // Cover both (float)(doubleProduct) and STRAFE's float*float product.
        double speed = up(Math.max(Math.abs(mob.getSpeed()), up(upperFloat(attributeSpeed) * upperFloat(scratch.modifier))) * 2);
        double jumpMultiplier = rabbit ? upperFloat((double)(0.5F / 0.42F)) : 1;
        double jump = up(up(upperFloat(jumpAttribute) * jumpMultiplier) * upperFloat(scratch.jumpFactor) * 2);
        step = upperFloat(step);
        if (!Double.isFinite(speed) || !Double.isFinite(jump) || !Double.isFinite(step)
                || speed > Float.MAX_VALUE / 8.0 || jump > Float.MAX_VALUE / 8.0) return false;
        // 26.2 getFrictionInfluencedSpeed: f<=.6 uses speed, otherwise
        // speed*.21600002F/(f*f*f). f is clamped to [0,1], so 2*speed
        // is an outward upper bound. Air uses .02F. Native normalized input
        // components are <=2; rotation sums two products, hence 4*a,2*a,4*a.
        double acceleration = up(Math.max(up(2 * speed), (double)0.02F));
        double horizontalImpulse = up(scratch.leapHorizontal + 0.2); // possible sprint jump
        if (rabbit) horizontalImpulse = up(horizontalImpulse + up(4 * (double)0.1F));
        double dx = up(up(up(Math.abs(velocity.x) + horizontalImpulse) + up(4 * acceleration)) + CONTACT);
        double dz = up(up(up(Math.abs(velocity.z) + horizontalImpulse) + up(4 * acceleration)) + CONTACT);
        double dy = up(up(up(Math.max(Math.abs(velocity.y), Math.max(jump, scratch.leapVertical)) + up(2 * acceleration)) + step) + CONTACT);
        if (rabbit) dy = up(dy + up(2 * (double)0.1F));
        // These exact controls/goals only retain yya or clear it. Normalization
        // cannot increase its magnitude beyond this outward factor-two bound.
        // Ground/rabbit jumps only raise Y; egg/leap goals can replace it.
        double verticalInput = mob.yya == 0 ? 0 : Math.min(2, up(2 * Math.abs((double)mob.yya)));
        double downward = up(up(Math.max(Math.max(0, -velocity.y), scratch.downwardImpulse)
                + up(acceleration * verticalInput)) + CONTACT);
        if (!noFallDamage(mob, scratch.fallDistance, downward)) return false;
        // Gravity, drag, collision restitution and chicken's falling multiplier
        // update NEXT tick's velocity after the sole move, not its displacement.
        // JumpControl/jumping may be pending; their full ground jump is included.
        double minX = Math.nextDown(Math.min(box.minX - dx, mob.xOld - halfWidth));
        double maxX = Math.nextUp(Math.max(box.maxX + dx, mob.xOld + halfWidth));
        double minY = Math.nextDown(Math.min(box.minY - dy, mob.yOld));
        double maxY = Math.nextUp(Math.max(box.maxY + dy, mob.yOld + dimensions.height()));
        double minZ = Math.nextDown(Math.min(box.minZ - dz, mob.zOld - halfWidth));
        double maxZ = Math.nextUp(Math.max(box.maxZ + dz, mob.zOld + halfWidth));
        if (!blocks(mob, access, minX, Math.min(minY, Math.nextDown(eyeMinY - dy)), minZ,
                maxX, Math.max(maxY, Math.nextUp(eyeMaxY + dy)), maxZ, scratch, false)) return false;
        out.set(minX, minY, minZ, maxX, maxY, maxZ);
        return access.hasNoGameEventListeners(out, 16);
    }

    static boolean emptyBlockEffects(InsideBlockEffectApplier.StepBasedCollector collector) {
        if (collector == null || collector.getClass() != InsideBlockEffectApplier.StepBasedCollector.class) return false;
        var effects = (NativeGroundMotionAccessors.Effects) collector;
        var pending = effects.fabrolia$groundEffectsInStep();
        var complete = effects.fabrolia$groundFinalEffects();
        // Lithium lazily initializes these same queues; null means no entries,
        // not a separate effect store. Never call an unknown collection override.
        return (pending == null || pending instanceof java.util.EnumSet<?> && pending.isEmpty())
                && (complete == null || complete.getClass() == java.util.ArrayList.class && complete.isEmpty())
                && emptyEffectCallbacks(effects.fabrolia$groundBeforeEffects())
                && emptyEffectCallbacks(effects.fabrolia$groundAfterEffects());
    }

    private static boolean emptyEffectCallbacks(java.util.Map<net.minecraft.world.entity.InsideBlockEffectType,
            java.util.List<java.util.function.Consumer<Entity>>> callbacks) {
        if (callbacks == null) return true;
        if (callbacks.getClass() != java.util.EnumMap.class) return false;
        for (var list : callbacks.values()) {
            if (list == null || list.getClass() != java.util.ArrayList.class || !list.isEmpty()) return false;
        }
        return true;
    }

    /* The sole native PathNavigation lambda captures its own server's debug
       subscribers. A matching host alone does not prove that retained receiver. */
    private static final ClassValue<Field[]> DEBUG_CAPTURE = new ClassValue<>() {
        @Override protected Field[] computeValue(Class<?> type) {
            if (!type.isHidden() || type.getNestHost() != PathNavigation.class
                    || !NATIVE.get(PathNavigation.class)) return new Field[0];
            Field[] fields = type.getDeclaredFields();
            if (fields.length != 1 || Modifier.isStatic(fields[0].getModifiers())
                    || !Modifier.isFinal(fields[0].getModifiers()) || !fields[0].trySetAccessible()) return new Field[0];
            return fields;
        }
    };

    private static boolean controls(Mob mob, NativeEntityMotion.Access access) {
        var move = mob.getMoveControl();
        var look = mob.getLookControl();
        var jump = mob.getJumpControl();
        var body = ((NativeGroundMotionAccessors.Controls) mob).fabrolia$groundBodyControl();
        var navigation = mob.getNavigation();
        boolean rabbit = mob instanceof net.minecraft.world.entity.animal.rabbit.Rabbit;
        if (rabbit ? !NATIVE.get(move.getClass())
                || !move.getClass().getName().equals("net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitMoveControl")
                || jump.getClass() != net.minecraft.world.entity.animal.rabbit.Rabbit.RabbitJumpControl.class
                || ((NativeGroundMotionAccessors.RabbitJump) jump).fabrolia$groundJumpRabbit() != mob
                : move.getClass() != MoveControl.class || jump.getClass() != JumpControl.class) return false;
        if (look.getClass() != LookControl.class || body.getClass() != BodyRotationControl.class
                || mob.getSensing().getClass() != Sensing.class
                || navigation.getClass() != GroundPathNavigation.class && navigation.getClass() != WallClimberNavigation.class) return false;
        BlockPos home = mob.getHomePosition();
        if (home == null || home.getClass() != BlockPos.class && home.getClass() != BlockPos.MutableBlockPos.class) return false;
        var moveView = (NativeGroundMotionAccessors.Move) move;
        var lookView = (NativeGroundMotionAccessors.Look) look;
        var navView = (NativeEntityDependencyAccessors.Navigation) navigation;
        if (moveView.fabrolia$groundMoveMob() != mob || lookView.fabrolia$groundLookMob() != mob
                || ((NativeGroundMotionAccessors.Jump) jump).fabrolia$groundJumpMob() != mob
                || ((NativeGroundMotionAccessors.Body) body).fabrolia$groundBodyMob() != mob
                || ((NativeGroundMotionAccessors.Sense) mob.getSensing()).fabrolia$groundSensingMob() != mob
                || navView.fabrolia$navigationMob() != mob || navView.fabrolia$navigationLevel() != access.level()
                || !Double.isFinite(move.getSpeedModifier())
                || !Double.isFinite(((NativeGroundMotionAccessors.Navigation) navigation).fabrolia$groundNavigationSpeed())
                || !Double.isFinite(move.getWantedX()) || !Double.isFinite(move.getWantedY()) || !Double.isFinite(move.getWantedZ())
                || !Float.isFinite(moveView.fabrolia$groundStrafeForwards()) || !Float.isFinite(moveView.fabrolia$groundStrafeRight())
                || !Double.isFinite(lookView.fabrolia$groundLookX()) || !Double.isFinite(lookView.fabrolia$groundLookY())
                || !Double.isFinite(lookView.fabrolia$groundLookZ()) || !Float.isFinite(lookView.fabrolia$groundLookSpeed())
                || !Float.isFinite(lookView.fabrolia$groundLookAngle())) return false;
        if (!pathColumns(navView.fabrolia$navigationPath(), mob, access)) return false;
        var evaluator = navigation.getNodeEvaluator();
        var finder = ((NativeGroundMotionAccessors.Navigation) navigation).fabrolia$groundPathFinder();
        if (evaluator == null || evaluator.getClass() != WalkNodeEvaluator.class || finder.getClass() != PathFinder.class
                || ((NativeGroundMotionAccessors.Finder) finder).fabrolia$groundFinderEvaluator() != evaluator) return false;
        var evaluatorView = (NativeGroundMotionAccessors.Evaluator) evaluator;
        if (evaluatorView.fabrolia$groundEvaluatorMob() != null || evaluatorView.fabrolia$groundEvaluatorContext() != null) return false;
        var debug = ((NativeGroundMotionAccessors.Finder) finder).fabrolia$groundCaptureDebug();
        if (debug == null) return false;
        Field[] fields = DEBUG_CAPTURE.get(debug.getClass());
        try {
            return fields.length == 1 && fields[0].get(debug) == access.level().getServer().debugSubscribers();
        } catch (IllegalAccessException failure) {
            return false;
        }
    }

    private static int goalOccurrences(GoalSelector selector, Goal goal, boolean runningOnly) {
        if (selector.getClass() != GoalSelector.class) return -1;
        int count = 0;
        for (var wrapped : selector.getAvailableGoals()) {
            if (wrapped.getClass() != WrappedGoal.class) return -1;
            if (wrapped.getGoal() == goal && (!runningOnly || wrapped.isRunning())) count++;
        }
        return count;
    }

    private static boolean goals(Mob mob, GoalSelector selector, NativeEntityMotion.Access access, Scratch scratch) {
        if (selector.getClass() != GoalSelector.class) return false;
        for (var wrapped : selector.getAvailableGoals()) {
            if (wrapped.getClass() != WrappedGoal.class) return false;
            Goal goal = wrapped.getGoal();
            GoalProgram program = GOALS.get(goal.getClass());
            if (program == UNKNOWN) return false;
            try {
                for (Field field : program.sources) if (field.get(goal) != mob) return false;
                for (Field field : program.levels) if (field.get(goal) != access.level()) return false;
                for (Field field : program.navigations) if (field.get(goal) != mob.getNavigation()) return false;
                for (Field field : program.paths) {
                    if (!pathColumns((Path) field.get(goal), mob, access)) return false;
                }
                if (wrapped.isRunning()) {
                    for (Field field : program.positions) {
                        if (!positionColumns((BlockPos) field.get(goal), 1, access)) return false;
                    }
                    for (Field field : program.targets) {
                        Entity target = (Entity) field.get(goal);
                        if (target != null && !targetColumns(target, nodeMargin(mob), access)) return false;
                    }
                }
                scratch.searchRadius = Math.max(scratch.searchRadius, program.searchRadius);
                if (program.searchRange != null) {
                    int range = program.searchRange.getInt(goal);
                    if (range < 0) return false;
                    scratch.searchRadius = Math.max(scratch.searchRadius, (double)range + 1);
                }
                for (Field field : program.speeds) {
                    double value = field.getDouble(goal);
                    if (!Double.isFinite(value)) return false;
                    scratch.modifier = Math.max(scratch.modifier, Math.abs(value));
                }
                if (program.leap != null) {
                    float leap = program.leap.getFloat(goal);
                    if (!Float.isFinite(leap)) return false;
                    scratch.leapVertical = Math.max(scratch.leapVertical, Math.abs(leap));
                    scratch.downwardImpulse = Math.max(scratch.downwardImpulse, -(double)leap);
                    // Each installed LeapAtTargetGoal may start. Its normalized
                    // .4 impulse plus .2*old velocity never exceeds oldAbs+.8.
                    scratch.leapHorizontal = up(scratch.leapHorizontal + 0.8);
                }
            } catch (IllegalAccessException failure) {
                return false;
            }
            if (program.eats && mob instanceof Sheep sheep && sheep.isBaby() && !sheep.isAgeLocked()) {
                GoalSelector ordinary = mob.getGoalSelector();
                int installed = goalOccurrences(ordinary, goal, false);
                if (installed < 0) return false;
                // Count a shared EatBlockGoal only once, but allow both native
                // selector passes to decrement its retained timer. An inactive
                // goal starts at reducedTickDelay(40)==20; at most two native
                // passes cannot reach the eating threshold reducedTickDelay(4)==2.
                if (selector == ordinary || installed == 0) {
                    int normalTicks = goalOccurrences(ordinary, goal, true);
                    int targetTicks = goalOccurrences(((NativeEntityDependencyAccessors.MobTarget) mob).fabrolia$targetSelector(), goal, true);
                    if (normalTicks < 0 || targetTicks < 0 || normalTicks > 1 || targetTicks > 1) return false;
                    int timer = ((net.minecraft.world.entity.ai.goal.EatBlockGoal) goal).getEatAnimationTick();
                    if (timer > 2 && (long)timer - 2 <= normalTicks + targetTicks) {
                        scratch.growthTicks += 60L * 20L;
                    }
                }
            }
            if (goal.getClass() == net.minecraft.world.entity.ai.goal.BreedGoal.class
                    && mob.isBaby() && wrapped.isRunning()) return false;
            if (program.egg) {
                scratch.leapVertical = Math.max(scratch.leapVertical, 0.3);
                scratch.downwardImpulse = Math.max(scratch.downwardImpulse, 0.3);
                // RemoveBlockGoal.stop resets this even when the egg vanished.
                scratch.fallDistance = Math.max(scratch.fallDistance, 1.0);
            }
            // An already running spear goal can still tick on the half-rate AI
            // pass even after losing its weapon. Its item-use program is not
            // certified merely because canUse would now return false.
            if (program.spear && wrapped.isRunning()) return false;
        }
        return Double.isFinite(scratch.modifier) && Double.isFinite(scratch.leapHorizontal);
    }


    private static boolean targetColumns(Entity target, double margin, NativeEntityMotion.Access access) {
        // This Access method proves target identity/ownership BEFORE reading
        // its position. A reference in a source-owned goal is not that proof.
        if (!access.readyEntityColumns(target, margin)) return false;
        return safeColumns(access, target.getX() - margin, target.getZ() - margin,
                target.getX() + margin, target.getZ() + margin);
    }

    private static boolean safeColumns(NativeEntityMotion.Access access, double minX, double minZ, double maxX, double maxZ) {
        return access.readyNavigationColumns(minX, minZ, maxX, maxZ);
    }

    private static boolean safeState(Mob source, net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.getClass() != net.minecraft.world.level.block.state.BlockState.class
                || !BLOCKS.get(state.getBlock().getClass()) || !NativeBlockQueries.collision(source, state)
                || state.is(BlockTags.CLIMBABLE) || !state.getFluidState().isEmpty()) return false;
        var sound = state.getSoundType();
        if (sound == null || sound.getClass() != net.minecraft.world.level.block.SoundType.class) return false;
        if (!state.isAir()) {
            Object predicate = ((NativeGroundMotionAccessors.Suffocation) (Object) state).fabrolia$groundSuffocation();
            if (DEFAULT_SUFFOCATION == null || !SUFFOCATION.get(DEFAULT_SUFFOCATION.getClass())
                    || predicate == null || predicate.getClass() != DEFAULT_SUFFOCATION.getClass()) return false;
        }
        return true;
    }

    private static boolean noFallDamage(Mob source, double fallDistance, double downward) {
        // Native immunity is a current tag, not a Chicken-class assumption.
        // Otherwise the audited Block.fallOn passes exactly 1.0F, and native
        // LivingEntity/AbstractHorse only call hurt when floor(damage) > 0.
        if (source.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) return true;
        double safeFall = attribute(source, Attributes.SAFE_FALL_DISTANCE);
        double multiplier = attribute(source, Attributes.FALL_DAMAGE_MULTIPLIER);
        if (!Double.isFinite(safeFall) || !(multiplier >= 0) || !Double.isFinite(multiplier)
                || !Double.isFinite(downward)) return false;
        // Entity.checkFallDamage first subtracts (float)movement.y, including
        // on the landing move. Round that narrowing and each native sum out.
        double distance = up(fallDistance + upperFloat(downward));
        double power = Math.max(0, up(up(distance + 1.0E-6) - safeFall));
        double damage = up(power * multiplier);
        return Double.isFinite(damage) && damage < 1.0;
    }

    private static boolean clearEyeBox(Mob source, NativeEntityMotion.Access access, Scratch scratch) {
        // Entity.isInWall uses the CURRENT eye box before AI/travel. The initial
        // contact scan established a dry native collision/effect program. Reject
        // overlap with its full block cell conservatively, without a shape call.
        double halfWidth = (((NativeGroundMotionAccessors.Geometry) source).fabrolia$groundDimensions().width() * 0.8F) / 2.0;
        double minX = source.getX() - halfWidth, maxX = source.getX() + halfWidth;
        double minY = source.getEyeY() - 1.0E-6 / 2.0, maxY = source.getEyeY() + 1.0E-6 / 2.0;
        double minZ = source.getZ() - halfWidth, maxZ = source.getZ() + halfWidth;
        if (!access.readyColumns(minX, minZ, maxX, maxZ)) return false;
        int x0 = (int)Math.floor(minX), x1 = (int)Math.floor(maxX);
        int y0 = (int)Math.floor(minY), y1 = (int)Math.floor(maxY);
        int z0 = (int)Math.floor(minZ), z1 = (int)Math.floor(maxZ);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                var chunk = access.level().getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                for (int y = y0; y <= y1; y++) {
                    var state = chunk.getBlockState(scratch.pos.set(x, y, z));
                    if (!safeState(source, state)) return false;
                    if (!state.isAir() && state.blocksMotion()
                            && ((NativeEntityDependencyAccessors.BlockCollision) state.getBlock()).fabrolia$hasCollision()
                            && minX < x + 1.0 && maxX > x && minY < y + 1.0 && maxY > y
                            && minZ < z + 1.0 && maxZ > z) return false;
                }
            }
        }
        return true;
    }

    private static boolean blocks(Mob source, NativeEntityMotion.Access access, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, Scratch scratch, boolean jumpFactors) {
        // Match BlockCollisions' floor(bound +/- 1e-7) +/- 1, not inflate(1).
        double x0d = Math.floor(Math.nextDown(minX - 1.0E-7)) - 1;
        double x1d = Math.floor(Math.nextUp(maxX + 1.0E-7)) + 1;
        double z0d = Math.floor(Math.nextDown(minZ - 1.0E-7)) - 1;
        double z1d = Math.floor(Math.nextUp(maxZ + 1.0E-7)) + 1;
        double y0d = Math.floor(Math.nextDown(minY - 1.0E-7)) - 1;
        double y1d = Math.floor(Math.nextUp(maxY + 1.0E-7)) + 1;
        if (!(x0d > Integer.MIN_VALUE && x1d < Integer.MAX_VALUE && z0d > Integer.MIN_VALUE && z1d < Integer.MAX_VALUE
                && y0d >= access.level().getMinY() && y1d < access.level().getMaxY())
                || !access.readyColumns(x0d, z0d, x1d, z1d)) return false;
        int x0 = (int)x0d, x1 = (int)x1d, y0 = (int)y0d, y1 = (int)y1d, z0 = (int)z0d, z1 = (int)z1d;
        if (DEFAULT_SUFFOCATION == null || !SUFFOCATION.get(DEFAULT_SUFFOCATION.getClass())) return false;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                var chunk = access.level().getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                for (int y = y0; y <= y1; y++) {
                    var state = chunk.getBlockState(scratch.pos.set(x, y, z));
                    if (!safeState(source, state)) return false;
                    Block block = state.getBlock();
                    float friction = block.getFriction(), speed = block.getSpeedFactor(), jump = block.getJumpFactor();
                    if (!Float.isFinite(friction) || !Float.isFinite(speed) || !Float.isFinite(jump)) return false;
                    if (jumpFactors) scratch.jumpFactor = Math.max(scratch.jumpFactor, Math.abs(jump));
                }
            }
        }
        return true;
    }

    // Obtain the actual native method-reference class once, on disposable
    // attribute objects. Host/capture shape alone would also admit unrelated
    // AttributeMap Consumers such as assignAllValues' lambda.
    private static final Field ATTRIBUTE_DIRTY_RECEIVER = attributeDirtyReceiver();

    private static Field attributeDirtyReceiver() {
        if (Attributes.MOVEMENT_SPEED.value().getClass()
                != net.minecraft.world.entity.ai.attributes.RangedAttribute.class) return null;
        var supplier = net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder()
                .add(Attributes.MOVEMENT_SPEED).build();
        var map = new net.minecraft.world.entity.ai.attributes.AttributeMap(supplier);
        var instance = map.getInstance(Attributes.MOVEMENT_SPEED);
        if (instance == null) return null;
        var callback = ((NativeGroundMotionAccessors.AttributeDirty) instance).fabrolia$groundAttributeDirty();
        if (callback == null) return null;
        Class<?> type = callback.getClass();
        if (!type.isHidden() || type.getNestHost() != net.minecraft.world.entity.ai.attributes.AttributeMap.class
                || !NATIVE.get(type)) return null;
        Field[] fields = type.getDeclaredFields();
        if (fields.length != 1 || fields[0].getType() != net.minecraft.world.entity.ai.attributes.AttributeMap.class
                || Modifier.isStatic(fields[0].getModifiers()) || !Modifier.isFinal(fields[0].getModifiers())
                || !fields[0].trySetAccessible()) return null;
        return fields[0];
    }

    /** Shared source-state proof; reads callbacks, never invokes them. */
    static boolean sourceAttributes(Mob source) {
        var map = source.getAttributes();
        if (map.getClass() != net.minecraft.world.entity.ai.attributes.AttributeMap.class
                || ATTRIBUTE_DIRTY_RECEIVER == null) return false;
        var values = (NativeGroundMotionAccessors.AttributeValues) map;
        var supplier = values.fabrolia$groundAttributeSupplier();
        if (supplier == null || supplier.getClass() != net.minecraft.world.entity.ai.attributes.AttributeSupplier.class) return false;
        // Absent mutable instances are copied from these native templates with
        // a freshly bound map callback; template onDirty is not invoked.
        for (var entry : ((NativeGroundMotionAccessors.AttributeDefaults) supplier).fabrolia$groundDefaultAttributes().entrySet()) {
            if (!attributeState(entry.getKey(), entry.getValue())) return false;
        }
        var attributes = values.fabrolia$groundAttributes();
        for (var entry : attributes.entrySet()) {
            var instance = entry.getValue();
            if (!attributeState(entry.getKey(), instance)) return false;
            var callback = ((NativeGroundMotionAccessors.AttributeDirty) instance).fabrolia$groundAttributeDirty();
            if (callback == null || callback.getClass() != ATTRIBUTE_DIRTY_RECEIVER.getDeclaringClass()) return false;
            try {
                if (ATTRIBUTE_DIRTY_RECEIVER.get(callback) != map) return false;
            } catch (IllegalAccessException failure) {
                return false;
            }
        }
        for (var instance : map.getAttributesToUpdate()) {
            if (instance == null || instance.getClass() != net.minecraft.world.entity.ai.attributes.AttributeInstance.class
                    || !attributeState(instance.getAttribute(), instance)
                    || attributes.get(instance.getAttribute()) != instance || instance.getAttribute().is(Attributes.SCALE)) return false;
        }
        return true;
    }

    private static boolean attributeState(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder,
                                          net.minecraft.world.entity.ai.attributes.AttributeInstance instance) {
        return holder != null && holder.getClass() == net.minecraft.core.Holder.Reference.class && holder.isBound()
                && holder.value().getClass() == net.minecraft.world.entity.ai.attributes.RangedAttribute.class
                && instance != null && instance.getClass() == net.minecraft.world.entity.ai.attributes.AttributeInstance.class
                && instance.getAttribute() == holder;
    }

    private static double attribute(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var values = (NativeGroundMotionAccessors.AttributeValues) mob.getAttributes();
        var instance = values.fabrolia$groundAttributes().get(attribute);
        if (instance == null) {
            var supplier = values.fabrolia$groundAttributeSupplier();
            if (supplier.getClass() != net.minecraft.world.entity.ai.attributes.AttributeSupplier.class) return Double.NaN;
            instance = ((NativeGroundMotionAccessors.AttributeDefaults) supplier).fabrolia$groundDefaultAttributes().get(attribute);
        }
        // getValue may invoke sanitizeValue when dirty. Prove the concrete
        // instance and the requested native attribute before calling it.
        if (instance == null || instance.getClass() != net.minecraft.world.entity.ai.attributes.AttributeInstance.class
                || instance.getAttribute() != attribute
                || attribute.value().getClass() != net.minecraft.world.entity.ai.attributes.RangedAttribute.class) return Double.NaN;
        return instance.getValue();
    }

    private static double upperFloat(double value) {
        return (double)Math.nextUp((float)value);
    }

    private static double up(double value) {
        return Math.nextUp(value);
    }
}
