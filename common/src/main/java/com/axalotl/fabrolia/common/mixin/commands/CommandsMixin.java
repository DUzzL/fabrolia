package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.ConsoleCommandFenceScope;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.PlayerCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RconCommandFenceScope;
import com.axalotl.fabrolia.common.regions.RegionMainBlockingWaitGuard;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.RegionTaskFanIn;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;

/**
 * A8 — the main-context command fence. With async islands live, a console /
 * player / RCON / minecart command dispatched on the server thread could
 * write into an in-flight island; quiesce every island for the duration of
 * the ONE dispatch (localized in time — never an ongoing barrier). Island
 * coordinator dispatches (the proven-local inline path) skip — they already
 * own their state. Nested dispatches (execute, functions) skip via the
 * fenceWindowHeld reentrancy check.
 *
 * <p><b>Bounded fence (2026-07-17 deadlock redesign):</b> same discipline as
 * the function fence — main may hold vanilla monitors at any dispatch site,
 * so one bounded quiesce attempt is allowed. If ownership cannot be acquired,
 * the exact invocation cannot be preserved and the physical Main fails-stop;
 * it is never acknowledged and replayed later after its caller continued.
 * The unbounded cases are identity-bound one-shot receipts for the exact
 * monitor-free dedicated-console edge and for an authenticated vanilla RCON
 * task executing at depth one in the top-level server wait drain. Players,
 * direct/mod calls, nested commands, and RCON tasks pumped by a nested
 * managedBlock remain bounded. Unauthenticated player/direct calls remain
 * bounded too; a parsed player command gains an unbounded window only through
 * the identity-bound one-shot receipt rooted in A5's exact tagged Main task.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    // Seconds, not 50 ms (audit D-01): a unit running past the budget fail-stopped
    // the server for an ordinary command (OP sign click, mod server.execute, a
    // nested managedBlock drain) under load, with production units at 37-100 ms.
    // The quiesce pumps Main's chunk services while it waits, as mainClaim does;
    // a real deadlock against a foreign monitor still ends in the fail-stop, well
    // before the vanilla watchdog.
    @org.spongepowered.asm.mixin.Unique
    private static final long FENCE_TRY_NANOS = 5_000_000_000L;

    @WrapMethod(
            method = "performPrefixedCommand("
                    + "Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V",
            require = 1,
            allow = 1)
    private void fabrolia$fenceMainCommands(CommandSourceStack source, String command,
                                            Operation<Void> original) {
        MinecraftServer server = source.getServer();
        if (fabrolia$isForeignCommand(server)) {
            fabrolia$runForeignCommand(server,
                    () -> ((Commands) (Object) this).performPrefixedCommand(source, command));
            return;
        }
        // Both one-shot ledgers are consumed independently before every fast
        // path. Bitwise combination below is deliberate: a short-circuit must
        // never leave the other same-thread capability live for a callback.
        boolean cleanConsoleRoot =
                ConsoleCommandFenceScope.claimDedicatedConsolePrefixedCommand(
                        (Commands) (Object) this, source, command);
        boolean cleanRconRoot =
                RconCommandFenceScope.claimDedicatedRconPrefixedCommand(
                        (Commands) (Object) this, source, command);
        if (cleanConsoleRoot && cleanRconRoot) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "command invocation claimed both console and RCON roots"));
            return;
        }
        boolean cleanMainRoot = cleanConsoleRoot | cleanRconRoot;
        if (Thread.currentThread() instanceof RegionWorkerThread
                || IslandCommands.inRoutedBatch()
                || !IslandCommands.requiresMainFence()) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(source, command));
            return;
        }
        if (IslandCommands.fenceWindowHeld()) {
            // Nested dispatch inside a held window (for example a fenced
            // function running commands) is already quiescent.
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(source, command));
            return;
        }
        boolean took = cleanMainRoot
                ? IslandCommands.quiesceAllExcept(null)
                : IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
        if (!took) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "could not fence the exact Main command body: " + command));
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(
                    IslandCommands.MAIN_FENCED::increment);
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(source, command));
        } catch (Throwable thrown) {
            // The vanilla command body has entered. Close the shared start
            // gate before releasing even the first global-fence claim.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    /**
     * Minecraft 26.2's play listener parses unsigned/signed chat commands itself
     * and invokes this lower-level overload directly.  Fencing only
     * performPrefixedCommand therefore left every player command outside A8's
     * global ownership window.  Keep this exact descriptor independently pinned;
     * the prefixed path re-enters here with fenceWindowHeld and does not reacquire.
     */
    @WrapMethod(
            method = "performCommand(Lcom/mojang/brigadier/ParseResults;"
                    + "Ljava/lang/String;)V",
            require = 1,
            allow = 1)
    private void fabrolia$fenceParsedMainCommands(
            ParseResults<CommandSourceStack> parsed, String command,
            Operation<Void> original) {
        MinecraftServer server = parsed.getContext().getSource().getServer();
        if (fabrolia$isForeignCommand(server)) {
            fabrolia$runForeignCommand(server,
                    () -> ((Commands) (Object) this).performCommand(parsed, command));
            return;
        }
        boolean cleanPlayerRoot = PlayerCommandFenceScope.claimParsedCommand(
                (Commands) (Object) this, parsed, command);
        if (Thread.currentThread() instanceof RegionWorkerThread
                || IslandCommands.inRoutedBatch()
                || !IslandCommands.requiresMainFence()) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(parsed, command));
            return;
        }
        if (IslandCommands.fenceWindowHeld()) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(parsed, command));
            return;
        }
        boolean took = cleanPlayerRoot
                ? IslandCommands.quiesceAllExcept(null)
                : IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
        if (!took) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "could not fence the exact parsed command body: " + command));
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.commitSuccessIfHealthy(
                    IslandCommands.MAIN_FENCED::increment);
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(parsed, command));
        } catch (Throwable thrown) {
            // The parsed vanilla command body has entered. Cleanup may only
            // add context behind the generation's authoritative first failure.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
    }

    /**
     * Thread ownership, not the current async setting, selects the API venue.
     * inRoutedBatch is Main-confined state and cannot authenticate a foreign
     * caller. Exact actors are logical owners, not ordinary external threads.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean fabrolia$isForeignCommand(MinecraftServer server) {
        return server != null
                && !(Thread.currentThread() instanceof RegionWorkerThread)
                && !RegionalPlayerEntityTicksV2.inExactPlayerActor()
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server);
    }

    /**
     * Synchronous handoff before any command capability, claim or causal permit
     * is acquired. Reenter the same public overload on Main: invoking the raw
     * original here would bypass its fence. The ordinary vanilla queue retains
     * its existing drain boundaries; no region pump gains arbitrary commands.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void fabrolia$runForeignCommand(MinecraftServer server, Runnable reentry) {
        RegionMainBlockingWaitGuard.failIfCurrentThreadParticipates("foreign command Main handoff");
        RegionScheduler.throwWorkerFailureIfAny();
        if (server.isStopped() || server.isShutdown()) {
            throw new RejectedExecutionException("server terminated before foreign command admission");
        }

        // Throwable is a result VALUE: even a canonical CompletionException
        // must escape unchanged, not be confused with a future transport wrapper.
        CompletableFuture<Throwable> terminal = new CompletableFuture<>();
        Runnable root = RegionScheduler.cancellableRoot(() -> {
            if (!RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
                throw new IllegalStateException("foreign command task entered off physical Main");
            }
            reentry.run();
        }, terminal::complete);
        RegionTaskFanIn.CancellableQueueEntry cancellation =
                (RegionTaskFanIn.CancellableQueueEntry) root;
        Throwable submissionFailure = null;
        try {
            server.schedule(new TickTask(server.getTickCount(), () -> {
                try {
                    RegionScheduler.throwWorkerFailureIfAny();
                    if (server.isStopped() || server.isShutdown()) {
                        cancellation.cancelQueuedBeforeStartWithFailure(
                                new RejectedExecutionException("server terminated before foreign command entry"));
                        return;
                    }
                    root.run();
                    // A previously cancelled root returns without entering its
                    // body. Its failure terminal already won; false is expected.
                    terminal.complete(null);
                } catch (Throwable failure) {
                    cancellation.cancelQueuedBeforeStartWithFailure(failure);
                    terminal.complete(failure);
                }
            }));
        } catch (Throwable failure) {
            // schedule may enqueue and THEN throw. Cancel only an unentered
            // root; if Main already entered it, wait through its complete tail.
            submissionFailure = RegionScheduler.publishFatalAndSelectWinner(failure);
            cancellation.cancelQueuedBeforeStartWithFailure(submissionFailure);
        }

        boolean interrupted = false;
        boolean cancellationAttempted = submissionFailure != null;
        try {
            while (!terminal.isDone()) {
                if (!cancellationAttempted) {
                    Throwable failure = null;
                    try {
                        RegionScheduler.throwWorkerFailureIfAny();
                        if (server.isStopped() || server.isShutdown()) {
                            failure = new RejectedExecutionException(
                                    "server terminated while foreign command awaited Main");
                        }
                    } catch (Throwable winner) {
                        failure = winner;
                    }
                    if (failure != null) {
                        cancellationAttempted = true;
                        cancellation.cancelQueuedBeforeStartWithFailure(failure);
                    }
                }
                if (Thread.interrupted()) interrupted = true;
                if (!terminal.isDone()) LockSupport.parkNanos(terminal, 1_000_000L);
            }
            Throwable failure = terminal.join();
            if (submissionFailure != null) failure = submissionFailure;
            if (failure != null) CommandsMixin.<RuntimeException>fabrolia$throwForeignFailure(failure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$throwForeignFailure(Throwable failure) throws T {
        throw (T) failure;
    }

}
