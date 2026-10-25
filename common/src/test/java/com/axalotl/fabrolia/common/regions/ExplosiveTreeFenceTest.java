package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.CreeperExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.LargeFireballExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.MinecartTntExplosionAccessor;
import com.axalotl.fabrolia.common.mixin.entity.PrimedTntExplosionAccessor;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit B-02: explosive entities other than a root primed TNT (TNT minecart,
 * swelling creeper, large fireball, primed TNT riding in a tree) whose possible
 * blast leaves the island tick under the global fence.
 */
final class ExplosiveTreeFenceTest {
    private static final Vec3 CENTRE = new Vec3(64.5, 64, 64.5); // middle of home's only cell

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaStrengthsStayOnTheWorker() throws Exception {
        Fixture f = new Fixture();
        assertFalse(f.leaves(cart(4.0F, 1.0F)), "default cart: power <= 11.5");
        assertFalse(f.leaves(creeper(3, true, true)), "charged default creeper: power 6");
        assertFalse(f.leaves(fireball(1)), "ghast fireball: power 1");
        assertFalse(f.leaves(at(Pig.class)), "not an explosive");
    }

    @Test
    void raisedStrengthsTakeTheFence() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.leaves(cart(128.0F, 0.0F)), "NBT explosion_power 128");
        assertTrue(f.leaves(cart(4.0F, 20.0F)), "speed factor raises the cart's reach");
        assertTrue(f.leaves(creeper(100, true, false)), "swelling creeper with ExplosionRadius 100");
        assertFalse(f.leaves(creeper(100, false, false)), "a creeper that is not swelling cannot explode this tick");
        assertTrue(f.leaves(fireball(100)), "fireball with ExplosionPower 100");
    }

    @Test
    void primedTntRidingInATreeTakesTheFence() throws Exception {
        Fixture f = new Fixture();
        Tnt tnt = tnt(0, 100.0F);
        assertFalse(f.leaves(tnt), "the root primed TNT keeps its own requiresGlobalFence check");
        Pig vehicle = at(Pig.class);
        passengers(vehicle, tnt);
        assertTrue(f.leaves(vehicle), "a riding primed TNT explodes inside the root's tick");
        tnt.fuse = 40;
        assertFalse(f.leaves(vehicle), "a riding primed TNT with fuse left cannot explode this tick");
    }

    @Test
    void entityTickRoutesExplosiveTreesBeforeTheWorkerBody() throws Exception {
        String text = source("common/regions/RegionizedServer.java");
        int tnt = text.indexOf("TntExplosionFootprint.requiresGlobalFence(this, region, tnt)");
        int tree = text.indexOf("TntExplosionFootprint.explosiveTreeLeavesIsland(this, region, e)", tnt);
        int fenced = text.indexOf("RegionScheduler.queueMainSyncFencedTick(e,", tree);
        int workerBody = text.indexOf("ParallelProcessor.tickEntity(lvl, e, true)", tree);
        assertTrue(tnt >= 0 && tree > tnt && fenced > tree && workerBody > fenced,
                "explosive trees must take the globally fenced MAIN_SYNC lane before the worker tick");
    }

    private static final class Fixture {
        final RegionizedServer runtime;
        final IslandRegistry.PIsland home;

        Fixture() throws Exception {
            ServerLevel level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
            runtime = (RegionizedServer) unsafe().allocateInstance(RegionizedServer.class);
            ThreadedRegionizer regionizer = new ThreadedRegionizer(level, new RegionGrid(3));
            regionizer.owner = runtime;
            set(RegionizedServer.class, runtime, "level", level);
            set(RegionizedServer.class, runtime, "regionizer", regionizer);
            runtime.asyncActive = true;
            home = new IslandRegistry.PIsland(1L, 100L);
            home.level = level;
            Long2ObjectOpenHashMap<EpochDomain> cells = new Long2ObjectOpenHashMap<>();
            cells.put(regionizer.grid.keyFor(new ChunkPos(0, 0).pack()), home.domain);
            set(RegionizedServer.class, runtime, "cellDomainSnapshot", cells);
        }

        boolean leaves(Entity root) throws Exception {
            Method method = TntExplosionFootprint.class.getDeclaredMethod("treeLeavesIsland",
                    RegionizedServer.class, IslandRegistry.PIsland.class, Entity.class, boolean.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, runtime, home, root, true);
        }
    }

    private static Cart cart(float base, float factor) throws Exception {
        Cart cart = at(Cart.class);
        cart.base = base;
        cart.factor = factor;
        return cart;
    }

    private static Boom creeper(int radius, boolean swelling, boolean powered) throws Exception {
        Boom creeper = at(Boom.class);
        creeper.radius = radius;
        creeper.swelling = swelling;
        creeper.powered = powered;
        return creeper;
    }

    private static Ball fireball(int power) throws Exception {
        Ball fireball = at(Ball.class);
        fireball.power = power;
        return fireball;
    }

    private static Tnt tnt(int fuse, float power) throws Exception {
        Tnt tnt = at(Tnt.class);
        tnt.fuse = fuse;
        tnt.power = power;
        return tnt;
    }

    private static <T extends Entity> T at(Class<T> type) throws Exception {
        @SuppressWarnings("unchecked")
        T entity = (T) unsafe().allocateInstance(type);
        passengers(entity);
        set(Entity.class, entity, "position", CENTRE);
        set(Entity.class, entity, "blockPosition", BlockPos.containing(CENTRE));
        set(Entity.class, entity, "deltaMovement", Vec3.ZERO);
        return entity;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Cart extends MinecartTNT implements MinecartTntExplosionAccessor {
        float base;
        float factor;
        private Cart() { super((EntityType) null, (Level) null); }
        @Override public float fabrolia$explosionPowerBase() { return base; }
        @Override public float fabrolia$explosionSpeedFactor() { return factor; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Boom extends Creeper implements CreeperExplosionAccessor {
        int radius;
        boolean swelling;
        boolean powered;
        private Boom() { super((EntityType) null, (Level) null); }
        @Override public int fabrolia$explosionRadius() { return radius; }
        @Override public boolean isIgnited() { return false; }
        @Override public int getSwellDir() { return swelling ? 1 : -1; }
        @Override public boolean isPowered() { return powered; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Ball extends LargeFireball implements LargeFireballExplosionAccessor {
        int power;
        private Ball() { super((EntityType) null, (Level) null); }
        @Override public int fabrolia$explosionPower() { return power; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Tnt extends PrimedTnt implements PrimedTntExplosionAccessor {
        int fuse;
        float power;
        private Tnt() { super((EntityType) null, (Level) null); }
        @Override public int getFuse() { return fuse; }
        @Override public float fabrolia$explosionPower() { return power; }
    }

    private static void passengers(Entity root, Entity... passengers) throws Exception {
        set(Entity.class, root, "passengers", ImmutableList.copyOf(passengers));
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        throw new AssertionError("source tree not available");
    }
}
