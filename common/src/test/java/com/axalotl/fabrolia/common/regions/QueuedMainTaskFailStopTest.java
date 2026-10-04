package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct failure-contract checks for work published to Minecraft's task queue. */
final class QueuedMainTaskFailStopTest {

    private Field poolField;
    private Field orphanFatalField;
    private Object previousPool;
    private Object previousOrphanFatal;

    @BeforeEach
    void isolateFatalGate() throws ReflectiveOperationException {
        poolField = RegionScheduler.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        orphanFatalField.setAccessible(true);
        previousPool = poolField.get(null);
        previousOrphanFatal = orphanFatalField.get(null);
        poolField.set(null, null);
        orphanFatalField.set(null, null);
    }

    @AfterEach
    void restoreFatalGate() throws IllegalAccessException {
        poolField.set(null, previousPool);
        orphanFatalField.set(null, previousOrphanFatal);
    }

    @Test
    void queuedMainWrappersPublishAndRethrowTheExactPrimary() throws Exception {
        assertExactFailStopWrapper(NavRecompute.class, "runFailStopMainTask");
    }

    private void assertExactFailStopWrapper(Class<?> owner, String methodName)
            throws Exception {
        Method wrapper = owner.getDeclaredMethod(methodName, Runnable.class);
        wrapper.setAccessible(true);

        RuntimeException primary = new RuntimeException(owner.getSimpleName());
        InvocationTargetException first = assertThrows(
                InvocationTargetException.class,
                () -> wrapper.invoke(null, (Runnable) () -> { throw primary; }));
        assertSame(primary, first.getCause(),
                "the server task must rethrow the exact body Throwable");
        assertSame(primary, orphanFatalField.get(null),
                "the same object must arm the physical-server fatal gate");

        AtomicBoolean successorEntered = new AtomicBoolean();
        InvocationTargetException successor = assertThrows(
                InvocationTargetException.class,
                () -> wrapper.invoke(null, (Runnable) () -> successorEntered.set(true)));
        assertSame(primary, successor.getCause(),
                "a later task must observe the first shared fatal unchanged");
        assertFalse(successorEntered.get(),
                "no successor body may start after the first queued-task failure");
    }

    

    

    

    

    

    

    

    

    

    
}
