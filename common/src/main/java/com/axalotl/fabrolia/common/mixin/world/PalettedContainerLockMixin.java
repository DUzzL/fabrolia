package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.utils.FabroliaPalettedContainerLock;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lithium removes PalettedContainer's vanilla ThreadingDetector path. Fabrolia block
 * writes need a real per-section guard so palette entries and bit storage cannot
 * be observed in different publication phases by chunk packet serialization.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerLockMixin<T> implements FabroliaPalettedContainerLock {

    @Unique
    private volatile ReentrantReadWriteLock fabrolia$containerLock;

    @Unique
    private ReentrantReadWriteLock fabrolia$lock() {
        ReentrantReadWriteLock lock = fabrolia$containerLock;
        if (lock != null) return lock;
        synchronized (this) {
            lock = fabrolia$containerLock;
            if (lock == null) {
                lock = new ReentrantReadWriteLock();
                fabrolia$containerLock = lock;
            }
            return lock;
        }
    }

    @Override
    public void fabrolia$readLock() {
        fabrolia$lock().readLock().lock();
    }

    @Override
    public void fabrolia$readUnlock() {
        fabrolia$lock().readLock().unlock();
    }

    @Override
    public void fabrolia$writeLock() {
        fabrolia$lock().writeLock().lock();
    }

    @Override
    public void fabrolia$writeUnlock() {
        fabrolia$lock().writeLock().unlock();
    }

    @WrapMethod(method = "getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;")
    private T fabrolia$writeLockGetAndSet(int x, int y, int z, T value, Operation<T> original) {
        fabrolia$writeLock();
        try {
            return original.call(x, y, z, value);
        } finally {
            fabrolia$writeUnlock();
        }
    }

    @WrapMethod(method = "getAndSetUnchecked(IIILjava/lang/Object;)Ljava/lang/Object;")
    private T fabrolia$writeLockGetAndSetUnchecked(int x, int y, int z, T value, Operation<T> original) {
        fabrolia$writeLock();
        try {
            return original.call(x, y, z, value);
        } finally {
            fabrolia$writeUnlock();
        }
    }

    @WrapMethod(method = "set(IIILjava/lang/Object;)V")
    private void fabrolia$writeLockSet(int x, int y, int z, T value, Operation<Void> original) {
        fabrolia$writeLock();
        try {
            original.call(x, y, z, value);
        } finally {
            fabrolia$writeUnlock();
        }
    }

    @WrapMethod(method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V")
    private void fabrolia$writeLockRead(FriendlyByteBuf buf, Operation<Void> original) {
        fabrolia$writeLock();
        try {
            original.call(buf);
        } finally {
            fabrolia$writeUnlock();
        }
    }

    @WrapMethod(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V")
    private void fabrolia$readLockWrite(FriendlyByteBuf buf, Operation<Void> original) {
        fabrolia$readLock();
        try {
            original.call(buf);
        } finally {
            fabrolia$readUnlock();
        }
    }

    @WrapMethod(method = "getSerializedSize()I")
    private int fabrolia$readLockSerializedSize(Operation<Integer> original) {
        fabrolia$readLock();
        try {
            return original.call();
        } finally {
            fabrolia$readUnlock();
        }
    }

    @WrapMethod(method = "pack(Lnet/minecraft/world/level/chunk/Strategy;)Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;")
    private PalettedContainerRO.PackedData<T> fabrolia$readLockPack(Strategy<T> strategy, Operation<PalettedContainerRO.PackedData<T>> original) {
        fabrolia$readLock();
        try {
            return original.call(strategy);
        } finally {
            fabrolia$readUnlock();
        }
    }

    @WrapMethod(method = "copy()Lnet/minecraft/world/level/chunk/PalettedContainer;")
    private PalettedContainer<T> fabrolia$readLockCopy(Operation<PalettedContainer<T>> original) {
        fabrolia$readLock();
        try {
            return original.call();
        } finally {
            fabrolia$readUnlock();
        }
    }

    @WrapMethod(method = "count(Lnet/minecraft/world/level/chunk/PalettedContainer$CountConsumer;)V")
    private void fabrolia$readLockCount(PalettedContainer.CountConsumer<T> consumer, Operation<Void> original) {
        fabrolia$readLock();
        try {
            original.call(consumer);
        } finally {
            fabrolia$readUnlock();
        }
    }
}
