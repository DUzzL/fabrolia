package com.axalotl.fabrolia.common.regions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The audited native player observer surface, not player gameplay ownership.
 * Positions are current native immutable values, never a tick snapshot. Spectator
 * mode and Vanish/PDA authoritative mutations must remain globally excluded from
 * island observers; the optional cache adapter only synchronizes lazy cache state.
 */
public final class NativePlayerObservations {
    private NativePlayerObservations() {}

    /** Installed only together with both private-lock Vanish cache wrappers. */
    public interface VanishCache {}

    /** Reads the actual native link, not a copied graph or a gameplay getter. */
    public interface RidingMetadata {
        Entity fabrolia$queryVehicle();
    }

    private static final ClassValue<Boolean> RIDING = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Field vehicle = Entity.class.getDeclaredField("vehicle");
                return vehicle.getType() == Entity.class
                        && Modifier.isVolatile(vehicle.getModifiers())
                        && Modifier.isFinal(Entity.class.getMethod("fabrolia$queryVehicle").getModifiers())
                        && declaredBy(type, Entity.class, "isPassenger")
                        && declaredBy(type, Entity.class, "getVehicle");
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    /**
     * Admission metadata only: no virtual gameplay getter or callback is entered.
     * Removed/unindexed ancestors are deliberately not skipped. A cycle or a
     * concurrently changing/long chain conservatively selects the original Main
     * producer; the actual notRiding callback still traverses its full live chain.
     *
     * <p>Supported native and FSit link additions check this capability before
     * their bodies. A transition outside the capability needs already-exclusive
     * Main, which cannot cross an admitted E. Local native-to-native changes need
     * not preserve link identity: every receiver they can introduce stays safe.
     * This is not a sandbox for arbitrary reflective/mod field mutation.
     */
    static boolean supportsRidingClosure(Entity root) {
        Entity cursor = root;
        for (int depth = 0; cursor != null; depth++) {
            if (depth == 64 || !supportsRidingReceiver(cursor)) return false;
            cursor = ((RidingMetadata) cursor).fabrolia$queryVehicle();
        }
        return true;
    }

    private static boolean supportsRidingReceiver(Entity entity) {
        return entity instanceof RidingMetadata && RIDING.get(entity.getClass());
    }

    /**
     * Invariant at the supported mutation entry, never a late writer upgrade.
     * Opaque packet producers must have selected Main before packet.handle;
     * unsupported synchronous mod entries fail before changing the native graph.
     */
    public static void requireSupportedRideTransition(Entity vehicle) {
        if (RegionScheduler.workerCount() == 0 || RegionScheduler.ownsExclusiveGameplay()) return;
        // No depth limit at mutation entry: long native chains remain legal.
        // The slower cursor detects a cycle without a per-transition collection.
        Entity cursor = vehicle;
        Entity slower = vehicle;
        boolean advanceSlower = false;
        while (cursor != null) {
            if (!supportsRidingReceiver(cursor)) {
                throw new IllegalStateException("unsupported riding callback transition requires exclusive Main");
            }
            cursor = ((RidingMetadata) cursor).fabrolia$queryVehicle();
            if (advanceSlower) {
                if (!supportsRidingReceiver(slower)) {
                    throw new IllegalStateException("riding callback metadata changed before mutation entry");
                }
                slower = ((RidingMetadata) slower).fabrolia$queryVehicle();
            }
            advanceSlower = !advanceSlower;
            if (cursor != null && cursor == slower) {
                throw new IllegalStateException("cyclic riding callback transition");
            }
        }
    }

    private static final ClassValue<Boolean> SUPPORTED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type != ServerPlayer.class && !isExactCarpetPlayer(type)) return false;
            try {
                Field position = Entity.class.getDeclaredField("position");
                if (position.getType() != Vec3.class
                        || !Modifier.isVolatile(position.getModifiers())) return false;
                if (!declaredBy(type, Entity.class, "position")
                        || !declaredBy(type, Entity.class, "getX")
                        || !declaredBy(type, Entity.class, "getY")
                        || !declaredBy(type, Entity.class, "getZ")
                        || !declaredBy(type, Entity.class, "distanceToSqr",
                                double.class, double.class, double.class)
                        || !declaredBy(type, Entity.class, "distanceToSqr", Vec3.class)
                        || !declaredBy(type, Entity.class, "distanceToSqr", Entity.class)
                        || !declaredBy(type, Player.class, "isSpectator")
                        || !declaredBy(type, ServerPlayer.class, "gameMode")) return false;
                return !VanishCache.class.isAssignableFrom(type)
                        || declaredBy(type, ServerPlayer.class, "vanish$isVanished")
                        && declaredBy(type, ServerPlayer.class, "vanish$setDirty");
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return false;
            }
        }
    };

    private static boolean isExactCarpetPlayer(Class<?> type) {
        if (!"carpet.patches.EntityPlayerMPFake".equals(type.getName())) return false;
        try {
            return type == Class.forName("carpet.patches.EntityPlayerMPFake", false,
                    NativePlayerObservations.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError incompatible) {
            return false;
        }
    }

    private static boolean declaredBy(Class<?> type, Class<?> owner, String name,
                                      Class<?>... parameters) throws NoSuchMethodException {
        return type.getMethod(name, parameters).getDeclaringClass() == owner;
    }

    // Successful certificates cover exactly these two immutable runtime classes.
    // Avoid a ClassValue lookup for every player pair; negative/throwing paths
    // retain the original classifier and never publish a fast-path certificate.
    private static volatile boolean certifiedServerPlayer;
    private static volatile Class<?> certifiedCarpetPlayer;

    /** Does not read gameplay fields or call any player getter/predicate. */
    public static boolean isSupportedPlayer(ServerPlayer player) {
        if (player == null) return false;
        Class<?> type = player.getClass();
        if (type == ServerPlayer.class ? certifiedServerPlayer : type == certifiedCarpetPlayer) {
            return true;
        }
        boolean supported = SUPPORTED.get(type);
        if (supported) {
            if (type == ServerPlayer.class) certifiedServerPlayer = true;
            else certifiedCarpetPlayer = type;
        }
        return supported;
    }

    /** Identity only: the caller still invokes its actual callback exactly once. */
    public static boolean isSupportedPredicate(Predicate<?> predicate) {
        return predicate == EntitySelector.NO_SPECTATORS
                || predicate != null && predicate == VanishPredicate.INSTANCE;
    }

    private static final class VanishPredicate {
        private static final Predicate<?> INSTANCE = find();

        private static Predicate<?> find() {
            // No optional class load when the cache adapter is absent, including
            // vanilla-only startup. Unknown versions/shapes are not certified.
            if (!VanishCache.class.isAssignableFrom(ServerPlayer.class)) return null;
            try {
                Class<?> mod = Class.forName("me.drex.vanish.VanishMod", false,
                        NativePlayerObservations.class.getClassLoader());
                Field field = mod.getField("NO_SPECTATORS_AND_NO_VANISH");
                int modifiers = field.getModifiers();
                if (field.getDeclaringClass() != mod || field.getType() != Predicate.class
                        || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                        || !declaredBy(ServerPlayer.class, ServerPlayer.class, "vanish$isVanished")
                        || !declaredBy(ServerPlayer.class, ServerPlayer.class, "vanish$setDirty")) {
                    return null;
                }
                return (Predicate<?>) field.get(null);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                return null;
            }
        }
    }

    /** Actual callback entry, never used by producer preflight. */
    public static boolean testPredicate(ServerPlayer player, Predicate<? super ServerPlayer> predicate) {
        if (predicate == null) return true;
        if (RegionScheduler.workerCount() != 0
                && !(isSupportedPlayer(player) && isSupportedPredicate(predicate))) {
            requireTargetAuthority(player);
        }
        return predicate.test(player);
    }

    /** Rich callbacks require real gameplay ownership of the observed player. */
    public static void requireGameplayObservation(ServerPlayer player) {
        if (RegionScheduler.workerCount() != 0) {
            requireTargetAuthority(player);
        }
    }

    public static Vec3 position(ServerPlayer player) {
        if (RegionScheduler.workerCount() != 0 && !isSupportedPlayer(player)) requireTargetAuthority(player);
        // SynchronisePlugin strengthens the actual Entity field to ACC_VOLATILE.
        // The audited getter acquires exactly one immutable native Vec3 reference.
        return player.position();
    }

    public static double distanceToSqr(ServerPlayer player, double x, double y, double z) {
        if (RegionScheduler.workerCount() == 0) return player.distanceToSqr(x, y, z);
        if (!isSupportedPlayer(player)) {
            requireTargetAuthority(player);
            // Genuine owners retain an unknown implementation's virtual behavior.
            return player.distanceToSqr(x, y, z);
        }
        Vec3 current = player.position();
        double dx = current.x - x;
        double dy = current.y - y;
        double dz = current.z - z;
        // Preserve Entity.distanceToSqr's subtraction direction and evaluation order.
        return dx * dx + dy * dy + dz * dz;
    }

    private static void requireTargetAuthority(ServerPlayer player) {
        if (!RegionScheduler.ownsExclusiveGameplay()
                && !AsyncIslandPlayerTicks.ownsAdvancementObservation(player)) {
            throw new IllegalStateException("unsupported foreign player observation: "
                    + player.getClass().getName());
        }
    }
}
