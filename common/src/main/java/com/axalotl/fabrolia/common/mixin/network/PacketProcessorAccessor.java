package com.axalotl.fabrolia.common.mixin.network;

import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla's shutdown edge for queued play packets: {@code stopServer} closes the
 * server's processor first, after which {@code processQueuedPackets} handles none.
 */
@Mixin(PacketProcessor.class)
public interface PacketProcessorAccessor {
    @Accessor("closed")
    boolean fabrolia$isClosed();
}
