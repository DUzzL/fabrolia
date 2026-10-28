package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.utils.SyncItemPickup;
import com.axalotl.fabrolia.api.utils.FabroliaCompatible;
import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.api.FabroliaIslandApi;
import com.axalotl.fabrolia.common.config.FabroliaConfig;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual dispatch eligibility and the public W-clock query. */
@Isolated
class EntitySafetyPolicyTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nestedUnsupportedPassengerCannotInheritWorkerVenueAndDismountReleasesRoot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TestEntity root = fixture.entity(TestEntity.class, EntityTypes.PIG);
            TestEntity middle = fixture.entity(TestEntity.class, EntityTypes.PIG);
            TestEntity unsupported = fixture.entity(TestEntity.class, fixture.moddedType);
            CompatibleEntity compatible = fixture.entity(CompatibleEntity.class, fixture.moddedType);
            passengers(root, middle);
            assertFalse(ParallelProcessor.requiresMainThread(root));
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));

            passengers(middle, unsupported);
            assertTrue(ParallelProcessor.requiresMainThread(root));
            assertTrue(ParallelProcessor.shouldTickSynchronously(root));

            passengers(middle, compatible);
            assertFalse(ParallelProcessor.requiresMainThread(root));
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));

            passengers(middle);
            assertFalse(ParallelProcessor.requiresMainThread(root));
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));
        }
    }

    @Test
    void everyHardMainReasonAnywhereInTheTreeKeepsTheWholeTreeOnMain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TestEntity root = fixture.entity(TestEntity.class, EntityTypes.PIG);
            TestEntity middle = fixture.entity(TestEntity.class, EntityTypes.PIG);
            passengers(root, middle);
            for (Class<? extends Entity> type : List.of(
                    WitherBoss.class, EnderDragon.class, ServerPlayer.class)) {
                Entity reason = type.cast(unsafe().allocateInstance(type));
                passengers(reason);
                passengers(middle, reason);
                assertTrue(ParallelProcessor.requiresMainThread(root), type + " two levels down");
                passengers(middle);
                assertFalse(ParallelProcessor.requiresMainThread(root));
            }
            TestEntity forced = fixture.entity(TestEntity.class, EntityTypes.PIG);
            passengers(middle, forced);
            assertFalse(ParallelProcessor.requiresMainThread(root));
            @SuppressWarnings("unchecked")
            Set<UUID> forcedMain = (Set<UUID>) field(ParallelProcessor.class, "BLACKLISTED_ENTITIES").get(null);
            ParallelProcessor.forceMainThread(forced);
            try {
                assertTrue(ParallelProcessor.requiresMainThread(root));
            } finally {
                forcedMain.remove(forced.getUUID());
            }
            assertFalse(ParallelProcessor.requiresMainThread(root));
        }
    }

    @Test
    void existingEntityAndItsVehicleSeeExactWildcardAndRemovedPolicies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TestEntity root = fixture.entity(TestEntity.class, EntityTypes.PIG);
            TestEntity passenger = fixture.entity(TestEntity.class, EntityTypes.ITEM);
            passengers(root, passenger);
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));

            fixture.policy("minecraft:item");
            assertTrue(ParallelProcessor.shouldTickSynchronously(passenger));
            assertTrue(ParallelProcessor.shouldTickSynchronously(root));
            assertFalse(ParallelProcessor.requiresMainThread(root),
                    "regional serialization deliberately does not honor the flat-pool class policy");

            fixture.policy();
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));
            fixture.policy("minecraft:*");
            assertTrue(ParallelProcessor.shouldTickSynchronously(root));
            fixture.policy("othermod:*");
            assertFalse(ParallelProcessor.shouldTickSynchronously(root));
        }
    }

    @Test
    void worldTimeRemainsRawWallTimeAcrossWorkerLogicalClocks() throws Exception {
        TestLevel level = (TestLevel) unsafe().allocateInstance(TestLevel.class);
        level.data = new WallData(900L);
        assertEquals(900L, FabroliaIslandApi.worldTime(level));
        FutureTask<Void> query = new FutureTask<>(() -> {
            RegionWorkerThread worker = (RegionWorkerThread) Thread.currentThread();
            EpochDomain domain = new EpochDomain();
            worker.currentDomain = domain;
            try {
                domain.unitGameTime = 17L;
                assertEquals(17L, level.getGameTime());
                assertEquals(900L, FabroliaIslandApi.worldTime(level));
                domain.unitGameTime = 1400L;
                assertEquals(1400L, level.getGameTime());
                assertEquals(900L, FabroliaIslandApi.worldTime(level));
            } finally {
                worker.currentDomain = null;
            }
            return null;
        });
        Thread worker = new RegionWorkerThread(query, "world-time-policy-test");
        worker.start();
        try {
            query.get(5, TimeUnit.SECONDS);
        } finally {
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
    }

    private static final class Fixture implements AutoCloseable {
        final Set<String> previousPolicy = FabroliaConfig.synchronizedEntities;
        final boolean previousDisabled = FabroliaConfig.disabled;
        final boolean previousShuttingDown;
        final EntityType<?> moddedType;
        final Map<EntityType<?>, Holder.Reference<EntityType<?>>> registeredValues;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            previousShuttingDown = field(ParallelProcessor.class, "isShuttingDown").getBoolean(null);
            field(ParallelProcessor.class, "isShuttingDown").setBoolean(null, false);
            FabroliaConfig.disabled = false;
            // Supply only the registry identity consumed by EntityType.getKey;
            // remove it on close rather than permanently registering a test mod.
            moddedType = (EntityType<?>) unsafe().allocateInstance(EntityType.class);
            registeredValues = (Map<EntityType<?>, Holder.Reference<EntityType<?>>>)
                    field(MappedRegistry.class, "byValue").get(BuiltInRegistries.ENTITY_TYPE);
            registeredValues.put(moddedType, Holder.Reference.createStandAlone(
                    BuiltInRegistries.ENTITY_TYPE,
                    ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse("safety_test:passenger"))));
            policy();
        }

        <T extends TestEntity> T entity(Class<T> type, EntityType<?> entityType) throws Exception {
            T entity = type.cast(unsafe().allocateInstance(type));
            entity.guard = new SyncItemPickup.Guard();
            entity.entityType = entityType;
            entity.cache = -1;
            field(Entity.class, "uuid").set(entity, UUID.randomUUID());
            passengers(entity);
            return entity;
        }

        void policy(String... entries) {
            FabroliaConfig.synchronizedEntities = new HashSet<>(Set.of(entries));
            FabroliaConfig.onConfigLoaded();
        }

        @Override
        public void close() throws Exception {
            registeredValues.remove(moddedType);
            FabroliaConfig.synchronizedEntities = previousPolicy;
            FabroliaConfig.onConfigLoaded();
            FabroliaConfig.disabled = previousDisabled;
            field(ParallelProcessor.class, "isShuttingDown").setBoolean(null, previousShuttingDown);
        }
    }

    private static class TestEntity extends ItemEntity implements ParallelProcessor.TickGuard, SyncItemPickup.Guarded {
        EntityType<?> entityType;
        byte cache;
        SyncItemPickup.Guard guard;
        @Override public SyncItemPickup.Guard fabrolia$consumptionGuard() { return guard; }
        private TestEntity() { super(null, 0, 0, 0, null); }
        @Override public EntityType<?> getType() { return entityType; }
        @Override public EntityTickLease fabrolia$tickLease() { throw new AssertionError("not ticking"); }
        @Override public byte fabrolia$getSyncCache() { return cache; }
        @Override public void fabrolia$setSyncCache(byte value) { cache = value; }
    }

    @FabroliaCompatible
    private static final class CompatibleEntity extends TestEntity {}

    private static final class TestLevel extends ServerLevel {
        LevelData data;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public LevelData getLevelData() { return data; }
        @Override public long getGameTime() {
            long local = IslandClock.contextTime();
            return local >= 0 ? local : data.getGameTime();
        }
    }

    private record WallData(long time) implements LevelData, RawGameTime {
        @Override public long fabrolia$rawGameTime() { return time; }
        @Override public long getGameTime() { return time; }
        @Override public RespawnData getRespawnData() { throw new UnsupportedOperationException(); }
        @Override public boolean isHardcore() { return false; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return false; }
    }

    private static void passengers(Entity root, Entity... passengers) throws Exception {
        field(Entity.class, "passengers").set(root, ImmutableList.copyOf(passengers));
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
