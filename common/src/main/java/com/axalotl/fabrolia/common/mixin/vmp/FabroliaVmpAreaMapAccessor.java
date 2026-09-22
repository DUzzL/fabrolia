package com.axalotl.fabrolia.common.mixin.vmp;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the radius stored by VMP's {@code AreaMap} for one externally
 * serialized player-area mutation.
 *
 * <p>The manager must use this applied value rather than read ChunkMap's
 * mutable view distance again after the mutation. A concurrent distance
 * setter publishes its requested value before its manager callback obtains
 * Fabrolia's mutation lock, so a second external read can otherwise describe
 * a radius that VMP has not applied yet.</p>
 */
@Pseudo
@Mixin(targets = "com.ishland.vmp.common.maps.AreaMap", remap = false)
public interface FabroliaVmpAreaMapAccessor {

    @Accessor("viewDistances")
    Reference2IntOpenHashMap<Object> fabrolia$getAppliedViewDistances();
}
