package com.axalotl.fabrolia.common.utils;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/** Ownership of in-flight transport reads in one Level instance, not an inventory lock.
 * Reentrant reads call vanilla synchronously, in order, with the same arguments. The outermost
 * reader owns the slot through normal or exceptional return. Only a different thread concurrently
 * querying this address sees Fabrolia's existing conservative busy response; it never waits. */
public final class ContainerQueryOwners {
    public interface Access {
        ContainerQueryOwners fabrolia$containerQueryOwners();
    }

    private final ConcurrentHashMap<BlockPos, Thread> active = new ConcurrentHashMap<>();

    public boolean read(TransportItemTarget target, Level level, Operation<Boolean> original) {
        BlockPos pos = target.pos().immutable();
        Thread caller = Thread.currentThread();
        Thread previous = active.putIfAbsent(pos, caller);
        if (previous != null) return previous != caller || original.call(target, level);
        try {
            return original.call(target, level);
        } finally {
            active.remove(pos, caller);
        }
    }
}
