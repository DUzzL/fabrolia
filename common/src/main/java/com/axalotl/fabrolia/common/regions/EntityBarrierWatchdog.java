package com.axalotl.fabrolia.common.regions;

/**
 * Deterministic state machine for one island entity-barrier wait.
 *
 * <p>The only progress version is the barrier's own monotonic pending count.
 * Work completed for another island, an empty poll, and a no-op helper never
 * renew this deadline.  An exact ready receipt or member claim is instead an
 * in-flight ownership proof: it prevents a leak verdict without pretending
 * progress occurred.  One accepted recovery receipt gets one fresh window for
 * the unchanged pending version; a later unchanged, unwitnessed expiry is
 * terminal.
 */
final class EntityBarrierWatchdog {
    static final long DEADLINE_NANOS = 3_000_000_000L;
    static final int UNWITNESSED_POLLS_BEFORE_HEAL = 4;

    enum Action {
        WAIT,
        HEAL,
        FAIL
    }

    private int observedPending;
    private long windowStartedNanos;
    private int consecutiveUnwitnessedPolls;
    private boolean healAttemptedForVersion;

    EntityBarrierWatchdog(int pending, long nowNanos) {
        if (pending < 0) throw new IllegalArgumentException("negative entity pending");
        observedPending = pending;
        windowStartedNanos = nowNanos;
    }

    Action observe(int pending, long nowNanos, boolean exactWitness) {
        observeOwnProgress(pending, nowNanos);
        if (exactWitness) {
            consecutiveUnwitnessedPolls = 0;
            return Action.WAIT;
        }
        if (healAttemptedForVersion) {
            return nowNanos - windowStartedNanos > DEADLINE_NANOS
                    ? Action.FAIL : Action.WAIT;
        }
        return ++consecutiveUnwitnessedPolls
                >= UNWITNESSED_POLLS_BEFORE_HEAL
                ? Action.HEAL : Action.WAIT;
    }

    /**
     * Complete the HEAL action.  Only a recovery which actually published at
     * least one exact receipt consumes this version's one heal and renews its
     * window.  A no-op heal at an already-expired, unwitnessed version proves
     * no recovery exists and therefore fails immediately.
     */
    Action afterHeal(int pending, int acceptedRecoveries, long nowNanos,
                     boolean exactWitness) {
        boolean progressed = observeOwnProgress(pending, nowNanos);
        if (progressed) return Action.WAIT;
        healAttemptedForVersion = true;
        consecutiveUnwitnessedPolls = 0;
        if (acceptedRecoveries > 0) {
            windowStartedNanos = nowNanos;
            return Action.WAIT;
        }
        if (exactWitness || nowNanos - windowStartedNanos <= DEADLINE_NANOS) {
            return Action.WAIT;
        }
        return Action.FAIL;
    }

    int observedPending() {
        return observedPending;
    }

    long windowStartedNanos() {
        return windowStartedNanos;
    }

    private boolean observeOwnProgress(int pending, long nowNanos) {
        if (pending < 0) {
            throw new IllegalStateException("negative entity barrier pending=" + pending);
        }
        if (pending > observedPending) {
            throw new IllegalStateException(
                    "entity barrier pending increased inside one dispatch: old="
                            + observedPending + " new=" + pending);
        }
        if (pending == observedPending) return false;
        observedPending = pending;
        windowStartedNanos = nowNanos;
        consecutiveUnwitnessedPolls = 0;
        healAttemptedForVersion = false;
        return true;
    }
}
