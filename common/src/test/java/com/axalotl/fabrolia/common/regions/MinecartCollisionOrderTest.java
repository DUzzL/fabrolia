package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual 26.3 push body; only the level's environmental inputs are supplied. */
class MinecartCollisionOrderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void aCartCollisionChangesBothCartsBeforeTheNextCollision() throws Exception {
        Chain chain = chain();
        chain.left.push(chain.middle);

        assertEquals(0.09, chain.left.getDeltaMovement().x, 1.0E-8);
        assertEquals(0.15, chain.middle.getDeltaMovement().x, 1.0E-8);
        assertEquals(Vec3.ZERO, chain.right.getDeltaMovement());
    }

    @Test
    void aCollisionChainAcrossARegionBorderKeepsVanillaOrder() throws Exception {
        Chain vanilla = chain();
        vanilla.left.push(vanilla.middle);
        vanilla.middle.push(vanilla.right);

        Chain reversed = chain();
        reversed.middle.push(reversed.right);
        reversed.left.push(reversed.middle);

        assertArrayEquals(new double[]{0.09, 0.055, 0.125}, momentum(vanilla), 1.0E-8);
        assertArrayEquals(new double[]{0.065, 0.115, 0.05}, momentum(reversed), 1.0E-8);
        assertNotEquals(vanilla.middle.getDeltaMovement(), reversed.middle.getDeltaMovement(),
                "region wave order changes the actual vanilla push outcome");

        Region leftRegion = new Region(null, 1L);
        Region rightRegion = new Region(null, 2L);
        assertTrue(leftRegion.entities.add(vanilla.left));
        assertTrue(leftRegion.entities.add(vanilla.middle));
        assertTrue(rightRegion.entities.add(vanilla.right));
        assertTrue(RegionizedServer.holdsOrderSensitiveEntity(List.of(leftRegion, rightRegion)),
                "the parallel-mode admission must retain the serial vanilla pass for this chain");
    }

    private static Chain chain() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        TestLevel level = (TestLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(TestLevel.class);
        // The middle and right carts lie on opposite sides of the 128-block grid boundary.
        return new Chain(cart(level, 126.9, 0.2), cart(level, 127.7, 0.0), cart(level, 128.5, 0.0));
    }

    private static Minecart cart(TestLevel level, double x, double velocity) {
        Minecart cart = new Minecart(EntityTypes.MINECART, level);
        assertInstanceOf(OldMinecartBehavior.class, cart.getBehavior());
        cart.setPos(x, 64, 16);
        cart.setYRot(0);
        cart.setDeltaMovement(velocity, 0, 0);
        return cart;
    }

    private static double[] momentum(Chain chain) {
        return new double[]{chain.left.getDeltaMovement().x, chain.middle.getDeltaMovement().x,
                chain.right.getDeltaMovement().x};
    }

    private record Chain(Minecart left, Minecart middle, Minecart right) {}

    private static final class TestLevel extends ServerLevel {
        private int nextId;

        private TestLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }

        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.VANILLA_SET; }
        @Override public int getNextEntityId() { return ++nextId; }
    }
}
