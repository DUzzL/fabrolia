package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.api.FabroliaFunctionScopes;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.FunctionInstantiationException;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Model tests for candidate staging and exact live-generation activation. */
final class FunctionReloadTransactionTest {

    @AfterEach
    void clearActiveGeneration() {
        FunctionAnalyzer.clear();
    }

    @Test
    void abandonedCandidateCannotOverwriteTheActiveGeneration() {
        Identifier oldId = Identifier.parse("fabrolia_test:old");
        Identifier newId = Identifier.parse("fabrolia_test:new");
        CommandFunction<CommandSourceStack> oldFunction = function(oldId);
        CommandFunction<CommandSourceStack> newFunction = function(newId);

        FunctionAnalyzer.ReloadStage oldStage = FunctionAnalyzer.beginReload();
        FunctionAnalyzer.analyzeInto(
                oldStage, oldId, oldFunction, List.of("setblock ~ ~ ~ stone"));
        FunctionAnalyzer.activateInitial(FunctionAnalyzer.seal(
                oldStage, Map.of(oldId, oldFunction)));

        FunctionAnalyzer.ReloadStage abandoned = FunctionAnalyzer.beginReload();
        FunctionAnalyzer.analyzeInto(
                abandoned, newId, newFunction, List.of("setblock ~ ~ ~ dirt"));

        assertEquals(FunctionAnalyzer.Kind.PURE_LOCAL_REL,
                FunctionAnalyzer.analysisOf(oldId).kind());
        assertNull(FunctionAnalyzer.analysisOf(newId),
                "staging alone must never mutate the active generation");
    }

    @Test
    void sealKeepsOnlyTheExactFunctionObjectVanillaApplied() {
        Identifier id = Identifier.parse("fabrolia_test:identity");
        CommandFunction<CommandSourceStack> staged = function(id);
        CommandFunction<CommandSourceStack> replacement = function(id);
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.beginReload();
        FunctionAnalyzer.analyzeInto(
                stage, id, staged, List.of("setblock ~ ~ ~ stone"));

        FunctionAnalyzer.activateInitial(FunctionAnalyzer.seal(
                stage, Map.of(id, replacement)));

        assertNull(FunctionAnalyzer.analysisOf(id),
                "same id with a different returned object must fail closed");
        assertEquals(FabroliaFunctionScopes.Scope.UNKNOWN,
                FabroliaFunctionScopes.scopeOf(id));
    }

    @Test
    void analyzerFailureCrossesVanillasPerFunctionOmissionAsTheSameThrowable() {
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.beginReload();
        AssertionError exact = new AssertionError("exact analyzer failure");
        FunctionAnalyzer.recordFailure(stage, exact);

        Throwable observed = assertThrows(Throwable.class,
                () -> FunctionAnalyzer.seal(stage, Map.of()));

        assertSame(exact, observed);
    }

    @Test
    void failedVanillaReplacementDoesNotActivateCandidateMetadata() {
        Identifier oldId = Identifier.parse("fabrolia_test:old_commit");
        Identifier newId = Identifier.parse("fabrolia_test:new_commit");
        FunctionAnalyzer.Prepared oldPrepared = prepared(
                oldId, function(oldId), "setblock ~ ~ ~ stone");
        FunctionAnalyzer.Prepared newPrepared = prepared(
                newId, function(newId), "setblock ~ ~ ~ dirt");
        FunctionAnalyzer.activateInitial(oldPrepared);
        IllegalStateException exact = new IllegalStateException("vanilla replace failed");

        Throwable observed = assertThrows(Throwable.class,
                () -> FunctionAnalyzer.replaceAndActivate(newPrepared, () -> {
                    throw exact;
                }));

        assertSame(exact, observed);
        assertEquals(FunctionAnalyzer.Kind.PURE_LOCAL_REL,
                FunctionAnalyzer.analysisOf(oldId).kind());
        assertNull(FunctionAnalyzer.analysisOf(newId));
    }

    @Test
    void compilerScopeRegistrationBelongsOnlyToItsBoundCandidate() {
        Identifier id = Identifier.parse("fabrolia_test:compiled");
        CommandFunction<CommandSourceStack> function = function(id);
        FabroliaFunctionScopes.Scope compilerScope = new FabroliaFunctionScopes.Scope(
                false, true, false, false, false, false, "compiler exact scope");
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.beginReload();

        FunctionAnalyzer.withReloadStage(stage, () -> {
            FabroliaFunctionScopes.register(id, compilerScope);
            return null;
        });
        FunctionAnalyzer.analyzeInto(
                stage, id, function, List.of("setblock ~ ~ ~ stone"));
        FunctionAnalyzer.activateInitial(FunctionAnalyzer.seal(
                stage, Map.of(id, function)));

        assertSame(compilerScope, FabroliaFunctionScopes.scopeOf(id));

        FabroliaFunctionScopes.Scope staleLateScope = new FabroliaFunctionScopes.Scope(
                false, false, true, true, true, false, "late stale scope");
        FabroliaFunctionScopes.register(id, staleLateScope);
        assertSame(compilerScope, FabroliaFunctionScopes.scopeOf(id),
                "an unbound id-only registration has no exact-generation receipt");
    }

    @Test
    void overlappingPreparationExecutorsKeepTheirExactReloadStage() {
        FunctionAnalyzer.ReloadStage first = FunctionAnalyzer.beginReload();
        FunctionAnalyzer.ReloadStage second = FunctionAnalyzer.beginReload();
        Phaser overlap = new Phaser(3);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Executor firstExecutor =
                    FunctionAnalyzer.scopedReloadExecutor(first, workers);
            Executor secondExecutor =
                    FunctionAnalyzer.scopedReloadExecutor(second, workers);
            CompletableFuture<FunctionAnalyzer.ReloadStage> firstObserved =
                    CompletableFuture.supplyAsync(() -> {
                        overlap.arriveAndAwaitAdvance();
                        return FunctionAnalyzer.currentReloadStage();
                    }, firstExecutor);
            CompletableFuture<FunctionAnalyzer.ReloadStage> secondObserved =
                    CompletableFuture.supplyAsync(() -> {
                        overlap.arriveAndAwaitAdvance();
                        return FunctionAnalyzer.currentReloadStage();
                    }, secondExecutor);

            overlap.arriveAndAwaitAdvance();

            assertSame(first, firstObserved.join());
            assertSame(second, secondObserved.join());
        }
    }

    @Test
    void cloneDestinationExtentCannotAcquireALocalFunctionProof() {
        // All three written coordinates fit in one cell, but the translated
        // two-block destination extends beyond its final block.
        assertEquals(FunctionAnalyzer.Kind.ROUTED, FunctionAnalyzer.analyze(List.of(
                "clone 0 64 0 1 64 0 127 64 0")).kind());
        assertEquals(FunctionAnalyzer.Kind.ROUTED, FunctionAnalyzer.analyze(List.of(
                "clone ~ ~ ~ ~1 ~ ~ ~63 ~ ~")).kind());
    }

    private static FunctionAnalyzer.Prepared prepared(
            Identifier id, CommandFunction<CommandSourceStack> function, String line) {
        FunctionAnalyzer.ReloadStage stage = FunctionAnalyzer.beginReload();
        FunctionAnalyzer.analyzeInto(stage, id, function, List.of(line));
        return FunctionAnalyzer.seal(stage, Map.of(id, function));
    }

    private static CommandFunction<CommandSourceStack> function(Identifier id) {
        return new CommandFunction<>() {
            @Override
            public Identifier id() {
                return id;
            }

            @Override
            public InstantiatedFunction<CommandSourceStack> instantiate(
                    CompoundTag arguments,
                    CommandDispatcher<CommandSourceStack> dispatcher)
                    throws FunctionInstantiationException {
                return null;
            }
        };
    }
}
