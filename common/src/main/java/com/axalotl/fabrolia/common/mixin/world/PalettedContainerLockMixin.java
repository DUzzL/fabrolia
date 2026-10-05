package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.utils.EmbeddedReadWriteLock;
import com.axalotl.fabrolia.common.utils.FabroliaPalettedContainerLock;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lithium removes PalettedContainer's vanilla ThreadingDetector path. Fabrolia block
 * writes need a real per-section guard so palette entries and bit storage cannot
 * be observed in different publication phases by chunk packet serialization.
 *
 * <p>The lock state lives in two fields of the container and waits on its
 * monitor ({@link EmbeddedReadWriteLock}): a per-container
 * {@code ReentrantReadWriteLock} cost ~120 bytes for each of the ~2 containers
 * per loaded chunk section (≈110 MB on the 40-player stand).
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerLockMixin<T>
        implements FabroliaPalettedContainerLock, EmbeddedReadWriteLock.State {

    @Unique
    private int fabrolia$lockState;

    @Unique
    private Thread fabrolia$lockWriter;

    @Override
    public int fabrolia$lockState() {
        return fabrolia$lockState;
    }

    @Override
    public void fabrolia$lockState(int state) {
        fabrolia$lockState = state;
    }

    @Override
    public Thread fabrolia$lockWriter() {
        return fabrolia$lockWriter;
    }

    @Override
    public void fabrolia$lockWriter(Thread writer) {
        fabrolia$lockWriter = writer;
    }

    @Override
    public void fabrolia$readLock() {
        EmbeddedReadWriteLock.readLock(this);
    }

    @Override
    public void fabrolia$readUnlock() {
        EmbeddedReadWriteLock.readUnlock(this);
    }

    @Override
    public void fabrolia$writeLock() {
        EmbeddedReadWriteLock.writeLock(this);
    }

    @Override
    public void fabrolia$writeUnlock() {
        EmbeddedReadWriteLock.writeUnlock(this);
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
