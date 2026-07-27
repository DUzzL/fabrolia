package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A8 — one fence per datapack FUNCTION execution (tick tag, /function,
 * schedules): the whole function body runs under a single quiesce window
 * instead of one per contained command (performPrefixedCommand's fence skips
 * via the fenceWindowHeld reentrancy check). Exactly-once trivially holds —
 * vanilla's single invocation is preserved, only fenced. Island-cadence
 * decomposition of proven-local function bodies is the Phase-3 refinement
 * (a8 checkpoint).
 *
 * <p><b>Bounded fence (2026-07-17 deadlock redesign):</b> main may reach this
 * wrap holding vanilla monitors (an advancement reward function fired from
 * inside a synchronized trigger was the production three-way deadlock:
 * main → unit claim → entity barrier → worker → trigger monitor → main).
 * The fence therefore NEVER parks main unboundedly: one bounded quiesce
 * attempt, then the invocation defers to the server main task queue — it
 * re-runs between ticks with every monitor released and fences there. There
 * is NO unfenced fallback: if islands stay busy the function keeps deferring
 * (rate-limited WARN) until the fence succeeds; world-mutating logic never
 * runs without ownership protection.
 */
@Mixin(ServerFunctionManager.class)
public abstract class ServerFunctionManagerMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final long FENCE_TRY_NANOS = 50_000_000L;

    @org.spongepowered.asm.mixin.Unique
    private boolean fabrolia$inTickTag;

    /** Tick-tag window marker (main-confined): execute() calls inside tick()
     *  are per-server-tick tick functions — the island-dispatch candidates.
     *  Also the once-per-tick main belt for the advancement drain: frameless
     *  main awards + worker-side direct mutations apply here (tick head,
     *  no monitors held) if the scheduled pump missed them. */
    @WrapMethod(method = "tick")
    private void fabrolia$markTickTag(Operation<Void> original) {
        com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBuffer.drainMainFrameless();
        fabrolia$inTickTag = true;
        try {
            original.call();
        } finally {
            fabrolia$inTickTag = false;
        }
    }

    @WrapMethod(method = "execute")
    private void fabrolia$fenceFunction(CommandFunction<CommandSourceStack> function,
                                        CommandSourceStack source, Operation<Void> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                || !RegionScheduler.anyAsyncActive()) {
            original.call(function, source);
            return;
        }
        // A8 Phase 3 — the tick-function hybrid: a tick-tag function PROVEN
        // local to exactly one island (absolute-coordinate allowlisted body)
        // dispatches to that island's lane and runs at ITS cadence — a slow
        // island's local tick work slows with it; global work stays here,
        // fenced, exactly once per server tick. Analysis covers interpreted
        // and MCFC-compiled bodies alike (source-level; MCFC == observable
        // equivalence). No analysis / any doubt → fenced global path.
        if (fabrolia$inTickTag) {
            var owner = com.axalotl.fabrolia.common.regions.IslandCommands
                    .tickFunctionIslandOwner(function.id());
            if (owner != null) {
                ServerFunctionManager self = (ServerFunctionManager) (Object) this;
                com.axalotl.fabrolia.common.regions.CommandShards.enqueueIslandTask(owner,
                        function.id(), () -> self.execute(function, source));
                com.axalotl.fabrolia.common.regions.IslandCommands.TICK_FN_ISLAND_DISPATCH.increment();
                return;
            }
        }
        // Reentrancy: a nested dispatch inside an already-held window (a
        // function called by a fenced function/command, or our own deferred
        // retry below) is already quiescent — run inline.
        if (IslandCommands.fenceWindowHeld()) {
            if (com.axalotl.fabrolia.common.parallelised.advancement.AdvancementBatchScope.active()) {
                com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                        .REWARD_FUNCTIONS.increment();
            }
            original.call(function, source);
            return;
        }
        boolean took = IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
        if (!took) {
            fabrolia$deferFenced(function, source, 1);
            return;
        }
        IslandCommands.FUNCTION_FENCED.increment();
        // task #22: inside an advancement batch scope, the FIRST function
        // keeps the window (sticky) — nested/subsequent functions and the
        // deferred lane run in the held window; the batch end releases once.
        boolean sticky = com.axalotl.fabrolia.common.parallelised.advancement
                .AdvancementBatchScope.active();
        if (sticky) {
            com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                    .REWARD_FUNCTIONS.increment();
            com.axalotl.fabrolia.common.parallelised.advancement.AdvancementDiag
                    .QUIESCE_TAKES.increment();
        }
        try {
            original.call(function, source);
        } finally {
            if (sticky) {
                com.axalotl.fabrolia.common.parallelised.advancement
                        .AdvancementBatchScope.noteStickyFence();
            } else {
                IslandCommands.releaseAllExcept();
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$deferFenced(CommandFunction<CommandSourceStack> function,
                                      CommandSourceStack source, int attempt) {
        ServerFunctionManager self = (ServerFunctionManager) (Object) this;
        source.getServer().execute(() -> {
            if (attempt == 100 || attempt % 400 == 0) {
                org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                        "[async-islands] function fence still deferring '{}' (attempt {}) — "
                        + "islands busy; retrying at main-safe points, never unfenced",
                        function.id(), attempt);
            }
            if (IslandCommands.fenceWindowHeld()) {
                // Another main-context holder is mid-window — already
                // quiescent; the wrap's reentrancy head runs it inline.
                self.execute(function, source);
                return;
            }
            boolean t = IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
            if (!t) {
                fabrolia$deferFenced(function, source, attempt + 1);
                return;
            }
            IslandCommands.FUNCTION_FENCED.increment();
            try {
                // Window held by us — the wrap's reentrancy head runs the
                // vanilla body inline under this quiesce.
                self.execute(function, source);
            } finally {
                IslandCommands.releaseAllExcept();
            }
        });
    }
}
