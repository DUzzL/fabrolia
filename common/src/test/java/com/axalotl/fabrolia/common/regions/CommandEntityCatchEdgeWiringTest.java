package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins publication-before-cleanup at wrappers which enter vanilla/Fabric bodies. */
final class CommandEntityCatchEdgeWiringTest {

    @Test
    void functionBodiesPublishBeforeMarkerOrFenceCleanup() throws Exception {
        String source = source("common/mixin/commands/ServerFunctionManagerMixin.java");
        assertBodyCatchBeforeCleanup(source, "fabrolia$markTickTag(",
                "original.call()", "fabrolia$inTickTag = false");
        int tick = source.indexOf("fabrolia$markTickTag(");
        int drain = source.indexOf("AdvancementBuffer.beginTickHeadWindow(", tick);
        int admission = source.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(() -> original.call())", drain);
        int tickBody = source.indexOf("original.call()", admission);
        int tickCatch = source.indexOf("catch (Throwable thrown)", tickBody);
        int markerCleanup = source.indexOf("fabrolia$inTickTag = false", tickCatch);
        assertTrue(tick >= 0 && drain > tick && admission > drain
                        && tickBody > admission && tickCatch > tickBody
                        && markerCleanup > tickCatch,
                "the empty advancement-drain path must still atomically admit the tick-tag "
                        + "vanilla body against peer W before marker cleanup");
        assertBodyCatchBeforeCleanup(source, "fabrolia$fenceFunction(",
                "original.call(function, source)", "if (failure != null)");
        String function = section(source, "fabrolia$fenceFunction(",
                "@org.spongepowered.asm.mixin.Unique");
        assertTrue(function.contains("RegionScheduler.runSuccessBodyIfHealthy(")
                        && function.contains("RegionScheduler.commitSuccessIfHealthy("),
                "function body and metrics need separate healthy cuts");
    }


    @Test
    void spawnPackFailurePublishesBeforeReturningItsReservation() throws Exception {
        String source = source("common/mixin/entity/spawn/NaturalSpawnerMixin.java");
        String body = section(source, "fabrolia$releasePackAfterSpawn(",
                "private static Throwable fabrolia$mergeFailure");
        int noToken = body.indexOf("if (token == 0)");
        int noTokenAdmission = body.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", noToken);
        int noTokenBody = body.indexOf(
                "original.call(category, level, chunk, extraTest, spawnCallback)",
                noTokenAdmission);
        int tokenClear = body.indexOf("reservation.set(0)", noTokenBody);
        int admission = body.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(", tokenClear);
        int pack = body.indexOf(
                "original.call(category, level, chunk, extraTest, spawnCallback)", admission);
        int bodyCatch = body.indexOf("catch (Throwable thrown)", pack);
        int primary = body.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(thrown)", bodyCatch);
        int release = body.indexOf(
                "fabrolia$releaseLocalPack(category, chunk.getPos().pack(), token)", primary);
        int cleanupCatch = body.indexOf("catch (Throwable cleanup)", release);
        int winner = body.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(", cleanupCatch);
        int terminal = body.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", cleanupCatch);
        int rethrow = body.indexOf("fabrolia$rethrow(failure)", terminal);
        assertTrue(noToken >= 0 && noTokenAdmission > noToken
                        && noTokenBody > noTokenAdmission && tokenClear > noTokenBody
                        && admission > tokenClear && pack > admission && bodyCatch > pack
                        && primary > bodyCatch && release > primary
                        && cleanupCatch > release && winner > cleanupCatch
                        && terminal > cleanupCatch && rethrow > terminal,
                "both pack bodies need atomic entry; reserved failure must select W before "
                        + "mandatory release, then normal return needs a post-cleanup cut");
        assertFalse(body.contains("throwWorkerFailureIfAny"),
                "check-then-pack is not an atomic W-vs-entry cut");
        assertFalse(source.contains("catch (Throwable ignored)"));
    }

    @Test
    void sleepWakeBodyIsAtomicAndFenceCleanupCannotHideW() throws Exception {
        String source = source("common/mixin/world/SleepWakeMixin.java");
        String body = section(source, "fabrolia$ownerSafeWake(",
                "@SuppressWarnings(\"unchecked\")");
        int quiesce = body.indexOf("rs.quiesceAllIslands()");
        int admission = body.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(original::call)", quiesce);
        int bodyCatch = body.indexOf("catch (Throwable primary)", admission);
        int primary = body.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(primary)", bodyCatch);
        int release = body.indexOf("rs.releaseAllIslands(failure)", primary);
        int terminal = body.indexOf(
                "RegionScheduler.commitSuccessIfHealthy(() -> {})", release);
        int rethrow = body.indexOf("rethrow(failure)", terminal);
        int fallback = body.indexOf(
                "RegionScheduler.runSuccessBodyIfHealthy(original::call)", rethrow);
        assertTrue(quiesce >= 0 && admission > quiesce && bodyCatch > admission
                        && primary > bodyCatch && release > primary
                        && terminal > release && rethrow > terminal && fallback > rethrow,
                "wake body must enter atomically; exact W is selected before exhaustive fence "
                        + "release and checked again at the post-cleanup success terminal");
        assertFalse(body.contains("throwWorkerFailureIfAny"));
        assertFalse(body.contains("finally"),
                "a throwing cleanup edge must not replace the wake-body primary");
    }

    @Test
    void debugPacketAndHereCommandsDoNotConvertEnteredFailuresIntoResults() throws Exception {
        String source = source("common/commands/RegionCommand.java");
        int command = source.indexOf("literal(\"offmainpkt\")");
        int body = source.indexOf("sp.connection.handleUseItem(pkt)", command);
        int caught = source.indexOf("catch (Throwable th)", body);
        int expectedSentinel = source.indexOf(
                "th instanceof net.minecraft.server.RunningOnDifferentThreadException", caught);
        int fatal = source.indexOf(".publishAndPropagate(th)", expectedSentinel);
        int impossibleReturn = source.indexOf(
                "fatal off-main packet publication returned", fatal);
        assertTrue(command >= 0 && body > command && caught > body
                        && expectedSentinel > caught && fatal > expectedSentinel
                        && impossibleReturn > fatal,
                "an unexpected entered debug packet failure must publish and escape exact W");
        assertFalse(source.substring(command, impossibleReturn)
                        .contains("unexpected off-main throw"),
                "the unexpected packet branch may not log-and-return a normal command result");

        assertHereCommandCatchesOnlyPlayerLookup(source, "islandRateHere(", "applyRate(");
        assertHereCommandCatchesOnlyPlayerLookup(source, "islandSprintHere(", "applySprint(");

        int nameHere = source.indexOf("literal(\"name\")");
        int nameLookup = source.indexOf("getPlayerOrException()", nameHere);
        int nameSyntax = source.indexOf(
                "catch (com.mojang.brigadier.exceptions.CommandSyntaxException", nameLookup);
        int aliasWrite = source.indexOf("islN.alias =", nameSyntax);
        int clearHere = source.indexOf("literal(\"clear\")", aliasWrite);
        int clearLookup = source.indexOf("getPlayerOrException()", clearHere);
        int clearSyntax = source.indexOf(
                "catch (com.mojang.brigadier.exceptions.CommandSyntaxException", clearLookup);
        int clearWrite = source.indexOf("islN.alias = null", clearSyntax);
        assertTrue(nameHere >= 0 && nameLookup > nameHere && nameSyntax > nameLookup
                        && aliasWrite > nameSyntax && clearHere > aliasWrite
                        && clearLookup > clearHere && clearSyntax > clearLookup
                        && clearWrite > clearSyntax,
                "name here/clear here may translate only player lookup refusal; alias and "
                        + "response failures must propagate");
        assertFalse(source.substring(nameSyntax, clearHere).contains("catch (Exception"));

        int tps = source.indexOf("literal(\"tps\")");
        int tpsLookup = source.indexOf("getPlayerOrException()", tps);
        int tpsSyntax = source.indexOf(
                "catch (com.mojang.brigadier.exceptions.CommandSyntaxException", tpsLookup);
        int tpsToggle = source.indexOf("TpsHud.toggle(player)", tpsSyntax);
        int tpsSend = source.indexOf("on ? \"subscribed (action bar)\"", tpsToggle);
        assertTrue(tps >= 0 && tpsLookup > tps && tpsSyntax > tpsLookup
                        && tpsToggle > tpsSyntax && tpsSend > tpsToggle,
                "TPS HUD may translate only the pre-body player lookup refusal; mutation and "
                        + "response failures must propagate");
        assertFalse(source.substring(tpsSyntax, tpsSend).contains("catch (Exception"));
    }

    @Test
    void configReloadNeverConvertsRuntimeOrResponseFailuresIntoCommandSuccess()
            throws Exception {
        String source = source("common/commands/ConfigCommand.java");
        int reloadCommand = source.indexOf("private static LiteralArgumentBuilder<CommandSourceStack> buildReloadCommand()");
        int reload = source.indexOf("PlatformUtils.reloadConfig()", reloadCommand);
        int apply = source.indexOf(".RegionalPlayerEntityTicksV2.applyConfiguredPreference(", reload);
        int success = source.indexOf("ctx.getSource().sendSuccess", apply);
        int nextCommand = source.indexOf(
                "private static LiteralArgumentBuilder<CommandSourceStack> buildToggleCommand()",
                success);
        assertTrue(reloadCommand >= 0 && reload > reloadCommand && apply > reload
                        && success > apply && nextCommand > success,
                "reload, runtime preference application, and success response must remain one "
                        + "uncaught causal path");
        String body = source.substring(reloadCommand, nextCommand);
        assertFalse(body.contains("catch (Exception"),
                "reload/runtime/send failure may not be caught and converted to return 1");
        assertFalse(body.contains("Failed to reload config"),
                "an entered reload failure must propagate instead of becoming operator text");
    }

    @Test
    void runtimeTransitionCommandsPublishExactWBeforePreferenceRollback() throws Exception {
        String source = source("common/commands/RegionCommand.java");
        int threading = source.indexOf("private static void setEnabled(");
        int transitionCatch = source.indexOf("catch (Throwable failure)", threading);
        int transitionWinner = source.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(failure)", transitionCatch);
        int threadingRollback = source.indexOf(
                "FabroliaConfig.enableRegionThreading = previous", transitionWinner);

        int player = source.indexOf("private static int setPlayerEntityTicks(");
        int playerCatch = source.indexOf("catch (Throwable failure)", player);
        int playerWinner = source.indexOf(
                "RegionScheduler.publishFatalAndSelectWinner(failure)", playerCatch);
        int playerRollback = source.indexOf(
                "FabroliaConfig.enablePlayerTickActor = previousPreference", playerWinner);
        assertTrue(threading >= 0 && transitionCatch > threading
                        && transitionWinner > transitionCatch
                        && threadingRollback > transitionWinner
                        && player > threadingRollback && playerCatch > player
                        && playerWinner > playerCatch && playerRollback > playerWinner,
                "start/stop/player ownership transition W must close the generation before "
                        + "fallible persisted-preference cleanup");
    }

    

    private static void assertHereCommandCatchesOnlyPlayerLookup(
            String source, String method, String mutation) {
        int start = source.indexOf(method);
        int lookup = source.indexOf("getPlayerOrException()", start);
        int syntaxCatch = source.indexOf(
                "catch (com.mojang.brigadier.exceptions.CommandSyntaxException", lookup);
        int mutationCall = source.indexOf(mutation, syntaxCatch);
        int nextMethod = source.indexOf("private static", mutationCall + mutation.length());
        String tail = source.substring(syntaxCatch,
                nextMethod < 0 ? source.length() : nextMethod);
        assertTrue(start >= 0 && lookup > start && syntaxCatch > lookup
                        && mutationCall > syntaxCatch,
                "only pre-body player lookup may map CommandSyntaxException to 'player required': "
                        + method);
        assertFalse(tail.contains("catch (Exception"),
                "the entered pacing mutation may not be caught and converted to success/error text");
    }

    private static void assertBodyCatchBeforeCleanup(String source, String method,
                                                     String body, String cleanup) {
        int methodStart = source.indexOf(method);
        int bodyStart = source.indexOf(body, methodStart);
        int cleanupStart = source.indexOf(cleanup, bodyStart);
        int bodyCatch = source.indexOf("catch (Throwable thrown)", bodyStart);
        int fatal = source.indexOf(
                "failure = RegionScheduler.publishFatalAndSelectWinner(thrown)", bodyCatch);
        assertTrue(methodStart >= 0 && bodyStart > methodStart && bodyCatch > bodyStart
                        && cleanupStart > bodyStart && bodyCatch < cleanupStart
                        && fatal > bodyCatch && fatal < cleanupStart,
                "entered body must publish the authoritative fatal before cleanup: " + method);
    }

    private static String section(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle,
                Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                "source section not found: " + startNeedle);
        return source.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }
}
