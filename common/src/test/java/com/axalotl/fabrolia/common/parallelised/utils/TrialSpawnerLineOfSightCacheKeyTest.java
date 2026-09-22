package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TrialSpawnerLineOfSightCacheKeyTest {

    @Test
    void hashCollisionNeverReusesAnotherEndpointPairsVisibility() {
        ResourceKey<Level> dimension = dimension("collision");
        long firstFrom = 0L;
        long firstTo = 0L;
        long secondFrom = 1L;
        long secondTo = Long.rotateRight(1L, 17);

        assertEquals(
                TrialSpawnerLineOfSightCache.endpointHash(firstFrom, firstTo),
                TrialSpawnerLineOfSightCache.endpointHash(secondFrom, secondTo),
                "fixture must exercise one actual cache bucket collision");

        TrialSpawnerLineOfSightCache.storeCached(
                100L, dimension, firstFrom, firstTo, true);
        assertEquals(Boolean.TRUE, TrialSpawnerLineOfSightCache.lookupCached(
                100L, dimension, firstFrom, firstTo));
        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                100L, dimension, secondFrom, secondTo),
                "a colliding endpoint pair is a miss, never another ray's visibility");

        TrialSpawnerLineOfSightCache.storeCached(
                100L, dimension, secondFrom, secondTo, false);
        assertEquals(Boolean.FALSE, TrialSpawnerLineOfSightCache.lookupCached(
                100L, dimension, secondFrom, secondTo));
        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                100L, dimension, firstFrom, firstTo),
                "overwriting a hash bucket must not make the old pair read the new result");

        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                100L + TrialSpawnerLineOfSightCache.TTL_TICKS + 1L,
                dimension, secondFrom, secondTo));
    }

    @Test
    void sameEndpointsInAnotherDimensionAreACacheMiss() {
        ResourceKey<Level> firstDimension = dimension("first");
        ResourceKey<Level> secondDimension = dimension("second");
        long from = 0x1234L;
        long to = 0x5678L;

        TrialSpawnerLineOfSightCache.storeCached(
                200L, firstDimension, from, to, true);

        assertEquals(Boolean.TRUE, TrialSpawnerLineOfSightCache.lookupCached(
                200L, firstDimension, from, to));
        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                200L, secondDimension, from, to),
                "visibility from another dimension must never be reused");

        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                200L + TrialSpawnerLineOfSightCache.TTL_TICKS + 1L,
                firstDimension, from, to));
    }

    @Test
    void successiveServerOwnerCannotReusePreviousWorldVisibility() {
        ResourceKey<Level> dimension = dimension("server-restart");
        Object firstServer = new Object();
        Object secondServer = new Object();
        long from = 0x9ABCL;
        long to = 0xDEF0L;

        TrialSpawnerLineOfSightCache.selectCacheOwner(firstServer);
        TrialSpawnerLineOfSightCache.storeCached(
                300L, dimension, from, to, true);
        TrialSpawnerLineOfSightCache.selectCacheOwner(firstServer);
        assertEquals(Boolean.TRUE, TrialSpawnerLineOfSightCache.lookupCached(
                300L, dimension, from, to),
                "the active server keeps its own cache entries");

        TrialSpawnerLineOfSightCache.selectCacheOwner(secondServer);
        assertNull(TrialSpawnerLineOfSightCache.lookupCached(
                300L, dimension, from, to),
                "a new server instance must start with an empty LOS cache");
    }

    private static ResourceKey<Level> dimension(String path) {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("fabrolia-test", path));
    }
}
