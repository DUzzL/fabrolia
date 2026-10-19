package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-16/17/18 production stops: after SERVER_STOPPING stopped the region pool,
 * a queued A5 Main ingress drain handled play packets that vanilla's closed
 * PacketProcessor would have dropped, then tried to publish an owed entity ordinal
 * to the stopped pool. The failure escaped stopServer before the world was saved.
 */
final class A5ShutdownIngressTest {

    @Test
    void aQueuedDecodedPacketOfANonAcceptingListenerIsNotHandled() {
        AtomicInteger handled = new AtomicInteger();
        AsyncIslandPlayerTicks.handle(new DecodedPacket(handled), new Listener(false));
        assertEquals(0, handled.get(), "vanilla's ListenerAndPacket.handle skips it");
        AsyncIslandPlayerTicks.handle(new DecodedPacket(handled), new Listener(true));
        assertEquals(1, handled.get());
    }

    @Test
    void inProcessEntriesKeepTheirOwnLifetimeRules() {
        AtomicInteger handled = new AtomicInteger();
        AsyncIslandPlayerTicks.handle(new InProcessPacket(handled), new Listener(false));
        assertEquals(1, handled.get(),
                "continuations model server-executor work, which vanilla still runs");
        assertTrue(AsyncIslandPlayerTicks.vanillaHandlesQueuedPacket(
                new InProcessPacket(handled), new Listener(false)));
        assertFalse(AsyncIslandPlayerTicks.vanillaHandlesQueuedPacket(
                new DecodedPacket(handled), new Listener(false)));
    }

    @Test
    void everyListenerLaneContinuationIsAnInProcessEntry() throws Exception {
        String continuations = source("common/regions/AsyncIslandPacketContinuations.java");
        assertEquals(3, count(continuations,
                "implements Packet<ServerGamePacketListenerImpl>, InProcessListenerPacket"));
        assertEquals(3, count(continuations, "implements Packet<ServerGamePacketListenerImpl>"));
        String ticks = source("common/regions/AsyncIslandPlayerTicks.java");
        assertEquals(1, count(ticks,
                "implements Packet<ServerGamePacketListenerImpl>, InProcessListenerPacket"));
        assertEquals(1, count(ticks, "implements Packet<ServerGamePacketListenerImpl>"));
    }

    @Test
    void packetGatesPrecedeEveryHandledBody() throws Exception {
        String ticks = source("common/regions/AsyncIslandPlayerTicks.java");
        String handle = section(ticks, "static void handle(Packet packet, PacketListener listener) {",
                "public static boolean vanillaContainsPacketFailure");
        int gate = handle.indexOf("if (!vanillaHandlesQueuedPacket(packet, listener)) {");
        int body = handle.indexOf("packet.handle(listener);");
        assertTrue(gate >= 0 && body > gate);
        String gates = section(ticks, "static boolean vanillaHandlesQueuedPacket(",
                "static boolean vanillaPacketIngressClosed(");
        assertTrue(gates.contains("packet instanceof InProcessListenerPacket"));
        assertTrue(gates.contains("!vanillaPacketIngressClosed(listener) && listener.shouldHandleMessage(packet)"));

        String drain = section(ticks, "private static void drainMainIngress(PlayerState state,",
                "private static boolean isOwnVenueIngressHead(");
        int sequence = drain.indexOf("hasInvalidBlockAckSequence(inbound.packet)");
        int sequenceGate = drain.indexOf(
                "vanillaHandlesQueuedPacket(inbound.packet, inbound.listener)", sequence);
        int packetError = drain.indexOf("inbound.listener.onPacketError(", sequence);
        assertTrue(sequence >= 0 && sequenceGate > sequence && packetError > sequenceGate,
                "vanilla checks the block-ack sequence inside the handler, after both gates");
    }

    @Test
    void anOwedEntityOrdinalRetiresWithoutABodyOnceStopServerBegan() throws Exception {
        String drain = section(source("common/regions/AsyncIslandPlayerTicks.java"),
                "private static void drainMainIngress(PlayerState state,",
                "private static boolean isOwnVenueIngressHead(");
        int ordinal = drain.indexOf("if (inbound.entityOrdinal()) {");
        int closed = drain.indexOf("if (vanillaPacketIngressClosed(listener)) {", ordinal);
        int poll = drain.indexOf("state.mainIngress.pollFirst();", closed);
        int account = drain.indexOf("accountMainEntityOrdinalCompletionLocked(state, inbound);", poll);
        int next = drain.indexOf("continue;", account);
        int activation = drain.indexOf("state.activeMainEntityFallback = inbound;", ordinal);
        assertTrue(ordinal >= 0 && closed > ordinal && poll > closed && account > poll
                        && next > account && activation > next,
                "the shutdown retirement must precede activation and publication");
    }

    private record Listener(boolean accepting) implements PacketListener {
        @Override public PacketFlow flow() { return PacketFlow.SERVERBOUND; }
        @Override public ConnectionProtocol protocol() { return ConnectionProtocol.PLAY; }
        @Override public void onDisconnect(DisconnectionDetails details) {}
        @Override public boolean isAcceptingMessages() { return accepting; }
    }

    private record DecodedPacket(AtomicInteger handled) implements Packet<Listener> {
        @Override public PacketType<? extends Packet<Listener>> type() { return null; }
        @Override public void handle(Listener listener) { handled.incrementAndGet(); }
    }

    private record InProcessPacket(AtomicInteger handled)
            implements Packet<Listener>, InProcessListenerPacket {
        @Override public PacketType<? extends Packet<Listener>> type() { return null; }
        @Override public void handle(Listener listener) { handled.incrementAndGet(); }
    }

    private static int count(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
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
