package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.commands.PlayerListFunctionSnapshotMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker-side PlayerList broadcasts (positional sounds, level events, death
 * messages) read one published player copy for the whole call instead of the
 * live list Main mutates.
 */
final class WorkerBroadcastSnapshotTest {
    private static final List<ServerPlayer> LIVE = List.of();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        FunctionPlayerSnapshot.publish(List.of());
    }

    @Test
    void workerCallReadsOnePinnedCopyAcrossARepublish() throws Throwable {
        List<ServerPlayer> first = List.of(player("a"), player("b"));
        List<ServerPlayer> second = List.of(player("c"));
        FunctionPlayerSnapshot.publish(first);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new RegionWorkerThread(() -> {
            try {
                pinned(() -> {
                    assertEquals(first, read());
                    FunctionPlayerSnapshot.publish(second);
                    assertEquals(first, read(), "a republish must not change a running call's list");
                    pinned(() -> {
                        assertEquals(first, read(), "a nested broadcast keeps the outer copy");
                        return null;
                    });
                    assertEquals(first, read(), "the nested exit keeps the outer pin");
                    return null;
                });
                assertSame(LIVE, read(), "outside a wrapped call the field reads through");
                pinned(() -> {
                    assertEquals(second, read(), "the next call pins the newest copy");
                    return null;
                });
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "broadcast-worker");
        worker.start();
        worker.join();
        if (failure.get() != null) throw failure.get();
    }

    @Test
    void mainKeepsTheLiveList() throws Throwable {
        FunctionPlayerSnapshot.publish(List.of(player("a")));
        pinned(() -> {
            assertSame(LIVE, read(), "Main reads vanilla's live list");
            return null;
        });
    }

    @Test
    void everyWrappedVanillaMethodReadsThePlayerField() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = PlayerList.class.getResourceAsStream("PlayerList.class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        List<String> targets = List.of(
                "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;"
                        + "Lnet/minecraft/network/protocol/Packet;)V",
                "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
                "broadcastSystemToAllExceptTeam(Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/network/chat/Component;)V",
                "getPlayerByName(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;");
        for (String target : targets) {
            var method = node.methods.stream().filter(m -> target.equals(m.name + m.desc))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + target));
            int reads = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.name.equals("players")) reads++;
            }
            assertTrue(reads > 0, target + " must read PlayerList.players");
        }
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("broadcastSystemToTeam")));
    }

    @SuppressWarnings("unchecked")
    private static Object pinned(Supplier<Object> body) {
        try {
            Method method = PlayerListFunctionSnapshotMixin.class.getDeclaredMethod(
                    "fabrolia$withPinnedPlayers", Supplier.class);
            method.setAccessible(true);
            return method.invoke(null, body);
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            if (thrown.getCause() instanceof Error error) throw error;
            throw new AssertionError(thrown.getCause());
        } catch (ReflectiveOperationException problem) {
            throw new AssertionError(problem);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> read() {
        try {
            Method method = PlayerListFunctionSnapshotMixin.class.getDeclaredMethod(
                    "fabrolia$readPinnedPlayers", PlayerList.class, Operation.class);
            method.setAccessible(true);
            Operation<List<ServerPlayer>> live = arguments -> LIVE;
            Object mixin = new PlayerListFunctionSnapshotMixin() {};
            return (List<ServerPlayer>) method.invoke(mixin, null, live);
        } catch (Exception problem) {
            throw new AssertionError(problem);
        }
    }

    private static ServerPlayer player(String name) throws Exception {
        Named player = (Named) unsafe().allocateInstance(Named.class);
        player.name = name;
        return player;
    }

    private static final class Named extends ServerPlayer {
        String name;
        private Named() { super(null, null, null, null); }
        @Override public String getScoreboardName() { return name; }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
