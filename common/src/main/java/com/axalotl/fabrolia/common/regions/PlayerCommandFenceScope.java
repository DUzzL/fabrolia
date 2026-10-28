package com.axalotl.fabrolia.common.regions;

/**
 * Exact one-shot provenance for a player chat command which reached its parsed
 * {@code Commands.performCommand} edge from A5's listener FIFO.
 *
 * <p>The root is opened only while A5's exact tagged Main task consumes its own
 * command packet in a proven depth-one, monitor-free task drain. The listener
 * method entry and the final invoke are framed separately, so a recursive/mod
 * command cannot borrow the outer command's unbounded fence. Server, listener,
 * packet, and parsed-command identities are compared by reference.</p>
 *
 * <p>Failure handling below only retires ThreadLocal provenance and rethrows the
 * exact primary. It never acknowledges, retries, skips, or replays a command
 * body.</p>
 */
public final class PlayerCommandFenceScope {

    private static final ThreadLocal<Frame> LOCAL =
            ThreadLocal.withInitial(Frame::new);

    private PlayerCommandFenceScope() {}

    /** Package-private: only the authenticated exact A5 Main task may open it. */
    static void runCleanMainTask(
            Object server, Object listener, Object packet, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        requirePhysicalMain("A5 player-command Main task");
        Frame frame = LOCAL.get();
        if (frame.rootActive) {
            throw new IllegalStateException("nested exact A5 player-command Main task");
        }

        frame.rootActive = true;
        frame.rootServer = server;
        frame.rootListener = listener;
        frame.rootPacket = packet;
        frame.entryDepth = 0;
        frame.exactEntryDepth = 0;
        frame.entryOffered = false;

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        finishAndRethrow(failure, closeRoot(frame, server, listener, packet));
    }

    /** Frames both private vanilla player-command method entries. */
    public static void runPlayerCommandEntry(Object listener, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.rootActive || frame.rootListener != listener) {
            body.run();
            return;
        }
        if (frame.entryDepth == Integer.MAX_VALUE) {
            throw new IllegalStateException("A5 player-command entry depth overflow");
        }

        int enteredDepth = ++frame.entryDepth;
        boolean exact = enteredDepth == 1 && !frame.entryOffered;
        if (exact) {
            frame.entryOffered = true;
            frame.exactEntryDepth = enteredDepth;
        }

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Throwable cleanup = null;
        if (frame.entryDepth != enteredDepth || (exact && frame.edgeActive)) {
            cleanup = new IllegalStateException(
                    "A5 player-command entry frame was not closed cleanly");
        }
        frame.entryDepth = enteredDepth - 1;
        if (exact) frame.exactEntryDepth = 0;
        finishAndRethrow(failure, cleanup);
    }

    /** Exact private-method callsite immediately around parsed command dispatch. */
    public static void runParsedCommandEdge(
            Object listener, Object commands, Object parsed, Object command,
            Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactEntry(frame, listener) || frame.edgeActive) {
            body.run();
            return;
        }

        frame.edgeActive = true;
        frame.edgeClaimed = false;
        frame.edgeCommands = commands;
        frame.edgeParsed = parsed;
        frame.edgeCommand = command;

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Throwable cleanup = null;
        if (!frame.edgeActive
                || frame.edgeCommands != commands
                || frame.edgeParsed != parsed
                || frame.edgeCommand != command) {
            cleanup = new IllegalStateException(
                    "A5 player parsed-command capability frame was replaced");
        } else if (!frame.edgeClaimed) {
            cleanup = new IllegalStateException(
                    "A5 player parsed-command capability was not claimed");
        }
        clearEdge(frame);
        finishAndRethrow(failure, cleanup);
    }

    /** First operation of the parsed CommandsMixin wrapper. */
    public static boolean claimParsedCommand(
            Object commands, Object parsed, Object command) {
        Frame frame = LOCAL.get();
        if (!frame.edgeActive || frame.edgeClaimed
                || frame.edgeCommands != commands
                || frame.edgeParsed != parsed
                || frame.edgeCommand != command) {
            return false;
        }
        frame.edgeClaimed = true;
        return true;
    }

    static boolean cleanRootActive(Object listener, Object packet) {
        Frame frame = LOCAL.get();
        return frame.rootActive
                && frame.rootListener == listener
                && frame.rootPacket == packet;
    }

    private static boolean inExactEntry(Frame frame, Object listener) {
        return frame.rootActive
                && frame.rootListener == listener
                && frame.exactEntryDepth != 0
                && frame.entryDepth == frame.exactEntryDepth;
    }

    private static Throwable closeRoot(
            Frame frame, Object server, Object listener, Object packet) {
        Throwable cleanup = null;
        if (!frame.rootActive
                || frame.rootServer != server
                || frame.rootListener != listener
                || frame.rootPacket != packet
                || !frame.entryOffered
                || frame.entryDepth != 0
                || frame.exactEntryDepth != 0
                || frame.edgeActive) {
            cleanup = new IllegalStateException(
                    "exact A5 player-command Main task was not closed cleanly");
        }
        frame.rootActive = false;
        frame.rootServer = null;
        frame.rootListener = null;
        frame.rootPacket = null;
        frame.entryDepth = 0;
        frame.exactEntryDepth = 0;
        frame.entryOffered = false;
        clearEdge(frame);
        return cleanup;
    }

    private static void clearEdge(Frame frame) {
        frame.edgeActive = false;
        frame.edgeClaimed = false;
        frame.edgeCommands = null;
        frame.edgeParsed = null;
        frame.edgeCommand = null;
    }

    private static void requirePhysicalMain(String operation) {
        var server = RegionScheduler.getServer();
        if (server != null
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException(operation + " entered off physical Main");
        }
    }

    private static void finishAndRethrow(Throwable failure, Throwable cleanup) {
        if (failure == null) {
            failure = cleanup;
        } else if (cleanup != null && cleanup != failure) {
            failure.addSuppressed(cleanup);
        }
        if (failure != null) {
            FailStops.<RuntimeException>rethrow(failure);
        }
    }

    private static final class Frame {
        boolean rootActive;
        Object rootServer;
        Object rootListener;
        Object rootPacket;

        int entryDepth;
        int exactEntryDepth;
        boolean entryOffered;

        boolean edgeActive;
        boolean edgeClaimed;
        Object edgeCommands;
        Object edgeParsed;
        Object edgeCommand;
    }
}
