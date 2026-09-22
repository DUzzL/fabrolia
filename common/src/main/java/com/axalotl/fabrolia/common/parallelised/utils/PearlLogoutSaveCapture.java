package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

import java.util.Set;

/**
 * Same-thread capture of the exact pearl identities successfully written by a
 * PlayerList.remove save. PlayerList must later mark only that stable set
 * UNLOADED_WITH_PLAYER. A second weak iteration could otherwise see a
 * cross-dimension destination which the save iterator legitimately missed and
 * remove the only world-persistable copy.
 */
public final class PearlLogoutSaveCapture {

    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<>();

    private PearlLogoutSaveCapture() {}

    public static Frame begin(ServerPlayer owner) {
        Frame frame = new Frame(owner, ACTIVE.get());
        ACTIVE.set(frame);
        return frame;
    }

    public static void record(ServerPlayer owner, ThrownEnderpearl pearl) {
        Frame frame = ACTIVE.get();
        if (frame != null && frame.owner == owner) {
            frame.saved.add(pearl);
        }
    }

    /** Returns null after a failed save; otherwise returns the detached exact
     * identity set (which may be empty). */
    public static Set<ThrownEnderpearl> finish(Frame frame, boolean succeeded) {
        if (ACTIVE.get() != frame) {
            return null;
        }
        if (frame.previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(frame.previous);
        }
        return succeeded ? frame.saved : null;
    }

    public static final class Frame {
        private final ServerPlayer owner;
        private final Frame previous;
        private final ConcurrentIdentitySet<ThrownEnderpearl> saved =
                new ConcurrentIdentitySet<>();

        private Frame(ServerPlayer owner, Frame previous) {
            this.owner = owner;
            this.previous = previous;
        }
    }
}
