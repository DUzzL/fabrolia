package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.api.utils.SnapshotList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerChunkIndexSnapshotTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void rebuildPinsOnePublishedListGenerationAndRetainsNativeOrder() throws Exception {
        ServerPlayer first = player(-1, 0);
        ServerPlayer second = player(1, 0);
        SnapshotList<ServerPlayer> backing = new SnapshotList<>();
        backing.add(first);
        backing.add(second);
        // Force publication between structural operations, not a probabilistic race.
        List<ServerPlayer> changing = new AbstractList<>() {
            @Override public Iterator<ServerPlayer> iterator() {
                Iterator<ServerPlayer> generation = backing.iterator();
                backing.clear();
                return generation;
            }
            @Override public int size() {
                int count = backing.size();
                backing.clear();
                return count;
            }
            @Override public ServerPlayer get(int index) { return backing.get(index); }
        };
        TestLevel level = level(changing);
        try {
            PlayerChunkIndex.rebuild(level);
            PlayerChunkIndex index = PlayerChunkIndex.usable(level);
            assertNotNull(index);
            assertEquals(List.of(first, second), index.candidates(0, 0, Double.POSITIVE_INFINITY));
            assertTrue(backing.isEmpty());
        } finally {
            PlayerChunkIndex.onLevelUnload(level);
        }
    }


    @Test
    void hugeAndInfiniteRadiusHavePlayerBoundedCostAndKeepTheWholeCensus() throws Exception {
        ServerPlayer nearest = player(100_000, 0);
        TestLevel level = level(List.of(nearest, player(200_000, 0)));
        try {
            PlayerChunkIndex.rebuild(level);
            PlayerChunkIndex index = PlayerChunkIndex.usable(level);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertEquals(level.players(), index.candidates(0, 0, 1_000_000));
                assertEquals(level.players(), index.candidates(0, 0, Double.POSITIVE_INFINITY));
            });
        } finally {
            PlayerChunkIndex.onLevelUnload(level);
        }
    }

    @Test
    void nestedCandidateQueryCannotReplaceTheOuterGeneration() throws Exception {
        ServerPlayer first = player(2, 0);
        ServerPlayer nearest = player(1, 0);
        ArrayList<ServerPlayer> players = new ArrayList<>(List.of(first, nearest));
        for (int i = 0; i < 40; i++) players.add(player(10_000 + i * 16, 10_000));
        TestLevel level = level(players);
        try {
            PlayerChunkIndex.rebuild(level);
            PlayerChunkIndex index = PlayerChunkIndex.usable(level);
            List<ServerPlayer> outer = index.candidates(0, 0, 16);
            index.candidates(10_000, 10_000, 0);
            assertEquals(List.of(first, nearest), outer);
        } finally {
            PlayerChunkIndex.onLevelUnload(level);
        }
    }


    private static ServerPlayer player(double x, double z) throws Exception {
        ServerPlayer player = (ServerPlayer) unsafe().allocateInstance(TestPlayer.class);
        Field position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(player, new Vec3(x, 0, z));
        return player;
    }

    private static TestLevel level(List<ServerPlayer> players) throws Exception {
        TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
        level.testPlayers = players;
        return level;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static final class TestPlayer extends ServerPlayer {
        private TestPlayer() {
            super(null, null, null, null);
            throw new AssertionError("constructor bypassed for player position fixture");
        }
    }

    private static final class TestLevel extends ServerLevel {
        private List<ServerPlayer> testPlayers;
        private TestLevel() {
            super(null, null, null, null, null, null, false, 0, null, false);
            throw new AssertionError("constructor bypassed for level identity fixture");
        }
        @Override public List<ServerPlayer> players() { return testPlayers; }
        @Override public MinecraftServer getServer() { return null; }
        @Override public long getGameTime() { return 40; }
    }
}
