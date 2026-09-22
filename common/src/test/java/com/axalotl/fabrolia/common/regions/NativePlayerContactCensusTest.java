package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Long2IntConcurrentHashMap;
import com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor;
import com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class NativePlayerContactCensusTest {
    private static final AABB CONTACT_BOX = new AABB(7, 7, 7, 10, 11, 10);
    private static final AABB OUTSIDE_BOX = new AABB(12, 8, 8, 13, 9, 9);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ownedUncertifiedEntityOutsideContactBoxDoesNotDetachPlayer() throws Exception {
        try (Fixture f = new Fixture()) {
            f.candidate(OUTSIDE_BOX, 0L, true);
            assertTrue(f.query(true));
        }
    }

    @Test
    void intersectingUncertifiedContactStillRequiresMain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.candidate(new AABB(9, 8, 8, 11, 9, 9), 0L, true);
            assertFalse(f.query(true));
        }
    }

    @Test
    void unindexedCandidateCannotBePrunedUsingItsBoundingBox() throws Exception {
        try (Fixture f = new Fixture()) {
            f.candidate(null, 0L, false);
            assertFalse(f.query(true));
        }
    }

    @Test
    void foreignCandidateCannotBePrunedUsingItsBoundingBox() throws Exception {
        try (Fixture f = new Fixture()) {
            f.candidate(null, 1L, true);
            assertFalse(f.query(true));
        }
    }

    @Test
    void movingEntityQueryRetainsItsConservativeCensus() throws Exception {
        try (Fixture f = new Fixture()) {
            f.candidate(OUTSIDE_BOX, 0L, true);
            assertFalse(f.query(false));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestLevel level;
        final RegionizedServer runtime;
        final IslandRegistry.PIsland island;
        final ServerPlayer source;
        final Object check;
        final Method query;

        Fixture() throws Exception {
            level = allocate(TestLevel.class);
            level.manager = allocate(TestManager.class);
            level.manager.storage = new EntitySectionStorage<>(EntityAccess.class, ignored -> Visibility.TICKING);
            runtime = allocate(RegionizedServer.class);
            field(RegionizedServer.class, "level").set(runtime, level);
            field(RegionizedServer.class, "regionizer").set(runtime,
                    new ThreadedRegionizer(level, new RegionGrid(RegionGrid.DEFAULT_SHIFT)));
            IslandRegistry registry = new IslandRegistry();
            registry.applyRebuildCells(List.of(new LongArrayList(new long[]{0L})), 0L);
            island = registry.byCell(0L);
            island.level = level;
            field(RegionizedServer.class, "islandRegistry").set(runtime, registry);
            Region region = runtime.regionizer().regionForGridKey(0L);
            region.initializeDomainAtCreation(island.domain);
            source = allocate(ServerPlayer.class);
            Class<?> checkType = Class.forName(NativeEntityDependencies.class.getName() + "$Check");
            var constructor = checkType.getDeclaredConstructor();
            constructor.setAccessible(true);
            check = constructor.newInstance();
            field(checkType, "runtime").set(check, runtime);
            field(checkType, "region").set(check, region);
            query = checkType.getDeclaredMethod("entityQuery", Entity.class, Class.class, AABB.class, boolean.class);
            query.setAccessible(true);
            assertTrue(island.tryClaimAutonomousWork());
            island.noteClaim("player-contact-census");
        }

        void candidate(AABB box, long key, boolean indexed) throws Exception {
            ContactEntity entity = allocate(ContactEntity.class);
            entity.key = key;
            field(Entity.class, "id").setInt(entity, 2);
            field(Entity.class, "bb").set(entity, box);
            if (indexed) runtime.regionizer().regionForGridKey(key).entities.add(entity);
            level.manager.storage.getOrCreateSection(SectionPos.asLong(0, 0, 0)).add(entity);
        }

        boolean query(boolean playerContacts) throws Exception {
            return (boolean) query.invoke(check, source, Entity.class, CONTACT_BOX, playerContacts);
        }

        @Override
        public void close() {
            island.clearClaim();
            island.unitClaim.set(false);
        }
    }

    private static final class ContactEntity extends ItemEntity implements RegionKeyed {
        long key;
        private ContactEntity() { super(null, 0, 0, 0, null); }
        @Override public long fabrolia$getRegionKey() { return key; }
        @Override public void fabrolia$setRegionKey(long key) { this.key = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) { throw new AssertionError("not ticking"); }
        @Override public boolean fabrolia$carryRegionTickMark(long from, long to) { throw new AssertionError("not ticking"); }
        @Override public void playerTouch(Player player) { throw new AssertionError("preflight invoked a callback"); }
    }

    private static final class TestLevel extends ServerLevel implements ServerLevelEntityManagerAccessor {
        TestManager manager;
        private TestLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
        @Override public PersistentEntitySectionManager<Entity> fabrolia$entityManager() { return manager; }
    }

    private static final class TestManager extends PersistentEntitySectionManager<Entity> implements TickingChunksAccess {
        EntitySectionStorage<EntityAccess> storage;
        private TestManager() { super(Entity.class, null, null); }
        @Override public EntitySectionStorage<EntityAccess> fabrolia$sectionStorage() { return storage; }
        @Override public Long2IntConcurrentHashMap fabrolia$tickingChunks() { throw new AssertionError("not ticking"); }
        @Override public void fabrolia$finishStatusTransitions(boolean healthy) { throw new AssertionError("not transitioning"); }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        return type.cast(unsafe.allocateInstance(type));
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
