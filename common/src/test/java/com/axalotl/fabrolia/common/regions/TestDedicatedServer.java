package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.dedicated.DedicatedServer;

/** Native server shell for Unsafe-allocated fixtures; unit tests do not apply the server mixin. */
public final class TestDedicatedServer extends DedicatedServer {
    private boolean nativeDeadlineSet;
    private long nativeDeadline;

    private TestDedicatedServer() {
        super(null, null, null, null, null, null, null, null, null, null);
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
