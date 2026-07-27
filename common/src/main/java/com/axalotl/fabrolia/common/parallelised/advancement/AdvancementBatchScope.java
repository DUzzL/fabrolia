package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;

/**
 * One re-entrant safe scope around the complete per-tick advancement batch
 * (task #22). While active, the FIRST reward FUNCTION to need the global
 * command fence takes it STICKY — the fence stays held (so every subsequent
 * function, nested function and cascaded grant in the same batch runs inside
 * the already-quiescent window via {@code fenceWindowHeld()}) and is released
 * exactly once at {@link #end}. Ticks whose batch fires no function reward
 * take no quiesce at all.
 *
 * <p>Main-thread only (the batch apply point is main-owned); plain fields.
 */
public final class AdvancementBatchScope {

    private static boolean active;
    private static boolean stickyHeld;

    private AdvancementBatchScope() {}

    public static boolean active() {
        return active;
    }

    public static void begin() {
        active = true;
    }

    /** The function fence took the window inside this batch: keep it held;
     *  {@link #end} releases. */
    public static void noteStickyFence() {
        stickyHeld = true;
    }

    public static void end() {
        active = false;
        if (stickyHeld) {
            stickyHeld = false;
            try {
                IslandCommands.releaseAllExcept();
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("Fabrolia-Advancement")
                        .error("sticky fence release failed", t);
            }
        }
    }
}
