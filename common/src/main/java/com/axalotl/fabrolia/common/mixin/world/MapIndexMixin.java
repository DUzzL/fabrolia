package com.axalotl.fabrolia.common.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import org.spongepowered.asm.mixin.Mixin;

/** Two islands creating a map at once must never draw the same map id. */
@Mixin(MapIndex.class)
public abstract class MapIndexMixin {
    @WrapMethod(method = "getNextMapId")
    private synchronized MapId fabrolia$serializedNextMapId(Operation<MapId> original) {
        return original.call();
    }
}
