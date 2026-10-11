package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.EntityMixin;
import com.axalotl.fabrolia.common.mixin.entity.EntityTickListMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTickList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The real 26.3 list supplies the order and birth-cut oracle, not entity IDs. */
@Isolated
class EntityPhaseMembershipParityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void snapshotMatchesVanillaInsertionOrderWithReverseIdsAndReadmission() throws Exception {
        Entity older = entity(30), younger = entity(10);
        Fixture candidate = new Fixture();
        EntityTickList vanilla = new EntityTickList();
        candidate.add(older); candidate.add(younger);
        vanilla.add(older); vanilla.add(younger);
        assertEquals(ids(vanilla), ids(candidate.snapshot()));
        assertEquals(List.of(30, 10), ids(candidate.snapshot()));

        candidate.remove(older); candidate.add(older);
        vanilla.remove(older); vanilla.add(older);
        assertEquals(ids(vanilla), ids(candidate.snapshot()));
        assertEquals(List.of(10, 30), ids(candidate.snapshot()));
    }

    @Test
    void duplicateAddRetainsVanillaPositionInsteadOfAppendingAgain() throws Exception {
        Entity first = entity(30), second = entity(10);
        Fixture candidate = new Fixture();
        EntityTickList vanilla = new EntityTickList();
        for (Entity entity : List.of(first, second, first)) {
            candidate.add(entity); vanilla.add(entity);
        }
        assertEquals(ids(vanilla), ids(candidate.snapshot()));
        assertEquals(List.of(30, 10), ids(candidate.snapshot()));
    }

    @Test
    void neighborBirthDuringTheFinitePassFirstAppearsOnTheNextCut() throws Exception {
        Entity source = entity(10), target = entity(20), birth = entity(30);
        Fixture candidate = new Fixture();
        EntityTickList vanilla = new EntityTickList();
        candidate.add(source); candidate.add(target);
        vanilla.add(source); vanilla.add(target);

        List<Integer> actual = new ArrayList<>();
        for (Entity entity : candidate.snapshot()) {
            actual.add(entity.getId());
            if (entity == source) candidate.add(birth);
        }
        List<Integer> expected = new ArrayList<>();
        vanilla.forEach(entity -> {
            expected.add(entity.getId());
            if (entity == source) vanilla.add(birth);
        });
        assertEquals(expected, actual);
        assertEquals(List.of(10, 20), actual,
                "the unclaimed destination region must not admit this newborn this phase");
        assertEquals(ids(vanilla), ids(candidate.snapshot()));
        assertEquals(List.of(10, 20, 30), ids(candidate.snapshot()),
                "the next phase must admit the birth; exclusion must not become permanent");
    }

    @Test
    void scheduledEventAndArrivalBirthsBeforeTheCutAreImmediatelyEligible() throws Exception {
        Fixture candidate = new Fixture();
        Entity arrival = entity(50), scheduledBirth = entity(20), pistonBirth = entity(10);
        candidate.add(arrival); candidate.add(scheduledBirth); candidate.add(pistonBirth);
        assertEquals(List.of(arrival, scheduledBirth, pistonBirth), candidate.snapshot(),
                "pre-entity-phase births retain the same-tick first visit and insertion order");
    }

    @Test
    void captureIsFiniteImmutableAndDoesNotFollowSubsequentMembershipChanges() throws Exception {
        Fixture candidate = new Fixture();
        Entity first = entity(10), second = entity(20), later = entity(30);
        candidate.add(first); candidate.add(second);
        List<Entity> cut = candidate.snapshot();
        candidate.remove(second); candidate.add(later);
        assertEquals(List.of(first, second), cut,
                "vanilla's already-accepted frame also retains a removed member's identity");
        assertEquals(List.of(first, later), candidate.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> cut.add(later));
    }

    @Test
    void unchangedMembershipSharesOneImmutableCutAcrossIslands() throws Exception {
        Fixture candidate = new Fixture();
        Entity first = entity(10), second = entity(20);
        candidate.add(first); candidate.add(second);
        List<Entity> islandA = candidate.snapshot();
        List<Entity> islandB = candidate.snapshot();
        assertSame(islandA, islandB, "no membership change: the second island reuses the copy");

        candidate.remove(first);
        List<Entity> afterRemoval = candidate.snapshot();
        assertEquals(List.of(second), afterRemoval);
        assertEquals(List.of(first, second), islandA, "an earlier cut never changes");

        candidate.add(first);
        assertEquals(List.of(second, first), candidate.snapshot(),
                "readmission appends exactly as vanilla's linked map does");
    }

    @Test
    void snapshotDoesNotReserveOrCorruptVanillasSingleIterationSlot() throws Exception {
        Fixture candidate = new Fixture();
        Entity first = entity(10), birth = entity(20);
        candidate.add(first);
        candidate.snapshot();
        List<Integer> visited = new ArrayList<>();
        candidate.forEach(entity -> {
            visited.add(entity.getId());
            try {
                candidate.add(birth);
                assertEquals(List.of(first, birth), candidate.snapshot());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertEquals(List.of(10), visited, "snapshot must not replace the iterated COW identity");
        assertNull(candidate.iterated());
        List<Integer> next = new ArrayList<>();
        candidate.forEach(entity -> next.add(entity.getId()));
        assertEquals(List.of(10, 20), next);
    }

    @Test
    void anotherIslandCanCaptureWhileTheCanonicalConsumerIsOutsideItsMonitor() throws Exception {
        Fixture candidate = new Fixture();
        Entity entity = entity(10);
        candidate.add(entity);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FutureTask<Void> consumer = new FutureTask<>(() -> {
            candidate.forEach(member -> {
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            return null;
        });
        Thread worker = new Thread(consumer, "entity-list-snapshot-test");
        worker.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(entity), candidate.snapshot());
            assertNotNull(candidate.iterated(), "capture must leave the canonical frame open");
        } finally {
            release.countDown();
            worker.join(3_000L);
        }
        consumer.get(3, TimeUnit.SECONDS);
        assertFalse(worker.isAlive());
        assertNull(candidate.iterated());
    }

    @Test
    void livePassengerMembershipCanIncludeABirthExcludedFromTheRootSnapshot() throws Exception {
        Fixture candidate = new Fixture();
        Entity root = entity(10), newbornPassenger = entity(20);
        candidate.add(root);
        List<Entity> rootCut = candidate.snapshot();
        candidate.add(newbornPassenger);
        assertFalse(rootCut.contains(newbornPassenger));
        assertTrue(candidate.contains(newbornPassenger),
                "vanilla tickPassenger checks live contains; root birth-cut is not that gate");
        assertEquals(List.of(root, newbornPassenger), candidate.snapshot());
    }

    @Test
    void actualTickMarkKeepsEveryFuseStepOnceAcrossSameIslandCellMigration() {
        TickMark mark = new TickMark();
        int fuse = 80, ticks = 0;
        long prefix = 1L << 48;
        for (int epoch = 1; epoch <= 80; epoch++) {
            long token = prefix | epoch;
            assertTrue(mark.fabrolia$tryMarkRegionTick(token));
            fuse--; ticks++;
            assertFalse(mark.fabrolia$tryMarkRegionTick(token),
                    "both cell visits share the island token after reindexing");
        }
        assertEquals(80, ticks);
        assertEquals(0, fuse);
    }

    @Test
    void actualDomainCarryPreventsDuplicateAndPreservesNextEpochLiveness() {
        TickMark mark = new TickMark();
        long source = (1L << 48) | 10, destination = (2L << 48) | 10;
        assertTrue(mark.fabrolia$tryMarkRegionTick(source));
        assertTrue(mark.fabrolia$carryRegionTickMark(source, destination));
        assertFalse(mark.fabrolia$tryMarkRegionTick(destination));
        assertTrue(mark.fabrolia$tryMarkRegionTick(destination + 1));
    }

    private static Entity entity(int id) throws Exception {
        PrimedTnt entity = (PrimedTnt) unsafe().allocateInstance(PrimedTnt.class);
        entity.setId(id);
        return entity;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static List<Integer> ids(List<Entity> entities) {
        return entities.stream().map(Entity::getId).toList();
    }

    private static List<Integer> ids(EntityTickList list) {
        List<Integer> ids = new ArrayList<>();
        list.forEach(entity -> ids.add(entity.getId()));
        return ids;
    }

    /** Executes the actual wrappers against the same real linked maps vanilla uses. */
    private static final class Fixture {
        final EntityTickListMixin list = new EntityTickListMixin();

        Fixture() throws Exception {
            field("active").set(list, new Int2ObjectLinkedOpenHashMap<Entity>());
            field("passive").set(list, new Int2ObjectLinkedOpenHashMap<Entity>());
        }

        List<Entity> snapshot() { return list.fabrolia$snapshotEntities(); }
        void add(Entity entity) throws Exception { invoke("add", Entity.class, entity); }
        void remove(Entity entity) throws Exception { invoke("remove", Entity.class, entity); }
        boolean contains(Entity entity) throws Exception { return (boolean) invoke("contains", Entity.class, entity); }
        void forEach(Consumer<Entity> consumer) throws Exception { invoke("forEach", Consumer.class, consumer); }
        Object iterated() throws Exception { return field("iterated").get(list); }

        private Object invoke(String name, Class<?> argumentType, Object argument) throws Exception {
            Method method = EntityTickListMixin.class.getDeclaredMethod(name, argumentType, Operation.class);
            method.setAccessible(true);
            try {
                return method.invoke(list, argument, null);
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof Exception exception) throw exception;
                if (wrapped.getCause() instanceof Error error) throw error;
                throw wrapped;
            }
        }

        private static Field field(String name) throws Exception {
            Field field = EntityTickListMixin.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }

    /** Runs the production AtomicLong/CAS marker, rather than a copied marker model. */
    private static final class TickMark extends EntityMixin {
        @Override public boolean isOnPortalCooldown() { return false; }
        @Override public int getPortalCooldown() { return 0; }
        @Override public Level level() { return null; }
        @Override public double getX() { return 0; }
        @Override public double getY() { return 0; }
        @Override public double getZ() { return 0; }
    }
}
