package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import com.axalotl.fabrolia.common.regions.FunctionLibraryAnalysisAccess;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A8 — one fence per datapack FUNCTION execution (tick tag, /function,
 * schedules): the whole function body runs under a single quiesce window
 * instead of one per contained command (performPrefixedCommand's fence skips
 * via the fenceWindowHeld reentrancy check). Vanilla's single invocation is
 * preserved in the current call; no function copy is moved to another tick.
 *
 * <p><b>Bounded fence (2026-07-17 deadlock redesign):</b> main may reach this
 * wrap holding vanilla monitors (an advancement reward function fired from
 * inside a synchronized trigger was the production three-way deadlock:
 * main → unit claim → entity barrier → worker → trigger monitor → main).
 * A nested/monitor-bearing invocation gets one bounded quiesce attempt; an
 * ownership miss is fatal because returning and replaying the function later
 * would let its current caller continue without the vanilla body. Tick-tag and
 * scheduled-event dispatch start at proven clean Main points and therefore
 * wait unboundedly for the fence instead of dropping or coalescing a logical
 * invocation. The deferred advancement batch is another proven clean Main
 * point: its entity barrier and trigger/advancement monitors have completed
 * before the serial reward body is entered.
 */
@Mixin(ServerFunctionManager.class)
public abstract class ServerFunctionManagerMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final long FENCE_TRY_NANOS = 50_000_000L;

    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$inTickTag;

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private MinecraftServer server;
    @org.spongepowered.asm.mixin.Shadow
    private java.util.List<CommandFunction<CommandSourceStack>> ticking;
    @org.spongepowered.asm.mixin.Shadow
    private boolean postReload;

    /** Initial boot installs an already-sealed candidate library. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$activateInitialFunctionAnalysis(
            MinecraftServer server, ServerFunctionLibrary library, CallbackInfo ci) {
        FunctionAnalyzer.activateInitial(
                ((FunctionLibraryAnalysisAccess) library)
                        .fabrolia$preparedFunctionAnalysis());
    }

    /**
     * Reload's library swap and metadata activation are one fair write-side
     * transaction. The Prepared snapshot was fully allocated by the reload
     * listener; after vanilla returns activation is one volatile assignment.
     */
    @WrapMethod(method = "replaceLibrary", require = 1, allow = 1)
    private void fabrolia$replaceLibraryAndAnalysis(
            ServerFunctionLibrary library, Operation<Void> original) {
        FunctionAnalyzer.Prepared prepared =
                ((FunctionLibraryAnalysisAccess) library)
                        .fabrolia$preparedFunctionAnalysis();
        FunctionAnalyzer.replaceAndActivate(prepared,
                () -> original.call(library));
    }

    /** Tick-tag window marker (main-confined): execute() calls inside tick()
     *  start from a clean Main stack and may wait for an unbounded fence.
     *  Also the once-per-tick advancement window: frameless main awards,
     *  worker-side direct mutations and the island worker lane apply here
     *  (tick head, no monitors held) under ONE sticky fence which the tick
     *  functions then share instead of each quiescing every island again. */
    @WrapMethod(method = "tick")
    private void fabrolia$markTickTag(Operation<Void> original) {
        boolean[] window = new boolean[1];
        Throwable failure = null;
        try {
            // The window drain clears its scheduled marker before applying the
            // drained batch. Its own leading health read cannot linearize that
            // mutation against a peer W, so admit the complete drain here.
            // Mirrors vanilla tick(): functions run only while the tick rate
            // runs normally; the load tag runs once after a reload.
            boolean functionsQueued = this.server.tickRateManager().runsNormally()
                    && (this.postReload || !this.ticking.isEmpty());
            RegionScheduler.runSuccessBodyIfHealthy(() -> window[0] =
                    com.axalotl.fabrolia.common.parallelised.advancement
                            .AdvancementBuffer.beginTickHeadWindow(functionsQueued));
            fabrolia$inTickTag = true;
            // The window drain may return through its empty fast path while a
            // peer worker is selecting W.  Treat the tick-tag invocation as its
            // own vanilla body: either this admission wins first and the entered
            // call finishes its cleanup chain, or W wins and original is never
            // entered.
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
        } catch (Throwable thrown) {
            // tick() is an entered vanilla function body. Publish before
            // restoring the main-confined tick-tag marker.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        fabrolia$inTickTag = false;
        if (window[0]) {
            // Mandatory pair: settles function-raised cascades in the held
            // window and releases the sticky fence exactly once.
            failure = com.axalotl.fabrolia.common.parallelised.advancement
                    .AdvancementBuffer.endTickHeadWindow(failure);
        }
        if (failure == null) {
            try {
                // Marker restoration is mandatory cleanup outside the body's
                // permit. Refuse a normal return if peer W won during that tail.
                RegionScheduler.commitSuccessIfHealthy(() -> {});
            } catch (Throwable peerFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(peerFailure);
            }
        }
        if (failure != null) {
            ServerFunctionManagerMixin.<RuntimeException>fabrolia$rethrow(failure);
        }
    }

    @WrapMethod(method = "execute")
    private void fabrolia$fenceFunction(CommandFunction<CommandSourceStack> function,
                                        CommandSourceStack source, Operation<Void> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                || !IslandCommands.requiresMainFence()) {
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(function, source));
            return;
        }
        // Reentrancy: a nested dispatch inside an already-held window (a
        // function called by a fenced function/command) is already quiescent
        // and runs inline in the same invocation.
        if (IslandCommands.fenceWindowHeld()) {
            if (com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope
                    .rewardFunctionActive()) {
                RegionScheduler.commitSuccessIfHealthy(() ->
                        com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                                .REWARD_FUNCTIONS.increment());
            }
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(function, source));
            return;
        }
        // Prepare every fallible receipt before acquiring the global fence.
        // Once quiesce succeeds, the cleanup envelope starts immediately.
        boolean sticky = com.axalotl.fabrolia.common.parallelised.advancement
                .AdvancementBatchScope.rewardFunctionActive();
        boolean[] stickyArmed = new boolean[1];
        Throwable failure = null;
        boolean cleanMainRoot = fabrolia$inTickTag
                || IslandCommands.inMainScheduledFunctionCallback()
                || com.axalotl.fabrolia.common.parallelised.advancement
                        .AdvancementBatchScope.cleanMainFenceRoot();
        boolean took = cleanMainRoot
                ? IslandCommands.quiesceAllExcept(null)
                : IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
        if (!took) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "could not fence the exact function body: " + function.id()));
            return;
        }
        // task #22: inside an advancement batch scope, the FIRST function
        // keeps the window (sticky) — nested/subsequent functions and the
        // deferred lane run in the held window; the batch end releases once.
        try {
            if (sticky) {
                // Arm the outer batch cleanup before any success metric/body.
                // Keep the local receipt visible even if W wins at the permit's
                // terminal edge, so this method never double-owns the release.
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    com.axalotl.fabrolia.common.parallelised.advancement
                            .AdvancementBatchScope.noteStickyFence();
                    stickyArmed[0] = true;
                });
            }
            RegionScheduler.commitSuccessIfHealthy(() -> {
                IslandCommands.FUNCTION_FENCED.increment();
                if (sticky) {
                    com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                            .REWARD_FUNCTIONS.increment();
                    com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                            .QUIESCE_TAKES.increment();
                }
            });
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(function, source));
        } catch (Throwable thrown) {
            // The exact function body has entered. The fence (sticky or
            // local) stays held until the shared first-wins fatal is visible.
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        if (stickyArmed[0]) {
            if (failure != null) IslandCommands.publishAndPropagate(failure);
        } else {
            IslandCommands.releaseAllExceptAndPropagate(failure);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void fabrolia$rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

}
