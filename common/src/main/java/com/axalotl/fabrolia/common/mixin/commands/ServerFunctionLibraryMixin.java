package com.axalotl.fabrolia.common.mixin.commands;

import com.axalotl.fabrolia.common.regions.FunctionAnalyzer;
import com.axalotl.fabrolia.common.regions.FunctionLibraryAnalysisAccess;
import com.axalotl.fabrolia.common.regions.FailStops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Candidate-local function-analysis transaction.
 *
 * <p>The exact library object owns its stage and prepared snapshot. Nothing is
 * globally visible until ServerFunctionManager installs this library. A failed
 * reload simply never reaches that install edge, so abort is structural rather
 * than a catch-and-continue branch.
 */
@Mixin(value = ServerFunctionLibrary.class, priority = 900)
public abstract class ServerFunctionLibraryMixin
        implements FunctionLibraryAnalysisAccess {

    @Shadow
    public abstract Map<Identifier, CommandFunction<CommandSourceStack>> getFunctions();

    @Unique
    private volatile FunctionAnalyzer.Prepared fabrolia$preparedFunctionAnalysis =
            FunctionAnalyzer.emptyPrepared();

    /** Begin before vanilla launches parse tasks; seal after its apply future. */
    @WrapMethod(
            method = "reload("
                    + "Lnet/minecraft/server/packs/resources/"
                    + "PreparableReloadListener$SharedState;"
                    + "Ljava/util/concurrent/Executor;"
                    + "Lnet/minecraft/server/packs/resources/"
                    + "PreparableReloadListener$PreparationBarrier;"
                    + "Ljava/util/concurrent/Executor;)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            require = 1,
            allow = 1)
    private CompletableFuture<Void> fabrolia$stageFunctionAnalysis(
            PreparableReloadListener.SharedState state,
            Executor preparationExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor applyExecutor,
            Operation<CompletableFuture<Void>> original) {
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.beginReload();
        Executor scopedPreparationExecutor =
                FunctionAnalyzer.scopedReloadExecutor(stage, preparationExecutor);
        CompletableFuture<Void> vanilla = FunctionAnalyzer.withReloadStage(stage,
                () -> original.call(
                        state, scopedPreparationExecutor, barrier, applyExecutor));
        return vanilla.thenRun(() -> {
            FunctionAnalyzer.Prepared prepared =
                    FunctionAnalyzer.seal(stage, this.getFunctions());
            fabrolia$preparedFunctionAnalysis = prepared;
        });
    }

    /**
     * The caller seam sees the final object returned by every mixin/compiler
     * inside CommandFunction.fromLines. Vanilla parse failures bypass this
     * success tail. Analyzer failures are recorded and rethrown exactly here;
     * seal() promotes the same failure above vanilla's per-function omission
     * handler and fails the candidate reload.
     */
    @WrapOperation(
            method = "lambda$reload$3(Ljava/util/Map$Entry;"
                    + "Lnet/minecraft/resources/Identifier;"
                    + "Lnet/minecraft/commands/CommandSourceStack;)"
                    + "Lnet/minecraft/commands/functions/CommandFunction;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/functions/CommandFunction;fromLines("
                            + "Lnet/minecraft/resources/Identifier;"
                            + "Lcom/mojang/brigadier/CommandDispatcher;"
                            + "Lnet/minecraft/commands/ExecutionCommandSource;"
                            + "Ljava/util/List;)"
                            + "Lnet/minecraft/commands/functions/CommandFunction;"),
            require = 1,
            allow = 1)
    private <T extends ExecutionCommandSource<T>> CommandFunction<T> fabrolia$analyzeExactFunction(
            Identifier id, CommandDispatcher<T> dispatcher, T source,
            List<String> lines, Operation<CommandFunction<T>> original) {
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.currentReloadStage();
        CommandFunction<T> function = original.call(id, dispatcher, source, lines);
        try {
            FunctionAnalyzer.analyzeInto(stage, id, function, lines);
        } catch (Throwable failure) {
            FunctionAnalyzer.recordFailure(stage, failure);
            return FailStops.<CommandFunction<T>, RuntimeException>rethrowValue(failure);
        }
        return function;
    }

    @Override
    public FunctionAnalyzer.Prepared fabrolia$preparedFunctionAnalysis() {
        return fabrolia$preparedFunctionAnalysis;
    }
}
