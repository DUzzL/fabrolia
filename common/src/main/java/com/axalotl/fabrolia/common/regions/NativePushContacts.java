package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Only the post-travel native push operation, never damage or a recipient tick. */
final class NativePushContacts {
    private NativePushContacts() {}

    private static final ClassValue<Boolean> PROGRAM = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> push = owner(type, "push", Entity.class);
                Class<?> pushable = owner(type, "isPushable");
                Class<?> alive = owner(type, "isAlive");
                Class<?> spectator = owner(type, "isSpectator");
                Class<?> team = owner(type, "getTeam");
                return (push == Entity.class || push == LivingEntity.class)
                        && (pushable == Entity.class || pushable == LivingEntity.class)
                        && (alive == Entity.class || alive == LivingEntity.class)
                        && (spectator == Entity.class || spectator == Player.class || spectator == ServerPlayer.class)
                        && (team == Entity.class || team == Player.class
                            || team == TamableAnimal.class && owner(type, "isTame") == TamableAnimal.class)
                        && owner(type, "push", double.class, double.class, double.class) == Entity.class
                        && owner(type, "getDeltaMovement") == Entity.class
                        && owner(type, "setDeltaMovement", Vec3.class) == Entity.class
                        && owner(type, "setDeltaMovement", double.class, double.class, double.class) == Entity.class
                        && owner(type, "canBeCollidedWith", Entity.class) == Entity.class
                        && owner(type, "canCollideWith", Entity.class) == Entity.class
                        && owner(type, "isVehicle") == Entity.class
                        && owner(type, "getVehicle") == Entity.class
                        && owner(type, "getRootVehicle") == Entity.class
                        && owner(type, "isPassengerOfSameVehicle", Entity.class) == Entity.class
                        && (!LivingEntity.class.isAssignableFrom(type)
                            || owner(type, "onClimbable") == LivingEntity.class
                                && owner(type, "getHealth") == LivingEntity.class);
            } catch (LinkageError incompatible) {
                return false;
            }
        }
    };
    private static final ClassValue<Boolean> SOURCE_PROGRAM = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return owner(type, "doPush", Entity.class) == LivingEntity.class;
        }
    };

    /** Called only after the caller has proved the recipient's actual ownership. */
    static boolean recipient(Entity entity) {
        if (!PROGRAM.get(entity.getClass())
                || entity instanceof TamableAnimal animal && animal.isTame()
                || entity.getVehicle() != null || entity.isVehicle()) return false;
        var movement = entity.getDeltaMovement();
        if (movement == null || movement.getClass() != Vec3.class) return false;
        var team = entity.getTeam();
        return team == null || team.getClass() == net.minecraft.world.scores.PlayerTeam.class;
    }

    static boolean source(Mob source) {
        return SOURCE_PROGRAM.get(source.getClass()) && recipient(source);
    }

    private static Class<?> owner(Class<?> type, String name, Class<?>... arguments) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(name, arguments);
                return current;
            } catch (NoSuchMethodException absent) {
                // Resolve the exact signature, including protected native methods.
            }
        }
        return null;
    }
}
