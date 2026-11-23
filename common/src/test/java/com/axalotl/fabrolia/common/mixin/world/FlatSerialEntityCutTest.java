package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.EntityTickLease;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class FlatSerialEntityCutTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void earlierTickCanPreventALaterMobsNativeHardDespawn() throws Exception {
        TestLevel level = allocate(TestLevel.class);
        level.first = allocate(FirstRoot.class);
        level.first.lease = new EntityTickLease();
        level.zombie = allocate(FarZombie.class);
        level.zombie.lease = new EntityTickLease();
        level.zombie.world = level;
        level.events = new ArrayList<>();
        // Establish that vanilla really does discard this mob at this distance
        // before the earlier root sets PersistenceRequired (e.g. via a command).
        level.zombie.checkDespawn();
        assertTrue(level.zombie.discarded);
        level.zombie.discarded = false;
        level.events.clear();

        var rate = new TickRateManager() {
            @Override public boolean isEntityFrozen(Entity entity) {
                level.events.add(entity == level.first ? "first freeze check" : "zombie freeze check");
                return super.isEntityFrozen(entity);
            }
        };
        var visit = ServerLevelMixin.class.getDeclaredMethod("fabrolia$tickFlatSerialCut", List.class,
                ServerLevel.class, TickRateManager.class, DistanceManager.class, ServerLevelMixin.FabroliaFlatFailureGate.class);
        visit.setAccessible(true); // static mixin helpers must be private even before transformation
        visit.invoke(null, List.of(level.first, level.zombie), level, rate, allocate(InRange.class),
                new ServerLevelMixin.FabroliaFlatFailureGate());

        assertFalse(level.zombie.discarded);
        assertEquals(List.of("first freeze check", "first tick", "zombie freeze check",
                "zombie despawn check", "zombie tick"), level.events);
    }

    private static final class TestLevel extends ServerLevel {
        FirstRoot first;
        FarZombie zombie;
        List<String> events;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public Player getNearestPlayer(Entity entity, double distance) { return first; }
        @Override public void tickNonPassenger(Entity entity) {
            if (entity == first) {
                events.add("first tick");
                zombie.setPersistenceRequired();
            } else {
                events.add("zombie tick");
            }
        }
    }

    private static final class FirstRoot extends ServerPlayer implements ParallelProcessor.TickGuard {
        EntityTickLease lease;
        private FirstRoot() { super(null, null, null, null); }
        @Override public double distanceToSqr(Entity entity) { return 256.0 * 256.0; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class FarZombie extends Zombie implements ParallelProcessor.TickGuard {
        TestLevel world;
        EntityTickLease lease;
        boolean discarded;
        private FarZombie() { super(null); }
        @Override public ServerLevel level() { return world; }
        @Override public EntityType<? extends Zombie> getType() { return EntityTypes.ZOMBIE; }
        @Override public ChunkPos chunkPosition() { return ChunkPos.ZERO; }
        @Override public void checkDespawn() {
            world.events.add("zombie despawn check");
            super.checkDespawn();
        }
        @Override public void remove(RemovalReason reason) { discarded = reason == RemovalReason.DISCARDED; }
        @Override public EntityTickLease fabrolia$tickLease() { return lease; }
        @Override public byte fabrolia$getSyncCache() { return 0; }
        @Override public void fabrolia$setSyncCache(byte value) {}
    }

    private static final class InRange extends DistanceManager {
        private InRange() { super(null, null, null); }
        @Override public boolean inEntityTickingRange(long pos) { return true; }
        @Override protected boolean isChunkToRemove(long pos) { throw new AssertionError(); }
        @Override protected ChunkHolder getChunk(long pos) { throw new AssertionError(); }
        @Override protected ChunkHolder updateChunkScheduling(long pos, int level, ChunkHolder chunk, int oldLevel) {
            throw new AssertionError();
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
