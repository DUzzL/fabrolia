package com.axalotl.fabrolia.common.mixin.world;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NetherPortalBlock.class)
public interface NetherPortalBlockInvoker {
    @Invoker("createDimensionTransition")
    static TeleportTransition fabrolia$createDimensionTransition(ServerLevel level,
            BlockUtil.FoundRectangle rectangle, Direction.Axis axis, Vec3 relative,
            Entity entity, TeleportTransition.PostTeleportTransition post) {
        throw new AssertionError("mixin invoker not applied");
    }
}
