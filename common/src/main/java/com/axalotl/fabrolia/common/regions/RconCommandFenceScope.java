package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.server.ReentrantBlockableEventLoopAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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

    /** The declaration-level loop exposes its real nesting count, not authority. */
    public interface NativeBlockingDepth {
        int fabrolia$nativeBlockingDepth();
    }

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
        frame.publishedGameplayServiced = false;

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

    /** Marks only the literal runAllTasks-to-pollTask invocation, not its nested polls. */
    public static boolean callRunAllTasksPoll(Object server, BooleanSupplier body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactRunAllTasksDrain(frame, server)
                || frame.runAllPollDepth != 0
                || frame.mainPollDepth != 0) {
            return body.getAsBoolean();
        }
        frame.runAllPollDepth = 1;
        frame.runAllPollClaimed = false;
        try {
            return body.getAsBoolean();
        } finally {
            frame.runAllPollDepth = 0;
            frame.runAllPollClaimed = false;
            frame.exactRunAllPollDepth = 0;
        }
    }

    /** Receipt for the literal outer managedBlock-to-pollTask invocation only. */
    public static boolean callManagedBlockPoll(Object server, BooleanSupplier body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactManagedBlockDrain(frame, server)
                || frame.managedPollDepth != 0 || frame.mainPollDepth != 0) {
            return body.getAsBoolean();
        }
        frame.managedPollDepth = 1;
        frame.managedPollClaimed = false;
        try {
            return body.getAsBoolean();
        } finally {
            frame.managedPollDepth = 0;
            frame.managedPollClaimed = false;
        }
    }

    /** Every Main poll entry consumes at most its own one-shot callsite receipt. */
    public static boolean callMainPoll(Object server, BooleanSupplier body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (frame.mainPollDepth == Integer.MAX_VALUE) {
            throw fatalInvariant("Main poll provenance depth overflow");
        }
        int enteredDepth = ++frame.mainPollDepth;
        boolean exact = frame.runAllPollDepth == enteredDepth
                && !frame.runAllPollClaimed
                && inExactRunAllTasksDrain(frame, server);
        if (exact) {
            frame.runAllPollClaimed = true;
            frame.exactRunAllPollDepth = enteredDepth;
        }
        boolean exactManaged = frame.managedPollDepth == enteredDepth
                && !frame.managedPollClaimed && inExactManagedBlockDrain(frame, server);
        if (exactManaged) {
            frame.managedPollClaimed = true;
            frame.exactManagedPollDepth = enteredDepth;
        }
        try {
            return body.getAsBoolean();
        } finally {
            frame.mainPollDepth = enteredDepth - 1;
            if (exact) frame.exactRunAllPollDepth = 0;
            if (exactManaged) frame.exactManagedPollDepth = 0;
        }
    }

    /** Provenance only: this does not confer command or gameplay authority. */
    public static boolean exactRunAllTasksPoll(Object server) {
        Frame frame = LOCAL.get();
        return frame.exactRunAllPollDepth != 0
                && frame.mainPollDepth == frame.exactRunAllPollDepth
                && inExactRunAllTasksDrain(frame, server);
    }

    static boolean exactManagedBlockPoll(Object server) {
        Frame frame = LOCAL.get();
        return frame.exactManagedPollDepth != 0
                && frame.mainPollDepth == frame.exactManagedPollDepth
                && inExactManagedBlockDrain(frame, server);
    }

    static boolean mayServicePublishedGameplay(MinecraftServer server) {
        Frame frame = LOCAL.get();
        if (!frame.waitRootActive || frame.waitServer != server) {
            throw fatalInvariant("optional gameplay service escaped its native wait root");
        }
        return !frame.publishedGameplayServiced || Util.getNanos() < server.getNextTickTime();
    }

    static void markPublishedGameplayServiced(MinecraftServer server) {
        Frame frame = LOCAL.get();
        if (!frame.waitRootActive || frame.waitServer != server) {
            throw fatalInvariant("optional gameplay grant escaped its native wait root");
        }
        frame.publishedGameplayServiced = true;
    }

    /**
     * Restrict only this service's outer native polls. Nested callbacks and
     * dependency waits keep their own ordinary progress protocol.
     */
    static void runNativeCheckpoint(Object server, Runnable body) {
        Frame frame = LOCAL.get();
        Object previousServer = frame.checkpointServer;
        int previousDepth = frame.checkpointPollDepth;
        frame.checkpointServer = server;
        frame.checkpointPollDepth = frame.mainPollDepth + 1;
        try {
            body.run();
        } finally {
            frame.checkpointServer = previousServer;
            frame.checkpointPollDepth = previousDepth;
        }
    }

    static boolean isNativeCheckpointPoll(Object server) {
        Frame frame = LOCAL.get();
        return frame.checkpointServer == server
                && frame.checkpointPollDepth == frame.mainPollDepth;
    }

    private static boolean inExactRunAllTasksDrain(Frame frame, Object server) {
        if (!frame.cleanDrainActive
                || frame.cleanDrainServer != server
                || frame.cleanDrainKind != DRAIN_RUN_ALL_TASKS
                || !inExactWaitEntry(frame, server)) return false;
        var physicalServer = RegionScheduler.getServer();
        return physicalServer == null || (physicalServer == server
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(physicalServer));
    }

    private static boolean inExactManagedBlockDrain(Frame frame, Object server) {
        if (!frame.cleanDrainActive || frame.cleanDrainServer != server
                || frame.cleanDrainKind != DRAIN_MANAGED_BLOCK
                || !inExactWaitEntry(frame, server)
                || !(server instanceof NativeBlockingDepth depth)
                || depth.fabrolia$nativeBlockingDepth() != frame.managedBlockingDepth) return false;
        var physicalServer = RegionScheduler.getServer();
        return physicalServer == null || (physicalServer == server
                && RegionalPlayerEntityTicksV2.isPhysicalServerThread(physicalServer));
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
            if (server instanceof NativeBlockingDepth depth) {
                int baseline = depth.fabrolia$nativeBlockingDepth();
                if (baseline < 0 || baseline == Integer.MAX_VALUE) {
                    throw fatalInvariant("invalid native managedBlock nesting count");
                }
                // Vanilla increments before evaluating even its first predicate.
                frame.managedBlockingDepth = baseline + 1;
            } else if (server instanceof MinecraftServer) {
                throw fatalInvariant("MinecraftServer is missing its native blocking-depth accessor");
            }
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
                    || frame.cleanDrainKind != kind
                    || frame.mainPollDepth != 0
                    || frame.runAllPollDepth != 0
                    || frame.exactRunAllPollDepth != 0
                    || frame.managedPollDepth != 0
                    || frame.exactManagedPollDepth != 0)) {
                invariant = "exact top-level Main task drain frame was replaced";
            }
            frame.cleanDrainActive = false;
            frame.cleanDrainServer = null;
            frame.cleanDrainKind = 0;
            frame.managedBlockingDepth = 0;
            if (invariant != null) throw fatalInvariant(invariant);
        }
        // An overdue tick may skip managedBlock and already-complete barriers.
        // Finish the published-control prefix, then budget whole gameplay grants.
        // Neither service inherits the retired native-drain provenance.
        var physicalServer = RegionScheduler.getServer();
        if (kind == DRAIN_RUN_ALL_TASKS
                && physicalServer != null && physicalServer == server) {
            RegionScheduler.throwWorkerFailureIfAny();
            RegionScheduler.servicePublishedMainGameplay(physicalServer);
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
        frame.publishedGameplayServiced = false;
        frame.managedBlockingDepth = 0;
        frame.managedPollDepth = 0;
        frame.managedPollClaimed = false;
        frame.exactManagedPollDepth = 0;
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
        boolean publishedGameplayServiced;
        int managedBlockingDepth;
        int managedPollDepth;
        boolean managedPollClaimed;
        int exactManagedPollDepth;

        boolean cleanDrainActive;
        Object cleanDrainServer;
        int cleanDrainKind;

        int mainPollDepth;
        int runAllPollDepth;
        boolean runAllPollClaimed;
        int exactRunAllPollDepth;
        Object checkpointServer;
        int checkpointPollDepth;

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
