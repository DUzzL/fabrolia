package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionAccessDetectorTest {
    @Test
    void scheduledFluidLambdaIsNotAttributedToAnExternalMod() {
        StackTraceElement[] trace = {
                new StackTraceElement("net.minecraft.world.level.material.FlowingFluid", "spreadTo", "FlowingFluid.java", 304),
                new StackTraceElement("com.axalotl.fabrolia.common.regions.IslandScheduledTicks",
                        "lambda$runOneCollectedTick$0", "IslandScheduledTicks.java", 1813)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.UNKNOWN,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void lambdaForDollarNamedMethodIsNotAMixinHandler() {
        StackTraceElement[] trace = {
                new StackTraceElement("com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin",
                        "lambda$fabrolia$guardTick$0", "ServerLevelMixin.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.UNKNOWN,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void externalLambdaStillIdentifiesAForeignWriter() {
        StackTraceElement[] trace = {
                new StackTraceElement("example.foreignmod.Writer",
                        "lambda$write$0", "Writer.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }

    @Test
    void foreignHandlerGraftedIntoVanillaRemainsAnExternalWriter() {
        StackTraceElement[] trace = {
                new StackTraceElement("net.minecraft.world.level.Level",
                        "handler$abc000$foreignmod$write", "Level.java", 1)
        };
        assertEquals(RegionAccessDetector.CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE,
                RegionAccessDetector.classify(trace, null, null, new StringBuilder()));
    }
}
