package com.axalotl.fabrolia.common.mixin.entity;

import com.axalotl.fabrolia.common.regions.RegionKeyed;
import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worldgen-private ride bypass must only ever admit components whose every
 * member is unpublished and player-free; publication anywhere in the ride tree,
 * even when reached only through a vehicle or passenger link, keeps the fence.
 */
class EntityUnpublishedRideTest {
    private static final EntityInLevelCallback PUBLISHED = new EntityInLevelCallback() {
        @Override public void onMove() {}
        @Override public void onRemove(Entity.RemovalReason reason) {}
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void freshWorldgenMountBypassesTheFence() throws Exception {
        Probe rider = probe();
        Probe mount = probe();
        assertTrue(unpublishedRide(rider, mount));
        assertTrue(unpublishedRide(rider));
    }

    @Test
    void publishedParticipantAnywhereInTheComponentKeepsTheFence() throws Exception {
        Probe rider = probe();
        Probe mount = probe();
        mount.callback = PUBLISHED;
        assertFalse(unpublishedRide(rider, mount), "published mount named directly");

        Probe indexedOnly = probe();
        indexedOnly.key = 7L;
        assertFalse(unpublishedRide(rider, indexedOnly), "region-indexed mount");

        Probe vehicle = probe();
        vehicle.callback = PUBLISHED;
        Probe rootRider = probe();
        link(vehicle, rootRider);
        assertFalse(unpublishedRide(rootRider), "published vehicle reached through the vehicle link");

        Probe publishedPassenger = probe();
        publishedPassenger.callback = PUBLISHED;
        Probe carrier = probe();
        link(carrier, publishedPassenger);
        assertFalse(unpublishedRide(carrier), "published passenger reached through the passenger link");
    }

    @Test
    void serverPlayerKeepsTheFence() throws Exception {
        ServerPlayer player = allocate(ServerPlayer.class);
        set(player, "passengers", ImmutableList.of());
        assertFalse(unpublishedRide(probe(), player));
    }

    private static boolean unpublishedRide(Entity... participants) throws Exception {
        Method predicate = EntityMixin.class.getDeclaredMethod("fabrolia$isUnpublishedRide", Entity[].class);
        predicate.setAccessible(true);
        return (boolean) predicate.invoke(null, (Object) participants);
    }

    private static void link(Probe vehicle, Probe passenger) throws Exception {
        set(vehicle, "passengers", ImmutableList.of(passenger));
        set(passenger, "vehicle", vehicle);
    }

    private static Probe probe() throws Exception {
        Probe probe = allocate(Probe.class);
        probe.callback = EntityInLevelCallback.NULL;
        probe.key = RegionKeyed.NO_REGION;
        set(probe, "passengers", ImmutableList.of());
        return probe;
    }

    private static void set(Entity entity, String name, Object value) throws Exception {
        Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    /** Untransformed stand-in for the mixin-merged Entity: carries only the two publication signals. */
    private static final class Probe extends ExperienceOrb implements EntityLevelCallbackAccessor, RegionKeyed {
        EntityInLevelCallback callback;
        long key;
        Probe() { super(null, null); }
        @Override public EntityInLevelCallback fabrolia$getLevelCallback() { return callback; }
        @Override public long fabrolia$getRegionKey() { return key; }
        @Override public void fabrolia$setRegionKey(long key) { this.key = key; }
        @Override public boolean fabrolia$tryMarkRegionTick(long token) { return false; }
        @Override public boolean fabrolia$carryRegionTickMark(long expectedFrom, long newToken) { return false; }
    }
}
