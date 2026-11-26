package com.axalotl.fabrolia.common.mixin.entity.movement;

import com.axalotl.fabrolia.api.fastutil.ConcurrentLongSortedSet;
import com.axalotl.fabrolia.api.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.regions.CopperSectionAccess;
import com.axalotl.fabrolia.common.regions.DiskSectionAttachments;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
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
import java.util.stream.LongStream;
import java.util.stream.Stream;

@Mixin(value = EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin<T extends EntityAccess> implements DiskSectionAttachments.Access {

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
    private DiskSectionAttachments fabrolia$sectionGuard;

    @Override
    public DiskSectionAttachments fabrolia$diskSectionAttachments() {
        return fabrolia$sectionGuard;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabrolia$replaceMaps(CallbackInfo ci) {
        this.fabrolia$sectionGuard = new DiskSectionAttachments((EntitySectionStorage<?>) (Object) this);
        Long2ObjectConcurrentHashMap<EntitySection<T>> newSections = new Long2ObjectConcurrentHashMap<>();
        newSections.putAll(this.sections);
        this.sections = newSections;

        ConcurrentLongSortedSet newIds = new ConcurrentLongSortedSet();
        newIds.addAll(this.sectionIds);
        this.sectionIds = newIds;
    }

    @ModifyReturnValue(method = "createSection", at = @At("RETURN"), require = 1)
    private EntitySection<T> fabrolia$bindCopperSection(EntitySection<T> section, long key) {
        ((CopperSectionAccess) section).fabrolia$bindCopperSection(SectionPos.sectionToChunk(key));
        return section;
    }

    @ModifyExpressionValue(method = "remove", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;remove(J)Ljava/lang/Object;"), require = 1)
    private Object fabrolia$detachCopperSection(Object removed) {
        if (removed != null) ((CopperSectionAccess) removed).fabrolia$detachCopperSection();
        return removed;
    }

    @WrapMethod(method = "getOrCreateSection")
    private EntitySection<T> fabrolia$guardGetOrCreate(long key, Operation<EntitySection<T>> original) {
        synchronized (fabrolia$sectionGuard) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "remove")
    private void fabrolia$guardRemove(long sectionKey, Operation<Void> original) {
        fabrolia$sectionGuard.remove(sectionKey, key -> original.call(key));
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