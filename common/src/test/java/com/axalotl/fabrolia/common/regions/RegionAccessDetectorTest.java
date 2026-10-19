package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegionAccessDetectorTest {
    @Test
    void scheduledFluidLambdaIsNotAttributedToAnExternalMod() {
        StackTraceElement[] trace = {
                new StackTraceElement("net.minecraft.world.level.material.FlowingFluid", "spreadTo", "FlowingFluid.java", 304),
                new StackTraceElement("com.axalotl.fabrolia.common.regions.IslandScheduledTicks",
                        "lambda$runOneCollectedTick$0", "IslandScheduledTicks.java", 1813)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.UNKNOWN,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void lambdaForDollarNamedMethodIsNotAMixinHandler() {
        StackTraceElement[] trace = {
                new StackTraceElement("com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin",
                        "lambda$fabrolia$guardTick$0", "ServerLevelMixin.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.UNKNOWN,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void externalLambdaStillIdentifiesAForeignWriter() {
        StackTraceElement[] trace = {
                new StackTraceElement("example.foreignmod.Writer",
                        "lambda$write$0", "Writer.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void foreignHandlerGraftedIntoVanillaRemainsAnExternalWriter() {
        StackTraceElement[] trace = {
                new StackTraceElement("net.minecraft.world.level.Level",
                        "handler$abc000$foreignmod$write", "Level.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    /**
     * 2026-10-18 production log: a bat on redstone ore, a wind charge and a block
     * break wrote across a region border inside their own island. The serial
     * island entity pass claims every region of the island on its own worker, so
     * the target's holder was the writer itself; eight such hits used up the
     * whole UNSAFE log budget 28 minutes after start.
     */
    @Test
    void crossRegionWriteIntoARegionHeldByTheWriterIsNotUnsafe() throws Exception {
        assertEquals(0, unsafeDelta(HolderKind.WRITER));
    }

    @Test
    void crossRegionWriteIntoARegionHeldByAnotherThreadIsUnsafe() throws Exception {
        assertEquals(1, unsafeDelta(HolderKind.OTHER_THREAD));
    }

    @Test
    void crossRegionWriteIntoAnIdleRegionIsNotUnsafe() throws Exception {
        assertEquals(0, unsafeDelta(HolderKind.NONE));
    }

    private enum HolderKind { WRITER, OTHER_THREAD, NONE }

    @SuppressWarnings("unchecked")
    private static long unsafeDelta(HolderKind kind) throws Exception {
        TickDomainsProductionPathTest.bootstrapMinecraft();
        TickDomainsProductionPathTest.TestLevel level = TickDomainsProductionPathTest.TestLevel.alloc();
        RegionizedServer runtime = new RegionizedServer(level, new RegionGrid(3));
        Map<ServerLevel, RegionizedServer> levels =
                (Map<ServerLevel, RegionizedServer>) field(RegionScheduler.class, "PER_LEVEL").get(null);
        boolean debug = FabroliaConfig.fabroliaDebugRegionAccess;
        boolean active = field(RegionScheduler.class, "active").getBoolean(null);
        AtomicLong logged = (AtomicLong) field(RegionAccessDetector.class, "LOGGED").get(null);
        long loggedBefore = logged.get();
        Thread other = new Thread(() -> {}, "other-region-worker");
        try {
            levels.put(level, runtime);
            field(RegionScheduler.class, "active").setBoolean(null, true);
            FabroliaConfig.fabroliaDebugRegionAccess = true;
            // Lockstep view: only the claim classification is under test here.
            runtime.asyncActive = false;
            Region writer = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(0, 0));
            Region target = runtime.regionizer().regionForGridKey(runtime.regionizer().grid.keyFor(8, 0));
            AtomicLong unsafe = (AtomicLong) field(RegionAccessDetector.class, "CROSS_UNSAFE").get(null);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            long[] delta = new long[1];
            RegionWorkerThread worker = new RegionWorkerThread(() -> {
                try {
                    if (kind == HolderKind.WRITER) setHolder(target, Thread.currentThread());
                    else if (kind == HolderKind.OTHER_THREAD) setHolder(target, other);
                    long before = unsafe.get();
                    // A block of region (8, 0) written while the worker ticks region (0, 0).
                    RegionAccessDetector.onBlockWrite(new BlockPos(8 * 16, 64, 0));
                    delta[0] = unsafe.get() - before;
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    try {
                        setHolder(target, null);
                    } catch (Throwable cleanup) {
                        failure.compareAndSet(null, cleanup);
                    }
                }
            }, "detector-test-worker");
            worker.currentRegion = writer;
            worker.start();
            worker.join(10_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get(), () -> "detector probe failed: " + failure.get());
            return delta[0];
        } finally {
            levels.remove(level);
            FabroliaConfig.fabroliaDebugRegionAccess = debug;
            field(RegionScheduler.class, "active").setBoolean(null, active);
            // Keep the production log budget of other tests intact.
            logged.set(loggedBefore);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setHolder(Region region, Thread holder) throws Exception {
        ((AtomicReference<Object>) field(Region.class, "currentWorker").get(region)).set(holder);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
