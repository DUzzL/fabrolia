package com.axalotl.fabrolia.common.mixin.c2me;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Private-field accessor used by the C2ME entity-id compatibility replacement.
 *
 * <p>This intentionally reads the physical {@link Level} owner.  It must not be
 * redirected through a logical-thread API: the paired wrapper decides explicitly
 * whether the caller is either that physical owner or the authenticated exact
 * player actor.</p>
 */
@Mixin(Level.class)
public interface FabroliaC2meLevelThreadAccessor {

    @Accessor("thread")
    Thread fabrolia$getPhysicalLevelThread();
}
