package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionalPlayerEntityTicksV2;
import com.axalotl.fabrolia.common.regions.FailStops;

/**
 * Exact physical-Main scope around routed advancement work. Fire-time
 * evaluation and awards run on the player's owner; only work routed to the
 * fenced Main lane reaches this scope. The scope acquires the existing global
 * command fence lazily, before the first routed item that needs it, and keeps
 * that exclusion through the item's rewards and cascades.
 *
 * <p>The drain point is main-owned. The exact owner thread is retained so an
 * unrelated mod thread cannot borrow the clean-Main fence capability or the
 * sticky receipt while Main is applying routed work.</p>
 */
public final class AdvancementBatchScope {

    private static volatile Thread owner;
    private static boolean stickyHeld;
    /**
     * Exact-owner depth of a serial vanilla award/direct-replay body.  The
     * outer batch scope also spans fence acquisition, whose chunk callbacks
     * must not borrow the monitor-free reward-body capability merely because
     * they re-enter on the same thread.
     */
    private static int rewardBodyDepth;

    /** Preallocated so detecting a leaked reward phase cannot itself fail with
     * OOME before the sticky global fence has entered mandatory cleanup. */
    private static final IllegalStateException LEAKED_REWARD_BODY =
            new IllegalStateException(
                    "advancement batch ended with an active reward body");

    private AdvancementBatchScope() {}

    public static boolean active() {
        return owner == Thread.currentThread();
    }

    /**
     * Cold lifecycle probe used only after every execution venue has joined.
     * Unlike {@link #active()}, this checks the global owner rather than asking
     * whether the calling thread happens to own the scope.
     */
    static boolean shutdownClean() {
        return owner == null && !stickyHeld && rewardBodyDepth == 0;
    }

    /** True only while the exact batch owner is inside a serial vanilla
     * award/direct-replay body which may synchronously dispatch rewards.
     * The entity-batch barrier and trigger / PlayerAdvancements monitors have
     * been released before that body begins, so its first function may wait
     * for the exact unbounded global fence. */
    public static boolean rewardFunctionActive() {
        return owner == Thread.currentThread() && rewardBodyDepth > 0;
    }

    public static void begin() {
        var server = RegionScheduler.getServer();
        if (server != null
                && !RegionalPlayerEntityTicksV2.isPhysicalServerThread(server)) {
            throw new IllegalStateException(
                    "advancement batch entered off physical Main");
        }
        Thread current = Thread.currentThread();
        if (owner != null) {
            throw new IllegalStateException("advancement batch scope already active");
        }
        stickyHeld = false;
        rewardBodyDepth = 0;
        owner = current;
    }

    /** Must run before applying routed work that needs the fence. The scope's
     * mandatory end() owns cleanup immediately after acquisition succeeds. */
    static void ensureEvaluationFence(AdvancementDiag.Site site) {
        if (!IslandCommands.requiresMainFence()) return;
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "advancement evaluation entered outside its exact Main batch");
        }
        if (IslandCommands.fenceWindowHeld()) return;
        long start = System.nanoTime();
        boolean took = IslandCommands.quiesceAllExcept(null);
        if (took) {
            stickyHeld = true;
            AdvancementDiag.quiesced(site, System.nanoTime() - start);
        } else if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
            IslandCommands.publishAndPropagate(new IllegalStateException(
                    "advancement batch could not establish its exact Main fence"));
        }
    }

    /** Entered immediately before one serial award/direct replay. */
    static void enterRewardBody() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "advancement reward body entered outside its exact batch owner");
        }
        if (rewardBodyDepth == Integer.MAX_VALUE) {
            throw new IllegalStateException("advancement reward body depth overflow");
        }
        rewardBodyDepth++;
    }

    /** Mandatory cleanup for {@link #enterRewardBody()}. */
    static void exitRewardBody() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "advancement reward body ended outside its exact batch owner");
        }
        if (rewardBodyDepth <= 0) {
            throw new IllegalStateException(
                    "advancement reward body scope is not active");
        }
        rewardBodyDepth--;
    }

    /** The function fence took the window inside this batch: keep it held;
     *  {@link #end} releases. */
    public static void noteStickyFence() {
        if (!rewardFunctionActive()) {
            throw new IllegalStateException(
                    "advancement batch sticky fence armed outside an exact reward body");
        }
        if (stickyHeld) {
            throw new IllegalStateException(
                    "advancement batch sticky fence already armed");
        }
        stickyHeld = true;
    }

    public static void end() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(
                    "advancement batch ended by a non-owner");
        }
        Throwable failure = rewardBodyDepth == 0 ? null : LEAKED_REWARD_BODY;
        boolean release = stickyHeld;
        stickyHeld = false;
        rewardBodyDepth = 0;
        owner = null;
        if (release) {
            // This is resource cleanup, not containment.  If release itself
            // fails the physical Main tick must observe that failure; callers
            // merge it behind an already-active primary when necessary.
            failure = IslandCommands.releaseAllExcept(failure);
        }
        if (failure != null) {
            FailStops.<RuntimeException>rethrow(failure);
        }
    }
}
