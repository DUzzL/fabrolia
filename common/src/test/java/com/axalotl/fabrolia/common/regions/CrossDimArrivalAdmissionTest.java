package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.world.CrossDimTeleportMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class CrossDimArrivalAdmissionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateUuidRefusalDropsTheArrivalAndSettlesItsLanding() throws Exception {
        Field unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        TestFallingBlock arrival = (TestFallingBlock) ((sun.misc.Unsafe) unsafeField.get(null))
                .allocateInstance(TestFallingBlock.class);
        arrival.setUUID(UUID.randomUUID());
        field(Entity.class, "levelCallback").set(arrival,
                net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
        field(Entity.class, "passengers").set(arrival, com.google.common.collect.ImmutableList.of());
        PersistentEntitySectionManager<Entity> manager =
                new PersistentEntitySectionManager<>(Entity.class, null, null);
        @SuppressWarnings("unchecked")
        Set<UUID> known = (Set<UUID>) field(PersistentEntitySectionManager.class, "knownUuids").get(manager);
        // An existing UUID can belong to a hidden section: visible lookup is not admission.
        known.add(arrival.getUUID());
        EndPlatformHandoff.Tracker tracker = new EndPlatformHandoff.Tracker(h -> ((Entity) h).isRemoved());
        tracker.track(1L, arrival);
        CrossDimTeleportMixin adapter = new CrossDimTeleportMixin() {};
        Method admission = CrossDimTeleportMixin.class.getDeclaredMethod("fabrolia$observeArrivalAdmission",
                ServerLevel.class, Entity.class, Operation.class);
        admission.setAccessible(true);
        Operation<Boolean> original = args -> manager.addNewEntity((Entity) args[1]);
        AtomicBoolean successor = new AtomicBoolean();
        // Audit A-14: vanilla addDuringTeleport ignores the refusal, the copy never
        // exists. The publication continues and nothing awaits the dropped landing.
        assertDoesNotThrow(() -> CrossDimArrivals.runPublished(() -> {
            assertFalse(invoke(admission, adapter, arrival, original));
            successor.set(true);
        }));
        assertTrue(successor.get(), "the rest of the cohort still publishes");
        assertTrue(arrival.isRemoved(), "the refused copy is dropped as vanilla drops it");
        assertFalse(tracker.airborne(1L), "a dropped arrival cannot hold the platform handoff forever");
        assertFalse(CrossDimArrivals.PUBLISHING.get());
        // Outside staged publication the vanilla void caller retains its refusal semantics.
        assertFalse(invoke(admission, adapter, arrival, original));
    }

    private static boolean invoke(Method method, Object adapter, Entity entity, Operation<Boolean> original) {
        try {
            return (boolean) method.invoke(adapter, null, entity, original);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestFallingBlock extends FallingBlockEntity {
        private TestFallingBlock() { super(null, null); }
        @Override public String toString() { return "duplicate falling block arrival"; }
    }
}
