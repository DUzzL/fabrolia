package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.mixin.entity.breed.AnimalMakeLoveMixin;
import com.axalotl.fabrolia.common.utils.PathDoorAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises production capture/guard handlers with deterministic owner-memory interleavings. */
class EntityAiInputSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void breedingTypeCheckAndConsumerUseSameTargetAfterPartnerErasesMemory() throws Exception {
        ErasingBrain brain = new ErasingBrain();
        Frog body = frog(brain);
        Frog partner = frog(new ErasingBrain());
        brain.setMemory(MemoryModuleType.BREED_TARGET, partner);
        brain.afterRead = () -> brain.eraseMemory(MemoryModuleType.BREED_TARGET);
        Breeding behavior = new Breeding();
        field(AnimalMakeLoveMixin.class, "partnerType").set(behavior, EntityTypes.FROG);
        Ref<Animal> captured = new Ref<>();
        Method capture = method(AnimalMakeLoveMixin.class, "fabrolia$captureBreedTarget",
                AnimalMakeLove.class, Animal.class, Operation.class, LocalRef.class);
        assertTrue((boolean) capture.invoke(behavior, null, body, null, captured));
        assertTrue(brain.getMemory(MemoryModuleType.BREED_TARGET).isEmpty());
        Method consume = method(AnimalMakeLoveMixin.class, "fabrolia$useCapturedBreedTarget",
                AnimalMakeLove.class, Animal.class, Operation.class, LocalRef.class);
        Animal consumed = (Animal) consume.invoke(behavior, null, body, null, captured);
        assertSame(partner, consumed, "partner clearing BREED_TARGET cannot invalidate this callback's target");
        assertEquals(12D, consumed.getX());
        assertFalse((boolean) capture.invoke(behavior, null, body, null, new Ref<Animal>()),
                "the next callback must observe the erased memory");
    }

    @Test
    void breedingTickGuardsTheOneActualGetterResultBeforeGazeMutation() throws Exception {
        ErasingBrain brain = new ErasingBrain();
        Frog body = frog(brain);
        Frog partner = frog(new ErasingBrain());
        brain.setMemory(MemoryModuleType.BREED_TARGET, partner);
        brain.afterRead = () -> brain.eraseMemory(MemoryModuleType.BREED_TARGET);
        Breeding behavior = new Breeding();
        Method getter = method(AnimalMakeLoveMixin.class, "fabrolia$readBreedTarget",
                Animal.class, CallbackInfoReturnable.class);
        Method guard = method(AnimalMakeLoveMixin.class, "fabrolia$guardCapturedTickTarget",
                ServerLevel.class, Animal.class, long.class, CallbackInfo.class, Animal.class);
        CallbackInfoReturnable<Animal> captured = new CallbackInfoReturnable<>("getBreedTarget", true);
        getter.invoke(behavior, body, captured);
        CallbackInfo tick = new CallbackInfo("tick", true);
        guard.invoke(behavior, null, body, 1L, tick, captured.getReturnValue());
        assertFalse(tick.isCancelled());
        assertSame(partner, captured.getReturnValue());
        CallbackInfoReturnable<Animal> absent = new CallbackInfoReturnable<>("getBreedTarget", true);
        getter.invoke(behavior, body, absent);
        CallbackInfo laterTick = new CallbackInfo("tick", true);
        guard.invoke(behavior, null, body, 2L, laterTick, absent.getReturnValue());
        assertTrue(laterTick.isCancelled(), "absence must stop the whole gaze/walk/breed transaction");
    }

    @Test
    void panicErasureCannotProduceNullChaseKeysOrDropAlreadyCapturedChasers() throws Exception {
        ErasingBrain brain = new ErasingBrain();
        Frog friend = frog(brain);
        Frog chased = frog(new ErasingBrain());
        friend.setId(1);
        chased.setId(2);
        brain.setMemory(MemoryModuleType.INTERACTION_TARGET, chased);
        brain.afterRead = () -> brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        CallbackInfoReturnable<Map<LivingEntity, Integer>> result =
                new CallbackInfoReturnable<>("checkHowManyChasersEachFriendHas", true);
        Method count = method(PlayTagWithOtherKidsMixin.class, "fabrolia$countCapturedTargets",
                List.class, CallbackInfoReturnable.class);
        count.invoke(null, List.of(friend), result);
        Map<LivingEntity, Integer> counts = result.getReturnValue();
        assertEquals(Map.of(chased, 1), counts);
        assertSame(chased, counts.entrySet().stream().filter(e -> e.getValue() > 0 && e.getValue() <= 5)
                .map(Map.Entry::getKey).findFirst().orElseThrow());
        CallbackInfoReturnable<Map<LivingEntity, Integer>> later =
                new CallbackInfoReturnable<>("checkHowManyChasersEachFriendHas", true);
        count.invoke(null, List.of(friend), later);
        assertTrue(later.getReturnValue().isEmpty(), "the erased target must not become a null map key");
    }

    @Test
    void spearUsesCapturedTargetEvenWhenLiveTargetWasClearedAndRejectsAbsentCapture() throws Exception {
        TargetMonster mob = allocate(TargetMonster.class);
        mob.setTarget(frog(new ErasingBrain()));
        LivingEntity captured = mob.getTarget();
        mob.setTarget(null); // the universal-anger reset wins after vanilla's local capture
        assertNull(mob.getTarget());
        Method guard = method(SpearUseGoalMixin.class, "fabrolia$nullSafeTarget",
                CallbackInfo.class, LivingEntity.class);
        Spear goal = new Spear();
        CallbackInfo present = new CallbackInfo("tick", true);
        guard.invoke(goal, present, captured);
        assertFalse(present.isCancelled());
        assertEquals(12D, captured.getX(), "vanilla must consume its guarded local, not re-fetch a live target");
        CallbackInfo absent = new CallbackInfo("tick", true);
        guard.invoke(goal, absent, mob.getTarget());
        assertTrue(absent.isCancelled(), "actual null capture must stop before target coordinates are read");
    }

    @Test
    void doorReaderKeepsCoherentStepWhileOwnerAdvancesToCompletionWithoutWaiting() throws Exception {
        List<Node> nodes = new ArrayList<>(List.of(new Node(0, 0, 0), new Node(1, 0, 0)));
        Path vanilla = new Path(nodes, new BlockPos(1, 0, 0), true);
        vanilla.setNextNodeIndex(1);
        ObservedPath owner = new ObservedPath();
        field(PathMixin.class, "nodes").set(owner, nodes);
        field(PathMixin.class, "nextNodeIndex").setInt(owner, 1);
        owner.fabrolia$publishDoorStep();
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        owner.afterRead = () -> {
            captured.countDown();
            await(resume);
        };
        CompletableFuture<Boolean> reader = CompletableFuture.supplyAsync(
                () -> owner.fabrolia$isComingThroughDoor(new BlockPos(1, 0, 0)));
        try {
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            Operation<Void> advance = args -> {
                vanilla.advance();
                setIndex(owner, vanilla.getNextNodeIndex());
                return null;
            };
            method(PathMixin.class, "fabrolia$advanceAndPublish", Operation.class).invoke(owner, advance);
            assertTrue(vanilla.isDone());
            resume.countDown();
            assertTrue(reader.get(5, TimeUnit.SECONDS), "the captured step survives completion without indexing the live path");
            assertFalse(owner.fabrolia$isComingThroughDoor(new BlockPos(1, 0, 0)));

            setIndex(owner, 1);
            owner.fabrolia$publishDoorStep();
            Operation<Void> truncate = args -> { vanilla.truncateNodes((int) args[0]); return null; };
            method(PathMixin.class, "fabrolia$changeIndexAndPublish", int.class, Operation.class)
                    .invoke(owner, 1, truncate);
            assertFalse(owner.fabrolia$isComingThroughDoor(new BlockPos(1, 0, 0)),
                    "trimming past the cursor must invalidate the published step");
        } finally {
            resume.countDown();
            reader.get(5, TimeUnit.SECONDS);
        }
    }

    private static Frog frog(Brain<?> brain) throws Exception {
        Frog frog = allocate(Frog.class);
        field(LivingEntity.class, "brain").set(frog, brain);
        field(Entity.class, "type").set(frog, EntityTypes.FROG);
        field(Entity.class, "position").set(frog, new Vec3(12, 0, 0));
        return frog;
    }

    private static void setIndex(PathMixin path, int index) {
        try { field(PathMixin.class, "nextNodeIndex").setInt(path, index); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }

    private static final class ErasingBrain extends Brain<LivingEntity> {
        Runnable afterRead;
        ErasingBrain() {
            super(List.of(MemoryModuleType.BREED_TARGET, MemoryModuleType.INTERACTION_TARGET),
                    List.of(), List.of(), MemoryMap.EMPTY, RandomSource.create(1));
        }
        @Override public <U> Optional<U> getMemory(MemoryModuleType<U> type) {
            Optional<U> captured = super.getMemory(type);
            Runnable erase = afterRead;
            afterRead = null;
            if (erase != null) erase.run();
            return captured;
        }
    }

    private static final class Ref<T> implements LocalRef<T> {
        private T value;
        @Override public T get() { return value; }
        @Override public void set(T value) { this.value = value; }
    }
    private static final class Breeding extends AnimalMakeLoveMixin { }
    private static final class Spear extends SpearUseGoalMixin { }
    private static final class TargetMonster extends Monster {
        TargetMonster() { super(EntityTypes.ZOMBIE, null); }
        @Override public boolean canAttack(LivingEntity target) { return true; }
    }
    private static final class ObservedPath extends PathMixin {
        Runnable afterRead;
        @Override public PathDoorAccess.Step fabrolia$doorStep() {
            PathDoorAccess.Step step = super.fabrolia$doorStep();
            Runnable hook = afterRead;
            afterRead = null;
            if (hook != null) hook.run();
            return step;
        }
    }
}
