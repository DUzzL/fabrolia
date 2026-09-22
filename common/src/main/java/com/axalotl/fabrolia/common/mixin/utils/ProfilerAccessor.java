package com.axalotl.fabrolia.common.mixin.utils;

import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Static accessor used to transfer the active profiler stack with the server turn. */
@Mixin(Profiler.class)
public interface ProfilerAccessor {

    @Accessor("ACTIVE")
    static ThreadLocal<ProfilerFiller> fabrolia$activeProfiler() {
        throw new AssertionError("mixin accessor was not transformed");
    }
}
