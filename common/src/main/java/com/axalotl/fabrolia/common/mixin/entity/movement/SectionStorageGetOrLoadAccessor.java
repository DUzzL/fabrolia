package com.axalotl.fabrolia.common.mixin.entity.movement;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/** Invoker for {@code SectionStorage.getOrLoad} (synchronous unpack of the
 *  section's chunk when absent) — used by the village-distance freshness fix
 *  in {@link PoiManagerDistanceMixin}. */
@Mixin(SectionStorage.class)
public interface SectionStorageGetOrLoadAccessor {

    @Invoker("getOrLoad")
    Optional<?> fabrolia$invokeGetOrLoad(long sectionPos);
}
