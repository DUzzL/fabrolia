package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongSortedSet;
import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@Mixin(value = EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {

    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<EntitySection<T>> sections;

    @Shadow
    @Final
    @Mutable
    private LongSortedSet sectionIds;

    @Shadow
    public abstract LongStream getExistingSectionPositionsInChunk(long chunkKey);

    @Unique
    private final StampedLock fabrolia$sectionGuard = new StampedLock();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceMaps(CallbackInfo ci) {
        Long2ObjectConcurrentHashMap<EntitySection<T>> newSections = new Long2ObjectConcurrentHashMap<>();
        newSections.putAll(this.sections);
        this.sections = newSections;

        ConcurrentLongSortedSet newIds = new ConcurrentLongSortedSet();
        newIds.addAll(this.sectionIds);
        this.sectionIds = newIds;
    }

    @WrapMethod(method = "getOrCreateSection")
    private EntitySection<T> fabrolia$guardGetOrCreate(long key, Operation<EntitySection<T>> original) {
        // StampedLock stamps are NOT reentrant and NOT thread-owned: a throw out
        // of original.call (the vanilla computeIfAbsent mapping function can throw,
        // and OOME is always possible) leaks the write stamp with no owner able to
        // release it — every later entity add/remove on this level, main included,
        // blocks forever. Audit 2026-07-26.
        long stamp = fabrolia$sectionGuard.writeLock();
        try {
            return original.call(key);
        } finally {
            fabrolia$sectionGuard.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$guardRemove(long sectionKey, Operation<Void> original) {
        long stamp = fabrolia$sectionGuard.writeLock();
        try {
            original.call(sectionKey);
        } finally {
            fabrolia$sectionGuard.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "getExistingSectionsInChunk")
    private Stream<EntitySection<T>> getExistingSections(long chunkKey, Operation<Stream<EntitySection<T>>> original) {
        return this.getExistingSectionPositionsInChunk(chunkKey)
                .mapToObj(this.sections::get)
                .filter(Objects::nonNull)
                .toList()
                .stream();
    }
}