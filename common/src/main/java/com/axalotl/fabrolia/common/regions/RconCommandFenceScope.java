package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.ReentrantBlockableEventLoopAccessor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Exact provenance for the vanilla RCON command path.
 *
 * <p>An unbounded command fence is offered only when two independent receipts
 * meet on physical Main:</p>
 *
 * <ol>
 *   <li>the command came through Minecraft 26.2's exact
 *       {@code RconClient.run -> DedicatedServer.runCommand -> executeBlocking}
 *       chain, and</li>
 *   <li>that exact queued task is being executed at depth one by the
 *       monitor-free {@code runServer -> waitUntilNextTick} task drain.</li>
 * </ol>
 *
 * <p>A task pumped by a nested {@code managedBlock} keeps the bounded unknown
 * caller path. Every capability is identity-bound and one-shot. The methods
 * below use {@code finally} only to retire provenance frames; no command body is
 * caught, retried, replayed, skipped, or acknowledged early.</p>
 */
public final class RconCommandFenceScope {

    private static final int DRAIN_RUN_ALL_TASKS = 1;
    private static final int DRAIN_MANAGED_BLOCK = 2;

    private static final ThreadLocal<Frame> LOCAL =
            ThreadLocal.withInitial(Frame::new);

    private RconCommandFenceScope() {}

    /** Exact authenticated RconClient callsite on the RCON client thread. */
    public static <T> T runRconClientCommand(
            Object server, Object command, Supplier<T> body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (frame.originActive) {
            throw fatalInvariant("nested exact RCON client command root");
        }

        frame.originActive = true;
        frame.originServer = server;
        frame.originCommand = command;
        frame.runCommandDepth = 0;
        frame.exactRunCommandDepth = 0;
        frame.runCommandClaimed = false;
        frame.executeTaskWrapped = false;

        boolean returned = false;
        try {
            T result = body.get();
            returned = true;
            return result;
        } finally {
            String invariant = null;
            if (returned) {
                if (!frame.originActive
                        || frame.originServer != server
                        || frame.originCommand != command) {
                    invariant = "exact RCON client root frame was replaced";
                } else if (!frame.runCommandClaimed) {
                    invariant = "exact RCON client root did not enter runCommand";
                } else if (!frame.executeTaskWrapped
                        || frame.runCommandDepth != 0
                        || frame.exactRunCommandDepth != 0) {
                    invariant = "exact RCON client root did not publish its Main task";
                }
            }
            clearOrigin(frame);
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Frames every public DedicatedServer.runCommand entry. */
    public static <T> T runDedicatedRconCommandEntry(
            Object server, Object command, Supplier<T> body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.originActive
                || frame.originServer != server) {
            return body.get();
        }
        if (frame.runCommandDepth == Integer.MAX_VALUE) {
            throw fatalInvariant("RCON runCommand provenance depth overflow");
        }

        int enteredDepth = ++frame.runCommandDepth;
        // Every same-server entry participates in the depth ledger, including
        // a recursive/mod-mediated runCommand carrying a different String.
        // Otherwise that nested entry reaches the shared executeBlocking
        // callsite while the outer exact depth is still visible and can steal
        // the outer command's one-shot task wrapper.
        boolean exact = enteredDepth == 1
                && frame.originCommand == command
                && !frame.runCommandClaimed;
        if (exact) {
            frame.runCommandClaimed = true;
            frame.exactRunCommandDepth = enteredDepth;
        }

        boolean returned = false;
        try {
            T result = body.get();
            returned = true;
            return result;
        } finally {
            String invariant = null;
            if (returned && frame.runCommandDepth != enteredDepth) {
                invariant = "RCON runCommand entry depth was corrupted";
            } else if (returned && exact && !frame.executeTaskWrapped) {
                invariant = "exact RCON runCommand did not wrap executeBlocking";
            }
            frame.runCommandDepth = enteredDepth - 1;
            if (exact) frame.exactRunCommandDepth = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /**
     * Exact executeBlocking edge. Unknown/direct/recursive runCommand calls keep
     * their original Runnable unchanged.
     */
    public static Runnable wrapDedicatedRconExecuteTask(
            Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactRunCommandEntry(frame, server)) return body;
        if (frame.executeTaskWrapped) {
            throw fatalInvariant("exact RCON executeBlocking task was wrapped twice");
        }
        frame.executeTaskWrapped = true;
        return new ExactRconMainTask(server, frame.originCommand, body);
    }

    /** Exact MinecraftServer spin lambda callsite. */
    public static void runServerLoopRoot(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        requirePhysicalMain("MinecraftServer runServer root");
        Frame frame = LOCAL.get();
        if (frame.serverLoopRootActive) {
            throw fatalInvariant("nested exact MinecraftServer runServer root");
        }

        frame.serverLoopRootActive = true;
        frame.serverLoopServer = server;
        frame.runServerDepth = 0;
        frame.exactRunServerDepth = 0;
        frame.runServerClaimed = false;

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned) {
                if (!frame.serverLoopRootActive
                        || frame.serverLoopServer != server) {
                    invariant = "exact MinecraftServer runServer root was replaced";
                } else if (!frame.runServerClaimed) {
                    invariant = "exact MinecraftServer runServer root did not enter";
                } else if (frame.runServerDepth != 0
                        || frame.exactRunServerDepth != 0
                        || frame.waitRootActive) {
                    invariant = "exact MinecraftServer runServer root did not close cleanly";
                }
            }
            clearServerLoop(frame);
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Frames every runServer entry; recursive/direct entries cannot offer waits. */
    public static void runServerEntry(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.serverLoopRootActive || frame.serverLoopServer != server) {
            body.run();
            return;
        }
        if (frame.runServerDepth == Integer.MAX_VALUE) {
            throw fatalInvariant("runServer provenance depth overflow");
        }

        int enteredDepth = ++frame.runServerDepth;
        boolean exact = enteredDepth == 1 && !frame.runServerClaimed;
        if (exact) {
            frame.runServerClaimed = true;
            frame.exactRunServerDepth = enteredDepth;
        }
        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned && frame.runServerDepth != enteredDepth) {
                invariant = "runServer entry depth was corrupted";
            }
            frame.runServerDepth = enteredDepth - 1;
            if (exact) frame.exactRunServerDepth = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Exact runServer callsite which offers one top-level wait receipt. */
    public static void runServerWaitUntilNextTick(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactRunServerEntry(frame, server)) {
            body.run();
            return;
        }
        requirePhysicalMain("runServer waitUntilNextTick root");
        if (frame.waitRootActive) {
            throw fatalInvariant("nested exact runServer waitUntilNextTick root");
        }

        frame.waitRootActive = true;
        frame.waitServer = server;
        frame.waitEntryDepth = 0;
        frame.exactWaitEntryDepth = 0;
        frame.waitEntryClaimed = false;
        frame.runAllTasksClaimed = false;
        frame.managedBlockClaimed = false;

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned) {
                if (!frame.waitRootActive || frame.waitServer != server) {
                    invariant = "exact runServer wait root frame was replaced";
                } else if (!frame.waitEntryClaimed) {
                    invariant = "exact runServer wait root did not enter its method";
                } else if (!frame.runAllTasksClaimed
                        || !frame.managedBlockClaimed
                        || frame.waitEntryDepth != 0
                        || frame.exactWaitEntryDepth != 0
                        || frame.cleanDrainActive
                        || frame.rconMainTaskActive) {
                    invariant = "exact runServer wait root did not close cleanly";
                }
            }
            clearWait(frame);
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Frames every waitUntilNextTick entry; only the offered depth-one entry is exact. */
    public static void runWaitUntilNextTickEntry(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.waitRootActive || frame.waitServer != server) {
            body.run();
            return;
        }
        if (frame.waitEntryDepth == Integer.MAX_VALUE) {
            throw fatalInvariant("waitUntilNextTick provenance depth overflow");
        }

        int enteredDepth = ++frame.waitEntryDepth;
        boolean exact = enteredDepth == 1 && !frame.waitEntryClaimed;
        if (exact) {
            frame.waitEntryClaimed = true;
            frame.exactWaitEntryDepth = enteredDepth;
        }

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned && frame.waitEntryDepth != enteredDepth) {
                invariant = "waitUntilNextTick entry depth was corrupted";
            }
            frame.waitEntryDepth = enteredDepth - 1;
            if (exact) frame.exactWaitEntryDepth = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Exact first task drain in waitUntilNextTick. */
    public static void runWaitUntilNextTickRunAllTasks(
            Object server, Runnable body) {
        runCleanDrain(server, DRAIN_RUN_ALL_TASKS, body);
    }

    /** Exact top-level managedBlock drain in waitUntilNextTick. */
    public static void runWaitUntilNextTickManagedBlock(
            Object server, Runnable body) {
        runCleanDrain(server, DRAIN_MANAGED_BLOCK, body);
    }

    /** Exact private lambda entry on physical Main. */
    public static void runDedicatedRconLambdaEntry(
            Object server, Object command, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.rconMainTaskActive
                || frame.rconMainTaskServer != server
                || frame.rconMainTaskCommand != command) {
            body.run();
            return;
        }
        if (frame.rconLambdaDepth == Integer.MAX_VALUE) {
            throw fatalInvariant("RCON lambda provenance depth overflow");
        }

        int enteredDepth = ++frame.rconLambdaDepth;
        boolean exact = enteredDepth == 1 && !frame.rconLambdaClaimed;
        if (exact) {
            frame.rconLambdaClaimed = true;
            frame.exactRconLambdaDepth = enteredDepth;
        }

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned && frame.rconLambdaDepth != enteredDepth) {
                invariant = "RCON lambda entry depth was corrupted";
            } else if (returned && exact && !frame.rconPrefixedEdgeClaimed) {
                invariant = "exact RCON lambda did not enter its command edge";
            }
            frame.rconLambdaDepth = enteredDepth - 1;
            if (exact) frame.exactRconLambdaDepth = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** Exact lambda -> Commands.performPrefixedCommand invoke. */
    public static void runDedicatedRconPrefixedCommand(
            Object server, Object commands, Object source, Object command,
            Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactRconLambdaEntry(frame, server, command)) {
            body.run();
            return;
        }
        if (frame.rconPrefixedActive) {
            throw fatalInvariant("nested exact RCON prefixed-command capability");
        }

        frame.rconPrefixedEdgeClaimed = true;
        frame.rconPrefixedActive = true;
        frame.rconPrefixedClaimed = false;
        frame.rconCommands = commands;
        frame.rconSource = source;
        frame.rconInvokeCommand = command;

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned) {
                if (!frame.rconPrefixedActive
                        || frame.rconCommands != commands
                        || frame.rconSource != source
                        || frame.rconInvokeCommand != command) {
                    invariant = "exact RCON prefixed-command frame was replaced";
                } else if (!frame.rconPrefixedClaimed) {
                    invariant = "exact RCON prefixed-command capability was not claimed";
                }
            }
            clearRconPrefixed(frame);
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /** First operation of CommandsMixin's prefixed wrapper. */
    public static boolean claimDedicatedRconPrefixedCommand(
            Object commands, Object source, Object command) {
        Frame frame = LOCAL.get();
        if (!frame.rconPrefixedActive
                || frame.rconPrefixedClaimed
                || frame.rconCommands != commands
                || frame.rconSource != source
                || frame.rconInvokeCommand != command) {
            return false;
        }
        frame.rconPrefixedClaimed = true;
        return true;
    }

    private static void runCleanDrain(
            Object server, int kind, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactWaitEntry(frame, server)) {
            body.run();
            return;
        }
        if (frame.cleanDrainActive) {
            throw fatalInvariant("nested exact top-level Main task drain");
        }
        if (kind == DRAIN_RUN_ALL_TASKS) {
            if (frame.runAllTasksClaimed) {
                throw fatalInvariant("exact waitUntilNextTick runAllTasks edge repeated");
            }
            frame.runAllTasksClaimed = true;
        } else {
            if (frame.managedBlockClaimed) {
                throw fatalInvariant("exact waitUntilNextTick managedBlock edge repeated");
            }
            frame.managedBlockClaimed = true;
        }

        frame.cleanDrainActive = true;
        frame.cleanDrainServer = server;
        frame.cleanDrainKind = kind;
        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned && (!frame.cleanDrainActive
                    || frame.cleanDrainServer != server
                    || frame.cleanDrainKind != kind)) {
                invariant = "exact top-level Main task drain frame was replaced";
            }
            frame.cleanDrainActive = false;
            frame.cleanDrainServer = null;
            frame.cleanDrainKind = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    /**
     * Read-only proof that the current task is running at depth one in the exact
     * monitor-free server wait drain. This does not create, consume, or otherwise
     * mutate any RCON provenance.
     */
    static boolean exactMonitorFreeMainTask(Object server) {
        Frame frame = LOCAL.get();
        if (!frame.cleanDrainActive
                || frame.cleanDrainServer != server) return false;
        var physicalServer = RegionScheduler.getServer();
        if (physicalServer != null
                && (physicalServer != server
                || !RegionalPlayerEntityTicksV2.isPhysicalServerThread(physicalServer))) {
            return false;
        }
        return server instanceof ReentrantBlockableEventLoopAccessor accessor
                && accessor.fabrolia$getReentrantCount() == 1;
    }

    static void runExactRconMainTask(
            Object server, Object command, int reentrantDepth, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.cleanDrainActive
                || frame.cleanDrainServer != server
                || reentrantDepth != 1) {
            body.run();
            return;
        }
        requirePhysicalMain("exact RCON Main task");
        if (frame.rconMainTaskActive) {
            throw fatalInvariant("nested exact RCON Main task capability");
        }

        frame.rconMainTaskActive = true;
        frame.rconMainTaskServer = server;
        frame.rconMainTaskCommand = command;
        frame.rconLambdaDepth = 0;
        frame.exactRconLambdaDepth = 0;
        frame.rconLambdaClaimed = false;
        frame.rconPrefixedEdgeClaimed = false;

        boolean returned = false;
        try {
            body.run();
            returned = true;
        } finally {
            String invariant = null;
            if (returned) {
                if (!frame.rconMainTaskActive
                        || frame.rconMainTaskServer != server
                        || frame.rconMainTaskCommand != command) {
                    invariant = "exact RCON Main task frame was replaced";
                } else if (!frame.rconLambdaClaimed
                        || !frame.rconPrefixedEdgeClaimed
                        || frame.rconLambdaDepth != 0
                        || frame.exactRconLambdaDepth != 0
                        || frame.rconPrefixedActive) {
                    invariant = "exact RCON Main task did not close its command chain";
                }
            }
            clearRconMainTask(frame);
            if (invariant != null) throw fatalInvariant(invariant);
        }
    }

    private static boolean inExactRunCommandEntry(Frame frame, Object server) {
        return frame.originActive
                && frame.originServer == server
                && frame.exactRunCommandDepth != 0
                && frame.runCommandDepth == frame.exactRunCommandDepth;
    }

    private static boolean inExactRunServerEntry(Frame frame, Object server) {
        return frame.serverLoopRootActive
                && frame.serverLoopServer == server
                && frame.exactRunServerDepth != 0
                && frame.runServerDepth == frame.exactRunServerDepth;
    }

    private static boolean inExactWaitEntry(Frame frame, Object server) {
        return frame.waitRootActive
                && frame.waitServer == server
                && frame.exactWaitEntryDepth != 0
                && frame.waitEntryDepth == frame.exactWaitEntryDepth;
    }

    private static boolean inExactRconLambdaEntry(
            Frame frame, Object server, Object command) {
        return frame.rconMainTaskActive
                && frame.rconMainTaskServer == server
                && frame.rconMainTaskCommand == command
                && frame.exactRconLambdaDepth != 0
                && frame.rconLambdaDepth == frame.exactRconLambdaDepth
                && !frame.rconPrefixedEdgeClaimed;
    }

    private static void clearOrigin(Frame frame) {
        frame.originActive = false;
        frame.originServer = null;
        frame.originCommand = null;
        frame.runCommandDepth = 0;
        frame.exactRunCommandDepth = 0;
        frame.runCommandClaimed = false;
        frame.executeTaskWrapped = false;
    }

    private static void clearWait(Frame frame) {
        frame.waitRootActive = false;
        frame.waitServer = null;
        frame.waitEntryDepth = 0;
        frame.exactWaitEntryDepth = 0;
        frame.waitEntryClaimed = false;
        frame.runAllTasksClaimed = false;
        frame.managedBlockClaimed = false;
        frame.cleanDrainActive = false;
        frame.cleanDrainServer = null;
        frame.cleanDrainKind = 0;
        clearRconMainTask(frame);
    }

    private static void clearServerLoop(Frame frame) {
        frame.serverLoopRootActive = false;
        frame.serverLoopServer = null;
        frame.runServerDepth = 0;
        frame.exactRunServerDepth = 0;
        frame.runServerClaimed = false;
        clearWait(frame);
    }

    private static void clearRconMainTask(Frame frame) {
        frame.rconMainTaskActive = false;
        frame.rconMainTaskServer = null;
        frame.rconMainTaskCommand = null;
        frame.rconLambdaDepth = 0;
        frame.exactRconLambdaDepth = 0;
        frame.rconLambdaClaimed = false;
        frame.rconPrefixedEdgeClaimed = false;
        clearRconPrefixed(frame);
    }

    private static void clearRconPrefixed(Frame frame) {
        frame.rconPrefixedActive = false;
        frame.rconPrefixedClaimed = false;
        frame.rconCommands = null;
        frame.rconSource = null;
        frame.rconInvokeCommand = null;
    }

    private static void requirePhysicalMain(String operation) {
        var server = RegionScheduler.getServer();
        if (server != null
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw fatalInvariant(operation + " entered off physical Main");
        }
    }

    private static RuntimeException fatalInvariant(String message) {
        IllegalStateException failure = new IllegalStateException(message);
        if (RegionScheduler.isActive()) {
            IslandCommands.publishAndPropagate(failure);
        }
        return failure;
    }

    private static final class ExactRconMainTask implements Runnable {
        private final Object server;
        private final Object command;
        private final Runnable body;
        private final AtomicBoolean entered = new AtomicBoolean();

        private ExactRconMainTask(Object server, Object command, Runnable body) {
            this.server = server;
            this.command = command;
            this.body = body;
        }

        @Override
        public void run() {
            if (!entered.compareAndSet(false, true)) {
                throw fatalInvariant("exact RCON executeBlocking task entered twice");
            }
            if (!(server instanceof ReentrantBlockableEventLoopAccessor accessor)) {
                throw fatalInvariant("MinecraftServer is missing its reentrant-depth accessor");
            }
            runExactRconMainTask(
                    server, command, accessor.fabrolia$getReentrantCount(), body);
        }
    }

    private static final class Frame {
        boolean originActive;
        Object originServer;
        Object originCommand;
        int runCommandDepth;
        int exactRunCommandDepth;
        boolean runCommandClaimed;
        boolean executeTaskWrapped;

        boolean serverLoopRootActive;
        Object serverLoopServer;
        int runServerDepth;
        int exactRunServerDepth;
        boolean runServerClaimed;

        boolean waitRootActive;
        Object waitServer;
        int waitEntryDepth;
        int exactWaitEntryDepth;
        boolean waitEntryClaimed;
        boolean runAllTasksClaimed;
        boolean managedBlockClaimed;

        boolean cleanDrainActive;
        Object cleanDrainServer;
        int cleanDrainKind;

        boolean rconMainTaskActive;
        Object rconMainTaskServer;
        Object rconMainTaskCommand;
        int rconLambdaDepth;
        int exactRconLambdaDepth;
        boolean rconLambdaClaimed;
        boolean rconPrefixedEdgeClaimed;

        boolean rconPrefixedActive;
        boolean rconPrefixedClaimed;
        Object rconCommands;
        Object rconSource;
        Object rconInvokeCommand;
    }
}
