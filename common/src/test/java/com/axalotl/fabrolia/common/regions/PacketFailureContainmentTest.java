package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 packet bodies follow vanilla PacketProcessor: another mod's ordinary
 * exception is reported through onPacketError instead of stopping the server,
 * while Fabrolia's own invariant failures stay fail-stop.
 */
final class PacketFailureContainmentTest {

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, owner + ".java", 1);
    }

    /** GroupChat 1.2.0: its suggestion provider reads an argument absent on that path. */
    private static IllegalArgumentException foreignSuggestionBug() {
        IllegalArgumentException failure =
                new IllegalArgumentException("No such argument 'group' exists on this command");
        failure.setStackTrace(new StackTraceElement[] {
                frame("com.mojang.brigadier.context.CommandContext", "getArgument"),
                frame("de.groupchat.fabric.GcCommands", "arg"),
                frame("com.axalotl.fabrolia.common.regions.AsyncIslandPlayerTicks", "handle")});
        return failure;
    }

    private static final class Recorder {
        final List<Exception> reported = new ArrayList<>();
        @SuppressWarnings("rawtypes")
        Packet packet;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Recorder deliver(RuntimeException thrownByBody) {
        Recorder recorder = new Recorder();
        PacketListener listener = (PacketListener) Proxy.newProxyInstance(
                PacketListener.class.getClassLoader(), new Class<?>[] {PacketListener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onPacketError")) {
                        recorder.reported.add((Exception) args[1]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        recorder.packet = (Packet) Proxy.newProxyInstance(
                Packet.class.getClassLoader(), new Class<?>[] {Packet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("handle")) throw thrownByBody;
                    throw new UnsupportedOperationException(method.getName());
                });
        AsyncIslandPlayerTicks.handle(recorder.packet, listener);
        return recorder;
    }

    @Test
    void foreignModExceptionIsReportedAndTheDrainContinues() {
        IllegalArgumentException bug = foreignSuggestionBug();
        Recorder recorder = deliver(bug);
        assertEquals(1, recorder.reported.size());
        assertSame(bug, recorder.reported.get(0));
    }

    @Test
    void fabroliaInvariantFromAMergedMixinMemberStaysFailStop() {
        IllegalStateException invariant = new IllegalStateException("A5 source-local packet left its claimed island");
        invariant.setStackTrace(new StackTraceElement[] {
                frame("net.minecraft.server.network.ServerGamePacketListenerImpl",
                        "handler$zbc000$fabrolia$ownUseItemOn"),
                frame("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleUseItemOn")});
        assertSame(invariant, assertThrows(IllegalStateException.class, () -> deliver(invariant)));
    }

    @Test
    void fabroliaFailureWrappedByVanillaStaysFailStop() {
        IllegalStateException invariant = new IllegalStateException("A5 claim lost");
        invariant.setStackTrace(new StackTraceElement[] {
                frame("java.util.Objects", "requireNonNull"),
                frame("com.axalotl.fabrolia.common.regions.IslandCommands", "mainClaim")});
        RuntimeException wrapped = new RuntimeException("Ticking player", invariant);
        wrapped.setStackTrace(new StackTraceElement[] {
                frame("net.minecraft.server.level.ServerPlayer", "doTick")});
        assertSame(wrapped, assertThrows(RuntimeException.class, () -> deliver(wrapped)));
    }

    @Test
    void errorsAreNeverContained() {
        StackOverflowError overflow = new StackOverflowError();
        Recorder recorder = new Recorder();
        assertThrows(StackOverflowError.class, () -> AsyncIslandPlayerTicks.handle(
                (Packet<?>) Proxy.newProxyInstance(Packet.class.getClassLoader(),
                        new Class<?>[] {Packet.class}, (proxy, method, args) -> {
                            throw overflow;
                        }),
                (PacketListener) Proxy.newProxyInstance(PacketListener.class.getClassLoader(),
                        new Class<?>[] {PacketListener.class}, (proxy, method, args) -> {
                            recorder.reported.add(new IllegalStateException(method.getName()));
                            return null;
                        })));
        assertTrue(recorder.reported.isEmpty());
    }
}
