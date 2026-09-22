package com.axalotl.fabrolia.api.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Exact-identity ownership for item intake and consumable simulation. No region
 * lane is acquired by pickup: callers may already hold a region or world claim.
 * Portal capture never waits for this ownership while retaining such a claim.
 */
public class SyncItemPickup {
    public interface PortalReservation {
        boolean fabrolia$isPortalPending();
        boolean fabrolia$isPortalResolutionOwner();
    }

    public interface Guarded {
        Guard fabrolia$consumptionGuard();
    }

    /** Stable per-entity ownership; consumption is distinct from a reentrant tick. */
    public static final class Guard extends ReentrantLock {
        private int consumptionDepth;

        public void enterActivity() {
            lock();
        }

        public void exitActivity() {
            unlock();
        }

        public void enterConsumption() {
            lock();
            consumptionDepth++;
        }

        public void exitConsumption() {
            if (!isHeldByCurrentThread() || consumptionDepth == 0)
                throw new IllegalMonitorStateException("Consumable guard is not consuming");
            consumptionDepth--;
            unlock();
        }

        /** An own tick may reserve; even an own native consumer may not. */
        public boolean tryEnterReservation() {
            if (!tryLock()) return false;
            if (consumptionDepth != 0) {
                unlock();
                return false;
            }
            return true;
        }

        public void exitReservation() {
            unlock();
        }

        public boolean consumingOnCurrentThread() {
            return isHeldByCurrentThread() && consumptionDepth != 0;
        }
    }

    /** Shared fallback merge/tick lane for synchronous or unowned execution. */
    private static final Object FALLBACK = new Object();

    private SyncItemPickup() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Object fallback() {
        return FALLBACK;
    }

    /** Nonconsumables need no additional identity lock during source capture. */
    public static Guard guard(Entity entity) {
        if (!(entity instanceof ItemEntity || entity instanceof ExperienceOrb)) return null;
        if (entity instanceof Guarded guarded) {
            Guard guard = guarded.fabrolia$consumptionGuard();
            if (guard != null) return guard;
        }
        throw new IllegalStateException("Consumable entity has no consumption guard");
    }

    /** Preparatory custody cannot revoke an already admitted tick or consumer. */
    public static boolean holdsUnreservedConsumption(Entity entity) {
        Guard guard = guard(entity);
        return guard != null && guard.isHeldByCurrentThread()
                && (!(entity instanceof PortalReservation reservation)
                    || !reservation.fabrolia$isPortalPending());
    }

    /** Rechecked under ownership, including after waiting for a native consumer. */
    public static boolean isAvailable(Entity entity) {
        if (entity.isRemoved()) return false;
        return !(entity instanceof PortalReservation reservation)
                || !reservation.fabrolia$isPortalPending()
                || reservation.fabrolia$isPortalResolutionOwner();
    }

    /** Whole ticks may submit their own portal source reentrantly. */
    public static void wrapTick(Entity entity, Runnable action) {
        Guard guard = guard(entity);
        if (guard == null) {
            if (isAvailable(entity)) action.run();
            return;
        }
        guard.enterActivity();
        try {
            if (isAvailable(entity)) action.run();
        } finally {
            guard.exitActivity();
        }
    }

    /** Native intake remains synchronous, but cannot reserve mid-consumption. */
    public static void wrap(ServerLevel level, ItemEntity entity, Runnable action) {
        Guard guard = guard(entity);
        guard.enterConsumption();
        try {
            if (isAvailable(entity)) action.run();
        } finally {
            guard.exitConsumption();
        }
    }
}
