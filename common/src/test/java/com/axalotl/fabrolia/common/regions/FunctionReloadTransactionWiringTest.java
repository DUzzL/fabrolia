package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source pins for the exact-object reload transaction and its read lease. */
final class FunctionReloadTransactionWiringTest {

    @Test
    void exactCallerStagesAndListenerSuccessSealsWithoutASuppressor() throws Exception {
        String mixin = source("mixin/commands/ServerFunctionLibraryMixin.java");
        int original = mixin.indexOf("original.call(id, dispatcher, source, lines)");
        int analyze = mixin.indexOf("FunctionAnalyzer.analyzeInto(", original);
        int caught = mixin.indexOf("catch (Throwable failure)", analyze);
        int record = mixin.indexOf("FunctionAnalyzer.recordFailure(stage, failure)", caught);
        int rethrow = mixin.indexOf("FailStops.<CommandFunction<T>, RuntimeException>rethrowValue(failure)", record);
        assertTrue(original >= 0 && analyze > original && caught > analyze
                        && record > caught && rethrow > record,
                "fromLines must return once before staging; analyzer failure is recorded "
                        + "and exactly rethrown");
        assertTrue(mixin.contains("method = \"lambda$reload$3("));
        assertTrue(mixin.contains("require = 1") && mixin.contains("allow = 1"));
        assertTrue(mixin.contains("return vanilla.thenRun(")
                        && mixin.contains("FunctionAnalyzer.seal(stage, this.getFunctions())"));
        assertTrue(mixin.contains("FunctionAnalyzer.scopedReloadExecutor("
                        + "stage, preparationExecutor)")
                        && mixin.contains("FunctionAnalyzer.withReloadStage(stage,")
                        && mixin.contains("FunctionAnalyzer.currentReloadStage()"),
                "the reload call and every preparation task must inherit their exact "
                        + "immutable stage token");
        assertFalse(mixin.contains("fabrolia$analysisStage")
                        || mixin.contains("fabrolia$retireStage")
                        || mixin.contains("compareAndSet(stage"),
                "overlapping reloads must not communicate through a listener stage slot");
        assertFalse(mixin.contains(".exceptionally(") || mixin.contains(".handle("),
                "abort is exceptional completion/no manager install, never success recovery");

        String config = resource("fabrolia.common.mixins.json");
        assertTrue(config.contains("\"commands.ServerFunctionLibraryMixin\""));
        assertFalse(config.contains("\"commands.CommandFunctionMixin\""));
    }

    @Test
    void managerSwapAndProofConsumptionUseTheSameFairGenerationGate() throws Exception {
        String analyzer = source("regions/FunctionAnalyzer.java");
        assertTrue(analyzer.contains("new ReentrantReadWriteLock(true)"));
        int replace = analyzer.indexOf("replace.run();");
        int activate = analyzer.indexOf("ACTIVE = next;", replace);
        int unlock = analyzer.indexOf("write.unlock();", activate);
        assertTrue(replace >= 0 && activate > replace && unlock > activate,
                "vanilla library replacement must precede allocation-free activation "
                        + "inside the same write lock");
        assertTrue(analyzer.contains("entry.function() != current")
                        && analyzer.contains("action.run(Exactness.EXACT, entry.analysis())"));

        String manager = source("mixin/commands/ServerFunctionManagerMixin.java");
        assertTrue(manager.contains("@Inject(method = \"<init>\", at = @At(\"TAIL\"))")
                        && manager.contains("FunctionAnalyzer.activateInitial("));
        assertTrue(manager.contains("@WrapMethod(method = \"replaceLibrary\"")
                        && manager.contains("FunctionAnalyzer.replaceAndActivate(prepared,"));

        String commands = source("regions/IslandCommands.java");
        int lease = commands.indexOf("FunctionAnalyzer.withExactCurrentAnalysis(");
        int exact = commands.indexOf("FunctionAnalyzer.Exactness.EXACT", lease);
        int vanilla = commands.indexOf("return original.getAsBoolean();", exact);
        assertTrue(lease >= 0 && exact > lease && vanilla > exact,
                "the exact local /function body must execute inside the read-lease callback");
        assertFalse(commands.contains("tickFunctionIslandOwner(")
                        || commands.contains("FunctionAnalyzer.analysisOf("),
                "no identity-free function proof may remain in routing code");
    }

    private static String source(String relative) throws Exception {
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && here != null; i++, here = here.getParent()) {
            Path direct = here.resolve("src/main/java/com/axalotl/fabrolia/common")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            Path module = here.resolve("common/src/main/java/com/axalotl/fabrolia/common")
                    .resolve(relative);
            if (Files.isRegularFile(module)) return Files.readString(module);
            Path resource = here.resolve("common/src/main/resources").resolve(relative);
            if (Files.isRegularFile(resource)) return Files.readString(resource);
        }
        throw new IllegalStateException("source not found: " + relative);
    }

    private static String resource(String relative) throws Exception {
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && here != null; i++, here = here.getParent()) {
            Path direct = here.resolve("src/main/resources").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            Path module = here.resolve("common/src/main/resources").resolve(relative);
            if (Files.isRegularFile(module)) return Files.readString(module);
        }
        throw new IllegalStateException("resource not found: " + relative);
    }
}
