package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.MinecraftServerMixin;
import net.minecraft.server.MinecraftServer;

import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercise the production reload wait decision with vanilla's actual task pump. */
class ReloadPhysicalVenueTest {
    @Test
    void physicalMainCompletesItsApplicationBeforeReturning() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        Pump pump = new Pump(Thread.currentThread());
        CompletableFuture<Void> applied = new CompletableFuture<>();
        AtomicReference<Thread> applicationThread = new AtomicReference<>();
        pump.schedule(() -> {
            applicationThread.set(Thread.currentThread());
            applied.complete(null);
        });

        if (reloadMayPump(server)) pump.managedBlock(applied::isDone);

        assertTrue(applied.isDone(), "physical-Main reload returned before application");
        assertSame(Thread.currentThread(), applicationThread.get());
    }

    @Test
    void regionWorkerCannotConsumeQueuedMainApplication() throws Exception {
        MinecraftServer server = serverOwnedBy(Thread.currentThread());
        Pump pump = new Pump(Thread.currentThread());
        CompletableFuture<Void> applied = new CompletableFuture<>();
        AtomicReference<Thread> applicationThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        pump.schedule(() -> {
            applicationThread.set(Thread.currentThread());
            applied.complete(null);
        });
        RegionWorkerThread worker = new RegionWorkerThread(() -> {
            try {
                if (reloadMayPump(server)) pump.managedBlock(applied::isDone);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "reload-venue-worker");
        worker.start();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertFalse(applied.isDone(), "worker pumped an arbitrary Main application");
        pump.managedBlock(applied::isDone);
        assertSame(Thread.currentThread(), applicationThread.get());
    }

    private static boolean reloadMayPump(MinecraftServer server) throws Exception {
        Method gate = MinecraftServerMixin.class.getDeclaredMethod(
                "onServerExecutionThreadPatch", MinecraftServer.class);
        gate.setAccessible(true);
        return (boolean) gate.invoke(new MinecraftServerMixin(), server);
    }

    private static MinecraftServer serverOwnedBy(Thread thread) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(com.axalotl.fabrolia.common.regions.TestDedicatedServer.class);
        Field serverThread = MinecraftServer.class.getDeclaredField("serverThread");
        serverThread.setAccessible(true);
        serverThread.set(server, thread);
        return server;
    }

    private static final class Pump extends BlockableEventLoop<Runnable> {
        private final Thread owner;
        Pump(Thread owner) {
            super("reload-venue-regression", true);
            this.owner = owner;
        }
        @Override protected boolean shouldRun(Runnable task) { return true; }
        @Override protected Thread getRunningThread() { return owner; }
        @Override public Runnable wrapRunnable(Runnable task) { return task; }
    }
}
