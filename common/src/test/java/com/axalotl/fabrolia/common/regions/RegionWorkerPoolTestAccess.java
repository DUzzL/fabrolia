package com.axalotl.fabrolia.common.regions;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Synthetic task/admission setup for fixtures; never packaged with the mod. */
final class RegionWorkerPoolTestAccess {
    private RegionWorkerPoolTestAccess() {}

    static Runnable reserveExternalTask(RegionWorkerPool pool, Runnable body) {
        invoke(pool, "enterRootAdmission");
        Runnable task = null;
        Throwable failure = null;
        try {
            synchronized (field(pool, "lifecycleLock")) {
                task = (Runnable) invoke(pool, "reserveExternalTaskLocked", body);
            }
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Object gate = task == null ? null : field(field(task, "permit"), "publication");
        failure = (Throwable) invoke(pool, "closeRootAdmission", failure, gate, null, null);
        if (failure != null) return rethrow(failure);
        return task;
    }

    static void leaveRootAdmission(RegionWorkerPool pool) {
        // Release fixture-owned admission through the same production cleanup.
        // An already published fatal is inspected by the test, not this fixture.
        invoke(pool, "closeRootAdmission", null, null, null, null);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Object target, String name, Object... args) {
        try {
            Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name)
                            && candidate.getParameterCount() == args.length)
                    .findFirst().orElseThrow(() -> new AssertionError("Missing fixture hook: " + name));
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            return rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
