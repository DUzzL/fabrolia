package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.platform.PlatformUtils;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Fail-closed predicates for tick injectors present in the production pack but not
 * available on the common compile classpath.
 *
 * <p>This is deliberately an eligibility belt, not another feature gate. Unknown
 * layout, inaccessible state, or an active mod action keeps that player on the
 * established main path. The checks do not claim arbitrary transformed-tick
 * equivalence; they only exclude the concrete known active-state hazards.
 */
final class PlayerTickKnownModEnvelope {

    private PlayerTickKnownModEnvelope() {}

    private interface Probe {
        boolean idle(ServerPlayer player);
    }

    private static final Probe CARPET = carpetProbe();
    private static final Probe FSIT = fsitProbe();

    static boolean idle(ServerPlayer player) {
        return CARPET.idle(player) && FSIT.idle(player);
    }

    static boolean idleForAsync(ServerPlayer player, IslandPlayerTurnSession.PlayerKind kind) {
        return FSIT.idle(player)
                && (kind == IslandPlayerTurnSession.PlayerKind.CARPET || CARPET.idle(player));
    }

    /** Main-thread, status-command diagnostic; never used on the per-tick hot path. */
    static String refusalReason(ServerPlayer player) {
        if (!CARPET.idle(player)) return "Carpet action-pack state is active or incompatible";
        if (!FSIT.idle(player)) return "FSit state is active or incompatible";
        return null;
    }

    private static Probe carpetProbe() {
        ClassLoader loader = ServerPlayer.class.getClassLoader();
        try {
            Class<?> playerInterface = Class.forName(
                    "carpet.fakes.ServerPlayerInterface", false, loader);
            Class<?> actionPackClass = Class.forName(
                    "carpet.helpers.EntityPlayerActionPack", false, loader);
            Method getActionPack = playerInterface.getMethod("getActionPack");
            Field actions = field(actionPackClass, "actions");
            Field currentBlock = field(actionPackClass, "currentBlock");
            Field blockHitDelay = field(actionPackClass, "blockHitDelay");
            Field hittingBlock = field(actionPackClass, "isHittingBlock");
            Field blockDamage = field(actionPackClass, "curBlockDamageMP");
            Field sneaking = field(actionPackClass, "sneaking");
            Field sprinting = field(actionPackClass, "sprinting");
            Field forward = field(actionPackClass, "forward");
            Field strafing = field(actionPackClass, "strafing");
            Field itemUseCooldown = field(actionPackClass, "itemUseCooldown");
            return player -> {
                if (!playerInterface.isInstance(player)) return true;
                try {
                    Object pack = getActionPack.invoke(player);
                    return pack != null
                            && ((Map<?, ?>) actions.get(pack)).isEmpty()
                            && currentBlock.get(pack) == null
                            && blockHitDelay.getInt(pack) == 0
                            && !hittingBlock.getBoolean(pack)
                            && blockDamage.getFloat(pack) == 0.0F
                            && !sneaking.getBoolean(pack)
                            && !sprinting.getBoolean(pack)
                            && forward.getFloat(pack) == 0.0F
                            && strafing.getFloat(pack) == 0.0F
                            && itemUseCooldown.getInt(pack) == 0;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    return false;
                }
            };
        } catch (ClassNotFoundException absent) {
            return player -> modAbsent("carpet");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return player -> false;
        }
    }

    private static Probe fsitProbe() {
        ClassLoader loader = ServerPlayer.class.getClassLoader();
        try {
            Class<?> poseHolder = Class.forName(
                    "dev.rvbsm.fsit.api.FSitPoseHolder", false, loader);
            Method getPose = poseHolder.getMethod("fsit$getPose");
            Field lastSentPose = field(ServerPlayer.class, "fsit$lastSentPose");
            Field sneakTrigger = field(ServerPlayer.class, "fsit$sneakTriggerTime");
            Field lastShift = field(ServerPlayer.class, "fsit$lastShiftKeyDown");
            Field attached = field(ServerPlayer.class, "fsit$attachedEntity");
            Field pendingRider = field(ServerPlayer.class, "fsit$pendingRider");
            return player -> {
                if (!poseHolder.isInstance(player)) return true;
                try {
                    return PlayerTickKnownModPoseState.idle(
                                    getPose.invoke(player), lastSentPose.get(player))
                            && sneakTrigger.getInt(player) == 0
                            && !lastShift.getBoolean(player)
                            && !player.isShiftKeyDown()
                            && attached.get(player) == null
                            && pendingRider.get(player) == null;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    return false;
                }
            };
        } catch (ClassNotFoundException absent) {
            return player -> modAbsent("fsit");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return player -> false;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) {
            throw new IllegalStateException("cannot inspect " + owner.getName() + "." + name);
        }
        return field;
    }

    private static boolean modAbsent(String id) {
        try {
            return !PlatformUtils.isModLoaded(id);
        } catch (RuntimeException platformFailure) {
            return false;
        }
    }

}

/** MC-free value predicate kept separate so the valid fresh-player state is unit-testable. */
final class PlayerTickKnownModPoseState {

    private PlayerTickKnownModPoseState() {}

    /**
     * FSit initialises the current pose to STANDING, while its last-sent pose remains
     * null until the first pose packet. That unsent baseline is idle; a non-standing
     * current or previously sent pose remains fail-closed.
     */
    static boolean idle(Object currentPose, Object lastSentPose) {
        return enumName(currentPose).equals("STANDING")
                && (lastSentPose == null || enumName(lastSentPose).equals("STANDING"));
    }

    private static String enumName(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        throw new ClassCastException("expected enum pose, got "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
