package com.axalotl.fabrolia.common.regions;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Admission policy for queued island-unit work.
 *
 * <p>A deadline drive submits work for one exact logical-tick frontier. The
 * task may wait behind the unit that made the deadline look due; if that unit
 * advances the frontier before the queued task acquires the claim, the task is
 * stale and must be discarded. Fresh self-chains (local sprint/coherence) are
 * captured after completion and therefore still match.
 */
public final class IslandUnitAdmission {

    private static final long ENTITY_UNLOAD_STARVATION_NANOS = 1_000_000_000L;
    private static final long ENTITY_UNLOAD_RETRY_COOLDOWN_NANOS = 1_000_000_000L;
    private static final int ENTITY_UNLOAD_FORCED_PER_PASS = 8;

    /**
     * The exact {@code processChunkUnload} call currently invoking
     * {@code storeChunkSections}.  A ThreadLocal makes the starvation lease
     * incapable of authorising an autosave, another dimension's manager, or
     * even another chunk reached by a nested call.
     */
    private static final ThreadLocal<EntityUnloadAttempt> ENTITY_UNLOAD_ATTEMPT =
            new ThreadLocal<>();
    /** Classification of vanilla's full-save retry loop, never ownership authority. */
    private static final ThreadLocal<Object> ENTITY_FULL_SAVE = new ThreadLocal<>();

    private IslandUnitAdmission() {}

    static boolean isFresh(long submittedAtLogicalTick, long currentLogicalTick) {
        return submittedAtLogicalTick == currentLogicalTick;
    }

    static boolean isDeadlineDue(long deadlineNanos, long nowNanos, long slackNanos) {
        return deadlineNanos == 0 || deadlineNanos <= nowNanos + slackNanos;
    }

    static boolean isSameDeadline(long submittedDeadlineNanos, long currentDeadlineNanos) {
        return submittedDeadlineNanos == currentDeadlineNanos;
    }

    /**
     * Opportunistic main-side saves must yield to an already queued unit.
     * Full save/topology fences use a different, blocking protocol and do not
     * consult this policy.
     */
    public static boolean opportunisticSaveMayClaim(boolean unitQueued) {
        return !unitQueued;
    }

    /**
     * Start the exact unload attempt which may carry one bounded starvation
     * lease.  The returned token must be closed in a {@code finally} around
     * the one vanilla {@code processChunkUnload} body invocation.
     */
    public static EntityUnloadAttempt beginEntityUnloadAttempt(
            Object manager, long chunkPos, boolean forceQueuedClaim) {
        EntityUnloadAttempt attempt = new EntityUnloadAttempt(
                manager, chunkPos, forceQueuedClaim, ENTITY_UNLOAD_ATTEMPT.get());
        ENTITY_UNLOAD_ATTEMPT.set(attempt);
        return attempt;
    }

    /**
     * Entity unload normally obeys the same queue-to-claim priority as every
     * opportunistic save.  Only the exact aged retry selected by the outer
     * unload loop may contend with a queued unit.  It still has to win
     * {@code unitClaim}; this method never grants ownership by itself.
     */
    public static boolean entityUnloadSaveMayClaim(
            Object manager, long chunkPos, boolean unitQueued) {
        if (!unitQueued) return true;
        EntityUnloadAttempt attempt = ENTITY_UNLOAD_ATTEMPT.get();
        return attempt != null
                && attempt.manager == manager
                && attempt.chunkPos == chunkPos
                && attempt.forceQueuedClaim;
    }

    static boolean isEntityUnloadAttempt(Object manager, long chunkPos) {
        EntityUnloadAttempt attempt = ENTITY_UNLOAD_ATTEMPT.get();
        return attempt != null && attempt.manager == manager && attempt.chunkPos == chunkPos;
    }

    public static void runFullEntitySave(Object manager, Runnable body) {
        Object previous = ENTITY_FULL_SAVE.get();
        ENTITY_FULL_SAVE.set(manager);
        try {
            body.run();
        } finally {
            if (previous == null) ENTITY_FULL_SAVE.remove();
            else ENTITY_FULL_SAVE.set(previous);
        }
    }

    static boolean isFullEntitySave(Object manager) {
        return ENTITY_FULL_SAVE.get() == manager;
    }

    /** Record that the exact unload returned false before its vanilla body
     * because the island claim/queue was unavailable. */
    public static void noteEntityUnloadOwnershipYield(Object manager, long chunkPos) {
        EntityUnloadAttempt attempt = ENTITY_UNLOAD_ATTEMPT.get();
        if (attempt != null
                && attempt.manager == manager
                && attempt.chunkPos == chunkPos) {
            attempt.ownershipYielded = true;
        }
    }

    public static boolean entityUnloadOwnershipYielded(EntityUnloadAttempt attempt) {
        return attempt.ownershipYielded;
    }

    /** No-throw mandatory cleanup: restoring the prior token cannot replace
     * a Throwable from the vanilla unload body. */
    public static void endEntityUnloadAttempt(EntityUnloadAttempt attempt) {
        if (attempt.previous == null) {
            ENTITY_UNLOAD_ATTEMPT.remove();
        } else {
            ENTITY_UNLOAD_ATTEMPT.set(attempt.previous);
        }
    }

    public static final class EntityUnloadAttempt {
        private final Object manager;
        private final long chunkPos;
        private final boolean forceQueuedClaim;
        private final EntityUnloadAttempt previous;
        private boolean ownershipYielded;

        private EntityUnloadAttempt(Object manager, long chunkPos,
                                    boolean forceQueuedClaim,
                                    EntityUnloadAttempt previous) {
            this.manager = manager;
            this.chunkPos = chunkPos;
            this.forceQueuedClaim = forceQueuedClaim;
            this.previous = previous;
        }
    }

    /**
     * Per-manager bounded fairness for C2ME's deferred entity-unload retries.
     * Only ownership-caused false results age.  A forced attempt is cooled
     * down even when it loses the claim, so the first permanently busy chunks
     * cannot consume every pass's quota and starve the rest of the set.
     */
    public static final class EntityUnloadRetryFairness {
        private final Long2LongOpenHashMap eligibleAtNanos =
                new Long2LongOpenHashMap();
        private final long starvationNanos;
        private final long retryCooldownNanos;
        private final int forcedPerPass;
        private int forcedThisPass;

        public EntityUnloadRetryFairness() {
            this(ENTITY_UNLOAD_STARVATION_NANOS,
                    ENTITY_UNLOAD_RETRY_COOLDOWN_NANOS,
                    ENTITY_UNLOAD_FORCED_PER_PASS);
        }

        EntityUnloadRetryFairness(long starvationNanos,
                                  long retryCooldownNanos,
                                  int forcedPerPass) {
            if (starvationNanos < 0 || retryCooldownNanos < 0 || forcedPerPass <= 0) {
                throw new IllegalArgumentException("invalid entity-unload fairness policy");
            }
            this.starvationNanos = starvationNanos;
            this.retryCooldownNanos = retryCooldownNanos;
            this.forcedPerPass = forcedPerPass;
        }

        public synchronized void beginPass() {
            forcedThisPass = 0;
        }

        /** Reserve, but do not itself acquire, one queued-claim contention. */
        public synchronized boolean tryGrant(long chunkPos, long nowNanos) {
            if (forcedThisPass >= forcedPerPass
                    || !eligibleAtNanos.containsKey(chunkPos)) {
                return false;
            }
            long eligibleAt = eligibleAtNanos.get(chunkPos);
            if (nowNanos - eligibleAt < 0) {
                return false;
            }
            forcedThisPass++;
            eligibleAtNanos.put(chunkPos, nowNanos + retryCooldownNanos);
            return true;
        }

        /**
         * Keep/age only an ownership yield.  Every path that reached the
         * vanilla body (true or false) clears the starvation state because it
         * was not denied by the queued island.
         */
        public synchronized void recordResult(
                long chunkPos, boolean ownershipYielded, long nowNanos) {
            if (!ownershipYielded) {
                eligibleAtNanos.remove(chunkPos);
                return;
            }
            if (!eligibleAtNanos.containsKey(chunkPos)) {
                eligibleAtNanos.put(chunkPos, nowNanos + starvationNanos);
            }
        }

        public synchronized void forget(long chunkPos) {
            eligibleAtNanos.remove(chunkPos);
        }

        synchronized int trackedCount() {
            return eligibleAtNanos.size();
        }
    }
}
