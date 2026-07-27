package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.api.utils.ConcurrentCollections;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;

@Mixin(GossipContainer.class)
public class GossipContainerMixin {

    @Shadow
    private final Map<UUID, ?> gossips = ConcurrentCollections.newHashMap();
}