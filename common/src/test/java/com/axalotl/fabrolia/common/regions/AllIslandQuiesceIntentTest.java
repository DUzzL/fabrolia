package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises production intent publication and restoration without acquiring claims. */
@Isolated
final class AllIslandQuiesceIntentTest {

    @Test
    void publishedAttemptFencesTheWholeStableSnapshotAndRestoresPriorInterests()
            throws Exception {
        IslandRegistry.PIsland first = new IslandRegistry.PIsland(1L, 0L);
        IslandRegistry.PIsland second = new IslandRegistry.PIsland(2L, 0L);
        IslandRegistry.PIsland third = new IslandRegistry.PIsland(3L, 0L);
        first.unitClaim.set(true);
        second.announceClaimInterest();
        ArrayList<IslandRegistry.PIsland> live = new ArrayList<>(List.of(first, second, third));

        Object attempt = publish(live);
        try {
            assertTrue(first.claimInterestAnnounced());
            assertTrue(second.claimInterestAnnounced());
            assertTrue(third.claimInterestAnnounced());
            // A retry/failure must restore the captured attempt, not re-enumerate
            // the registry after its membership changes.
            live.clear();
        } finally {
            assertNull(clear(attempt, null));
        }

        assertFalse(first.claimInterestAnnounced());
        assertTrue(second.claimInterestAnnounced(), "a pre-existing Main interest survives this attempt");
        assertFalse(third.claimInterestAnnounced());
        assertTrue(first.unitClaim.get(),
                "intent restoration must not release somebody else's physical claim");
    }

    @Test
    void nestedAttemptsRestoreTheirOuterInterestAndOldCleanupCannotEraseANewAttempt()
            throws Exception {
        IslandRegistry.PIsland island = new IslandRegistry.PIsland(1L, 0L);
        Object outer = publish(List.of(island));
        try {
            Object inner = publish(List.of(island));
            assertNull(clear(inner, null));
            assertTrue(island.claimInterestAnnounced(), "inner cleanup must retain outer acquisition priority");
        } finally {
            assertNull(clear(outer, null));
        }
        assertFalse(island.claimInterestAnnounced());

        Object next = publish(List.of(island));
        try {
            assertNull(clear(outer, null));
            assertTrue(island.claimInterestAnnounced(), "repeated cleanup must not revoke a later attempt");
        } finally {
            assertNull(clear(next, null));
        }
        assertFalse(island.claimInterestAnnounced());
    }

    @Test
    void failureCleanupRestoresEveryInterestWithoutReplacingThePrimaryOrReleasingClaims()
            throws Exception {
        IslandRegistry.PIsland first = new IslandRegistry.PIsland(1L, 0L);
        IslandRegistry.PIsland second = new IslandRegistry.PIsland(2L, 0L);
        IslandRegistry.PIsland third = new IslandRegistry.PIsland(3L, 0L);
        second.announceClaimInterest();
        first.unitClaim.set(true);
        third.unitClaim.set(true);
        Object attempt = publish(List.of(first, second, third));
        Throwable primary = new IllegalStateException("quiesce acquisition failed");

        assertSame(primary, clear(attempt, primary));
        assertFalse(first.claimInterestAnnounced());
        assertTrue(second.claimInterestAnnounced());
        assertFalse(third.claimInterestAnnounced());
        assertTrue(first.unitClaim.get());
        assertTrue(third.unitClaim.get());
        assertSame(primary, clear(attempt, primary));
    }

    @Test
    void partialPublicationRestoresOnlyPublishedSlotsAndPreservesTheCanonicalFailure()
            throws Exception {
        Field pool = field("pool");
        Field transitionPool = field("transitionPool");
        Field orphanFailure = field("orphanFatalFailure");
        Object previousPool = pool.get(null);
        Object previousTransitionPool = transitionPool.get(null);
        Object previousFailure = orphanFailure.get(null);
        try {
            pool.set(null, null);
            transitionPool.set(null, null);
            orphanFailure.set(null, null);
            Throwable primary = new IllegalStateException("earlier generation failure");
            assertSame(primary, RegionScheduler.publishFatalAndSelectWinner(primary));
            IslandRegistry.PIsland first = new IslandRegistry.PIsland(1L, 0L);
            IslandRegistry.PIsland second = new IslandRegistry.PIsland(2L, 0L);
            IslandRegistry.PIsland unpublished = new IslandRegistry.PIsland(3L, 0L);
            second.announceClaimInterest();
            unpublished.announceClaimInterest();
            first.unitClaim.set(true);

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> publish(Arrays.asList(first, second, null, unpublished)));

            assertSame(primary, thrown.getCause());
            assertTrue(Arrays.stream(primary.getSuppressed())
                    .anyMatch(NullPointerException.class::isInstance));
            assertFalse(first.claimInterestAnnounced(), "partial publication must restore its earlier slots");
            assertTrue(second.claimInterestAnnounced(), "published outer interest must survive failure");
            assertTrue(unpublished.claimInterestAnnounced(), "unpublished slots must not be reset");
            assertTrue(first.unitClaim.get());
        } finally {
            pool.set(null, previousPool);
            transitionPool.set(null, previousTransitionPool);
            orphanFailure.set(null, previousFailure);
        }
    }

    private static Object publish(Collection<IslandRegistry.PIsland> islands) throws Exception {
        Method publish = publisher();
        return publish.invoke(null, islands);
    }

    private static Method publisher() throws Exception {
        Method publish = RegionizedServer.class.getDeclaredMethod(
                "publishAllIslandClaimIntents", Collection.class);
        publish.setAccessible(true);
        return publish;
    }

    private static Throwable clear(Object attempt, Throwable primary) throws Exception {
        Method clear = RegionizedServer.class.getDeclaredMethod(
                "clearAllIslandClaimIntents", publisher().getReturnType(), Throwable.class);
        clear.setAccessible(true);
        return (Throwable) clear.invoke(null, attempt, primary);
    }

    private static Field field(String name) throws Exception {
        Field field = RegionScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
