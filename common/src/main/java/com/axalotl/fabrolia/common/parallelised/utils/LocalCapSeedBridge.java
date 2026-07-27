package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.MobCategory;

/**
 * Static state for {@code LocalMobCapCalculatorMixin}, hosted in a PLAIN class.
 *
 * <p>HARD LESSON (task #32, 2026-07-21 crash): adding {@code @Unique} STATIC
 * fields with initializers to that mixin introduced a {@code <clinit>} the
 * mixin never had — and at runtime the target's instance {@code @Unique}
 * initializers (the long-proven {@code fabrolia$capFlight}) silently stopped
 * running: NPE in {@code detachAndFree}'s in-flight wait on the first arena
 * tick, world-tick crash. Mixin static-initializer merging is not a contract
 * to lean on; statics referenced by mixin code live HERE instead, where the
 * JVM guarantees initialization. Never add initialized static fields to an
 * existing mixin.
 */
public final class LocalCapSeedBridge {

    private LocalCapSeedBridge() {}

    /** Set while fabrolia$seedJavaShadow replays the census seed into the JAVA
     *  maps: the native routing must stand aside so the vanilla body runs (the
     *  arena already holds these counts from its own native seed). */
    public static final ThreadLocal<Boolean> SEED_BYPASS = new ThreadLocal<>();

    public static final MobCategory[] CATS = MobCategory.values();
}
