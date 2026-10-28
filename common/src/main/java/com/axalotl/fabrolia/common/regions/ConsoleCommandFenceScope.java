package com.axalotl.fabrolia.common.regions;

/**
 * Zero-hot-allocation provenance ledger for the clean dedicated-console
 * command root. A grant requires the complete exact 26.2 chain:
 *
 * <ol>
 *   <li>{@code MinecraftServer.tickChildren -> tickConnection} callsite,</li>
 *   <li>the matching top-level {@code DedicatedServer.tickConnection} entry,</li>
 *   <li>{@code tickConnection -> handleConsoleInputs} callsite and matching
 *       top-level method entry,</li>
 *   <li>the exact {@code handleConsoleInputs -> performPrefixedCommand} invoke.</li>
 * </ol>
 *
 * <p>Every receipt is one-shot and identity-bound. Recursive direct method
 * calls enter at depth two (or after the one-shot was consumed) and retain the
 * bounded unknown-caller path. The command token is consumed by
 * {@code CommandsMixin} before any fast path. Catches below perform mandatory
 * frame cleanup and rethrow the exact primary Throwable; nothing is retried,
 * deferred, skipped or acknowledged.</p>
 */
public final class ConsoleCommandFenceScope {

    private static final ThreadLocal<Frame> LOCAL =
            ThreadLocal.withInitial(Frame::new);

    /* Reusable, fatal-only and stackless: no Throwable stacks are captured on
     * the per-tick root/drain path. */
    private static final IllegalStateException NESTED_ROOT = invariant(
            "nested exact dedicated tickConnection root");
    private static final IllegalStateException ROOT_NOT_ENTERED = invariant(
            "exact dedicated tickConnection root did not enter its method");
    private static final IllegalStateException ROOT_FRAME_CORRUPT = invariant(
            "exact dedicated tickConnection root frame was not closed cleanly");
    private static final IllegalStateException TICK_DEPTH_OVERFLOW = invariant(
            "dedicated tickConnection provenance depth overflow");
    private static final IllegalStateException NESTED_DRAIN = invariant(
            "nested exact dedicated console-input drain");
    private static final IllegalStateException DRAIN_NOT_ENTERED = invariant(
            "exact dedicated console-input drain did not enter its method");
    private static final IllegalStateException DRAIN_FRAME_CORRUPT = invariant(
            "exact dedicated console-input drain frame was not closed cleanly");
    private static final IllegalStateException DRAIN_DEPTH_OVERFLOW = invariant(
            "dedicated console-input provenance depth overflow");
    private static final IllegalStateException NESTED_COMMAND = invariant(
            "nested exact dedicated console command capability");
    private static final IllegalStateException COMMAND_NOT_CLAIMED = invariant(
            "exact dedicated console command capability was not claimed");
    private static final IllegalStateException COMMAND_FRAME_CORRUPT = invariant(
            "exact dedicated console command capability frame was replaced");

    private ConsoleCommandFenceScope() {}

    /** Exact outer callsite, after the player barrier and inside its W permit. */
    public static void runDedicatedTickConnection(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        requirePhysicalMain("dedicated tickConnection root");
        Frame frame = LOCAL.get();
        if (frame.rootActive) throw NESTED_ROOT;

        frame.rootServer = server;
        frame.tickEntryDepth = 0;
        frame.exactTickEntryDepth = 0;
        frame.tickEntryClaimed = false;
        frame.rootActive = true;

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        finishAndRethrow(failure, closeRoot(frame, server));
    }

    /** Every tickConnection entry is framed; only the entry immediately
     * consuming the outer root offer is exact. */
    public static void runDedicatedTickConnectionEntry(
            Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.rootActive || frame.rootServer != server) {
            body.run();
            return;
        }
        if (frame.tickEntryDepth == Integer.MAX_VALUE) throw TICK_DEPTH_OVERFLOW;

        int enteredDepth = ++frame.tickEntryDepth;
        boolean exact = enteredDepth == 1 && !frame.tickEntryClaimed;
        if (exact) {
            frame.tickEntryClaimed = true;
            frame.exactTickEntryDepth = enteredDepth;
        }

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Throwable cleanup = null;
        if (frame.tickEntryDepth != enteredDepth) cleanup = ROOT_FRAME_CORRUPT;
        frame.tickEntryDepth = enteredDepth - 1;
        if (exact) frame.exactTickEntryDepth = 0;
        finishAndRethrow(failure, cleanup);
    }

    /** Exact tickConnection callsite. Without the matching method-entry receipt
     * this executes vanilla and offers no clean command capability. */
    public static void runDedicatedConsoleInputs(Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactTickEntry(frame, server)) {
            body.run();
            return;
        }
        if (frame.drainActive) throw NESTED_DRAIN;

        frame.drainServer = server;
        frame.drainEntryDepth = 0;
        frame.exactDrainEntryDepth = 0;
        frame.drainEntryClaimed = false;
        frame.drainActive = true;

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        finishAndRethrow(failure, closeDrain(frame, server));
    }

    /** Frames every public handleConsoleInputs entry, so recursive direct calls
     * cannot borrow the active exact callsite marker. */
    public static void runDedicatedConsoleInputsEntry(
            Object server, Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!frame.drainActive || frame.drainServer != server) {
            body.run();
            return;
        }
        if (frame.drainEntryDepth == Integer.MAX_VALUE) throw DRAIN_DEPTH_OVERFLOW;

        int enteredDepth = ++frame.drainEntryDepth;
        boolean exact = enteredDepth == 1 && !frame.drainEntryClaimed;
        if (exact) {
            frame.drainEntryClaimed = true;
            frame.exactDrainEntryDepth = enteredDepth;
        }

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Throwable cleanup = null;
        if (frame.drainEntryDepth != enteredDepth) cleanup = DRAIN_FRAME_CORRUPT;
        frame.drainEntryDepth = enteredDepth - 1;
        if (exact) frame.exactDrainEntryDepth = 0;
        finishAndRethrow(failure, cleanup);
    }

    /** Exact innermost invoke. Direct/public/player paths run unchanged; the
     * separate authenticated RCON topology has its own provenance ledger. */
    public static void runDedicatedConsolePrefixedCommand(
            Object server, Object commands, Object source, Object command,
            Runnable body) {
        if (body == null) throw new NullPointerException("body");
        Frame frame = LOCAL.get();
        if (!inExactDrainEntry(frame, server)) {
            body.run();
            return;
        }
        if (frame.commandActive) throw NESTED_COMMAND;

        frame.commandReceiver = commands;
        frame.commandSource = source;
        frame.commandText = command;
        frame.commandClaimed = false;
        frame.commandActive = true;

        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        Throwable cleanup = null;
        if (!frame.commandActive
                || frame.commandReceiver != commands
                || frame.commandSource != source
                || frame.commandText != command) {
            cleanup = COMMAND_FRAME_CORRUPT;
        } else if (!frame.commandClaimed) {
            cleanup = COMMAND_NOT_CLAIMED;
        }
        clearCommand(frame);
        finishAndRethrow(failure, cleanup);
    }

    /** First operation of CommandsMixin's prefixed wrapper. */
    public static boolean claimDedicatedConsolePrefixedCommand(
            Object commands, Object source, Object command) {
        Frame frame = LOCAL.get();
        if (!frame.commandActive
                || frame.commandClaimed
                || frame.commandReceiver != commands
                || frame.commandSource != source
                || frame.commandText != command) {
            return false;
        }
        frame.commandClaimed = true;
        return true;
    }

    private static boolean inExactTickEntry(Frame frame, Object server) {
        return frame.rootActive
                && frame.rootServer == server
                && frame.exactTickEntryDepth != 0
                && frame.tickEntryDepth == frame.exactTickEntryDepth;
    }

    private static boolean inExactDrainEntry(Frame frame, Object server) {
        return frame.drainActive
                && frame.drainServer == server
                && frame.exactDrainEntryDepth != 0
                && frame.drainEntryDepth == frame.exactDrainEntryDepth;
    }

    private static Throwable closeDrain(Frame frame, Object server) {
        Throwable cleanup = null;
        if (!frame.drainActive || frame.drainServer != server) {
            cleanup = DRAIN_FRAME_CORRUPT;
        } else if (!frame.drainEntryClaimed) {
            cleanup = DRAIN_NOT_ENTERED;
        } else if (frame.drainEntryDepth != 0
                || frame.exactDrainEntryDepth != 0 || frame.commandActive) {
            cleanup = DRAIN_FRAME_CORRUPT;
        }
        frame.drainActive = false;
        frame.drainServer = null;
        frame.drainEntryDepth = 0;
        frame.exactDrainEntryDepth = 0;
        frame.drainEntryClaimed = false;
        clearCommand(frame);
        return cleanup;
    }

    private static Throwable closeRoot(Frame frame, Object server) {
        Throwable cleanup = null;
        if (!frame.rootActive || frame.rootServer != server) {
            cleanup = ROOT_FRAME_CORRUPT;
        } else if (!frame.tickEntryClaimed) {
            cleanup = ROOT_NOT_ENTERED;
        } else if (frame.tickEntryDepth != 0
                || frame.exactTickEntryDepth != 0
                || frame.drainActive || frame.commandActive) {
            cleanup = ROOT_FRAME_CORRUPT;
        }
        frame.rootActive = false;
        frame.rootServer = null;
        frame.tickEntryDepth = 0;
        frame.exactTickEntryDepth = 0;
        frame.tickEntryClaimed = false;
        frame.drainActive = false;
        frame.drainServer = null;
        frame.drainEntryDepth = 0;
        frame.exactDrainEntryDepth = 0;
        frame.drainEntryClaimed = false;
        clearCommand(frame);
        return cleanup;
    }

    private static void clearCommand(Frame frame) {
        frame.commandActive = false;
        frame.commandClaimed = false;
        frame.commandReceiver = null;
        frame.commandSource = null;
        frame.commandText = null;
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
            try {
                failure.addSuppressed(cleanup);
            } catch (Throwable suppressionFailure) {
                // Cleanup diagnostics cannot replace the exact primary; this
                // method never returns normally with either failure present.
            }
        }
        if (failure != null) {
            FailStops.<RuntimeException>rethrow(failure);
        }
    }

    private static IllegalStateException invariant(String message) {
        return new StacklessInvariantFailure(message);
    }

    private static final class Frame {
        boolean rootActive;
        Object rootServer;
        int tickEntryDepth;
        int exactTickEntryDepth;
        boolean tickEntryClaimed;

        boolean drainActive;
        Object drainServer;
        int drainEntryDepth;
        int exactDrainEntryDepth;
        boolean drainEntryClaimed;

        boolean commandActive;
        boolean commandClaimed;
        Object commandReceiver;
        Object commandSource;
        Object commandText;
    }

    private static final class StacklessInvariantFailure
            extends IllegalStateException {
        StacklessInvariantFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
