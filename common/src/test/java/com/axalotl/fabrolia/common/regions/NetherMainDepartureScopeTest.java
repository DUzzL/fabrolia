package com.axalotl.fabrolia.common.regions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The fake-removal view belongs to the source stack, not the pending transport. */
@Isolated
class NetherMainDepartureScopeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @SuppressWarnings("unchecked")
    void completedMainBoundaryEndsOnlyMainsDepartureView() throws Exception {
        assertFalse(RegionScheduler.isActive());
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        field(MinecraftServer.class, "serverThread").set(server, Thread.currentThread());
        field(MinecraftServer.class, "levels").set(server, Map.of());
        Field serverField = field(NetherPortalTransfers.class, "server");
        Object oldServer = serverField.get(null);
        var departures = (ThreadLocal<IdentityHashMap<Object, Object>>)
                field(NetherPortalTransfers.class, "DEPARTURES").get(null);
        var previous = departures.get();
        Class<?> transferType = Class.forName(NetherPortalTransfers.class.getName() + "$Transfer");
        Object transfer = unsafe.allocateInstance(transferType);
        field(transferType, "state").set(transfer, new AtomicInteger(
                field(NetherPortalTransfers.class, "WAITING").getInt(null)));
        Object entity = new Object();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch mainCompleted = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            serverField.set(null, server);
            departures.set(new IdentityHashMap<>());
            departures.get().put(entity, transfer);
            var workerView = worker.submit(() -> {
                departures.get().put(entity, transfer);
                try {
                    workerEntered.countDown();
                    assertTrue(mainCompleted.await(5, TimeUnit.SECONDS));
                    assertTrue(NetherPortalTransfers.hasDepartedThisTick(entity),
                            "Main cannot close another thread's unfinished source stack or cancel its transport");
                } finally {
                    departures.remove();
                }
                return null;
            });
            try {
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
                assertTrue(NetherPortalTransfers.hasDepartedThisTick(entity));
                NetherPortalTransfers.serviceMain(server);
                boolean mainViewEnded = !NetherPortalTransfers.hasDepartedThisTick(entity);
                mainCompleted.countDown();
                workerView.get(5, TimeUnit.SECONDS);
                assertTrue(mainViewEnded,
                        "completed vanilla Main player tick must not look removed during compatibility validation");
            } finally {
                mainCompleted.countDown();
            }
        } finally {
            departures.set(previous);
            serverField.set(null, oldServer);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
