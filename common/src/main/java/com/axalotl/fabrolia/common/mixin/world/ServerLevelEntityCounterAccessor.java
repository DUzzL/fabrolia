package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ServerLevel.class)
public interface ServerLevelEntityCounterAccessor {
    @Accessor("ENTITY_COUNTER")
    static AtomicInteger fabrolia$entityCounter() {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
