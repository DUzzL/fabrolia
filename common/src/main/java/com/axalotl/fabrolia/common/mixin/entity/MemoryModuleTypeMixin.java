package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.AtomicMemoryStorage.MemoryTypeIdAccess;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MemoryModuleType.class)
public class MemoryModuleTypeMixin implements MemoryTypeIdAccess {

    @Unique
    private int fabrolia$memoryId = -1;

    @Unique
    @Override
    public int fabrolia$memoryId() {
        return this.fabrolia$memoryId;
    }

    @Unique
    @Override
    public void fabrolia$setMemoryId(int id) {
        this.fabrolia$memoryId = id;
    }
}
