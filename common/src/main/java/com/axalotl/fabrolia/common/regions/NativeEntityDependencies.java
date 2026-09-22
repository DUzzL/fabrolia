package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.NativeEntityDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.NativeGoalDependencyAccessors;
import com.axalotl.fabrolia.common.mixin.entity.ProjectileOwnerAccessor;
import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementCaptureOwnership;
import com.axalotl.fabrolia.common.parallelised.utils.PlayerChunkIndex;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.BegGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

/**
 * Pre-body classification of native retained references and query producers.
 * Never resolves an EntityReference or evaluates an AI predicate. Candidate
 * gameplay fields are read only after their exact live index/claim witness.
 */
public final class NativeEntityDependencies {
    private NativeEntityDependencies() {}

    private static final ThreadLocal<Check> CHECK = ThreadLocal.withInitial(Check::new);
    private static final ThreadLocal<TickAdmission> ADMISSIONS =
            ThreadLocal.withInitial(TickAdmission::new);
    private static final ThreadLocal<TickAdmission> ACTIVE = new ThreadLocal<>();
    private static final StackWalker QUERY_CALLER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Class<?> SMALL_IMMUTABLE_LIST = java.util.List.of(Boolean.TRUE).getClass();
    private static final Goal.Flag[] GOAL_FLAGS = Goal.Flag.values();

    /** Reused per worker; nested classification never overwrites an entered E's proof. */
    static final class TickAdmission implements AutoCloseable {
        private final Check check = new Check();
        private TickAdmission next;
        private TickAdmission previous;
        private boolean leased;
        private boolean global;
        private boolean entered;
        private Entity entity;
        private DeclaredQuery[] queries = new DeclaredQuery[8];
        private int size;
        private TemptingQuery[] temptingQueries = new TemptingQuery[2];
        private int temptingSize;
        private final NativeEntityMotion.Bounds motion = new NativeEntityMotion.Bounds();
        private boolean motionCertified;

        boolean requiresGlobal() {
            return global;
        }

        void enterLocal() {
            if (!leased || entered || global) throw new IllegalStateException("invalid native E admission");
            previous = ACTIVE.get();
            entered = true;
            ACTIVE.set(this);
        }

        private void declare(Mob source, Class<?> producer, TargetingConditions conditions, double range,
                             boolean eyeOrigin) {
            if (size == queries.length) queries = java.util.Arrays.copyOf(queries, size * 2);
            DeclaredQuery query = queries[size];
            if (query == null) queries[size] = query = new DeclaredQuery();
            query.admission = this;
            query.source = source;
            query.producer = producer;
            query.conditions = conditions;
            query.range = range;
            query.origin = source.position();
            query.y = eyeOrigin ? source.getEyeY() : query.origin.y;
            var access = (NativeGoalDependencyAccessors.Targeting) conditions;
            query.selector = access.fabrolia$querySelector();
            query.lineOfSight = access.fabrolia$queryLineOfSight();
            query.invisible = access.fabrolia$queryTestInvisible();
            query.combat = access.fabrolia$queryCombat();
            size++;
        }
        private void declareTempting(net.minecraft.world.entity.PathfinderMob source,
                TemptingSensor sensor, NativeNautilusProgram.Snapshot program,
                TargetingConditions template, double range) {
            if (temptingSize == temptingQueries.length) {
                temptingQueries = java.util.Arrays.copyOf(temptingQueries, temptingSize * 2);
            }
            TemptingQuery query = temptingQueries[temptingSize];
            if (query == null) temptingQueries[temptingSize] = query = new TemptingQuery();
            query.admission = this;
            query.source = source;
            query.sensor = sensor;
            query.program = program;
            query.template = template;
            query.range = range;
            query.origin = source.position();
            query.roster = check.runtime.level.players();
            var access = (NativeGoalDependencyAccessors.Targeting) template;
            query.selector = access.fabrolia$querySelector();
            query.lineOfSight = access.fabrolia$queryLineOfSight();
            query.invisible = access.fabrolia$queryTestInvisible();
            query.combat = access.fabrolia$queryCombat();
            temptingSize++;
        }


        @Override
        public void close() {
            if (!leased || entered && ACTIVE.get() != this) {
                throw new IllegalStateException("native E admission closed out of order");
            }
            if (entered) {
                ACTIVE.set(previous);
                previous = null;
                entered = false;
            }
            for (int i = 0; i < size; i++) queries[i].clear();
            size = 0;
            for (int i = 0; i < temptingSize; i++) temptingQueries[i].clear();
            temptingSize = 0;
            motionCertified = false;
            check.navigationColumns.reset(null);
            check.meleeSource = null;
            check.motionContacts = false;
            entity = null;
            check.runtime = null;
            check.region = null;
            check.admission = null;
            check.global = false;
            leased = false;
        }
    }

    /** Backstops run before collision reads or position publication, never retry E. */
    public static void requireLocalEntityMove(Entity entity, net.minecraft.world.entity.MoverType type,
            net.minecraft.world.phys.Vec3 movement) {
        TickAdmission frame = motionAdmission(entity);
        if (frame == null) return;
        AABB box = entity.getBoundingBox();
        double step = entity.maxUpStep();
        if (type != net.minecraft.world.entity.MoverType.SELF
                || movement.getClass() != net.minecraft.world.phys.Vec3.class || !movement.isFinite()
                || !contains(frame.motion,
                    box.minX + Math.min(0, movement.x), box.minY + Math.min(0, movement.y),
                    box.minZ + Math.min(0, movement.z), box.maxX + Math.max(0, movement.x),
                    box.maxY + Math.max(step, movement.y), box.maxZ + Math.max(0, movement.z))) {
            throw new IllegalStateException("native entity move escaped its pre-entry envelope: " + entity.getId());
        }
    }

    public static void requireLocalEntityPosition(Entity entity, double x, double y, double z) {
        TickAdmission frame = motionAdmission(entity);
        if (frame == null) return;
        double half = (double) (entity.getBbWidth() / 2.0F);
        if (!contains(frame.motion, x - half, y, z - half, x + half, y + entity.getBbHeight(), z + half)) {
            throw new IllegalStateException("native entity position escaped its pre-entry envelope: " + entity.getId());
        }
    }

    public static void requireLocalEntityBox(Entity entity, AABB box) {
        TickAdmission frame = motionAdmission(entity);
        if (frame != null && (box.getClass() != AABB.class || !frame.motion.contains(box))) {
            throw new IllegalStateException("native entity bounds escaped their pre-entry envelope: " + entity.getId());
        }
    }

    private static boolean contains(NativeEntityMotion.Bounds envelope,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return minX >= envelope.minX && minY >= envelope.minY && minZ >= envelope.minZ
                && maxX <= envelope.maxX && maxY <= envelope.maxY && maxZ <= envelope.maxZ;
    }

    private static TickAdmission motionAdmission(Entity entity) {
        TickAdmission frame = ACTIVE.get();
        if (frame == null || frame.global || !frame.motionCertified) return null;
        if (entity != frame.entity) {
            // No movement certificate is needed for an unpublished constructor.
            // This does not grant ownership: existing construction/write guards
            // remain authoritative, and NO_REGION is not a construction witness.
            if (((com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor) entity)
                    .fabrolia$getLevelCallback() == net.minecraft.world.level.entity.EntityInLevelCallback.NULL) return null;
            throw new IllegalStateException("native local E moved an undeclared live recipient: " + entity.getId());
        }
        if (!frame.entered || entity.level() != frame.check.runtime.level
                || !frame.check.ownedRegion(frame.check.region)
                || !RegionScheduler.ownsCurrentContext(frame.check.runtime.level, entity.blockPosition())) {
            throw new IllegalStateException("native entity movement lost its real island claim: " + entity.getId());
        }
        return frame;
    }

    static TickAdmission prepareEntityTick(RegionizedServer runtime, Region region, Entity entity) {
        TickAdmission frame = ADMISSIONS.get();
        while (frame.leased) {
            if (frame.next == null) frame.next = new TickAdmission();
            frame = frame.next;
        }
        frame.leased = true;
        frame.entity = entity;
        frame.check.runtime = runtime;
        frame.check.region = region;
        frame.check.admission = frame;
        frame.check.global = RegionScheduler.ownsExclusiveGameplay();
        try {
            frame.check.navigationColumns.reset(entity instanceof Mob mob ? mob : null);
            frame.global = !frame.check.tickTree(entity, 0);
            return frame;
        } catch (Throwable failure) {
            frame.close();
            throw failure;
        }
    }
    /**
     * Inspect the next ordinal relative to the parked source island, not the
     * writer's global footprint. A false result leaves the complete ordinal
     * on its worker, including its index/token/frozen/arrival prefix.
     */
    static boolean canContinueMainRun(RegionizedServer runtime, Region region, Entity entity) {
        if (!RegionScheduler.ownsExclusiveGameplay()) {
            throw new IllegalStateException("native entity run inspection requires a writer fence");
        }
        if (entity == null || entity instanceof ServerPlayer || !nativeClass(entity.getClass())
                || entity.level() != runtime.level
                || !(entity instanceof RegionKeyed keyed)
                || keyed.fabrolia$getRegionKey() != region.gridKey
                || runtime.regionizer().grid.keyFor(entity.chunkPosition().pack()) != region.gridKey) {
            return false;
        }
        // Index movement could change the classification. Such an ordinal was
        // left untouched above; this prefix can only skip or mark the entity.
        if (com.axalotl.fabrolia.common.ParallelProcessor.requiresMainThread(entity)) return true;
        try (TickAdmission admission = prepareEntityTick(runtime, region, entity)) {
            return admission.requiresGlobal();
        }
    }

    /**
     * Internal mixin bridge. It cannot declare queries or grant an arbitrary
     * nearest-player caller a read pass: only the exact native producer stack
     * and object identities admitted before this E can obtain a handle.
     */
    public static DeclaredQuery declaredPlayerQuery(net.minecraft.server.level.ServerLevel level,
            java.util.List<?> players, TargetingConditions conditions, LivingEntity source,
            double x, double y, double z) {
        TickAdmission frame = ACTIVE.get();
        if (frame == null || frame.check.runtime.level != level || players != level.players()) return null;
        for (int i = 0; i < frame.size; i++) {
            DeclaredQuery query = frame.queries[i];
            if (query.source == source && query.conditions == conditions) {
                if (!query.nativeCaller()) return null;
                query.requireUnchanged(x, y, z);
                return query;
            }
        }
        return null;
    }

    public static final class DeclaredQuery {
        private TickAdmission admission;
        private Mob source;
        private Class<?> producer;
        private TargetingConditions conditions;
        private TargetingConditions.Selector selector;
        private net.minecraft.world.phys.Vec3 origin;
        private double range;
        private double y;
        private boolean lineOfSight;
        private boolean invisible;
        private boolean combat;
        private boolean calling;

        private DeclaredQuery() {}

        private boolean nativeCaller() {
            String method = producer == NearestAttackableTargetGoal.class ? "findTarget"
                    : producer == net.minecraft.world.entity.ambient.Bat.class ? "customServerAiStep" : "canUse";
            return QUERY_CALLER.walk(frames -> {
                var iterator = frames.iterator();
                while (iterator.hasNext()) {
                    var frame = iterator.next();
                    if (frame.getDeclaringClass() != net.minecraft.server.level.ServerLevel.class
                            || !frame.getMethodName().equals("getNearestEntity")) continue;
                    if (!iterator.hasNext()) return false;
                    var funnel = iterator.next();
                    if (funnel.getDeclaringClass() != net.minecraft.server.level.ServerEntityGetter.class
                            || !funnel.getMethodName().equals("getNearestPlayer")
                            || !iterator.hasNext()) return false;
                    var caller = iterator.next();
                    return caller.getDeclaringClass() == producer && caller.getMethodName().equals(method);
                }
                return false;
            });
        }

        private void requireUnchanged(double x, double queryY, double z) {
            var access = (NativeGoalDependencyAccessors.Targeting) conditions;
            if (calling || ACTIVE.get() != admission || !admission.entered
                    || !source.position().equals(origin) || x != origin.x || z != origin.z || queryY != y
                    || access.fabrolia$queryRange() != range
                    || access.fabrolia$querySelector() != selector
                    || access.fabrolia$queryLineOfSight() != lineOfSight
                    || access.fabrolia$queryTestInvisible() != invisible
                    || access.fabrolia$queryCombat() != combat) {
                throw new IllegalStateException("native player-query certificate changed after E entry");
            }
        }

        public <T extends LivingEntity> T findNearest(java.util.List<? extends T> players) {
            requireUnchanged(origin.x, y, origin.z);
            if (players != admission.check.runtime.level.players()) {
                throw new IllegalStateException("native player-query roster changed");
            }
            calling = true;
            try {
                double bound = Math.max(range, 2);
                double best = -1.0;
                T result = null;
                for (T candidate : players) {
                    if (!(candidate instanceof ServerPlayer player)) {
                        throw new IllegalStateException("non-player in native player-query roster");
                    }
                    var position = NativePlayerObservations.position(player);
                    // Only the audited pure native prefix is omitted. Such a
                    // candidate cannot pass the later visibility/range test,
                    // whose radius never exceeds max(range, 2). Never prune by
                    // the current best: LOS may update the source's sensing cache.
                    if (Math.abs(position.x - origin.x) > bound
                            || Math.abs(position.y - origin.y) > bound
                            || Math.abs(position.z - origin.z) > bound) continue;
                    if (!admission.check.dependency(player, 0)) {
                        throw new IllegalStateException("native query reached an unowned candidate");
                    }
                    NativePlayerObservations.requireGameplayObservation(player);
                    if (conditions.test(admission.check.runtime.level, source, candidate)) {
                        // Native ranking may use eye Y; the range test above
                        // deliberately uses the source's actual position.
                        double distance = candidate.distanceToSqr(origin.x, y, origin.z);
                        if (best == -1.0 || distance < best) {
                            best = distance;
                            result = candidate;
                        }
                    }
                }
                if (result != null && !admission.check.dependency(result, 0)) {
                    throw new IllegalStateException("native query returned an unowned winner");
                }
                return result;
            } finally {
                calling = false;
            }
        }

        private void clear() {
            admission = null;
            source = null;
            producer = null;
            conditions = null;
            selector = null;
            origin = null;
        }
    }
    /**
     * A streaming sensor declaration has its own invocation/copy lifetime; it
     * does not masquerade as a nearest-player call or grant receiver authority.
     */
    public static TemptingQuery beginTemptingSensor(TemptingSensor sensor,
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.PathfinderMob source) {
        TickAdmission admission = ACTIVE.get();
        if (admission == null) return null;
        if (admission.check.runtime.level == level) {
            for (int i = 0; i < admission.temptingSize; i++) {
                TemptingQuery query = admission.temptingQueries[i];
                if (query.sensor == sensor && query.source == source) {
                    query.begin();
                    return query;
                }
            }
        }
        throw new IllegalStateException("undeclared tempting sensor entered a local native E");
    }

    public static final class TemptingQuery {
        private TickAdmission admission;
        private net.minecraft.world.entity.PathfinderMob source;
        private TemptingSensor sensor;
        private NativeNautilusProgram.Snapshot program;
        private TargetingConditions template;
        private TargetingConditions conditions;
        private TargetingConditions.Selector selector;
        private net.minecraft.world.phys.Vec3 origin;
        private java.util.List<ServerPlayer> roster;
        private double range;
        private boolean lineOfSight;
        private boolean invisible;
        private boolean combat;
        private boolean calling;

        private TemptingQuery() {}

        private void requireSource() {
            if (admission == null || ACTIVE.get() != admission || !admission.entered
                    || !admission.check.ownedRegion(admission.check.region)
                    || source.level() != admission.check.runtime.level
                    || roster != admission.check.runtime.level.players()
                    || !program.isCurrent() || program.tempting() != sensor
                    || !source.position().equals(origin)
                    || (double) (float) source.getAttributeValue(Attributes.TEMPT_RANGE) != range) {
                throw new IllegalStateException("native tempting-sensor certificate changed after E entry");
            }
        }

        private void begin() {
            if (calling) throw new IllegalStateException("native tempting-sensor declaration reentered");
            requireSource();
            calling = true;
        }

        public void bind(TargetingConditions actualNativeCopy) {
            requireSource();
            if (!calling || conditions != null || actualNativeCopy == template
                    || actualNativeCopy == null || actualNativeCopy.getClass() != TargetingConditions.class) {
                throw new IllegalStateException("invalid native tempting-sensor copy");
            }
            var access = (NativeGoalDependencyAccessors.Targeting) actualNativeCopy;
            if (access.fabrolia$queryRange() != range
                    || access.fabrolia$querySelector() != selector
                    || access.fabrolia$queryLineOfSight() != lineOfSight
                    || access.fabrolia$queryTestInvisible() != invisible
                    || access.fabrolia$queryCombat() != combat) {
                throw new IllegalStateException("native tempting-sensor copy differs from its declaration");
            }
            conditions = actualNativeCopy;
        }

        public boolean permits(ServerPlayer candidate) {
            if (!calling || conditions == null || ACTIVE.get() != admission) {
                throw new IllegalStateException("native tempting predicate escaped its invocation");
            }
            var position = NativePlayerObservations.position(candidate);
            double bound = Math.max(range, 2);
            if (Math.abs(position.x - origin.x) > bound
                    || Math.abs(position.y - origin.y) > bound
                    || Math.abs(position.z - origin.z) > bound) return false;
            if (!admission.check.dependency(candidate, 0)) {
                throw new IllegalStateException("native tempting sensor reached an unowned candidate");
            }
            NativePlayerObservations.requireGameplayObservation(candidate);
            return true;
        }

        public void finish() {
            if (!calling || ACTIVE.get() != admission) {
                throw new IllegalStateException("native tempting-sensor declaration closed out of order");
            }
            conditions = null;
            calling = false;
        }

        private void clear() {
            if (calling) throw new IllegalStateException("native E ended inside a tempting sensor");
            admission = null;
            source = null;
            sensor = null;
            program = null;
            template = null;
            conditions = null;
            selector = null;
            origin = null;
            roster = null;
        }
    }


    /**
     * Origin certificate, not an EntityType namespace/package-name allowance.
     * Query implementations below additionally require an exact audited class.
     * In particular a mod subclass of a native goal cannot inherit its proof.
     */
    private static final ClassValue<Boolean> NATIVE_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            var source = type.getProtectionDomain().getCodeSource();
            var nativeSource = Entity.class.getProtectionDomain().getCodeSource();
            return source != null && nativeSource != null
                    && type.getClassLoader() == Entity.class.getClassLoader()
                    && source.equals(nativeSource);
        }
    };

    private static boolean nativeClass(Class<?> type) {
        return NATIVE_CLASS.get(type);
    }

    /**
     * Carpet's exact actor inherits the native observation/contact call graph.
     * Its declared overrides are only these audited lifecycle/actor operations,
     * none reached by targeting, visibility/equipment reads, LOS or base push.
     * Reject new overrides rather than treating observer metadata as a rich grant.
     */
    private static final ClassValue<Boolean> INHERITED_PLAYER_QUERIES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!type.getName().equals("carpet.patches.EntityPlayerMPFake")
                    || type.getSuperclass() != ServerPlayer.class) return false;
            try {
                if (type != Class.forName("carpet.patches.EntityPlayerMPFake", false,
                        NativeEntityDependencies.class.getClassLoader())) return false;
                for (var method : type.getDeclaredMethods()) {
                    int modifiers = method.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(modifiers)
                            || java.lang.reflect.Modifier.isPrivate(modifiers)) continue;
                    switch (method.getName()) {
                        case "onEquipItem", "kill", "tick", "startRiding", "die",
                             "getIpAddress", "allowsListing", "teleport" -> { }
                        default -> { return false; }
                    }
                }
                return true;
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static boolean queryImplementation(Entity entity) {
        return nativeClass(entity.getClass()) || entity instanceof ServerPlayer player
                && NativePlayerObservations.isSupportedPlayer(player)
                && INHERITED_PLAYER_QUERIES.get(player.getClass());
    }

    private static boolean declaredBy(Class<?> type, Class<?> owner, String method, Class<?>... arguments)
            throws NoSuchMethodException {
        return type.getMethod(method, arguments).getDeclaringClass() == owner;
    }

    private static boolean inheritsAllyTest(Class<?> type) throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                cursor.getDeclaredMethod("considersEntityAsAlly", Entity.class);
                return cursor == Entity.class;
            } catch (NoSuchMethodException absent) {
                // Walk only this one audited virtual method, not arbitrary state.
            }
        }
        throw new NoSuchMethodException("considersEntityAsAlly");
    }

    private static final boolean VANILLA_VISIBILITY = vanillaVisibility();

    private static boolean vanillaVisibility() {
        try {
            // NeoForge inserts LivingVisibilityEvent into this same native
            // declaring class. Its callbacks need not be pure and can increase
            // range, so method provenance alone cannot certify a bounded query.
            Class.forName("net.neoforged.neoforge.common.CommonHooks", false,
                    Entity.class.getClassLoader());
            return false;
        } catch (ClassNotFoundException vanillaPlatform) {
            return true;
        } catch (LinkageError unknownPlatform) {
            return false;
        }
    }

    /** Pure native callback methods, including the visibility upper-bound proof. */
    private static final ClassValue<Boolean> RICH_PLAYER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type != ServerPlayer.class && !INHERITED_PLAYER_QUERIES.get(type)) return false;
            try {
                return declaredBy(type, LivingEntity.class, "getVisibilityPercent", Entity.class)
                        && declaredBy(type, LivingEntity.class, "getArmorCoverPercentage")
                        && declaredBy(type, LivingEntity.class, "canBeSeenByAnyone")
                        && declaredBy(type, net.minecraft.world.entity.player.Player.class, "canBeSeenAsEnemy")
                        && declaredBy(type, net.minecraft.world.entity.player.Player.class, "getAbilities")
                        && declaredBy(type, LivingEntity.class, "isAlive")
                        && declaredBy(type, LivingEntity.class, "getMainHandItem")
                        && declaredBy(type, LivingEntity.class, "getOffhandItem")
                        && declaredBy(type, LivingEntity.class, "getItemBySlot",
                            net.minecraft.world.entity.EquipmentSlot.class)
                        && declaredBy(type, Entity.class, "isDiscrete")
                        && declaredBy(type, Entity.class, "isInvisible")
                        && declaredBy(type, Entity.class, "isInvulnerable")
                        && declaredBy(type, Entity.class, "getEyeY")
                        && declaredBy(type, Entity.class, "getTeam")
                        && declaredBy(type, Entity.class, "isAlliedTo", net.minecraft.world.scores.Team.class)
                        && inheritsAllyTest(type);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static final ClassValue<Boolean> QUERY_BACKEND = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type != net.minecraft.server.level.ServerLevel.class) return false;
            try {
                Class<?> getter = net.minecraft.server.level.ServerEntityGetter.class;
                return declaredBy(type, getter, "getNearestPlayer", TargetingConditions.class, LivingEntity.class)
                        && declaredBy(type, getter, "getNearestPlayer", TargetingConditions.class,
                            LivingEntity.class, double.class, double.class, double.class)
                        && declaredBy(type, type, "getNearestEntity", java.util.List.class,
                            TargetingConditions.class, LivingEntity.class,
                            double.class, double.class, double.class);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static final ClassValue<Boolean> QUERY_SOURCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!nativeClass(type) || !stableQueryPrefix(type)) return false;
            try {
                return declaredBy(type, Entity.class, "position")
                        && declaredBy(type, Entity.class, "getEyeY")
                        && declaredBy(type, Entity.class, "distanceToSqr",
                            double.class, double.class, double.class)
                        && declaredBy(type, LivingEntity.class, "getAttributeValue", net.minecraft.core.Holder.class)
                        && declaredBy(type, LivingEntity.class, "getInterpolation")
                        && declaredBy(type, Mob.class, "getSensing")
                        && declaredBy(type, LivingEntity.class, "hasLineOfSight", Entity.class)
                        && declaredBy(type, LivingEntity.class, "hasLineOfSight", Entity.class,
                            net.minecraft.world.level.ClipContext.Block.class,
                            net.minecraft.world.level.ClipContext.Fluid.class, double.class);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static final ClassValue<Boolean> COMBAT_QUERY_SOURCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                if (type == net.minecraft.world.entity.animal.nautilus.Nautilus.class) {
                    // The live null-owner / untamed branch is checked below.
                    // No owner lookup or foreign team callback is skipped.
                    return declaredBy(type, net.minecraft.world.entity.TamableAnimal.class, "canAttack", LivingEntity.class)
                            && declaredBy(type, net.minecraft.world.entity.TamableAnimal.class, "isOwnedBy", LivingEntity.class)
                            && declaredBy(type, OwnableEntity.class, "getOwner")
                            && declaredBy(type, net.minecraft.world.entity.TamableAnimal.class, "getOwnerReference")
                            && declaredBy(type, net.minecraft.world.entity.TamableAnimal.class, "isTame")
                            && declaredBy(type, net.minecraft.world.entity.TamableAnimal.class, "getTeam")
                            && declaredBy(type, Entity.class, "isAlliedTo", net.minecraft.world.scores.Team.class)
                            && methodOwner(type, "considersEntityAsAlly", Entity.class)
                                == net.minecraft.world.entity.TamableAnimal.class;
                }
                return declaredBy(type, Mob.class, "canAttack", LivingEntity.class)
                        && declaredBy(type, Entity.class, "getTeam")
                        && declaredBy(type, Entity.class, "isAlliedTo", net.minecraft.world.scores.Team.class)
                        && inheritsAllyTest(type);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static boolean querySourceImplementation(Mob source, boolean combat) {
        return QUERY_SOURCE.get(source.getClass())
                && source.getSensing().getClass() == net.minecraft.world.entity.ai.sensing.Sensing.class
                && (!(source instanceof net.minecraft.world.entity.animal.nautilus.Nautilus nautilus)
                    || !nautilus.isTame() && nautilus.getOwnerReference() == null)
                && (!combat || COMBAT_QUERY_SOURCE.get(source.getClass()));
    }

    /**
     * Audited 26.2 tick/baseTick/aiStep prefixes, not a namespace allowance.
     * Age changes/travel follow the goal selectors. Conversion/explosion either
     * leave this origin unchanged or remove this source before its AI is reached.
     */
    private static boolean stableQueryPrefix(Class<?> type) {
        return type == net.minecraft.world.entity.animal.sheep.Sheep.class
                || type == net.minecraft.world.entity.animal.cow.Cow.class
                || type == net.minecraft.world.entity.animal.pig.Pig.class
                || type == net.minecraft.world.entity.animal.chicken.Chicken.class
                || type == net.minecraft.world.entity.animal.equine.Horse.class
                || type == net.minecraft.world.entity.animal.equine.Donkey.class
                || type == net.minecraft.world.entity.animal.equine.Mule.class
                || type == net.minecraft.world.entity.animal.wolf.Wolf.class
                || type == net.minecraft.world.entity.monster.zombie.Zombie.class
                || type == net.minecraft.world.entity.monster.skeleton.Skeleton.class
                || type == net.minecraft.world.entity.monster.Creeper.class
                || type == net.minecraft.world.entity.monster.spider.Spider.class
                || type == net.minecraft.world.entity.animal.rabbit.Rabbit.class
                || type == net.minecraft.world.entity.animal.polarbear.PolarBear.class
                || type == net.minecraft.world.entity.animal.turtle.Turtle.class
                || type == net.minecraft.world.entity.animal.nautilus.Nautilus.class
                || type == net.minecraft.world.entity.ambient.Bat.class;
    }

    private static Class<?> methodOwner(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                cursor.getDeclaredMethod(name, parameters);
                return cursor;
            } catch (NoSuchMethodException absent) {
                // Resolve this exact native virtual call, without evaluating it.
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static final ClassValue<Boolean> CONSTANT_QUERY_GEOMETRY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!nativeClass(type)) return false;
            Class<?> base = net.minecraft.world.level.block.state.BlockBehaviour.class;
            Class<?> state = net.minecraft.world.level.block.state.BlockState.class;
            Class<?> getter = net.minecraft.world.level.BlockGetter.class;
            Class<?> context = net.minecraft.world.phys.shapes.CollisionContext.class;
            try {
                Class<?> shape = methodOwner(type, "getShape", state, getter, BlockPos.class, context);
                return (shape == base || shape == net.minecraft.world.level.block.AirBlock.class)
                        && methodOwner(type, "getCollisionShape", state, getter, BlockPos.class, context) == base
                        && methodOwner(type, "getInteractionShape", state, getter, BlockPos.class) == base
                        && methodOwner(type, "getFluidState", state) == base;
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static final ClassValue<Boolean> BAT_CONDUCTOR_GEOMETRY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!CONSTANT_QUERY_GEOMETRY.get(type)) return false;
            try {
                return methodOwner(type, "isCollisionShapeFullBlock",
                        net.minecraft.world.level.block.state.BlockState.class,
                        net.minecraft.world.level.BlockGetter.class, BlockPos.class)
                        == net.minecraft.world.level.block.state.BlockBehaviour.class;
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    /** The native callbacks reached by an idle player's collision/push/touch scans. */
    private static final ClassValue<Boolean> PLAYER_CONTACT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (!nativeClass(type) && !INHERITED_PLAYER_QUERIES.get(type)) return false;
            try {
                Class<?> push = type.getMethod("push", Entity.class).getDeclaringClass();
                Class<?> pushable = type.getMethod("isPushable").getDeclaringClass();
                Class<?> touch = type.getMethod("playerTouch",
                        net.minecraft.world.entity.player.Player.class).getDeclaringClass();
                // Vehicle boarding, damaging touches and arrow pickup stacks
                // have additional producers/state, not a provenance-only proof.
                return (push == Entity.class || push == LivingEntity.class)
                        && (pushable == Entity.class || pushable == LivingEntity.class)
                        && type.getMethod("push", double.class, double.class, double.class)
                                .getDeclaringClass() == Entity.class
                        && type.getMethod("canBeCollidedWith", Entity.class)
                                .getDeclaringClass() == Entity.class
                        && (touch == Entity.class
                            || type == net.minecraft.world.entity.item.ItemEntity.class
                            || type == net.minecraft.world.entity.ExperienceOrb.class);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };


    /** Native lambda host identity is checked without invoking the callback. */
    private static boolean callbackFrom(Object callback, Class<?> host) {
        return callback != null && callback.getClass().isHidden()
                && callback.getClass().getNestHost() == host && nativeClass(host);
    }

    // Hidden lambda classes cannot be mixin targets. This is a fixed native
    // capture schema (zero, one or two edges), never a reflective graph walk.
    private static final ClassValue<java.lang.reflect.Field[]> CAPTURES = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Field[] computeValue(Class<?> type) {
            if (!type.isHidden()) return null;
            var fields = type.getDeclaredFields();
            for (var field : fields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !field.trySetAccessible()) return null;
            }
            return fields;
        }
    };

    private static Object singleCapture(Object callback) {
        if (callback == null) return null;
        var fields = CAPTURES.get(callback.getClass());
        if (fields == null || fields.length != 1) return null;
        try {
            return fields[0].get(callback);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static boolean captures(Object callback, Object first, Object second) {
        if (callback == null) return false;
        var fields = CAPTURES.get(callback.getClass());
        int count = first == null ? 0 : second == null ? 1 : 2;
        if (fields == null || fields.length != count) return false;
        try {
            if (count == 0) return true;
            Object a = fields[0].get(callback);
            if (count == 1) return a == first;
            Object b = fields[1].get(callback);
            return a == first && b == second || a == second && b == first;
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    /** Local-footprint classification, including when a global owner prepares the next venue. */
    static boolean requiresFence(RegionizedServer runtime, Region region, Entity entity) {
        Check check = CHECK.get();
        if (check.runtime != null) {
            throw new IllegalStateException("native dependency preflight reentered");
        }
        check.runtime = runtime;
        check.region = region;
        check.global = RegionScheduler.ownsExclusiveGameplay();
        try {
            return !check.tickTree(entity, 0);
        } finally {
            check.runtime = null;
            check.region = null;
            check.global = false;
        }
    }

    private static final class Check implements net.minecraft.util.AbortableIterationConsumer<
            net.minecraft.world.level.entity.EntitySection<net.minecraft.world.level.entity.EntityAccess>>,
            NativeEntityMotion.Access {
        private RegionizedServer runtime;
        private Region region;
        private boolean global;
        private TickAdmission admission;
        private final BlockPos.MutableBlockPos queryBlock = new BlockPos.MutableBlockPos();
        private final Entity[] ownerPath = new Entity[16];
        private Entity querySource;
        private Class<?> queryType;
        private AABB queryBox;
        private boolean queryOwned;
        private boolean playerContacts;
        private boolean motionContacts;
        private int motionContactCount;
        private int crammingLimit;
        private Mob meleeSource;
        private final NativeBlockQueries.Columns navigationColumns = new NativeBlockQueries.Columns();

        @Override public net.minecraft.server.level.ServerLevel level() {
            return runtime.level;
        }

        @Override public boolean readyColumns(double minX, double minZ, double maxX, double maxZ) {
            return readyBlockRectangle(minX, minZ, maxX, maxZ);
        }

        @Override public boolean readyNavigationColumns(double minX, double minZ, double maxX, double maxZ) {
            if (!readyBlockRectangle(minX, minZ, maxX, maxZ)) return false;
            for (int z = (int) Math.floor(minZ) >> 4; z <= ((int) Math.floor(maxZ) >> 4); z++) {
                for (int x = (int) Math.floor(minX) >> 4; x <= ((int) Math.floor(maxX) >> 4); x++) {
                    var chunk = runtime.level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null || !navigationColumns.accepts(chunk)) return false;
                }
            }
            return true;
        }

        @Override public boolean readyEntityColumns(Entity target, double margin) {
            if (target == null || !(margin >= 0) || !Double.isFinite(margin) || !dependency(target, 0)) return false;
            return readyNavigationColumns(target.getX() - margin, target.getZ() - margin,
                    target.getX() + margin, target.getZ() + margin);
        }

        @Override public boolean hasNoGameEventListeners(NativeEntityMotion.Bounds footprint, int radius) {
            return NativeEmptyGameEvents.empty(this, footprint, radius);
        }

        /** The index, not mutable target coordinates, is the ownership witness. */
        private boolean indexedHere(Entity entity) {
            if (entity == null) return true;
            if (!(entity instanceof RegionKeyed keyed)) return false;
            long key = keyed.fabrolia$getRegionKey();
            if (key == RegionKeyed.NO_REGION) return false;
            Region target = runtime.regionizer().peekByGridKey(key);
            IslandRegistry.PIsland island = region.domain == null ? null : region.domain.owner;
            if (target == null || island == null || island.retired
                    || target.domain != region.domain || !runtime.ownsRegion(target)
                    || !global
                    && (!island.unitClaim.get()
                        || island.claimHolder != Thread.currentThread())) return false;
            // The set orders by native id; require object identity as well, not a
            // same-id replacement (a retained removed session is not its successor).
            var members = (java.util.NavigableSet<Entity>) target.entities;
            return members.ceiling(entity) == entity
                    && keyed.fabrolia$getRegionKey() == key;
        }

        /** Accessible TRACKED residents need not belong to the ticking index. */
        private boolean observedHere(Entity entity) {
            if (indexedHere(entity)) return true;
            if (entity instanceof net.minecraft.world.entity.player.Player || !queryImplementation(entity)
                    || !(entity instanceof RegionKeyed keyed)
                    || keyed.fabrolia$getRegionKey() != RegionKeyed.NO_REGION
                    || !(entity instanceof com.axalotl.fabrolia.common.ParallelProcessor.TickGuard guard)
                    || !(entity instanceof com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor access)) {
                return false;
            }
            var callback = access.fabrolia$getLevelCallback();
            if (!(callback instanceof EntityResidencyAccess residency)) return false;
            long section = residency.fabrolia$residentSection(entity);
            if (section == EntityResidencyAccess.NO_SECTION
                    || RegionScheduler.forLevel(runtime.level) != runtime
                    || runtime.regionizer().peekByGridKey(region.gridKey) != region) return false;
            int chunkX = net.minecraft.core.SectionPos.x(section);
            int chunkZ = net.minecraft.core.SectionPos.z(section);
            Region target = runtime.regionizer().peekByGridKey(
                    runtime.regionizer().grid.keyFor(chunkX, chunkZ));
            IslandRegistry.PIsland island = region.domain == null ? null : region.domain.owner;
            if (target == null || island == null || island.retired
                    || target.domain != region.domain || !runtime.ownsRegion(target)
                    || !global && (!island.unitClaim.get()
                        || island.claimHolder != Thread.currentThread())) return false;
            queryBlock.set(net.minecraft.core.SectionPos.sectionToBlockCoord(chunkX),
                    net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.y(section)),
                    net.minecraft.core.SectionPos.sectionToBlockCoord(chunkZ));
            if (!RegionScheduler.ownsCurrentContext(runtime.level, queryBlock)) return false;
            // A mover can publish its destination section before finishing its
            // original body. Residency is not a transfer of that body's lease.
            EntityTickLease lease = guard.fabrolia$tickLease();
            if (lease == null || lease.isArrivalPending() || guard.fabrolia$isPortalPending()
                    || NetherPortalPlayerAccess.requestForLifecycle(entity) != null) return false;
            Object owner = lease.get();
            if (owner != null && owner != Thread.currentThread()) return false;
            if (access.fabrolia$getLevelCallback() != callback
                    || !residency.fabrolia$isResident(entity, section)) return false;
            // Full-chunk construction is still HIDDEN until owner-claimed status
            // publication. Neither a provisional section nor a same-id replacement
            // is a live query participant.
            if (entity.level() != runtime.level || entity.isRemoved()
                    || runtime.level.getEntity(entity.getId()) != entity
                    || runtime.level.getEntity(entity.getUUID()) != entity) return false;
            BlockPos position = entity.blockPosition();
            return net.minecraft.core.SectionPos.asLong(position) == section
                    && RegionScheduler.ownsCurrentContext(runtime.level, position)
                    && keyed.fabrolia$getRegionKey() == RegionKeyed.NO_REGION;
        }

        private boolean reference(EntityReference<?> reference, int depth) {
            if (reference == null) return true;
            var value = ((NativeEntityDependencyAccessors.Reference) (Object) reference)
                    .fabrolia$dependencyValue();
            // UUID state is unresolved, NOT absent. Native resolution can find a
            // replacement session or another owner; only fenced Main may do that.
            var direct = value.right().orElse(null);
            return direct instanceof Entity entity && dependency(entity, depth);
        }

        /** Owner/kill-credit chains are explicit native edges, not arbitrary fields. */
        private boolean dependency(Entity entity, int depth) {
            return dependency(entity, depth, false);
        }

        private boolean dependency(Entity entity, int depth, boolean tickSource) {
            if (entity == null) return true;
            for (int i = 0; i < depth && i < ownerPath.length; i++) {
                if (ownerPath[i] == entity) return true;
            }
            if (depth >= ownerPath.length
                    || !(tickSource ? indexedHere(entity) : observedHere(entity))) return false;
            // Reject unknown implementations before invoking any overridable
            // retained-reference getter. The exact supported player envelope is
            // still locally owned here; this is not a foreign observer grant.
            if (!queryImplementation(entity)) return false;
            // Safe only after the index/residency claim witness. A removed direct reference
            // would fall back to UUID resolution, so it must not be treated local.
            if (entity.isRemoved()) return false;
            // A retained owner, combat target, love cause or query/contact
            // recipient can receive native advancement callbacks even when the
            // ticking root is not a player. Select that recipient's operation
            // envelope before entering the root's eager native prefix.
            if (entity instanceof ServerPlayer player) {
                // Cold transfers can retain an index entry while the exact
                // player turn/position belongs elsewhere. The spatial claim must
                // remain valid even if claim-free Main closes that retained
                // handoff stamp between admission and the metadata read.
                if (!RegionScheduler.ownsExclusiveGameplay()
                        && (!RegionScheduler.ownsCurrentContext(player.level(), player.blockPosition())
                            || !AsyncIslandPlayerTicks.ownsAdvancementObservation(player))) return false;
                if (AdvancementCaptureOwnership.requiresGlobalEntityInteractionEnvelope(player)) return false;
            }
            ownerPath[depth] = entity;
            try {
                if (!dependency(entity.getVehicle(), depth + 1)) return false;
                if (!owner(entity, depth + 1)) return false;
                if (entity instanceof LivingEntity living) {
                    var access = (NativeEntityDependencyAccessors.Living) entity;
                    if (!reference(access.fabrolia$lastHurtByPlayer(), depth + 1)
                            || !reference(access.fabrolia$lastHurtByMob(), depth + 1)
                            || !dependency(access.fabrolia$lastHurtMob(), depth + 1)) return false;
                    // Villager tag/chase behavior observes the other villager's
                    // interaction target, not just the first nearby entity.
                    var memories = ((NativeEntityDependencyAccessors.BrainMemories) living.getBrain())
                            .fabrolia$memories();
                    var interaction = memories.get(MemoryModuleType.INTERACTION_TARGET);
                    if (interaction != null && interaction.value() instanceof Entity target
                            && !dependency(target, depth + 1)) return false;
                    var attack = memories.get(MemoryModuleType.ATTACK_TARGET);
                    if (attack != null && attack.value() instanceof Entity target
                            && !dependency(target, depth + 1)) return false;
                }
                if (entity instanceof NativeEntityDependencyAccessors.MobTarget target
                        && !dependency(target.fabrolia$rawTarget(), depth + 1)) return false;
                if (entity instanceof NativeEntityDependencyAccessors.BreedingPlayer access
                        && !reference(access.fabrolia$loveCause(), depth + 1)) return false;
                if (entity instanceof NativeEntityDependencyAccessors.Caravan access
                        && (!dependency(access.fabrolia$caravanHead(), depth + 1)
                        || !dependency(access.fabrolia$caravanTail(), depth + 1))) return false;
                if (entity instanceof NativeEntityDependencyAccessors.SchoolLeader access
                        && !dependency(access.fabrolia$schoolLeader(), depth + 1)) return false;
                return true;
            } finally {
                ownerPath[depth] = null;
            }
        }

        private boolean owner(Entity entity, int depth) {
            if (entity instanceof OwnableEntity ownable) {
                return reference(ownable.getOwnerReference(), depth);
            }
            if (entity instanceof Projectile) {
                return reference(((ProjectileOwnerAccessor) entity).fabrolia$getOwnerReference(), depth);
            }
            if (entity instanceof NativeEntityDependencyAccessors.TraceableOwner access) {
                return reference(access.fabrolia$traceableOwner(), depth);
            }
            if (entity instanceof NativeEntityDependencyAccessors.ItemThrower access) {
                return reference(access.fabrolia$itemThrower(), depth);
            }
            // No getter fallback: an unknown TraceableEntity getter may already
            // observe/mutate its target while trying to discover the dependency.
            return !(entity instanceof TraceableEntity);
        }

        private boolean source(DamageSource source) {
            return source == null || dependency(source.getEntity(), 0)
                    && dependency(source.getDirectEntity(), 0);
        }

        private boolean entities(Iterable<? extends Entity> entities) {
            if (entities == null) return true;
            for (Entity entity : entities) if (!dependency(entity, 0)) return false;
            return true;
        }

        private boolean tickTree(Entity entity, int depth) {
            if (depth >= 16 || !dependency(entity, 0, true)) return false;
            // Executed roots/passengers retain the strict ticking-index witness.
            // Players retain their separately audited player-body envelope.
            if (!(entity instanceof ServerPlayer) && !NativeEntityKnownModEnvelope.idle(entity)) return false;
            if (!(entity instanceof ServerPlayer)) {
                if (!(entity instanceof Mob mob) || admission == null || admission.entity != entity
                        || !NativeEntityMotion.bounds(entity, this, admission.motion)
                        || !NativePushContacts.source(mob)) return false;
                admission.motionCertified = true;
                // A synchronously published child would join post-travel
                // contacts after our census and could cross the cramming limit.
                if (mob instanceof net.minecraft.world.entity.animal.Animal animal && animal.isInLove()) return false;
                var breed = ((NativeEntityDependencyAccessors.BrainMemories) mob.getBrain())
                        .fabrolia$memories().get(MemoryModuleType.BREED_TARGET);
                if (breed != null && breed.value() != null) return false;
                if (NativeGroundMotion.hasMeleeProducer(entity)) {
                    // A retained victim can be attacked before travel, including
                    // an otherwise invulnerable player with mod damage hooks.
                    if (((NativeEntityDependencyAccessors.MobTarget) mob).fabrolia$rawTarget() != null
                            || ((NativeEntityDependencyAccessors.Living) mob).fabrolia$lastHurtByMob() != null) return false;
                    meleeSource = mob;
                }
                if (!motionContacts(mob)) return false;
            }
            // Classify the next venue even when preflight is performed by Main.
            // Active Carpet actions can begin use inside the combined E/L body.
            if (entity instanceof ServerPlayer player
                    && AdvancementCaptureOwnership.requiresGlobalPlayerTickEnvelope(player,
                            player.isUsingItem()
                                    || PlayerTickKnownModEnvelope.hasActiveCarpetActions(player))) return false;
            if (!queries(entity)) return false;
            if (!dependency(entity.getVehicle(), 0)) return false;
            for (Entity passenger : entity.getPassengers()) {
                if (!tickTree(passenger, depth + 1)) return false;
            }
            // These native producers retain dependencies outside the bounded
            // owner/combat/goal/memory schema below: dragon phases, wither target
            // ids, fox trusted synced refs, goat ramCandidate and warden anger /
            // vibration state and frog tongue entity ids. They remain supported on the fenced native lane;
            // they are not classified as unsupported mod callbacks.
            if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                    || entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss
                    || entity instanceof net.minecraft.world.entity.animal.fox.Fox
                    || entity instanceof net.minecraft.world.entity.animal.goat.Goat
                    || entity instanceof net.minecraft.world.entity.monster.warden.Warden
                    || entity instanceof net.minecraft.world.entity.animal.frog.Frog) return false;
            // Conversion completion resolves conversionStarter's UUID and awards
            // the live player. Ordinary, non-converting zombie villagers stay local.
            if (entity instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager zombie
                    && zombie.isConverting()) return false;
            if (entity instanceof LivingEntity living) {
                var access = (NativeEntityDependencyAccessors.Living) living;
                if (!source(access.fabrolia$lastDamageSource())
                        || access.fabrolia$recentKineticEnemies() != null
                        && !entities(access.fabrolia$recentKineticEnemies().keySet())) return false;
                for (var entry : ((NativeEntityDependencyAccessors.Combat) living.getCombatTracker())
                        .fabrolia$combatEntries()) {
                    if (!source(entry.source())) return false;
                }
                for (var entry : ((NativeEntityDependencyAccessors.BrainMemories) living.getBrain())
                        .fabrolia$memories().entrySet()) {
                    // This list is used only for UUID equality, never resolution.
                    if (entry.getKey() != MemoryModuleType.UNREACHABLE_TONGUE_TARGETS
                            && !memory(living, entry.getValue().value())) return false;
                }
            }
            if (entity instanceof Mob mob) {
                var access = (NativeEntityDependencyAccessors.MobTarget) mob;
                if (!queryDependency(mob, access.fabrolia$rawTarget(), true)) return false;
                if (!goals(mob, mob.getGoalSelector())
                        || !goals(mob, access.fabrolia$targetSelector())) return false;
            }
            if (entity instanceof NeutralMob neutral
                    && !reference(neutral.getPersistentAngerTarget(), 0)) return false;
            if (entity instanceof Leashable leashable) {
                var leash = leashable.getLeashData();
                // Unresolved saved/id holders need native resolution on Main.
                if (leash != null && (leash.leashHolder == null
                        || !dependency(leash.leashHolder, 0))) return false;
            }
            if (entity instanceof net.minecraft.world.entity.player.Player player
                    && !dependency(player.fishing, 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.ProjectileDeflector access
                    && !dependency(access.fabrolia$lastDeflectedBy(), 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.HookTarget access
                    && !dependency(access.fabrolia$hookedIn(), 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.FireworkTarget access
                    && !dependency(access.fabrolia$attachedToEntity(), 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.BulletTarget access
                    && !reference(access.fabrolia$finalTarget(), 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.ArrowVictims access
                    && !entities(access.fabrolia$piercedAndKilledEntities())) return false;
            if (entity instanceof NativeEntityDependencyAccessors.OrbPlayer access
                    && !dependency(access.fabrolia$followingPlayer(), 0)) return false;
            if (entity instanceof NativeEntityDependencyAccessors.LightningTargets access
                    && (!dependency(access.fabrolia$cause(), 0)
                    || !entities(access.fabrolia$hitEntities()))) return false;
            if (entity instanceof NativeEntityDependencyAccessors.WololoTarget access
                    && !dependency(access.fabrolia$wololoTarget(), 0)) return false;
            if (entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager
                    && !dependency(villager.getTradingPlayer(), 0)) return false;
            return !(entity instanceof NativeEntityDependencyAccessors.LastTradingPlayer access)
                    || dependency(access.fabrolia$lastTradedPlayer(), 0);
        }

        private boolean memory(LivingEntity observer, Object value) {
            if (value == null) return true;
            if (value instanceof Entity entity) return queryDependency(observer, entity, true);
            if (value instanceof EntityReference<?> reference) return reference(reference, 0);
            if (value instanceof DamageSource source) return source(source);
            if (value instanceof EntityTracker tracker) {
                return value.getClass() == EntityTracker.class && queryDependency(observer, tracker.getEntity(), true);
            }
            if (value instanceof WalkTarget target) {
                return value.getClass() == WalkTarget.class && Float.isFinite(target.getSpeedModifier())
                        && target.getSpeedModifier() >= 0 && memory(observer, target.getTarget());
            }
            if (value instanceof NearestVisibleLivingEntities visible) {
                if (!(observer instanceof net.minecraft.world.entity.animal.nautilus.Nautilus nautilus)) return false;
                var program = NativeNautilusProgram.match(nautilus);
                return program != null && program.visibleMemoryMatches(visible)
                        && memory(observer, visible.nearbyEntities());
            }
            if (value instanceof java.util.List<?> values) {
                Class<?> type = value.getClass();
                if (type != java.util.ArrayList.class && type != java.util.List.of().getClass()
                        && type != SMALL_IMMUTABLE_LIST
                        && type != java.util.Collections.emptyList().getClass()) return false;
                for (int i = 0; i < values.size(); i++) if (!memory(observer, values.get(i))) return false;
                return true;
            }
            if (value instanceof BlockPosTracker tracker) {
                return value.getClass() == BlockPosTracker.class
                        && positionQueries(tracker.currentBlockPosition(), Math.ceil(observer.getBbWidth() + 1.0) + 1);
            }
            if (value instanceof net.minecraft.world.level.pathfinder.Path path) {
                return observer instanceof Mob mob && pathQueries(mob, path);
            }
            // UUID-valued ANGRY_AT / LIKED_PLAYER memories resolve live entities.
            if (value instanceof UUID) return false;
            return value instanceof Integer || value instanceof Long || value instanceof Float
                    || value instanceof Double || value instanceof Short || value instanceof Byte
                    || value instanceof Boolean || value.getClass() == BlockPos.class || value instanceof GlobalPos
                    || value.getClass() == net.minecraft.world.phys.Vec3.class
                    || value instanceof net.minecraft.util.Unit
                    || value instanceof net.minecraft.world.entity.ai.behavior.SpearAttack.SpearStatus;
        }

        private boolean ownedRegion(Region target) {
            IslandRegistry.PIsland island = region.domain == null ? null : region.domain.owner;
            return target != null && island != null && !island.retired
                    && target.domain == region.domain && runtime.ownsRegion(target)
                    && (global || island.unitClaim.get()
                        && island.claimHolder == Thread.currentThread());
        }

        /**
         * Cell coverage is deliberately rectangular: a non-convex island that
         * owns both endpoints does not necessarily own the intervening LOS.
         */
        private boolean rectangle(double minX, double minZ, double maxX, double maxZ) {
            if (!(minX >= Integer.MIN_VALUE && minZ >= Integer.MIN_VALUE
                    && maxX < Integer.MAX_VALUE && maxZ < Integer.MAX_VALUE
                    && minX <= maxX && minZ <= maxZ)) return false;
            int shift = runtime.regionizer().grid.shift + 4;
            int x0 = ((int) Math.floor(minX)) >> shift;
            int z0 = ((int) Math.floor(minZ)) >> shift;
            int x1 = ((int) Math.floor(maxX)) >> shift;
            int z1 = ((int) Math.floor(maxZ)) >> shift;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    Region target = runtime.regionizer().peekByGridKey(
                            (x & 0xffffffffL) | ((z & 0xffffffffL) << 32));
                    if (!ownedRegion(target)) return false;
                }
            }
            return true;
        }

        private boolean queryDependency(Entity source, Entity candidate, boolean lineOfSight) {
            if (candidate == null) return true;
            if (!dependency(candidate, 0)
                    || !source.position().isFinite() || !candidate.position().isFinite()
                    || !finiteBox(source.getBoundingBox()) || !finiteBox(candidate.getBoundingBox())) return false;
            // With no retained victim, native combat acquisition cannot select
            // an invulnerable player. Other reachable melee victims retain the
            // complete native Main E until their damage callbacks are closed.
            if (source == meleeSource && candidate != source && candidate instanceof LivingEntity
                    && (!(candidate instanceof ServerPlayer player) || !player.getAbilities().invulnerable)) return false;
            if (!lineOfSight) return true;
            AABB a = source.getBoundingBox();
            AABB b = candidate.getBoundingBox();
            if (source instanceof net.minecraft.world.entity.animal.nautilus.Nautilus) {
                // Retained trackers/visibility predicates can outlive a range
                // change. Water navigation also clips between retained points.
                double margin = Math.ceil(source.getBbWidth() + 1.0) + 1;
                return readyNavigationColumns(Math.min(a.minX, b.minX) - margin, Math.min(a.minZ, b.minZ) - margin,
                        Math.max(a.maxX, b.maxX) + margin, Math.max(a.maxZ, b.maxZ) + margin);
            }
            if (source instanceof Mob mob) return readyLineOfSight(mob, candidate);
            return rectangle(Math.min(a.minX, b.minX) - 1, Math.min(a.minZ, b.minZ) - 1,
                    Math.max(a.maxX, b.maxX) + 1, Math.max(a.maxZ, b.maxZ) + 1);
        }
        private boolean finiteBox(AABB box) {
            return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                    && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                    && box.minX <= box.maxX && box.minY <= box.maxY && box.minZ <= box.maxZ;
        }


        private boolean playerQuery(Entity source, boolean lineOfSight) {
            // TargetingConditions executes rich selectors before its range test.
            // The native candidate domain is the full live player list, not a
            // radius, a snapshot bucket, or only the eventual selected target.
            for (ServerPlayer candidate : runtime.level.players()) {
                if (!queryDependency(source, candidate, lineOfSight)) return false;
            }
            return true;
        }

        private boolean declaredPlayerQuery(Mob source, Class<?> producer, TargetingConditions conditions,
                                            double range, boolean eyeOrigin) {
            if (!VANILLA_VISIBILITY) return false;
            // A wholly owned callback domain needs no bounded-query declaration.
            boolean localLineOfSight = producer != TemptGoal.class
                    || ((NativeGoalDependencyAccessors.Targeting) conditions).fabrolia$queryLineOfSight();
            if (playerQuery(source, localLineOfSight)) return true;
            if (admission == null) return false;
            if (source != admission.entity || source.level() != runtime.level
                    || source.isInterpolating() || source.portalProcess != null
                    || !(range > 0) || !Double.isFinite(range)
                    || conditions.getClass() != TargetingConditions.class) return false;
            var access = (NativeGoalDependencyAccessors.Targeting) conditions;
            if (!querySourceImplementation(source, access.fabrolia$queryCombat())) return false;
            if (!source.position().isFinite() || !finiteBox(source.getBoundingBox())
                    || !Double.isFinite(source.getEyeY())) return false;
            for (var slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
                var stack = source.getItemBySlot(slot);
                if (!NativePlayerMotion.safeItem(stack)) return false;
                // Prefix damage may break equipment before detectEquipmentUpdates.
                // In particular removing a negative modifier can EXPAND a range.
                for (var entry : stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers()) {
                    if (entry.attribute().is(Attributes.FOLLOW_RANGE)
                            || entry.attribute().is(Attributes.TEMPT_RANGE)
                            || entry.attribute().is(Attributes.SCALE)) return false;
                }
            }
            for (int i = 0; i < admission.size; i++) {
                if (admission.queries[i].conditions == conditions) return false;
            }
            if (!QUERY_BACKEND.get(runtime.level.getClass())) return false;
            // The target selector finishes ticking before ordinary goal queries.
            // Do not admit a nonnative arrangement that can attack/move a
            // prospective player winner or mutate geometry in that prefix.
            for (var wrapped : ((NativeEntityDependencyAccessors.MobTarget) source)
                    .fabrolia$targetSelector().getAvailableGoals()) {
                if (wrapped.getClass() != net.minecraft.world.entity.ai.goal.WrappedGoal.class) return false;
                Goal targetGoal = wrapped.getGoal();
                if (!(targetGoal instanceof net.minecraft.world.entity.ai.goal.target.TargetGoal)
                        && !localAngerReset(source, targetGoal)) return false;
            }
            for (ServerPlayer candidate : runtime.level.players()) {
                if (!NativePlayerObservations.isSupportedPlayer(candidate)
                        || !RICH_PLAYER.get(candidate.getClass())
                        // notRiding reaches the complete live riding graph
                        // before range; this metadata proof is not a filter.
                        || producer == LookAtPlayerGoal.class
                            && !NativePlayerObservations.supportsRidingClosure(candidate)) return false;
            }
            // The audited native pre-range callbacks are pure, and vanilla
            // visibility only decreases 1. Candidates outside max(range, 2)
            // cannot win or reach LOS. Inside it, retain every native condition
            // and the actual roster order, not an index-sorted candidate list.
            if (!playerSensorQuery(source, range, access.fabrolia$queryLineOfSight())) return false;
            if (access.fabrolia$queryLineOfSight()) {
                double bound = Math.max(range, 2);
                var origin = source.position();
                for (ServerPlayer candidate : runtime.level.players()) {
                    var position = NativePlayerObservations.position(candidate);
                    if (Math.abs(position.x - origin.x) <= bound
                            && Math.abs(position.z - origin.z) <= bound
                            && Math.abs(position.y - origin.y) <= bound
                            && !readyLineOfSight(source, candidate)) return false;
                }
            }
            admission.declare(source, producer, conditions, range, eyeOrigin);
            return true;
        }

        /**
         * A worker clip must not enqueue a cold native chunk load after E entry.
         * Own and require every FULL chunk in the complete endpoint rectangle;
         * certify the audited block-query geometry without invoking it.
         * Native liquid geometry may inspect the owned source and above fluid;
         * other callbacks keep the original full-E Main path.
         */
        private boolean readyLineOfSight(Mob source, Entity candidate) {
            AABB a = source.getBoundingBox();
            AABB b = candidate.getBoundingBox();
            double minX = Math.min(a.minX, b.minX) - 1;
            double minZ = Math.min(a.minZ, b.minZ) - 1;
            double maxX = Math.max(a.maxX, b.maxX) + 1;
            double maxZ = Math.max(a.maxZ, b.maxZ) + 1;
            double sourceEye = source.getEyeY(), targetEye = candidate.getEyeY();
            double minY = Math.min(Math.min(a.minY, b.minY), Math.min(sourceEye, targetEye)) - 1;
            double maxY = Math.max(Math.max(a.maxY, b.maxY), Math.max(sourceEye, targetEye)) + 1;
            if (!readyBlockRectangle(minX, minZ, maxX, maxZ)
                    || !(minY >= Integer.MIN_VALUE && maxY < Integer.MAX_VALUE)
                    || !Double.isFinite(sourceEye) || !Double.isFinite(targetEye)) return false;
            int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
            int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);
            int y0 = Math.max((int) Math.floor(minY), runtime.level.getMinY());
            int y1 = Math.min((int) Math.floor(maxY), runtime.level.getMaxY());
            if (y0 > y1) return true;
            boolean debug = runtime.level.isDebug();
            for (int chunkZ = z0 >> 4; chunkZ <= (z1 >> 4); chunkZ++) {
                for (int chunkX = x0 >> 4; chunkX <= (x1 >> 4); chunkX++) {
                    var chunk = runtime.level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null || chunk.getClass() != net.minecraft.world.level.chunk.LevelChunk.class) return false;
                    if (!debug && navigationColumns.contains(source, chunk)) continue;
                    int firstX = Math.max(x0, chunkX << 4), lastX = Math.min(x1, (chunkX << 4) + 15);
                    int firstZ = Math.max(z0, chunkZ << 4), lastZ = Math.min(z1, (chunkZ << 4) + 15);
                    for (int sectionY = y0 >> 4; sectionY <= (y1 >> 4); sectionY++) {
                        int baseY = sectionY << 4;
                        if (!debug) {
                            int index = chunk.getSectionIndex(baseY);
                            var sections = chunk.getSections();
                            if (index < 0 || index >= sections.length) return false;
                            var section = sections[index];
                            if (section == null || section.getClass() != net.minecraft.world.level.chunk.LevelChunkSection.class
                                    || section.getStates().getClass() != net.minecraft.world.level.chunk.PalettedContainer.class) return false;
                            // An inconclusive palette is not rejection: unused entries
                            // and GlobalPalette still take the original bounded voxel scan.
                            if (navigationColumns.acceptsCollisionSection(source, section)) continue;
                        }
                        int firstY = Math.max(y0, baseY), lastY = Math.min(y1, baseY + 15);
                        for (int z = firstZ; z <= lastZ; z++) {
                            for (int x = firstX; x <= lastX; x++) {
                                for (int y = firstY; y <= lastY; y++) {
                                    var state = chunk.getBlockState(queryBlock.set(x, y, z));
                                    if (!NativeBlockQueries.collision(source, state)) return false;
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }

        private boolean playerSensorQuery(Entity source, double radius, boolean lineOfSight) {
            // PlayerSensor applies its native distance filter before rich
            // conditions. Attributes can extend this volume beyond one cell.
            if (!(radius > 0) || !Double.isFinite(radius)) return false;
            PlayerChunkIndex index = PlayerChunkIndex.usable(runtime.level);
            if (index == null || !index.supportsLiveObservations()) return false;
            var origin = source.position();
            if (!origin.isFinite()) return false;
            double bound = Math.max(radius, 2);
            // These are the actual index's radius+16 chunk buckets, not a
            // cell-width heuristic. Owning the complete rectangle also keeps a
            // foreign producer from moving into the live sensor's query volume.
            double minX = Math.floor((origin.x - bound - 16) / 16) * 16;
            double minZ = Math.floor((origin.z - bound - 16) / 16) * 16;
            double maxX = Math.floor((origin.x + bound + 16) / 16) * 16 + 15;
            double maxZ = Math.floor((origin.z + bound + 16) / 16) * 16 + 15;
            if (!rectangle(minX, minZ, maxX, maxZ)) return false;
            for (ServerPlayer candidate : index.candidates(origin.x, origin.z, bound)) {
                if (!queryDependency(source, candidate, lineOfSight)) return false;
            }
            // PlayerSensor consumes the full live list, not the bucket subset.
            // Check that exact structural generation and its current positions,
            // without evaluating spectator/AI callbacks to discover admission.
            // X/Z alone deliberately over-approximates the native 3D distance.
            int ordinal = 0;
            var indexedPlayers = index.allPlayers();
            for (ServerPlayer candidate : runtime.level.players()) {
                if (ordinal >= indexedPlayers.size() || indexedPlayers.get(ordinal++) != candidate
                        || !NativePlayerObservations.isSupportedPlayer(candidate)) return false;
                var position = NativePlayerObservations.position(candidate);
                if (!position.isFinite()) return false;
                if (Math.abs(position.x - origin.x) <= bound
                        && Math.abs(position.z - origin.z) <= bound
                        && !queryDependency(source, candidate, lineOfSight)) return false;
            }
            return ordinal == indexedPlayers.size();
        }

        private boolean entityQuery(Entity source, Class<?> type, double radius) {
            if (!(radius >= 0) || !Double.isFinite(radius) || !nativeClass(type)) return false;
            return entityQuery(source, type, source.getBoundingBox().inflate(radius), false);
        }

        private boolean playerQueries(ServerPlayer player) {
            // The native Player.aiStep touch scan follows travel and pushEntities.
            // Reuse the actual stationary-body certificate, not a guessed speed
            // margin: some admission callers have not run that certificate yet.
            if (!NativePlayerMotion.certifies(runtime, region.domain.owner, player)
                    || player.getCamera() != player) return false;
            AABB box = player.getBoundingBox();
            AABB collision = box.expandTowards(player.getDeltaMovement()).inflate(1.0E-7);
            AABB touch = box.inflate(1, 0.5, 1);
            return entityQuery(player, Entity.class, collision.minmax(touch), true);
        }

        private boolean entityQuery(Entity source, Class<?> type, AABB box, boolean contacts) {
            if (!finiteBox(box)) return false;
            // Own the native section traversal, including its horizontal two-block
            // expansion. Region.entities alone is not a candidate census: tracked
            // non-ticking entities can still occur in the native query index.
            if (!rectangle(box.minX - 2, box.minZ - 2, box.maxX + 2, box.maxZ + 2)) return false;
            var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess)
                    runtime.entityManagerHandle()).fabrolia$sectionStorage();
            querySource = source;
            queryType = type;
            queryBox = box;
            queryOwned = true;
            playerContacts = contacts;
            try {
                storage.forEachAccessibleNonEmptySection(box, this);
                return queryOwned;
            } finally {
                querySource = null;
                queryType = null;
                queryBox = null;
                playerContacts = false;
            }
        }

        @Override
        public net.minecraft.util.AbortableIterationConsumer.Continuation accept(
                net.minecraft.world.level.entity.EntitySection<net.minecraft.world.level.entity.EntityAccess> section) {
            var candidates = section.getEntities().iterator();
            while (candidates.hasNext()) {
                var candidate = candidates.next();
                // Ownership precedes live candidate geometry. Only the stationary
                // player-contact path may prune an owned candidate; moving-entity
                // queries retain their conservative full-section census.
                if (queryType.isInstance(candidate)
                        && (!(candidate instanceof Entity entity)
                            || (playerContacts ? !playerContact(entity)
                                : motionContacts ? !motionContact(entity)
                                : !queryDependency(querySource, entity, true)))) {
                    queryOwned = false;
                    return net.minecraft.util.AbortableIterationConsumer.Continuation.ABORT;
                }
            }
            return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
        }
        private boolean motionContacts(Mob source) {
            var bounds = admission.motion;
            motionContacts = true;
            motionContactCount = 0;
            crammingLimit = runtime.level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING);
            try {
                return entityQuery(source, Entity.class, new AABB(
                        bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ), false);
            } finally {
                motionContacts = false;
            }
        }

        private boolean motionContact(Entity candidate) {
            if (candidate == querySource) return true;
            if (!observedHere(candidate)) return false;
            AABB box = candidate.getBoundingBox();
            if (box.getClass() != AABB.class) return false;
            if (!box.intersects(queryBox)) return true;
            if (!dependency(candidate, 0) || !NativePushContacts.recipient(candidate)) return false;
            if (candidate instanceof LivingEntity
                    && !readyBlockRectangle(box.minX - 1, box.minZ - 1, box.maxX + 1, box.maxZ + 1)) return false;
            // Count the superset without calling pushability, team predicates or
            // RNG. This excludes cramming's source-damage branch before entry.
            return crammingLimit <= 0 || ++motionContactCount < crammingLimit;
        }

        private boolean playerContact(Entity candidate) {
            // Native getEntities excludes its source by identity, not by a
            // callback. In particular an audited Carpet source need not pretend
            // to be a vanilla rich-query target merely to exclude itself.
            if (candidate == querySource) return true;
            if (!observedHere(candidate)) return false;
            // Entity.getBoundingBox is final and callback-free. Under the
            // stationary player envelope, this claimed recipient cannot move
            // into the native contact box before the original touch scan.
            if (!candidate.getBoundingBox().intersects(queryBox)) return true;
            if (!PLAYER_CONTACT.get(candidate.getClass())
                    || !queryDependency(querySource, candidate, true)) return false;
            // Pickup can fill the selected hand before FoodData.tick. In
            // particular death-protection consume effects could then teleport.
            return !(candidate instanceof net.minecraft.world.entity.item.ItemEntity item)
                    || NativePlayerMotion.safeItem(item.getItem());
        }


        private boolean conditions(TargetingConditions conditions, Class<?> selectorHost) {
            if (conditions.getClass() != TargetingConditions.class) return false;
            var selector = ((NativeGoalDependencyAccessors.Targeting) conditions).fabrolia$querySelector();
            return selectorHost == null ? selector == null : callbackFrom(selector, selectorHost);
        }

        private boolean stableEquipment(Mob source) {
            var previous = ((NativeEntityDependencyAccessors.Living) source).fabrolia$lastEquipmentItems();
            for (var slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
                var stack = source.getItemBySlot(slot);
                // detectEquipmentUpdates runs before AI: changed modifiers or
                // location enchantment callbacks invalidate a tick-entry radius.
                if (!net.minecraft.world.item.ItemStack.matches(stack, previous.get(slot))
                        || !stack.isEmpty() && (!nativeClass(stack.getItem().getClass())
                            || stack.isEnchanted()
                            // Pre-AI lethal damage can consume teleport effects.
                            || stack.has(net.minecraft.core.component.DataComponents.DEATH_PROTECTION))) return false;
            }
            return true;
        }

        private boolean localAngerReset(Mob source, Goal goal) {
            if (source.getClass() != net.minecraft.world.entity.animal.polarbear.PolarBear.class
                    || goal.getClass() != net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal.class) return false;
            var access = (NativeGoalDependencyAccessors.ResetAngerSource) goal;
            return access.fabrolia$angerMob() == source && !access.fabrolia$alertOthersOfSameType();
        }

        private boolean squidSource(Mob source) {
            return source.getClass() == net.minecraft.world.entity.animal.squid.Squid.class
                    || source.getClass() == net.minecraft.world.entity.animal.squid.GlowSquid.class;
        }

        private boolean hurtByAlertQuery(Mob source) {
            double range = source.getAttributeValue(Attributes.FOLLOW_RANGE);
            return range >= 0 && Double.isFinite(range)
                    && entityQuery(source, source.getClass(),
                            AABB.unitCubeFromLowerCorner(source.position()).inflate(range, 10, range), false);
        }

        private boolean readyBlockRectangle(double minX, double minZ, double maxX, double maxZ) {
            if (!rectangle(minX, minZ, maxX, maxZ)) return false;
            int x0 = ((int) Math.floor(minX)) >> 4, x1 = ((int) Math.floor(maxX)) >> 4;
            int z0 = ((int) Math.floor(minZ)) >> 4, z1 = ((int) Math.floor(maxZ)) >> 4;
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    if (runtime.islandOwnerOfChunk(net.minecraft.world.level.ChunkPos.pack(x, z)) != region.domain.owner
                            || runtime.level.getChunkSource().getChunkNow(x, z) == null) return false;
                }
            }
            return true;
        }

        private boolean localBlockRectangle(Mob source, int radius) {
            if (source.isInterpolating() || source.portalProcess != null || !source.position().isFinite()) return false;
            BlockPos pos = source.blockPosition();
            return readyBlockRectangle((double) pos.getX() - radius, (double) pos.getZ() - radius,
                    (double) pos.getX() + radius, (double) pos.getZ() + radius);
        }

        private boolean blockGoalQuery(Mob source, NativeGoalDependencyAccessors.BlockTarget goal,
                                       int radius, int verticalStart) {
            if (goal.fabrolia$blockSearchRange() != radius || goal.fabrolia$verticalSearchRange() != 1
                    || goal.fabrolia$verticalSearchStart() != verticalStart
                    || !localBlockRectangle(source, radius - 1)) return false;
            if (!runningGoal(source, (Goal) goal)) return true;
            // Continuation/tick keep the old block target even when it is well
            // outside today's search volume. Do not replace it with that volume.
            BlockPos target = goal.fabrolia$goalBlockPos();
            return target != null && target.getY() < Integer.MAX_VALUE
                    && readyBlockRectangle(target.getX(), target.getZ(), target.getX(), target.getZ());
        }

        private boolean gardenQuery(Mob source, NativeGoalDependencyAccessors.BlockTarget goal) {
            if (!blockGoalQuery(source, goal, 16, 0)) return false;
            BlockPos origin = source.blockPosition();
            int minY = Math.max(origin.getY() - 1, runtime.level.getMinY());
            int maxY = Math.min(origin.getY() + 1, runtime.level.getMaxY());
            // Supporting blocks are y = -2,-1,0; carrot tests read above them.
            // With no carrot in this complete domain or the retained target,
            // neither canUse nor tick can mutate a crop.
            for (int z = origin.getZ() - 15; z <= origin.getZ() + 15; z++) {
                for (int x = origin.getX() - 15; x <= origin.getX() + 15; x++) {
                    var chunk = runtime.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    for (int y = minY; y <= maxY; y++) {
                        if (chunk.getBlockState(queryBlock.set(x, y, z)).getBlock()
                                instanceof net.minecraft.world.level.block.CarrotBlock) return false;
                    }
                }
            }
            if (!runningGoal(source, (Goal) goal)) return true;
            BlockPos target = goal.fabrolia$goalBlockPos();
            var chunk = runtime.level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
            // Real raids emit game events and neighbor callbacks. An owned
            // block is not their recipient closure; optimistic cross-region
            // setBlock deferral is not a synchronous operation certificate.
            return !(chunk.getBlockState(queryBlock.set(target.getX(), target.getY() + 1, target.getZ()))
                    .getBlock() instanceof net.minecraft.world.level.block.CarrotBlock);
        }

        private boolean runningGoal(Mob source, Goal goal) {
            for (var wrapped : source.getGoalSelector().getAvailableGoals()) {
                // Unknown wrappers are not evidence that a producer is dormant;
                // goals() rejects them before any native body can be admitted.
                if (wrapped.getClass() != net.minecraft.world.entity.ai.goal.WrappedGoal.class) return true;
                if (wrapped.getGoal() == goal && wrapped.isRunning()) return true;
            }
            for (var wrapped : ((NativeEntityDependencyAccessors.MobTarget) source)
                    .fabrolia$targetSelector().getAvailableGoals()) {
                if (wrapped.getClass() != net.minecraft.world.entity.ai.goal.WrappedGoal.class) return true;
                if (wrapped.getGoal() == goal && wrapped.isRunning()) return true;
            }
            return false;
        }

        private boolean inactiveEggGoal(net.minecraft.world.entity.animal.turtle.Turtle source, Goal goal) {
            if (source.hasEgg() || runningGoal(source, goal)
                    || !((NativeEntityDependencyAccessors.MobTarget) source)
                        .fabrolia$targetSelector().getAvailableGoals().isEmpty()) return false;
            // canUse short-circuits before discovery. Breeding assigns the egg
            // in the later goal-tick pass, after every canUse call. It cannot
            // start placement in this ordinal. Active laying still needs the
            // block/neighbor/game-event recipient closure.
            return true;
        }

        private boolean schoolingPostTravelQuery(Mob source) {
            if (admission == null || admission.entity != source || !admission.motionCertified
                    || !schoolingSource(source)) return false;
            var bounds = admission.motion;
            return entityQuery(source, source.getClass(), new AABB(
                    bounds.minX - 8, bounds.minY - 8, bounds.minZ - 8,
                    bounds.maxX + 8, bounds.maxY + 8, bounds.maxZ + 8), false);
        }

        private boolean schoolingSource(Mob source) {
            Class<?> type = source.getClass();
            return type == net.minecraft.world.entity.animal.fish.Cod.class
                    || type == net.minecraft.world.entity.animal.fish.Salmon.class
                    || type == net.minecraft.world.entity.animal.fish.TropicalFish.class;
        }

        private boolean powderSnowQuery(Mob source) {
            if (!source.wasInPowderSnow && !source.isInPowderSnow) return true;
            if (!localBlockRectangle(source, 0)) return false;
            BlockPos pos = source.blockPosition();
            if (pos.getY() == Integer.MAX_VALUE) return false;
            var chunk = runtime.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            var above = chunk.getBlockState(queryBlock.set(pos.getX(), pos.getY() + 1, pos.getZ()));
            return above.getBlock() == net.minecraft.world.level.block.Blocks.POWDER_SNOW
                    || CONSTANT_QUERY_GEOMETRY.get(above.getBlock().getClass());
        }

        private boolean squidFleeQuery(Mob source) {
            var reference = ((NativeEntityDependencyAccessors.Living) source).fabrolia$lastHurtByMob();
            if (reference == null) return true;
            var direct = ((NativeEntityDependencyAccessors.Reference) (Object) reference)
                    .fabrolia$dependencyValue().right().orElse(null);
            if (!(direct instanceof LivingEntity attacker) || !localBlockRectangle(source, 0)) return false;
            // dependency() already owns this retained attacker. A running flee
            // goal does not repeat canUse's distance bound on every AI ordinal.
            // Keep native subtract-then-add rounding: reassociation can select
            // a different block or chunk at an exact negative boundary.
            double x = source.getX() + (source.getX() - attacker.getX());
            double y = source.getY() + (source.getY() - attacker.getY());
            double z = source.getZ() + (source.getZ() - attacker.getZ());
            return Double.isFinite(y) && readyBlockRectangle(x, z, x, z);
        }

        private boolean batQuery(net.minecraft.world.entity.ambient.Bat source) {
            if (source.getClass() != net.minecraft.world.entity.ambient.Bat.class
                    || !source.getGoalSelector().getAvailableGoals().isEmpty()
                    || !((NativeEntityDependencyAccessors.MobTarget) source).fabrolia$targetSelector().getAvailableGoals().isEmpty()
                    || source.getNavigation().getClass() != net.minecraft.world.entity.ai.navigation.GroundPathNavigation.class
                    || !localBlockRectangle(source, 0)) return false;
            var navigation = (NativeEntityDependencyAccessors.Navigation) source.getNavigation();
            if (navigation.fabrolia$navigationMob() != source || navigation.fabrolia$navigationLevel() != runtime.level
                    || navigation.fabrolia$navigationPath() != null || navigation.fabrolia$hasDelayedRecomputation()) return false;
            BlockPos retainedTarget = ((NativeEntityDependencyAccessors.BatQuery) source).fabrolia$flightTarget();
            if (retainedTarget != null && !readyBlockRectangle(retainedTarget.getX(), retainedTarget.getZ(),
                    retainedTarget.getX(), retainedTarget.getZ())) return false;
            BlockPos pos = source.blockPosition();
            if (pos.getY() == Integer.MAX_VALUE) return false;
            var chunk = runtime.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            var above = chunk.getBlockState(queryBlock.set(pos.getX(), pos.getY() + 1, pos.getZ()));
            Object predicate = ((NativeEntityDependencyAccessors.Conductor) (Object) above).fabrolia$conductorPredicate();
            Object stonePredicate = ((NativeEntityDependencyAccessors.Conductor) (Object)
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()).fabrolia$conductorPredicate();
            // Bat passes its own position with the above state. The retained
            // native default predicate calls collision-full-block, so prove
            // its actual callback and constant geometry without evaluating it.
            if (predicate != stonePredicate
                    || !callbackFrom(predicate, net.minecraft.world.level.block.state.BlockBehaviour.class)
                    || !captures(predicate, null, null)
                    || !BAT_CONDUCTOR_GEOMETRY.get(above.getBlock().getClass())) return false;
            var targeting = NativeEntityDependencyAccessors.BatQuery.fabrolia$restingTargeting();
            var access = (NativeGoalDependencyAccessors.Targeting) targeting;
            return conditions(targeting, null) && !access.fabrolia$queryCombat() && access.fabrolia$queryRange() == 4
                    && declaredPlayerQuery(source, net.minecraft.world.entity.ambient.Bat.class, targeting, 4, false);
        }

        private boolean positionQueries(BlockPos position, double margin) {
            if (position == null) return true;
            if (position.getClass() != BlockPos.class || admission == null) return false;
            Entity source = admission.entity;
            return readyNavigationColumns(Math.min(source.getX(), position.getX()) - margin,
                    Math.min(source.getZ(), position.getZ()) - margin,
                    Math.max(source.getX(), position.getX()) + margin,
                    Math.max(source.getZ(), position.getZ()) + margin);
        }

        private boolean pathQueries(Mob source, net.minecraft.world.level.pathfinder.Path path) {
            if (path == null) return true;
            if (path.getClass() != net.minecraft.world.level.pathfinder.Path.class) return false;
            var nodes = ((com.axalotl.fabrolia.common.mixin.entity.NativeAquaticMotionAccessors.Nodes) (Object) path)
                    .fabrolia$aquaticNodes();
            if (nodes == null || nodes.getClass() != java.util.ArrayList.class) return false;
            double margin = Math.ceil(source.getBbWidth() + 1.0) + 1;
            BlockPos target = path.getTarget();
            if (target == null || target.getClass() != BlockPos.class) return false;
            double minX = Math.min(source.getX(), target.getX()), minZ = Math.min(source.getZ(), target.getZ());
            double maxX = Math.max(source.getX(), target.getX()), maxZ = Math.max(source.getZ(), target.getZ());
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i);
                if (node == null || node.getClass() != net.minecraft.world.level.pathfinder.Node.class) return false;
                minX = Math.min(minX, node.x);
                minZ = Math.min(minZ, node.z);
                maxX = Math.max(maxX, node.x);
                maxZ = Math.max(maxZ, node.z);
            }
            return readyNavigationColumns(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        private boolean aquaticNavigationQueries(Mob source, boolean schooling) {
            var navigation = source.getNavigation();
            var raw = (com.axalotl.fabrolia.common.mixin.entity.NativeGroundMotionAccessors.Navigation) navigation;
            float range = (float) source.getAttributeValue(Attributes.FOLLOW_RANGE);
            float length = Math.max(range, raw.fabrolia$groundRequiredPathLength());
            if (!(range >= 0) || !Float.isFinite(range) || !(length >= 0) || !Float.isFinite(length)) return false;
            // Entity-target schooling navigation uses offset16; the nautical
            // Brain and retained-path recomputation use block-target offset8.
            double margin = Math.ceil(source.getBbWidth() + 1.0) + 1;
            double pathRadius = (int) (length + (schooling ? 16 : 8));
            // DefaultRandomPos(h=10) includes native home bias and the
            // path-malus neighbour halo. Panic/swim tiers fit this rectangle.
            double radius = Math.max(pathRadius + margin, Math.ceil(1.5 * 10) + 1 + margin);
            var origin = source.blockPosition();
            return readyNavigationColumns(origin.getX() - radius, origin.getZ() - radius,
                            origin.getX() + radius, origin.getZ() + radius)
                    && pathQueries(source, navigation.getPath())
                    && positionQueries(raw.fabrolia$groundNavigationTarget(), margin);
        }

        private boolean nautilusQueries(net.minecraft.world.entity.animal.nautilus.Nautilus source,
                NativeNautilusProgram.Snapshot program) {
            if (program == null || !program.isCurrent() || !program.idleAndChargeStopped()
                    || com.axalotl.fabrolia.common.utils.SensorCandidateBridge.hasProvider()
                    || source.getSensing().getClass() != net.minecraft.world.entity.ai.sensing.Sensing.class
                    || ((com.axalotl.fabrolia.common.mixin.entity.NativeGroundMotionAccessors.Sense) source.getSensing())
                            .fabrolia$groundSensingMob() != source
                    || source.getGoalSelector().getClass() != GoalSelector.class
                    || !source.getGoalSelector().getAvailableGoals().isEmpty()) return false;
            var targets = ((NativeEntityDependencyAccessors.MobTarget) source).fabrolia$targetSelector();
            if (targets.getClass() != GoalSelector.class || !targets.getAvailableGoals().isEmpty()
                    || !sensorConditions(source) || !aquaticNavigationQueries(source, false)) return false;
            var movement = (com.axalotl.fabrolia.common.mixin.entity.NativeNautilusProgramAccessors.Movement) program.movement();
            return pathQueries(source, movement.fabrolia$nautilusPath())
                    && positionQueries(movement.fabrolia$nautilusLastTargetPos(),
                            Math.ceil(source.getBbWidth() + 1.0) + 1);
        }

        private boolean sensorConditions(Mob source) {
            var storage = (NativeSensorTargetingAccess) source;
            for (int slot = 0; slot < 6; slot++) {
                TargetingConditions prototype = switch (slot) {
                    case 0 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$targetConditions();
                    case 1 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$visibleTargetConditions();
                    case 2 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$attackConditions();
                    case 3 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$visibleAttackConditions();
                    case 4 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$unseenAttackConditions();
                    case 5 -> NativeEntityDependencyAccessors.SensorQueries.fabrolia$visibleUnseenAttackConditions();
                    default -> throw new AssertionError();
                };
                var condition = storage.fabrolia$existingSensorCondition(slot, prototype);
                if (condition == null || condition.getClass() != TargetingConditions.class) return false;
                var access = (NativeGoalDependencyAccessors.Targeting) condition;
                if (access.fabrolia$querySelector() != null || !Double.isFinite(access.fabrolia$queryRange())) return false;
            }
            return querySourceImplementation(source, true);
        }

        private boolean temptingQuery(net.minecraft.world.entity.animal.nautilus.Nautilus source,
                NativeNautilusProgram.Snapshot program, TemptingSensor sensor) {
            if (!VANILLA_VISIBILITY || admission == null || source != admission.entity
                    || program.tempting() != sensor || !program.isCurrent()
                    || com.axalotl.fabrolia.common.utils.SensorCandidateBridge.hasProvider()) return false;
            double range = (double) (float) source.getAttributeValue(Attributes.TEMPT_RANGE);
            if (!(range > 0) || !Double.isFinite(range) || !source.position().isFinite()
                    || !finiteBox(source.getBoundingBox())) return false;
            var template = NativeEntityDependencyAccessors.TemptingQuery.fabrolia$temptingTargeting();
            if (template == null || template.getClass() != TargetingConditions.class) return false;
            var targeting = (NativeGoalDependencyAccessors.Targeting) template;
            if (targeting.fabrolia$querySelector() != null || targeting.fabrolia$queryCombat()
                    || targeting.fabrolia$queryLineOfSight() || !targeting.fabrolia$queryTestInvisible()) return false;
            for (ServerPlayer candidate : runtime.level.players()) {
                if (!NativePlayerObservations.isSupportedPlayer(candidate) || !RICH_PLAYER.get(candidate.getClass())) return false;
            }
            if (!playerSensorQuery(source, range, false)) return false;
            admission.declareTempting(source, sensor, program, template, range);
            return true;
        }

        private boolean queries(Entity entity) {
            // These producers discover damage/effect recipients after movement
            // or an explosion. Retained owner references are not their victim
            // census; preserve the whole-E fallback until that closure is proven.
            if (AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope()
                    && (entity instanceof Projectile
                        || entity instanceof net.minecraft.world.entity.AreaEffectCloud
                        || entity instanceof net.minecraft.world.entity.LightningBolt
                        || entity instanceof net.minecraft.world.entity.monster.Creeper
                        || entity instanceof net.minecraft.world.entity.item.PrimedTnt
                        || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity
                        || entity instanceof net.minecraft.world.entity.projectile.EvokerFangs
                        || entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartTNT)) return false;
            // These bodies query after moving/teleporting or use an all-player
            // pre-distance callback domain not intercepted by the bounded backend.
            if (entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartSpawner
                    || entity instanceof net.minecraft.world.entity.animal.happyghast.HappyGhast
                    || entity instanceof net.minecraft.world.entity.monster.EnderMan
                    || entity instanceof net.minecraft.world.entity.monster.Shulker) return false;
            if (entity instanceof ServerPlayer player) return playerQueries(player);
            if (entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                return !entity.isPassenger() && playerQuery(entity, false);
            }
            if (!(entity instanceof Mob mob)) return true;
            // Native item pickup can award the item's thrower after travel.
            // The post-travel item census is not covered by retained mob targets.
            // Dolphin/Allay have additional pickup AI outside the base flag.
            if (AdvancementCaptureOwnership.mayRequireGlobalEntityInteractionEnvelope()
                    && (mob.canPickUpLoot()
                        || mob instanceof net.minecraft.world.entity.animal.dolphin.Dolphin
                        || mob instanceof net.minecraft.world.entity.animal.allay.Allay)) return false;
            // Passenger positioning and effect callbacks precede AI and can move
            // the origin/change attributes. They need their original Main body.
            if (mob.isPassenger() || mob.isSleeping() || mob.isUsingItem()
                    || !mob.getActiveEffects().isEmpty() || !stableEquipment(mob)) return false;
            // Bred foals run followMommy after travel with a fresh 16-block
            // entity query. It is not the pre-travel FollowParentGoal census.
            if (mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse
                    && horse.isBred() && horse.isBaby()) return false;
            if (mob instanceof net.minecraft.world.entity.animal.fish.AbstractSchoolingFish
                    && !schoolingPostTravelQuery(mob)) return false;
            // checkDespawn's unbounded NO_SPECTATORS scan is the one audited live
            // observer exception, not permission for TargetingConditions/AI.
            for (ServerPlayer candidate : runtime.level.players()) {
                if (!NativePlayerObservations.isSupportedPlayer(candidate)) return false;
            }
            NativeNautilusProgram.Snapshot nautical = null;
            if (mob instanceof net.minecraft.world.entity.animal.nautilus.Nautilus nautilus) {
                nautical = NativeNautilusProgram.match(nautilus);
                if (!nautilusQueries(nautilus, nautical)) return false;
            } else if ((mob instanceof net.minecraft.world.entity.animal.fish.AbstractSchoolingFish
                    || mob instanceof net.minecraft.world.entity.animal.squid.Squid)
                    && !aquaticNavigationQueries(mob,
                            mob instanceof net.minecraft.world.entity.animal.fish.AbstractSchoolingFish)) return false;
            for (var sensor : ((NativeEntityDependencyAccessors.BrainMemories) mob.getBrain())
                    .fabrolia$sensors().values()) {
                if (!nativeClass(sensor.getClass())) return false;
                if (sensor instanceof TemptingSensor tempting) {
                    if (!(mob instanceof net.minecraft.world.entity.animal.nautilus.Nautilus nautilus)
                            || nautical == null || !temptingQuery(nautilus, nautical, tempting)) return false;
                } else if (sensor instanceof PlayerSensor) {
                    if (sensor.getClass() != PlayerSensor.class
                            || !playerSensorQuery(mob, mob.getAttributeValue(Attributes.FOLLOW_RANGE), true)) return false;
                } else if (sensor instanceof net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor) {
                    if (sensor.getClass() != net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor.class
                            || !entityQuery(mob, LivingEntity.class,
                                    mob.getAttributeValue(Attributes.FOLLOW_RANGE))) return false;
                }
            }
            if (entity instanceof net.minecraft.world.entity.ambient.Bat bat && !batQuery(bat)) return false;
            return true;
        }

        private boolean goalQuery(Mob source, Goal goal) {
            Class<?> type = goal.getClass();
            // Even an exact native goal can be constructed for another mob and
            // installed in this selector. Constructor source identity is part of
            // the certificate, not inferred from selector membership.
            if (goal instanceof NativeGoalDependencyAccessors.MobSource access
                    && access.fabrolia$goalMob() != source
                    || goal instanceof NativeGoalDependencyAccessors.PathfinderSource pathfinder
                    && pathfinder.fabrolia$goalPathfinder() != source
                    || goal instanceof NativeGoalDependencyAccessors.MonsterSource monster
                    && monster.fabrolia$goalMonster() != source
                    || goal instanceof NativeGoalDependencyAccessors.CreeperSource creeper
                    && creeper.fabrolia$goalCreeper() != source
                    || goal instanceof NativeGoalDependencyAccessors.DolphinSource dolphin
                    && dolphin.fabrolia$goalDolphin() != source
                    || goal instanceof NativeGoalDependencyAccessors.TargetMob target
                    && target.fabrolia$targetingMob() != source
                    || goal instanceof NativeGoalDependencyAccessors.HorseSource horse
                    && horse.fabrolia$goalHorse() != source
                    || goal instanceof NativeGoalDependencyAccessors.MountPanicSource mount
                    && mount.fabrolia$panickingHorse() != source
                    || goal instanceof NativeGoalDependencyAccessors.RabbitSource rabbit
                    && (rabbit.fabrolia$goalRabbit() != source
                        || source.getClass() != net.minecraft.world.entity.animal.rabbit.Rabbit.class)
                    || goal instanceof NativeGoalDependencyAccessors.PolarBearSource bear
                    && (bear.fabrolia$goalPolarBear() != source
                        || source.getClass() != net.minecraft.world.entity.animal.polarbear.PolarBear.class)
                    || goal instanceof NativeGoalDependencyAccessors.FishSource fish
                    && (fish.fabrolia$swimmingFish() != source || !schoolingSource(source))
                    || goal instanceof NativeGoalDependencyAccessors.FlockSource flock
                    && (flock.fabrolia$flockingFish() != source || !schoolingSource(source))
                    || goal instanceof NativeGoalDependencyAccessors.TurtleSource turtle
                    && (turtle.fabrolia$goalTurtle() != source
                        || source.getClass() != net.minecraft.world.entity.animal.turtle.Turtle.class)
                    || goal instanceof NativeGoalDependencyAccessors.SquidSource squid
                    && (squid.fabrolia$goalSquid() != source || !squidSource(source))
                    || goal instanceof NativeGoalDependencyAccessors.SquidFleeSource fleeingSquid
                    && (fleeingSquid.fabrolia$fleeingSquid() != source || !squidSource(source))) return false;
            if (goal instanceof PanicGoal) {
                if (type != PanicGoal.class
                        && !type.getName().equals("net.minecraft.world.entity.animal.equine.AbstractHorse$MountPanicGoal")
                        && !type.getName().equals("net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitPanicGoal")
                        && !(type.getName().equals("net.minecraft.world.entity.animal.turtle.Turtle$TurtlePanicGoal")
                            && source.getClass() == net.minecraft.world.entity.animal.turtle.Turtle.class)) return false;
                var callback = ((NativeGoalDependencyAccessors.PanicQuery) goal).fabrolia$panicCausingDamageTypes();
                // The native TagKey constructor's sole Function lambda returns
                // its immutable captured damage tag; never call it to classify.
                return callbackFrom(callback, PanicGoal.class)
                            && singleCapture(callback) instanceof TagKey<?> tag
                            && tag.isFor(Registries.DAMAGE_TYPE)
                        || type == PanicGoal.class
                            && source.getClass() == net.minecraft.world.entity.animal.polarbear.PolarBear.class
                            && callbackFrom(callback, net.minecraft.world.entity.animal.polarbear.PolarBear.class)
                            && captures(callback, null, null);
            }
            if (goal instanceof BegGoal) {
                if (type != BegGoal.class) return false;
                var access = (NativeGoalDependencyAccessors.BegQuery) goal;
                var targeting = access.fabrolia$begTargeting();
                return access.fabrolia$beggingWolf() == source
                        && access.fabrolia$begLevel() == runtime.level
                        && conditions(targeting, null)
                        && declaredPlayerQuery(source, BegGoal.class, targeting,
                                ((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryRange(), false);
            }
            if (goal instanceof LookAtPlayerGoal) {
                if (type == net.minecraft.world.entity.ai.goal.LookAtTradingPlayerGoal.class) return true;
                if (type != LookAtPlayerGoal.class
                        && type != net.minecraft.world.entity.ai.goal.InteractGoal.class
                        && !type.getName().equals("net.minecraft.world.entity.animal.panda.Panda$PandaLookAtPlayerGoal")) return false;
                var access = (NativeGoalDependencyAccessors.LookAt) goal;
                if (access.fabrolia$lookingMob() != source) return false;
                var targeting = access.fabrolia$lookAtContext();
                if (access.fabrolia$lookAtType() == net.minecraft.world.entity.player.Player.class) {
                    var selector = ((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$querySelector();
                    Object notRiding = singleCapture(selector);
                    return conditions(targeting, LookAtPlayerGoal.class)
                            && callbackFrom(notRiding, net.minecraft.world.entity.EntitySelector.class)
                            && captures(notRiding, source, null)
                            && (type == LookAtPlayerGoal.class
                                || type == net.minecraft.world.entity.ai.goal.InteractGoal.class
                                ? declaredPlayerQuery(source, LookAtPlayerGoal.class, targeting,
                                    ((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryRange(), true)
                                : playerQuery(source, true));
                }
                return conditions(targeting, null)
                        && entityQuery(source, access.fabrolia$lookAtType(), access.fabrolia$lookDistance());
            }
            if (goal instanceof TemptGoal) {
                // Other native subclasses have extra callback/state contracts.
                if (type != TemptGoal.class) return false;
                var access = (NativeGoalDependencyAccessors.TemptQuery) goal;
                Class<?> host = access.fabrolia$temptItems().getClass().getNestHost();
                // These native predicates only test the supplied ItemStack's tag/item.
                if (host != net.minecraft.world.entity.animal.sheep.Sheep.class
                        && host != net.minecraft.world.entity.animal.cow.AbstractCow.class
                        && host != net.minecraft.world.entity.animal.equine.AbstractHorse.class
                        && host != net.minecraft.world.entity.animal.pig.Pig.class
                        && host != net.minecraft.world.entity.animal.chicken.Chicken.class
                        && host != net.minecraft.world.entity.animal.rabbit.Rabbit.class
                        && host != net.minecraft.world.entity.animal.turtle.Turtle.class) return false;
                return access.fabrolia$temptedMob() == source
                        && callbackFrom(access.fabrolia$temptItems(), host)
                        && captures(access.fabrolia$temptItems(), null, null)
                        && conditions(access.fabrolia$temptTargeting(), TemptGoal.class)
                        && captures(((NativeGoalDependencyAccessors.Targeting) access.fabrolia$temptTargeting())
                                .fabrolia$querySelector(), goal, null)
                        && declaredPlayerQuery(source, TemptGoal.class, access.fabrolia$temptTargeting(),
                                source.getAttributeValue(Attributes.TEMPT_RANGE), false);
            }
            if (goal instanceof NearestAttackableTargetGoal) {
                // The two audited subclasses inherit findTarget unchanged.
                // The bear's overridden follow distance is exactly one half.
                boolean bearDefense = type.getName().equals(
                        "net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearAttackPlayersGoal");
                if (type != NearestAttackableTargetGoal.class && !bearDefense
                        && !type.getName().equals("net.minecraft.world.entity.monster.spider.Spider$SpiderTargetGoal")) return false;
                var access = (NativeGoalDependencyAccessors.NearestTargetQuery) goal;
                var targeting = access.fabrolia$targetConditions();
                if (targeting.getClass() != TargetingConditions.class) return false;
                if (!((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryCombat()) return false;
                var selector = ((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$querySelector();
                Class<?> target = access.fabrolia$targetType();
                boolean bearSelector = source.getClass() == net.minecraft.world.entity.animal.polarbear.PolarBear.class
                        && type == NearestAttackableTargetGoal.class
                        && (target == net.minecraft.world.entity.player.Player.class
                            || target == net.minecraft.world.entity.animal.fox.Fox.class)
                        && callbackFrom(selector, net.minecraft.world.entity.animal.polarbear.PolarBear.class)
                        && captures(selector, source, null);
                if (selector != null && !bearSelector
                        && selector != net.minecraft.world.entity.animal.turtle.Turtle.BABY_ON_LAND_SELECTOR) return false;
                double radius = source.getAttributeValue(Attributes.FOLLOW_RANGE);
                if (bearDefense) {
                    if (target != net.minecraft.world.entity.player.Player.class || selector != null
                            || !entityQuery(source, net.minecraft.world.entity.animal.polarbear.PolarBear.class,
                                    source.getBoundingBox().inflate(8, 4, 8), false)) return false;
                    radius *= 0.5;
                }
                return target == net.minecraft.world.entity.player.Player.class || target == ServerPlayer.class
                        ? selector == null || bearSelector
                            ? declaredPlayerQuery(source, NearestAttackableTargetGoal.class, targeting, radius, true)
                            : playerQuery(source, true)
                        : entityQuery(source, target, radius);
            }
            if (goal instanceof AvoidEntityGoal) {
                if (type != AvoidEntityGoal.class
                        && !type.getName().equals("net.minecraft.world.entity.animal.rabbit.Rabbit$RabbitAvoidEntityGoal")) return false;
                var access = (NativeGoalDependencyAccessors.AvoidQuery) goal;
                Object predicate = access.fabrolia$predicateOnAvoidEntity();
                boolean nativePredicate = predicate == net.minecraft.world.entity.EntitySelector.NO_CREATIVE_OR_SPECTATOR
                        || source.getClass() == net.minecraft.world.entity.animal.fish.Cod.class
                            && access.fabrolia$avoidClass() == net.minecraft.world.entity.player.Player.class
                            && predicate == net.minecraft.world.entity.EntitySelector.NO_SPECTATORS
                        || source.getClass() == net.minecraft.world.entity.monster.spider.Spider.class
                            && access.fabrolia$avoidClass() == net.minecraft.world.entity.animal.armadillo.Armadillo.class
                            && callbackFrom(predicate, net.minecraft.world.entity.monster.spider.Spider.class)
                            && captures(predicate, null, null);
                double radius = access.fabrolia$avoidDistance();
                return access.fabrolia$avoidingMob() == source
                        && callbackFrom(access.fabrolia$avoidPredicate(), AvoidEntityGoal.class)
                        && captures(access.fabrolia$avoidPredicate(), null, null)
                        && nativePredicate && radius >= 0 && Double.isFinite(radius)
                        && nativeClass(access.fabrolia$avoidClass())
                        && conditions(access.fabrolia$avoidTargeting(), AvoidEntityGoal.class)
                        && captures(((NativeGoalDependencyAccessors.Targeting) access.fabrolia$avoidTargeting())
                                .fabrolia$querySelector(), access.fabrolia$avoidPredicate(),
                                access.fabrolia$predicateOnAvoidEntity())
                        && entityQuery(source, access.fabrolia$avoidClass(),
                                source.getBoundingBox().inflate(radius, 3, radius), false);
            }
            if (goal instanceof net.minecraft.world.entity.ai.goal.BreedGoal) {
                var access = (NativeGoalDependencyAccessors.Partner) goal;
                var targeting = NativeGoalDependencyAccessors.Partner.fabrolia$partnerTargeting();
                if (targeting == null || !conditions(targeting, null)
                        || ((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryCombat()
                        || !Double.isFinite(((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryRange())) return false;
                if (access.fabrolia$breedingAnimal() != source
                        || access.fabrolia$breedingLevel() != runtime.level
                        || !entityQuery(source, access.fabrolia$partnerClass(), 8)) return false;
                return type == net.minecraft.world.entity.ai.goal.BreedGoal.class
                        || type.getName().equals("net.minecraft.world.entity.animal.turtle.Turtle$TurtleBreedGoal")
                            && access.fabrolia$partnerClass() == net.minecraft.world.entity.animal.turtle.Turtle.class
                        || type.getName().equals("net.minecraft.world.entity.animal.panda.Panda$PandaBreedGoal")
                            && playerQuery(source, true);
            }
            if (type == net.minecraft.world.entity.ai.goal.FollowParentGoal.class) {
                return ((NativeGoalDependencyAccessors.Parent) goal).fabrolia$followingAnimal() == source
                        && entityQuery(source, source.getClass(), 8);
            }
            if (type == net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal.class) {
                var access = (NativeGoalDependencyAccessors.VillageQuery) goal;
                return access.fabrolia$villageMob() == source
                        && callbackFrom(access.fabrolia$canDealWithDoors(),
                                net.minecraft.world.entity.monster.zombie.Zombie.class)
                        && captures(access.fabrolia$canDealWithDoors(), source, null);
            }
            if (type == net.minecraft.world.entity.ai.goal.ClimbOnTopOfPowderSnowGoal.class) {
                var access = (NativeGoalDependencyAccessors.PowderSnowSource) goal;
                return access.fabrolia$climbingMob() == source && access.fabrolia$climbingLevel() == runtime.level
                        && powderSnowQuery(source);
            }
            if (type == net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal.class) {
                return localAngerReset(source, goal);
            }
            if (type == net.minecraft.world.entity.ai.goal.FollowFlockLeaderGoal.class) {
                return entityQuery(source, source.getClass(), 8);
            }
            if (goal instanceof net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal) {
                var targeting = NativeGoalDependencyAccessors.HurtByAlert.fabrolia$hurtByTargeting();
                if (targeting == null || !conditions(targeting, null)
                        || !((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryCombat()
                        || !Double.isFinite(((NativeGoalDependencyAccessors.Targeting) targeting).fabrolia$queryRange())) return false;
            }
            // Exact, native-origin non-player-query implementations. In particular
            // FollowOwner's teleport, SkeletonTrap's alive-player bypass,
            // DefendVillage/Phantom full-player scans and arbitrary mod goals do
            // not fall through to an instanceof-based approval.
            return switch (type.getName()) {
                case "net.minecraft.world.entity.ai.goal.FloatGoal",
                     "net.minecraft.world.entity.ai.goal.EatBlockGoal",
                     "net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal",
                     "net.minecraft.world.entity.ai.goal.RandomStrollGoal",
                     "net.minecraft.world.entity.ai.goal.RandomLookAroundGoal",
                     "net.minecraft.world.entity.ai.goal.RestrictSunGoal",
                     "net.minecraft.world.entity.ai.goal.RandomStandGoal",
                     "net.minecraft.world.entity.ai.goal.RunAroundLikeCrazyGoal",
                     "net.minecraft.world.entity.ai.goal.FleeSunGoal",
                     "net.minecraft.world.entity.ai.goal.MeleeAttackGoal",
                     "net.minecraft.world.entity.ai.goal.ZombieAttackGoal",
                     "net.minecraft.world.entity.ai.goal.SpearUseGoal",
                     "net.minecraft.world.entity.ai.goal.RangedBowAttackGoal",
                     "net.minecraft.world.entity.ai.goal.SwellGoal",
                     "net.minecraft.world.entity.ai.goal.LeapAtTargetGoal",
                     "net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal",
                     "net.minecraft.world.entity.monster.spider.Spider$SpiderAttackGoal",
                     "net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearMeleeAttackGoal",
                     "net.minecraft.world.entity.animal.squid.Squid$SquidRandomMovementGoal",
                     "net.minecraft.world.entity.animal.turtle.Turtle$TurtleRandomStrollGoal",
                     "net.minecraft.world.entity.monster.skeleton.AbstractSkeleton$1" -> true;
                // Random swim candidates include home bias and path-type neighbors.
                case "net.minecraft.world.entity.animal.fish.AbstractFish$FishSwimGoal" ->
                        localBlockRectangle(source, 16);
                case "net.minecraft.world.entity.animal.squid.Squid$SquidFleeGoal" ->
                        squidFleeQuery(source);
                case "net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal" ->
                        !((NativeGoalDependencyAccessors.HurtByAlert) goal).fabrolia$alertSameType()
                                || hurtByAlertQuery(source);
                case "net.minecraft.world.entity.animal.polarbear.PolarBear$PolarBearHurtByTargetGoal" ->
                        hurtByAlertQuery(source);
                case "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal" ->
                        gardenQuery(source, (NativeGoalDependencyAccessors.BlockTarget) goal);
                case "net.minecraft.world.entity.animal.turtle.Turtle$TurtleGoToWaterGoal" ->
                        blockGoalQuery(source, (NativeGoalDependencyAccessors.BlockTarget) goal, 24, -1);
                case "net.minecraft.world.entity.animal.turtle.Turtle$TurtleLayEggGoal" ->
                        inactiveEggGoal((net.minecraft.world.entity.animal.turtle.Turtle) source, goal);
                // Home's 16+8 search also reads one path-type neighbor. Travel's
                // larger hasChunksAt +/-34 probe encloses that local search,
                // not its stored +/-512 destination.
                case "net.minecraft.world.entity.animal.turtle.Turtle$TurtleGoHomeGoal" ->
                        localBlockRectangle(source, 25);
                case "net.minecraft.world.entity.animal.turtle.Turtle$TurtleTravelGoal" ->
                        localBlockRectangle(source, 58);
                case "net.minecraft.world.entity.animal.dolphin.Dolphin$DolphinSwimWithPlayerGoal" ->
                        playerQuery(source, false);
                default -> false;
            };
        }

        private boolean goals(Mob source, GoalSelector selector) {
            if (selector.getClass() != GoalSelector.class) return false;
            var locks = ((NativeEntityDependencyAccessors.GoalLocks) selector).fabrolia$lockedGoals();
            if (locks == null || locks.getClass() != java.util.EnumMap.class) return false;
            int uncovered = 0;
            for (int i = 0; i < GOAL_FLAGS.length; i++) {
                var locked = locks.get(GOAL_FLAGS[i]);
                if (locked != null) {
                    if (locked.getClass() != net.minecraft.world.entity.ai.goal.WrappedGoal.class) return false;
                    uncovered |= 1 << i;
                } else if (locks.containsKey(GOAL_FLAGS[i])) return false;
            }
            for (var wrapped : selector.getAvailableGoals()) {
                if (wrapped.getClass() != net.minecraft.world.entity.ai.goal.WrappedGoal.class
                        || !nativeClass(wrapped.getGoal().getClass())
                        || wrapped.isRunning() && wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.BreedGoal
                        || !goalQuery(source, wrapped.getGoal())
                        || !goal(source, wrapped.getGoal())) return false;
                for (int i = 0; i < GOAL_FLAGS.length; i++) {
                    if (locks.get(GOAL_FLAGS[i]) == wrapped) uncovered &= ~(1 << i);
                }
            }
            return uncovered == 0;
        }

        private boolean goal(Mob source, Goal goal) {
            if (goal instanceof NativeGoalDependencyAccessors.Player access
                    && !queryDependency(source, access.fabrolia$player(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Partner access
                    && !queryDependency(source, access.fabrolia$partner(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.FollowingMob access
                    && !queryDependency(source, access.fabrolia$followingMob(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Owner access
                    && !queryDependency(source, access.fabrolia$owner(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Parent access
                    && !queryDependency(source, access.fabrolia$parent(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Following access
                    && !queryDependency(source, access.fabrolia$following(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Target access
                    && !queryDependency(source, access.fabrolia$target(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.LookAt access
                    && !queryDependency(source, access.fabrolia$lookAt(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.Entity access
                    && !queryDependency(source, access.fabrolia$entity(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.PotentialTarget access
                    && !queryDependency(source, access.fabrolia$potentialTarget(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.OwnerLastHurtBy access
                    && !queryDependency(source, access.fabrolia$ownerLastHurtBy(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.OwnerLastHurt access
                    && !queryDependency(source, access.fabrolia$ownerLastHurt(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.TargetMob access
                    && !queryDependency(source, access.fabrolia$targetMob(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.OwnerPlayer access
                    && !queryDependency(source, access.fabrolia$ownerPlayer(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.SelectedPlayer access
                    && !queryDependency(source, access.fabrolia$selectedPlayer(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.TrustedLastHurtBy access
                    && !queryDependency(source, access.fabrolia$trustedLastHurtBy(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.TrustedLastHurt access
                    && !queryDependency(source, access.fabrolia$trustedLastHurt(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.PendingTarget access
                    && !queryDependency(source, access.fabrolia$pendingTarget(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.TargetItem access
                    && !queryDependency(source, access.fabrolia$targetItem(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.PursuedBannerItemEntity access
                    && !queryDependency(source, access.fabrolia$pursuedBannerItemEntity(), true)) return false;
            if (goal instanceof NativeGoalDependencyAccessors.ToAvoid access
                    && !queryDependency(source, access.fabrolia$toAvoid(), true)) return false;
            return true;
        }
    }
}
