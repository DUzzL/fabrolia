package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;

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
 * so one bounded quiesce attempt, then defer to the server main task queue
 * and retry with a clean stack. NO unfenced fallback: a command either runs
 * under the fence or keeps deferring (rate-limited WARN), never against
 * live islands.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final long FENCE_TRY_NANOS = 50_000_000L;

    @WrapMethod(method = "performPrefixedCommand")
    private void fabrolia$fenceMainCommands(CommandSourceStack source, String command,
                                            Operation<Void> original) {
        if (Thread.currentThread() instanceof RegionWorkerThread
                || IslandCommands.inRoutedBatch()
                || !RegionScheduler.anyAsyncActive()) {
            original.call(source, command);
            return;
        }
        if (IslandCommands.fenceWindowHeld()) {
            // Nested dispatch inside a held window (fenced function running
            // commands, or our deferred retry) — already quiescent.
            original.call(source, command);
            return;
        }
        boolean took = IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
        if (!took) {
            fabrolia$deferFenced(source, command, 1);
            return;
        }
        IslandCommands.MAIN_FENCED.increment();
        try {
            original.call(source, command);
        } finally {
            IslandCommands.releaseAllExcept();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void fabrolia$deferFenced(CommandSourceStack source, String command, int attempt) {
        Commands self = (Commands) (Object) this;
        source.getServer().execute(() -> {
            if (attempt == 100 || attempt % 400 == 0) {
                org.slf4j.LoggerFactory.getLogger("fabrolia").warn(
                        "[async-islands] command fence still deferring '{}' (attempt {}) — "
                        + "islands busy; retrying at main-safe points, never unfenced",
                        command, attempt);
            }
            if (IslandCommands.fenceWindowHeld()) {
                self.performPrefixedCommand(source, command);
                return;
            }
            boolean t = IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
            if (!t) {
                fabrolia$deferFenced(source, command, attempt + 1);
                return;
            }
            IslandCommands.MAIN_FENCED.increment();
            try {
                self.performPrefixedCommand(source, command);
            } finally {
                IslandCommands.releaseAllExcept();
            }
        });
    }
}
