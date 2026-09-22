package com.axalotl.fabrolia.common.utils;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the allocator; real constructor/publication mixins require the server rig. */
@Isolated
@Timeout(20)
class DragonEntityIdsTest {
    private static final int GROUP_SIZE = 9;
    private static sun.misc.Unsafe unsafe;
    private static Entity occupiedPart;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (sun.misc.Unsafe) field.get(null);
        occupiedPart = (Entity) unsafe.allocateInstance(EnderDragonPart.class);
    }

    @Test
    void ordinaryAndOtherDragonAllocationCannotSplitAReservedGroup() throws Exception {
        reserveWhileAnotherThreadAllocates(false);
    }

    @Test
    void rejectingAnOccupiedGroupCannotReclaimConcurrentAllocations() throws Exception {
        reserveWhileAnotherThreadAllocates(true);
    }

    private void reserveWhileAnotherThreadAllocates(boolean rejectFirst) throws Exception {
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch competingAllocationDone = new CountDownLatch(1);
        AtomicBoolean firstLookup = new AtomicBoolean(true);
        TestLevel level = level(id -> {
            if (firstLookup.compareAndSet(true, false)) {
                reserved.countDown();
                await(competingAllocationDone);
            }
            return rejectFirst && id == 1 ? occupiedPart : null;
        });
        TestLevel otherLevel = level(id -> null);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var competing = executor.submit(() -> {
                await(reserved);
                try {
                    // The ordinary path shares vanilla's incrementAndGet counter.
                    int ordinaryId = counter.incrementAndGet();
                    int otherDragon = DragonEntityIds.reserve(otherLevel, counter, GROUP_SIZE);
                    return new int[]{ordinaryId, otherDragon};
                } finally {
                    competingAllocationDone.countDown();
                }
            });
            Set<Integer> firstGroup = group(DragonEntityIds.reserve(level, counter, GROUP_SIZE));
            int[] other = competing.get(5, TimeUnit.SECONDS);
            Set<Integer> secondGroup = group(other[1]);
            assertFalse(firstGroup.contains(other[0]), "ordinary identity must not alias a part");
            assertFalse(secondGroup.contains(other[0]));
            assertTrue(firstGroup.stream().noneMatch(secondGroup::contains),
                    "two unpublished identity groups must remain disjoint");
            if (rejectFirst) {
                assertFalse(firstGroup.contains(1), "the occupied candidate must be abandoned");
            }
        }
    }

    @Test
    void occupiedParentInteriorAndLastPartRejectTheirEntireGroups() throws Exception {
        Set<Integer> occupied = Set.of(1, 14, 27);
        TestLevel level = level(id -> occupied.contains(id) ? occupiedPart : null);
        Set<Integer> ids = group(DragonEntityIds.reserve(level, new AtomicInteger(), GROUP_SIZE));
        assertTrue(ids.stream().noneMatch(occupied::contains),
                "every member must be checked, not only the new parent");
    }

    @Test
    void signedOverflowRetainsTheClientOffsetSequence() throws Exception {
        AtomicInteger counter = new AtomicInteger(Integer.MAX_VALUE - 3);
        int first = DragonEntityIds.reserve(level(id -> null), counter, GROUP_SIZE);
        Set<Integer> ids = group(first);
        assertEquals(Integer.MAX_VALUE - 2, first);
        assertTrue(ids.contains(Integer.MIN_VALUE), "negative ids remain valid after int wrap");
        assertFalse(ids.contains(counter.incrementAndGet()));
    }

    @Test
    void zeroInsideAGroupIsNeverIssuedToAnyPart() throws Exception {
        int first = DragonEntityIds.reserve(level(id -> null), new AtomicInteger(-5), GROUP_SIZE);
        Set<Integer> ids = group(first);
        assertFalse(ids.contains(0), "a nonzero parent alone does not make the group valid");
    }

    private static Set<Integer> group(int first) {
        Set<Integer> ids = new HashSet<>();
        for (int offset = 0; offset < GROUP_SIZE; offset++) {
            ids.add(first + offset);
        }
        return ids;
    }

    private static TestLevel level(IntFunction<Entity> lookup) throws InstantiationException {
        TestLevel level = (TestLevel) unsafe.allocateInstance(TestLevel.class);
        level.lookup = lookup;
        return level;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "allocation handshake timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class TestLevel extends ServerLevel {
        private IntFunction<Entity> lookup;

        private TestLevel() {
            super(null, null, null, null, null, null, false, 0L, null, false);
            throw new AssertionError("allocated via Unsafe");
        }

        @Override
        public Entity getEntityOrPart(int id) {
            return lookup.apply(id);
        }
    }
}
