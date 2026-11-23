package com.axalotl.fabrolia.common.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ContainerQueryOwnersTest {
    private static TransportItemTarget at(BlockPos pos) {
        return new TransportItemTarget(pos, null, null, null);
    }

    private static <T> T onAnotherThread(Callable<T> body) {
        var task = new FutureTask<>(body);
        Thread.startVirtualThread(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            task.cancel(true);
        }
    }

    @Test
    void nestedReadLeavesTheOuterReaderInChargeOfOnlyItsOwnLevel() {
        var first = new ContainerQueryOwners();
        var other = new ContainerQueryOwners();
        var target = at(new BlockPos(127, 82, 8));
        assertFalse(first.read(target, null, outer -> {
            assertFalse(first.read(target, null, inner -> {
                assertSame(outer[0], inner[0]);
                return false;
            }));
            return onAnotherThread(() -> {
                assertTrue(first.read(target, null, ignored -> fail("competing read entered the same address")));
                return other.read(target, null, ignored -> false);
            });
        }));
        assertFalse(onAnotherThread(() -> first.read(target, null, ignored -> false)));
    }

    @Test
    void mutablePositionAndThrowDoNotLoseOrStrandTheOriginalAddress() {
        var owners = new ContainerQueryOwners();
        var mutable = new BlockPos.MutableBlockPos(4, 80, -3);
        var originalAddress = at(mutable.immutable());
        var thrown = new IllegalStateException("native callback");
        assertSame(thrown, assertThrows(IllegalStateException.class, () -> owners.read(at(mutable), null, args -> {
            mutable.set(5, 80, -3);
            assertTrue(onAnotherThread(() -> owners.read(originalAddress, null, ignored -> fail("lost the original address"))));
            throw thrown;
        })));
        assertFalse(onAnotherThread(() -> owners.read(originalAddress, null, ignored -> false)));
    }
}
