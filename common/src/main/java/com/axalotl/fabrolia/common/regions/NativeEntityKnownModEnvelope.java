package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.platform.PlatformUtils;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owner-only, current-state exclusions for known NPC tick injectors. These checks
 * neither freeze callback registration nor certify arbitrary transformed bodies.
 * Only schemas are cached; callback/action collections are checked at each entry.
 */
final class NativeEntityKnownModEnvelope {
    private NativeEntityKnownModEnvelope() {}

    private interface Probe {
        boolean idle(Entity entity);
    }

    private static final Probe CARPET = carpetProbe();
    private static final Probe FLUIDS = fluidProbe();

    /** The static invocation initializes both schemas on Main before workers start. */
    static void initialize() {}

    static boolean idle(Entity entity) {
        return CARPET.idle(entity) && FLUIDS.idle(entity);
    }

    private static Probe carpetProbe() {
        try {
            Class<?> group = Class.forName("carpet.script.EntityEventsGroup", false,
                    Entity.class.getClassLoader());
            return carpetProbe(field(Entity.class, "events"), group,
                    field(group, "entity"), field(group, "actions"));
        } catch (ClassNotFoundException absent) {
            return entity -> modAbsent("carpet");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return entity -> false;
        }
    }

    private static Probe carpetProbe(Field events, Class<?> groupClass, Field owner, Field actions) {
        if (events.getType() != groupClass || Modifier.isStatic(events.getModifiers())
                || owner.getType() != Entity.class || Modifier.isStatic(owner.getModifiers())
                || !Modifier.isFinal(owner.getModifiers())
                || actions.getType() != Map.class || Modifier.isStatic(actions.getModifiers())) {
            return entity -> false;
        }
        return entity -> {
            try {
                Object group = events.get(entity);
                // Do not inspect an unrelated group's mutable action collection.
                if (group == null || group.getClass() != groupClass || owner.get(group) != entity) return false;
                Object callbacks = actions.get(group);
                return callbacks != null && callbacks.getClass() == HashMap.class
                        && ((HashMap<?, ?>) callbacks).isEmpty();
            } catch (ReflectiveOperationException | RuntimeException incompatible) {
                return false;
            }
        };
    }

    private static Probe fluidProbe() {
        try {
            Class<?> registry = Class.forName(
                    "net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl",
                    false, Entity.class.getClassLoader());
            return fluidProbe(field(registry, "TRACKED_FLUIDS"),
                    field(Entity.class, "wasTouchingCustomFluid"));
        } catch (ClassNotFoundException absent) {
            return entity -> modAbsent("fabric-content-registries-v0");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return entity -> false;
        }
    }

    private static Probe fluidProbe(Field registry, Field touching) {
        if (registry.getType() != HashMap.class || !Modifier.isStatic(registry.getModifiers())
                || !Modifier.isFinal(registry.getModifiers())
                || touching.getType() != Set.class || Modifier.isStatic(touching.getModifiers())) {
            return entity -> false;
        }
        try {
            // Resolve/initialize the registry on Main, but never cache its emptiness.
            Object tracked = registry.get(null);
            if (tracked == null || tracked.getClass() != HashMap.class) return entity -> false;
            return entity -> {
                try {
                    if (registry.get(null) != tracked || !((HashMap<?, ?>) tracked).isEmpty()) return false;
                    Object previous = touching.get(entity);
                    // An empty registry alone does not certify retained exit state.
                    return previous != null && previous.getClass() == HashSet.class
                            && ((HashSet<?>) previous).isEmpty();
                } catch (ReflectiveOperationException | RuntimeException incompatible) {
                    return false;
                }
            };
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return entity -> false;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) {
            throw new IllegalStateException("cannot inspect " + owner.getName() + "." + name);
        }
        return field;
    }

    private static boolean modAbsent(String id) {
        try {
            return !PlatformUtils.isModLoaded(id);
        } catch (LinkageError | RuntimeException | java.util.ServiceConfigurationError platformFailure) {
            return false;
        }
    }
}
