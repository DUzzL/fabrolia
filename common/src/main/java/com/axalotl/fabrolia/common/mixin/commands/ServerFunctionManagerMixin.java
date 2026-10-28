package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import com.axalotl.fabrolia.common.regions.FunctionFenceDiag;
import com.axalotl.fabrolia.common.regions.FunctionLibraryAnalysisAccess;
import com.axalotl.fabrolia.common.regions.FunctionPartition;
import com.axalotl.fabrolia.common.regions.IslandFunctionHead;
import com.axalotl.fabrolia.common.regions.IslandFunctionScope;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.regions.FailStops;
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
 *
 * <p><b>Per-tick roots on island heads:</b> {@code #minecraft:tick} roots the
 * active {@code FunctionPlan} partitions are armed here for the island heads;
 * Main runs only their remainder partition, without a fence, and only
 * Main-resident roots ask the tick-head window for the global fence. On a
 * region worker (C3) only plan-proven owner-local bodies run, inside their
 * partition's ownership guards; anything else is a fail-stop.
 */
@Mixin(ServerFunctionManager.class)
public abstract class ServerFunctionManagerMixin {

    // Seconds, not 50 ms (audit D-01): a unit running past the budget fail-stopped
    // the server for an ordinary command (OP sign click, mod server.execute, a
    // nested managedBlock drain) under load, with production units at 37-100 ms.
    // The quiesce pumps Main's chunk services while it waits, as mainClaim does;
    // a real deadlock against a foreign monitor still ends in the fail-stop, well
    // before the vanilla watchdog.
    @org.spongepowered.asm.mixin.Unique
    private static final long FENCE_TRY_NANOS = 5_000_000_000L;

    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$inTickTag;

    /** True while the {@code #minecraft:tick} tag itself (not {@code #load}) executes. */
    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$inTickFunctionsTag;

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private MinecraftServer server;
    @org.spongepowered.asm.mixin.Shadow
    private java.util.List<CommandFunction<CommandSourceStack>> ticking;
    @org.spongepowered.asm.mixin.Shadow
    private boolean postReload;

    /** Initial boot installs an already-sealed candidate library and its plan. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$activateInitialFunctionAnalysis(
            MinecraftServer server, ServerFunctionLibrary library, CallbackInfo ci) {
        FunctionAnalyzer.Prepared prepared =
                ((FunctionLibraryAnalysisAccess) library).fabrolia$preparedFunctionAnalysis();
        FunctionAnalyzer.activateInitial(prepared,
                FunctionAnalyzer.plan(prepared, library, server));
        fabrolia$resolveRewardFunctions(server);
    }

    /**
     * Vanilla's {@code CacheableFunction} resolves lazily through plain fields;
     * island owners award concurrently, so every reward function of the active
     * advancements is resolved here, on Main, before islands can award (boot,
     * or inside the fenced library swap).
     */
    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$resolveRewardFunctions(MinecraftServer server) {
        ServerFunctionManager self = (ServerFunctionManager) (Object) this;
        for (net.minecraft.advancements.AdvancementHolder holder
                : server.getAdvancements().getAllAdvancements()) {
            holder.value().rewards().function().ifPresent(f -> f.get(self));
        }
    }

    /**
     * Reload's library swap and metadata activation are one fair write-side
     * transaction. The Prepared snapshot was fully allocated by the reload
     * listener and the plan is resolved against the new generation's tags and
     * registries before the write lock. With async islands live the swap also
     * holds the global fence: no island head is inside a function pass of the
     * old generation while its library is replaced.
     */
    @WrapMethod(method = "replaceLibrary", require = 1, allow = 1)
    private void fabrolia$replaceLibraryAndAnalysis(
            ServerFunctionLibrary library, Operation<Void> original) {
        FunctionAnalyzer.Prepared prepared =
                ((FunctionLibraryAnalysisAccess) library)
                        .fabrolia$preparedFunctionAnalysis();
        com.axalotl.fabrolia.common.regions.FunctionPlan plan =
                FunctionAnalyzer.plan(prepared, library, this.server);
        if (!IslandCommands.requiresMainFence() || IslandCommands.fenceWindowHeld()) {
            FunctionAnalyzer.replaceAndActivate(prepared, plan,
                    () -> { original.call(library); fabrolia$resolveRewardFunctions(this.server); });
            return;
        }
        if (!IslandCommands.quiesceAllExcept(null)) {
            if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
                IslandCommands.publishAndPropagate(new IllegalStateException(
                        "could not fence the function library replacement"));
                return;
            }
            FunctionAnalyzer.replaceAndActivate(prepared, plan,
                    () -> { original.call(library); fabrolia$resolveRewardFunctions(this.server); });
            return;
        }
        Throwable failure = null;
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> FunctionAnalyzer.replaceAndActivate(
                    prepared, plan, () -> {
                        original.call(library);
                        fabrolia$resolveRewardFunctions(this.server);
                    }));
        } catch (Throwable thrown) {
            failure = RegionScheduler.publishFatalAndSelectWinner(thrown);
        }
        IslandCommands.releaseAllExceptAndPropagate(failure);
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
            // runs normally; the load tag runs once after a reload. Partitioned
            // #tick roots are island-head work (armed after the tag, so a
            // reload's #load precedes their first island pass), so only
            // Main-resident roots ask the window for the global fence.
            boolean runs = this.server.tickRateManager().runsNormally();
            boolean islands = IslandCommands.requiresMainFence();
            com.axalotl.fabrolia.common.regions.FunctionPlan plan = FunctionAnalyzer.plan();
            boolean functionsQueued = runs
                    && (this.postReload || fabrolia$mainResidentTicking(plan, islands));
            RegionScheduler.runSuccessBodyIfHealthy(() -> window[0] =
                    com.axalotl.fabrolia.common.parallelised.advancement
                            .AdvancementBuffer.beginTickHeadWindow(functionsQueued));
            if (functionsQueued) {
                FunctionFenceDiag.noteTickFunctionsQueued(
                        islands && IslandCommands.fenceWindowHeld());
            }
            fabrolia$inTickTag = true;
            // The window drain may return through its empty fast path while a
            // peer worker is selecting W.  Treat the tick-tag invocation as its
            // own vanilla body: either this admission wins first and the entered
            // call finishes its cleanup chain, or W wins and original is never
            // entered.
            RegionScheduler.runSuccessBodyIfHealthy(() -> original.call());
            IslandFunctionHead.publishTickRoots(plan, runs && islands);
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
            FailStops.<RuntimeException>rethrow(failure);
        }
    }

    /** Whether any #tick function of this tick still runs as a whole on Main. */
    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$mainResidentTicking(
            com.axalotl.fabrolia.common.regions.FunctionPlan plan, boolean islands) {
        for (CommandFunction<CommandSourceStack> f : this.ticking) {
            if (!islands || plan.partitionedTickRoot(f) == null) return true;
        }
        return false;
    }

    @WrapMethod(method = "executeTagFunctions", require = 1, allow = 1)
    private void fabrolia$markTickFunctionsTag(
            java.util.Collection<CommandFunction<CommandSourceStack>> functions,
            net.minecraft.resources.Identifier tag, Operation<Void> original) {
        boolean previous = fabrolia$inTickFunctionsTag;
        fabrolia$inTickFunctionsTag = net.minecraft.resources.Identifier.withDefaultNamespace("tick").equals(tag);
        try {
            original.call(functions, tag);
        } finally {
            fabrolia$inTickFunctionsTag = previous;
        }
    }

    /**
     * The tick tag's own dispatch of each root. A partitioned root is island
     * head work: Main runs only its remainder partition, unfenced, at this
     * exact vanilla position between the Main-resident roots.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "executeTagFunctions",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/ServerFunctionManager;execute("
                            + "Lnet/minecraft/commands/functions/CommandFunction;"
                            + "Lnet/minecraft/commands/CommandSourceStack;)V"),
            require = 1,
            allow = 1)
    private void fabrolia$partitionTickRoot(ServerFunctionManager self,
                                            CommandFunction<CommandSourceStack> function,
                                            CommandSourceStack sender, Operation<Void> original) {
        if (fabrolia$inTickFunctionsTag && IslandCommands.requiresMainFence()) {
            com.axalotl.fabrolia.common.regions.FunctionPlan.RootPlan root =
                    FunctionAnalyzer.plan().partitionedTickRoot(function);
            if (root != null) {
                IslandFunctionHead.runMainPartition(root, this.server);
                return;
            }
        }
        original.call(self, function, sender);
    }

    @WrapMethod(method = "execute")
    private void fabrolia$fenceFunction(CommandFunction<CommandSourceStack> function,
                                        CommandSourceStack source, Operation<Void> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread) {
            // C3 (also for C2 owner-local rewards): a worker runs only a body the
            // exact active plan proves owner-local, under the live-generation
            // lease and inside its partition's ownership guards. Anything else
            // would run unfenced next to live islands: fail-stop.
            boolean ran = FunctionAnalyzer.withExactOwnerLocalProof(
                    (ServerFunctionManager) (Object) this, function, proven -> {
                        if (!proven) return false;
                        FunctionPartition partition =
                                IslandFunctionScope.partitionOfCurrentWorker(this.server);
                        RegionScheduler.commitSuccessIfHealthy(
                                FunctionFenceDiag.WORKER_OWNER_LOCAL::increment);
                        IslandFunctionScope.run(partition, () -> RegionScheduler
                                .runSuccessBodyIfHealthy(() -> original.call(function, source)));
                        return true;
                    });
            if (!ran) {
                IslandCommands.publishAndPropagate(new IllegalStateException(
                        "function " + function.id() + " executed on region worker "
                                + Thread.currentThread().getName()
                                + " is not proven owner-local by the active function plan"));
            }
            return;
        }
        // C2 on physical Main: the advancement reward routing point proved this
        // exact call owner-local (exact plan object, generation lease) and runs it
        // inside the scope of a partition owning the player; no fence.
        if (com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRewardRouting
                .isOwnerLocalReward(function, source)) {
            if (IslandFunctionScope.current() == null) {
                IslandCommands.publishAndPropagate(new IllegalStateException("owner-local reward "
                        + function.id() + " reached execute outside its ownership scope"));
                return;
            }
            RegionScheduler.runSuccessBodyIfHealthy(
                    () -> original.call(function, source));
            return;
        }
        if (IslandFunctionScope.current() != null) {
            // Inside an owner-local pass vanilla only queues this body into the
            // pass's ExecutionContext: it would run under the pass's guards
            // without a proof. The routing point never sends work here.
            IslandCommands.publishAndPropagate(new IllegalStateException("function "
                    + function.id() + " dispatched inside an owner-local pass without an owner-local proof"));
            return;
        }
        if (!IslandCommands.requiresMainFence()) {
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
                || sticky;
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
                FunctionFenceDiag.functionFenced(function.id());
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

}
