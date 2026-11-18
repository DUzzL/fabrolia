package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client chooses how many packet continuations it produces. They must be refused
 * before they can reach the routed-mutation bound whose overflow stops the server
 * (6000 empty book edits in one burst did, 2026-11-18).
 */
class ClientRoutedContinuationBoundTest {

    @Test
    void theClientBoundIsBelowTheFatalBound() throws Exception {
        Field fatal = AsyncIslandPlayerTicks.class.getDeclaredField("MAX_ROUTED_MUTATIONS");
        fatal.setAccessible(true);
        assertTrue(AsyncIslandPlayerTicks.MAX_CLIENT_ROUTED_MUTATIONS > 0);
        assertTrue(AsyncIslandPlayerTicks.MAX_CLIENT_ROUTED_MUTATIONS < fatal.getInt(null),
                "client-driven continuations must be refused before the fail-stop bound");
    }

    @Test
    void aClientOfferIsDroppedBeforeEitherFailStop() throws Exception {
        String ticks = source("regions/AsyncIslandPlayerTicks.java");
        String offer = section(ticks, "private static ClientRoutedOffer offerRoutedMutation(",
                "private static void droppedClientRoutedMutation(");
        int disconnectingDrop = offer.indexOf("if (clientDriven) return ClientRoutedOffer.DROPPED;");
        int disconnectingThrow = offer.indexOf("' targeted a disconnecting listener\"");
        int clientBound = offer.indexOf("clientDriven && state.routedIngressCount >= MAX_CLIENT_ROUTED_MUTATIONS");
        int fatalBound = offer.indexOf("state.routedIngressCount >= MAX_ROUTED_MUTATIONS");
        int append = offer.indexOf("state.routedIngressCount++");
        assertTrue(disconnectingDrop >= 0 && disconnectingDrop < disconnectingThrow,
                "a client continuation for a disconnecting listener is dropped, not fatal");
        assertTrue(clientBound >= 0 && clientBound < fatalBound && fatalBound < append,
                "the client bound is checked before the fatal bound and before the append");
    }

    @Test
    void playerPacketContinuationsUseTheClientOffer() throws Exception {
        String continuations = source("regions/AsyncIslandPacketContinuations.java");
        String enqueuePlayer = section(continuations, "public static boolean enqueuePlayer(",
                "public static boolean enqueueMain(");
        assertTrue(enqueuePlayer.contains("AsyncIslandPlayerTicks.offerClientRoutedMutation("));
        assertFalse(enqueuePlayer.contains("AsyncIslandPlayerTicks.enqueueRoutedMutation("),
                "enqueueRoutedMutation fail-stops on overflow and on a disconnecting listener");
        assertTrue(enqueuePlayer.contains("offer != AsyncIslandPlayerTicks.ClientRoutedOffer.NOT_OWNED"),
                "a dropped continuation is owned by A5: it must not fall back to vanilla's inline path");
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        assertTrue(start >= 0, "missing " + startNeedle);
        int end = text.indexOf(endNeedle, start);
        assertTrue(end > start, "missing " + endNeedle);
        return text.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("src/main/java/com/axalotl/fabrolia/common", relative);
        if (!Files.exists(path)) {
            path = Path.of("common").resolve(path);
        }
        return Files.readString(path);
    }
}
