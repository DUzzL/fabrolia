package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.WritableLevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnIndexStrikeLifecycleTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leavingTheVisibleSetEndsAPendingPurgeStrike() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Cow mob = (Cow) unsafe.allocateInstance(Cow.class);
        mob.setId(42);
        try {
            SpawnIndex.onTrackingStart(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob), "first failed sighting only strikes");

            SpawnIndex.onTrackingEnd(level, mob);

            // The strike ledger must not keep the departed entity: had it kept
            // it, this sighting after a re-entry would count as the second.
            SpawnIndex.onTrackingStart(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob),
                    "a strike taken before the entity left cannot carry over");
            assertTrue(SpawnIndex.strikeForPurge(level, mob), "consecutive sightings still purge");
        } finally {
            SpawnIndex.clearLevel(level);
        }
    }

    @Test
    void aStrikeFromAWalkThatOutlivedTheEntitysMembershipIsNotKept() throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Cow mob = (Cow) unsafe.allocateInstance(Cow.class);
        mob.setId(43);
        try {
            SpawnIndex.onTrackingStart(level, mob);
            // A census iterator already holds the mob; a worker ends its
            // tracking before the census reaches it and strikes it.
            SpawnIndex.onTrackingEnd(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob));

            assertFalse(SpawnIndex.strikeForPurge(level, mob),
                    "a strike on an entity outside the visible set must not stay in the ledger");
        } finally {
            SpawnIndex.clearLevel(level);
        }
    }

    @Test
    void purgeRemovesMembershipAndContributionTogether() throws Exception {
        ServerLevel level = censusLevel();
        LedgerZombie mob = ledgerZombie(44);
        try {
            SpawnIndex.onTrackingStart(level, mob);
            assertAgreement(level, mob, true);

            assertTrue(SpawnIndex.purge(level, mob, e -> true));
            assertAgreement(level, mob, false);
        } finally {
            SpawnIndex.clearLevel(level);
            SpawnCensus.clearLevel(level);
        }
    }

    @Test
    void purgeKeepsAnEntityTheRecheckFindsPresent() throws Exception {
        ServerLevel level = censusLevel();
        LedgerZombie mob = ledgerZombie(45);
        try {
            SpawnIndex.onTrackingStart(level, mob);
            assertFalse(SpawnIndex.strikeForPurge(level, mob));

            assertFalse(SpawnIndex.purge(level, mob, e -> false),
                    "a section add that landed before the transaction keeps the entity");
            assertAgreement(level, mob, true);
            assertFalse(SpawnIndex.strikeForPurge(level, mob),
                    "the passed re-check clears the pending strike");
        } finally {
            SpawnIndex.clearLevel(level);
            SpawnCensus.clearLevel(level);
        }
    }

    /** 2026-10-24 review, finding 2: the audit purge and a worker's section-add
     *  re-insert used to interleave as remove / ensurePresent / update(false),
     *  leaving a live index member with no census contribution. Every round
     *  races one purge against one ensurePresent; after each round membership,
     *  the tracked flag and the counted monster must agree. */
    @Test
    void sectionAddRacingAPurgeLeavesMembershipAndLedgerInAgreement() throws Exception {
        ServerLevel level = censusLevel();
        LedgerZombie mob = ledgerZombie(46);
        int rounds = 20_000;
        CyclicBarrier start = new CyclicBarrier(3);
        CyclicBarrier done = new CyclicBarrier(3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread audit = new Thread(() -> race(start, done, rounds, failure,
                () -> SpawnIndex.purge(level, mob, e -> true)), "test-audit");
        Thread worker = new Thread(() -> race(start, done, rounds, failure,
                () -> SpawnIndex.ensurePresent(level, mob)), "test-worker");
        try {
            audit.start();
            worker.start();
            for (int i = 0; i < rounds; i++) {
                if (!SpawnIndex.getEntities(level).contains(mob)) {
                    SpawnIndex.onTrackingStart(level, mob);
                }
                start.await(10, TimeUnit.SECONDS);
                done.await(10, TimeUnit.SECONDS);
                if (failure.get() != null) {
                    throw new AssertionError("racing thread failed", failure.get());
                }
                boolean member = SpawnIndex.getEntities(level).contains(mob);
                assertEquals(member, mob.tracked, "round " + i + ": membership vs tracked flag");
                assertEquals(member ? 1 : 0, monsters(level), "round " + i + ": counted monsters");
            }
        } finally {
            audit.interrupt();
            worker.interrupt();
            audit.join(10_000);
            worker.join(10_000);
            SpawnIndex.clearLevel(level);
            SpawnCensus.clearLevel(level);
        }
    }

    private static void race(CyclicBarrier start, CyclicBarrier done, int rounds,
                             AtomicReference<Throwable> failure, Runnable step) {
        try {
            for (int i = 0; i < rounds; i++) {
                start.await(10, TimeUnit.SECONDS);
                try {
                    step.run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
                done.await(10, TimeUnit.SECONDS);
            }
        } catch (Exception interruptedOrBroken) {
            failure.compareAndSet(null, interruptedOrBroken);
        }
    }

    private static void assertAgreement(ServerLevel level, LedgerZombie mob, boolean member) {
        assertEquals(member, SpawnIndex.getEntities(level).contains(mob), "membership");
        assertEquals(member, mob.tracked, "tracked flag");
        assertEquals(member ? 1 : 0, monsters(level), "counted monsters");
    }

    private static int monsters(ServerLevel level) {
        var vector = SpawnCensus.census(level).live.get(0L);
        return vector == null ? 0 : vector.get(MobCategory.MONSTER.ordinal());
    }

    /** A level whose census never rebuilds its cost-type set (game time 0). */
    private static ServerLevel censusLevel() throws Exception {
        ServerLevel level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
        Object data = Proxy.newProxyInstance(SpawnIndexStrikeLifecycleTest.class.getClassLoader(),
                new Class<?>[]{WritableLevelData.class},
                (proxy, method, args) -> method.getName().equals("getGameTime") ? 0L : null);
        Field levelData = Level.class.getDeclaredField("levelData");
        levelData.setAccessible(true);
        levelData.set(level, data);
        SpawnCensus.LevelCensus census = SpawnCensus.census(level);
        census.costTypes = type -> false;
        census.costTypesBuiltAt = 0L;
        return level;
    }

    private static LedgerZombie ledgerZombie(int id) throws Exception {
        LedgerZombie mob = (LedgerZombie) unsafe().allocateInstance(LedgerZombie.class);
        mob.lock = new Object();
        mob.setId(id);
        return mob;
    }

    /** Unit tests run without mixins, so the census ledger EntityMixin adds
     *  is implemented here: an eligible monster at chunk 0. */
    private static final class LedgerZombie extends Cow implements SpawnCensus.Ledger {
        Object lock;
        volatile boolean tracked;
        long chunk;
        int state;

        private LedgerZombie() {
            super(EntityTypes.COW, null);
        }

        @Override public EntityType<?> getType() { return EntityTypes.ZOMBIE; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public boolean isPersistenceRequired() { return false; }
        @Override public boolean requiresCustomPersistence() { return false; }
        @Override public boolean isDeadOrDying() { return false; }
        @Override public Object fabrolia$censusLock() { return lock; }
        @Override public boolean fabrolia$censusTracked() { return tracked; }
        @Override public void fabrolia$setCensusTracked(boolean value) { tracked = value; }
        @Override public long fabrolia$censusChunk() { return chunk; }
        @Override public void fabrolia$setCensusChunk(long value) { chunk = value; }
        @Override public int fabrolia$censusState() { return state; }
        @Override public void fabrolia$setCensusState(int value) { state = value; }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
