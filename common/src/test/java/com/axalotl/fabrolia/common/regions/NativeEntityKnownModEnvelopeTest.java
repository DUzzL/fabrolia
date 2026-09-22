package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class NativeEntityKnownModEnvelopeTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void carpetRegistrationOutsideTickInvalidatesTheNextAdmission() throws Throwable {
        TestEntity source = entity();
        Object probe = carpetProbe();
        assertTrue(idle(probe, source));
        source.events.actions.put("damage", Map.of("callback", new Object()));
        assertFalse(idle(probe, source));
        source.events.actions.clear();
        assertTrue(idle(probe, source));
    }

    @Test
    void anEmptyCarpetGroupMustBelongToTheActualSource() throws Throwable {
        TestEntity source = entity();
        source.events = new EventGroup(entity());
        Object probe = carpetProbe();
        assertFalse(idle(probe, source));
        source.events = new EventGroup(source);
        assertTrue(idle(probe, source));
    }

    @Test
    void unknownActionCollectionsAreNotInvokedToDetermineEligibility() throws Throwable {
        TestEntity source = entity();
        source.events.actions = new CallbackMap();
        assertFalse(idle(carpetProbe(), source));
    }

    @Test
    void customFluidRegistrationAndRetainedExitStateBothExcludeNativeEntry() throws Throwable {
        TestEntity source = entity();
        Registry.tracked.clear();
        Object probe = fluidProbe();
        try {
            assertTrue(idle(probe, source));
            source.touching.add("previous-fluid");
            assertFalse(idle(probe, source));
            source.touching.clear();
            assertTrue(idle(probe, source));
            Registry.tracked.put("registered-fluid", new Object());
            assertFalse(idle(probe, source));
            Registry.tracked.clear();
            assertTrue(idle(probe, source));
        } finally {
            Registry.tracked.clear();
        }
    }

    @Test
    void unknownFluidContactCollectionsAreNotInvokedDuringInspection() throws Throwable {
        TestEntity source = entity();
        source.touching = new CallbackSet();
        Registry.tracked.clear();
        assertFalse(idle(fluidProbe(), source));
    }

    private static Object carpetProbe() throws Exception {
        Method factory = NativeEntityKnownModEnvelope.class.getDeclaredMethod(
                "carpetProbe", Field.class, Class.class, Field.class, Field.class);
        factory.setAccessible(true);
        return factory.invoke(null, field(TestEntity.class, "events"), EventGroup.class,
                field(EventGroup.class, "owner"), field(EventGroup.class, "actions"));
    }

    private static Object fluidProbe() throws Exception {
        Method factory = NativeEntityKnownModEnvelope.class.getDeclaredMethod(
                "fluidProbe", Field.class, Field.class);
        factory.setAccessible(true);
        return factory.invoke(null, field(Registry.class, "tracked"), field(TestEntity.class, "touching"));
    }

    private static boolean idle(Object probe, Entity source) throws Throwable {
        Method method = Class.forName(NativeEntityKnownModEnvelope.class.getName() + "$Probe")
                .getDeclaredMethod("idle", Entity.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(probe, source);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static TestEntity entity() throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        TestEntity source = (TestEntity) unsafe.allocateInstance(TestEntity.class);
        source.events = new EventGroup(source);
        source.touching = new HashSet<>();
        return source;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestEntity extends ItemEntity {
        EventGroup events;
        Set<Object> touching;
        private TestEntity() { super(null, 0, 0, 0, null); }
    }

    private static final class EventGroup {
        final Entity owner;
        Map<Object, Object> actions = new HashMap<>();
        private EventGroup(Entity owner) { this.owner = owner; }
    }

    private static final class Registry {
        static final HashMap<Object, Object> tracked = new HashMap<>();
    }

    private static final class CallbackMap extends HashMap<Object, Object> {
        @Override public boolean isEmpty() { throw new AssertionError("callback collection entered"); }
    }

    private static final class CallbackSet extends HashSet<Object> {
        @Override public boolean isEmpty() { throw new AssertionError("callback collection entered"); }
    }
}
