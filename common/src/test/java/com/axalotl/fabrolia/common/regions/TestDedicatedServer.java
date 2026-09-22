package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.util.thread.BlockableEventLoop;

import java.lang.reflect.Field;

/** Native server shell for Unsafe-allocated fixtures; unit tests do not apply the server mixin. */
public final class TestDedicatedServer extends DedicatedServer
        implements RegionalPlayerEntityTicksV2.PhysicalServerThreadAccess,
        RconCommandFenceScope.NativeBlockingDepth {
    int nativeIdleWaits;
    private static final Field BLOCKING_COUNT;
    private boolean nativeDeadlineSet;
    private long nativeDeadline;

    static {
        try {
            BLOCKING_COUNT = BlockableEventLoop.class.getDeclaredField("blockingCount");
            BLOCKING_COUNT.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private TestDedicatedServer() {
        super(null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public Thread fabrolia$physicalServerThread() {
        return super.getRunningThread();
    }

    @Override
    protected void waitForTasks() {
        nativeIdleWaits++;
        super.waitForTasks();
    }

    @Override
    public int fabrolia$nativeBlockingDepth() {
        try {
            return BLOCKING_COUNT.getInt(this);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    /** General fixtures leave the deadline open; deadline tests set it explicitly. */
    @Override
    public long getNextTickTime() {
        return nativeDeadlineSet ? nativeDeadline : Long.MAX_VALUE;
    }

    void setNativeTickDeadline(long deadline) {
        nativeDeadline = deadline;
        nativeDeadlineSet = true;
    }
}
