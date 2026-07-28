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

    /** Task #36 (2026-07-28 stress test, 12k mobs pinning an island): the
     *  deferred lane must ESCALATE like the routed-command batch does —
     *  bounded attempts under permanent island load never win, and the
     *  retry chains grew without bound (tick tag adds one chain per server
     *  tick; 54M attempts, 1.1 GB of WARN log). Deferred tasks run on the
     *  clean main queue with every monitor released, so an unbounded quiesce
     *  is safe there (the 2026-07-17 monitor hazard applies only to the
     *  in-place first attempt). Main-confined. */
    @org.spongepowered.asm.mixin.Unique
    private static int fabrolia$fenceStarve;

    @org.spongepowered.asm.mixin.Unique
    private static final int FENCE_STARVE_ESCALATE = 8;

    /** Tick-tag chains coalesce by function id (missed-cycle semantics, same
     *  as CommandShards island tasks): while one tick_timer invocation is
     *  still deferred, this tick's fresh copy is DROPPED — running a per-tick
     *  function once instead of N queued times is vanilla-under-lag, and the
     *  alternative is one new immortal retry chain per server tick.
     *  One-shot executions (/function, advancement rewards) are never
     *  coalesced — exactly-once holds for them. */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.Set<net.minecraft.resources.Identifier> fabrolia$deferredTickFns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.spongepowered.asm.mixin.Unique
    private static volatile long fabrolia$lastDeferWarnNanos;

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
            // Task #36: tick-tag copies coalesce — one deferred chain per
            // function id; a fresh per-tick copy while one is queued is a
            // missed cycle, not a second chain.
            if (fabrolia$inTickTag && !fabrolia$deferredTickFns.add(function.id())) {
                return;
            }
            fabrolia$deferFenced(function, source, fabrolia$inTickTag, 1);
            return;
        }
        fabrolia$fenceStarve = 0;
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
                                      CommandSourceStack source, boolean tickTag, int attempt) {
        ServerFunctionManager self = (ServerFunctionManager) (Object) this;
        source.getServer().execute(() -> {
            // Task #36: time-throttled (the attempt-modulo version wrote a
            // 1.1 GB log at 12k-mob load — hundreds of lines/sec across the
            // accumulated chains).
            long now = System.nanoTime();
            long last = fabrolia$lastDeferWarnNanos;
            if (now - last > 5_000_000_000L) {
                fabrolia$lastDeferWarnNanos = now;
                org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                        "[async-islands] function fence still deferring '{}' (attempt {}, starve {}) — "
                        + "islands busy; escalates to an unbounded fence after {} misses, never unfenced",
                        function.id(), attempt, fabrolia$fenceStarve, FENCE_STARVE_ESCALATE);
            }
            if (IslandCommands.fenceWindowHeld()) {
                // Another main-context holder is mid-window — already
                // quiescent; the wrap's reentrancy head runs it inline.
                if (tickTag) fabrolia$deferredTickFns.remove(function.id());
                self.execute(function, source);
                return;
            }
            // Task #36 escalation: this task runs on the clean main queue —
            // no vanilla monitors held — so after enough consecutive bounded
            // misses the fence goes UNBOUNDED (exactly drainRoutedBatch's
            // fenceStarve discipline). A permanently-loaded island can delay
            // datapack functions by units, not forever.
            boolean t = fabrolia$fenceStarve >= FENCE_STARVE_ESCALATE
                    ? IslandCommands.quiesceAllExcept(null)
                    : IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
            if (!t) {
                fabrolia$fenceStarve++;
                fabrolia$deferFenced(function, source, tickTag, attempt + 1);
                return;
            }
            fabrolia$fenceStarve = 0;
            if (tickTag) fabrolia$deferredTickFns.remove(function.id());
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
