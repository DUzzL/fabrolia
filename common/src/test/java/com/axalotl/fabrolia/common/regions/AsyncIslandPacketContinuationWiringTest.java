package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.Packet;
import org.junit.jupiter.api.Test;

import static com.axalotl.fabrolia.common.regions.AsyncIslandPacketPolicy.Lane.MAIN_FIFO;
import static org.junit.jupiter.api.Assertions.assertSame;

class AsyncIslandPacketContinuationWiringTest {
    @Test
    void internalContinuationPacketsFailClosedToMain() {
        for (Class<?> nested : AsyncIslandPacketContinuations.class.getDeclaredClasses()) {
            if (Packet.class.isAssignableFrom(nested)) {
                assertSame(MAIN_FIFO, AsyncIslandPacketPolicy.classifyType(nested));
            }
        }
    }
}
