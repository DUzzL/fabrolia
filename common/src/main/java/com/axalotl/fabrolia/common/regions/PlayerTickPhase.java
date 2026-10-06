package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Identity-based, single-server-tick admission ledger used by regional player ticks v2.
 *
 * <p>This class deliberately has no Minecraft dependencies so the transition rules can be
 * exercised deterministically. A phase snapshots its gate at construction. An object can be
 * reserved once, a started body can never fall back/retry, and sealing waits for every started
 * body without a timeout that could let the next vanilla phase overlap it.
 */
final class PlayerTickPhase<P, S> {

    enum State { RESERVED, RUNNING, MAIN_FALLBACK, DONE, FAILED }
    enum Admission { WORKER, MAIN_FALLBACK, REJECTED }

    static final class Claim<P, S> {
        final P object;
        final S session;
        State state = State.RESERVED;
        boolean vanillaConsumerEntered;
        Thread executor;
        CompletionReceipt<P, S> completionReceipt;

        Claim(P object, S session) {
            this.object = object;
            this.session = session;
        }
    }

    /**
     * Offered terminal receipt for one RUNNING claim.  The scheduler cut may only
     * perform {@link #acceptSuccess()}, a single non-blocking volatile write.  The
     * owning ledger consumes the terminal and wakes its barrier later, after the
     * lifecycle monitor has been released and owner-local retirement is complete.
     */
    static final class CompletionReceipt<P, S> {
        private static final int OFFERING = 0;
        private static final int ACCEPTED = 1;

        final PlayerTickPhase<P, S> owner;
        final Claim<P, S> claim;
        private volatile int successReceipt = OFFERING;
        private boolean consumed;
        private boolean signalled;
        private Throwable provisionalFailure;

        private CompletionReceipt(PlayerTickPhase<P, S> owner, Claim<P, S> claim) {
            this.owner = owner;
            this.claim = claim;
        }

        /** Fixed receipt write: the only operation legal in the scheduler cut. */
        void acceptSuccess() {
            successReceipt = ACCEPTED;
        }

        private boolean successAccepted() {
            return successReceipt == ACCEPTED;
        }
    }

    private final boolean enabledSnapshot;
    private final IdentityHashMap<P, Claim<P, S>> claims = new IdentityHashMap<>();
    private boolean sealed;
    private int running;
    private Throwable firstFailure;

    PlayerTickPhase(boolean enabledSnapshot) {
        this.enabledSnapshot = enabledSnapshot;
    }

    boolean enabledSnapshot() {
        return enabledSnapshot;
    }

    synchronized Claim<P, S> reserve(P object, S session) {
        Claim<P, S> claim = new Claim<>(object, session);
        return register(claim) ? claim : null;
    }

    /**
     * Register a claim allocated before the entity-local lease CAS.  V2 uses the Claim
     * identity itself as the opaque lease token, so no allocation is needed after the
     * entity becomes RESERVED.
     */
    synchronized boolean register(Claim<P, S> claim) {
        if (claim == null) return false;
        if (sealed) {
            failLocked(new IllegalStateException("player tick offered after phase seal"));
            return false;
        }
        if (claims.containsKey(claim.object)) {
            failLocked(new IllegalStateException("same player object offered twice in one server tick"));
            return false;
        }
        claims.put(claim.object, claim);
        return true;
    }

    synchronized Admission startWorker(Claim<P, S> claim, P observedObject,
                                       S observedSession, boolean eligible) {
        if (claim == null) return Admission.REJECTED;
        if (sealed) {
            rejectLocked(claim, "player worker start after phase seal");
            return Admission.REJECTED;
        }
        if (claim.state != State.RESERVED) {
            rejectLocked(claim, "invalid player tick start from " + claim.state);
            return Admission.REJECTED;
        }
        if (claim.object != observedObject || claim.session != observedSession) {
            rejectLocked(claim, "player object/session identity changed before worker start");
            return Admission.REJECTED;
        }
        if (!eligible) {
            rejectLocked(claim, "player eligibility changed after v2 reservation");
            return Admission.REJECTED;
        }
        claim.state = State.RUNNING;
        running++;
        return Admission.WORKER;
    }

    synchronized boolean startMainFallback(Claim<P, S> claim, P observedObject,
                                           S observedSession, boolean stillCurrent) {
        if (claim == null) return false;
        if (claim.state != State.MAIN_FALLBACK) {
            rejectLocked(claim, "invalid main fallback start from " + claim.state);
            return false;
        }
        if (claim.object != observedObject || claim.session != observedSession || !stillCurrent) {
            rejectLocked(claim, "player object/session changed before main fallback");
            return false;
        }
        claim.state = State.RUNNING;
        running++;
        return true;
    }

    synchronized boolean cancelUnsubmittedWorkerToMain(Claim<P, S> claim,
                                                        Runnable publishFallback) {
        if (claim == null || claim.state != State.RUNNING || claim.vanillaConsumerEntered) {
            rejectLocked(claim, "invalid unsubmitted worker cancellation");
            return false;
        }
        try {
            // Publish while RUNNING is still counted. The barrier cannot observe
            // running==0 and an empty fallback queue in between these two acts.
            publishFallback.run();
        } catch (Throwable publicationFailure) {
            claim.state = State.FAILED;
            running--;
            failLocked(publicationFailure);
            notifyAll();
            return false;
        }
        claim.state = State.MAIN_FALLBACK;
        running--;
        notifyAll();
        return true;
    }

    /** Fail any claim whose vanilla body has definitely not entered yet. */
    synchronized void failBeforeBody(Claim<P, S> claim, Throwable failure) {
        if (claim == null) {
            failLocked(failure);
            return;
        }
        if (claim.state == State.RESERVED || claim.state == State.MAIN_FALLBACK) {
            claim.state = State.FAILED;
            failLocked(failure);
            notifyAll();
            return;
        }
        if (claim.state == State.RUNNING && !claim.vanillaConsumerEntered) {
            claim.state = State.FAILED;
            running--;
            failLocked(failure);
            notifyAll();
            return;
        }
        failLocked(failure);
    }

    synchronized void markVanillaConsumerEntered(Claim<P, S> claim) {
        if (claim == null || claim.state != State.RUNNING) {
            IllegalStateException failure = new IllegalStateException(
                    "vanilla entity consumer entered without a RUNNING identity claim");
            failLocked(failure);
            throw failure;
        }
        if (claim.vanillaConsumerEntered) {
            IllegalStateException failure = new IllegalStateException(
                    "vanilla entity consumer entered twice for one identity claim");
            failLocked(failure);
            throw failure;
        }
        if (claim.executor == null) claim.executor = Thread.currentThread();
        else if (claim.executor != Thread.currentThread()) {
            IllegalStateException failure = new IllegalStateException(
                    "vanilla entity consumer entered on a thread other than its bound executor");
            failLocked(failure);
            throw failure;
        }
        claim.vanillaConsumerEntered = true;
    }

    synchronized boolean bindExecutor(Claim<P, S> claim) {
        if (claim == null || claim.state != State.RUNNING || claim.executor != null) {
            rejectLocked(claim, "invalid player executor bind");
            return false;
        }
        claim.executor = Thread.currentThread();
        return true;
    }

    synchronized boolean isExecutor(Claim<P, S> claim) {
        return claim != null && claim.state == State.RUNNING
                && claim.executor == Thread.currentThread();
    }

    synchronized void complete(Claim<P, S> claim, Throwable failure) {
        if (claim == null) {
            IllegalStateException completionFailure =
                    new IllegalStateException("player tick completed without a claim");
            failLocked(completionFailure);
            throw completionFailure;
        }
        if (claim.state != State.RUNNING) {
            IllegalStateException completionFailure =
                    new IllegalStateException("player tick completed from " + claim.state);
            failLocked(completionFailure);
            throw completionFailure;
        }
        if (claim.executor != Thread.currentThread()) {
            try {
                IllegalStateException executorFailure = new IllegalStateException(
                        "player tick completed by a thread other than its bound executor");
                if (failure == null) failure = executorFailure;
                else if (failure != executorFailure) {
                    try {
                        failure.addSuppressed(executorFailure);
                    } catch (Throwable ignored) {
                        // Diagnostic suppression must not strand the RUNNING counter.
                    }
                }
            } catch (Throwable diagnosticFailure) {
                // Even OOME while constructing diagnostics is a terminal failure; the
                // sole RUNNING decrement below must still happen.
                if (failure == null) failure = diagnosticFailure;
            }
        }
        if (failure == null && !claim.vanillaConsumerEntered) {
            try {
                failure = new IllegalStateException(
                        "guarded entity consumer returned without entering tickNonPassenger");
            } catch (Throwable diagnosticFailure) {
                failure = diagnosticFailure;
            }
        }
        claim.state = failure == null ? State.DONE : State.FAILED;
        if (failure != null) failLocked(failure);
        running--;
        notifyAll();
        if (failure != null) PlayerTickPhase.<RuntimeException>rethrow(failure);
    }

    /**
     * Reserve the sole completion authority while the claim is still RUNNING.
     * Callers which also own an outer state ledger must acquire that ledger first.
     */
    synchronized CompletionReceipt<P, S> offerCompletion(Claim<P, S> claim) {
        if (claim == null) {
            throw new IllegalStateException("player tick offered completion without a claim");
        }
        if (claim.state != State.RUNNING) {
            throw new IllegalStateException(
                    "player tick offered completion from " + claim.state);
        }
        if (claim.completionReceipt != null) {
            throw new IllegalStateException("player tick completion was offered twice");
        }
        CompletionReceipt<P, S> receipt = new CompletionReceipt<>(this, claim);
        claim.completionReceipt = receipt;
        return receipt;
    }

    /**
     * Consume an accepted success without decrementing {@link #running} or waking
     * a waiter.  A locally-discovered invariant is returned to the caller so it can
     * first win/select the generation-wide W and then poison this receipt.
     */
    synchronized Throwable consumeAcceptedCompletion(CompletionReceipt<P, S> receipt) {
        Claim<P, S> claim = validateOpenReceiptLocked(receipt);
        if (!receipt.successAccepted()) {
            throw new IllegalStateException(
                    "player tick consumed an unaccepted success receipt");
        }
        Throwable failure = completionInvariantFailureLocked(claim, null);
        claim.state = failure == null ? State.DONE : State.FAILED;
        receipt.provisionalFailure = failure;
        receipt.consumed = true;
        return failure;
    }

    /**
     * Consume or rewrite an unpublished terminal as FAILED with the caller's
     * already-selected exact W.  The barrier remains closed until signalCompletion.
     */
    synchronized Throwable poisonCompletion(CompletionReceipt<P, S> receipt,
                                             Throwable selectedFailure) {
        if (selectedFailure == null) {
            throw new NullPointerException("selectedFailure");
        }
        Claim<P, S> claim = validateReceiptOwnerLocked(receipt);
        if (receipt.signalled) {
            throw new IllegalStateException(
                    "player tick tried to poison an already-signalled completion");
        }
        if (!receipt.consumed) {
            if (claim.state != State.RUNNING) {
                throw new IllegalStateException(
                        "player tick poisoned completion from " + claim.state);
            }
            selectedFailure = completionInvariantFailureLocked(claim, selectedFailure);
            receipt.consumed = true;
        } else {
            selectedFailure = mergeFailure(selectedFailure, receipt.provisionalFailure);
        }
        claim.state = State.FAILED;
        receipt.provisionalFailure = selectedFailure;
        failLocked(selectedFailure);
        return selectedFailure;
    }

    /** Sole barrier-opening edge for a receipt consumed outside the lifecycle cut. */
    synchronized void signalCompletion(CompletionReceipt<P, S> receipt) {
        validateReceiptOwnerLocked(receipt);
        if (!receipt.consumed) {
            throw new IllegalStateException(
                    "player tick signalled an unconsumed completion");
        }
        if (receipt.signalled) {
            throw new IllegalStateException(
                    "player tick completion was signalled twice");
        }
        if (running <= 0) {
            throw new IllegalStateException(
                    "player tick completion signal found no running obligation");
        }
        receipt.signalled = true;
        running--;
        notifyAll();
    }

    synchronized void fail(Throwable failure) {
        failLocked(failure);
    }

    synchronized void seal() {
        sealed = true;
    }

    void sealAndAwait() {
        seal();
        boolean interrupted = false;
        synchronized (this) {
            while (running != 0) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized boolean awaitRunningNanos(long nanos) {
        if (running == 0) return true;
        boolean interrupted = false;
        try {
            long millis = nanos / 1_000_000L;
            int remainder = (int) (nanos % 1_000_000L);
            try {
                wait(millis, remainder);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return running == 0;
    }

    /**
     * Exceptional-path barrier shared by production and deterministic tests.
     * A failed progress invocation is retained and is never retried: it may have
     * partially entered a causal body. Running claims still own their completion,
     * so the barrier waits for them without using that failure to force-open.
     */
    static Throwable sealAndAwaitAll(Iterable<? extends PlayerTickPhase<?, ?>> phases,
                                     Runnable progress) {
        ArrayList<PlayerTickPhase<?, ?>> snapshot = new ArrayList<>();
        for (PlayerTickPhase<?, ?> phase : phases) {
            snapshot.add(phase);
            phase.seal();
        }
        Throwable failure = null;
        int suppressed = 0;
        boolean interrupted = Thread.interrupted();
        for (PlayerTickPhase<?, ?> phase : snapshot) {
            while (!phase.awaitRunningNanos(100_000L)) {
                if (Thread.interrupted()) interrupted = true;
                if (failure == null) {
                    try {
                        progress.run();
                    } catch (Throwable pumpFailure) {
                        failure = pumpFailure;
                    }
                }
            }
            Throwable phaseFailure = phase.firstFailure();
            if (failure == null) failure = phaseFailure;
            else if (phaseFailure != null && failure != phaseFailure && suppressed < 8) {
                try {
                    failure.addSuppressed(phaseFailure);
                    suppressed++;
                } catch (Throwable ignored) {
                    // Waiting is mandatory; diagnostics cannot replace first failure.
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return failure;
    }

    synchronized Throwable firstFailure() {
        return firstFailure;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    synchronized int claimCount() {
        return claims.size();
    }

    synchronized State stateOf(P object) {
        Claim<P, S> claim = claims.get(object);
        return claim == null ? null : claim.state;
    }

    synchronized boolean vanillaConsumerEntered(Claim<P, S> claim) {
        return claim != null && claim.vanillaConsumerEntered;
    }

    private Claim<P, S> validateOpenReceiptLocked(CompletionReceipt<P, S> receipt) {
        Claim<P, S> claim = validateReceiptOwnerLocked(receipt);
        if (receipt.consumed || receipt.signalled) {
            throw new IllegalStateException(
                    "player tick completion receipt was already consumed");
        }
        if (claim.state != State.RUNNING) {
            throw new IllegalStateException(
                    "player tick consumed completion from " + claim.state);
        }
        return claim;
    }

    private Claim<P, S> validateReceiptOwnerLocked(CompletionReceipt<P, S> receipt) {
        if (receipt == null || receipt.owner != this || receipt.claim == null
                || receipt.claim.completionReceipt != receipt) {
            throw new IllegalStateException(
                    "player tick completion receipt lost its claim identity");
        }
        return receipt.claim;
    }

    private Throwable completionInvariantFailureLocked(Claim<P, S> claim,
                                                       Throwable failure) {
        if (claim.executor != Thread.currentThread()) {
            try {
                failure = mergeFailure(failure, new IllegalStateException(
                        "player tick completed by a thread other than its bound executor"));
            } catch (Throwable diagnosticFailure) {
                failure = mergeFailure(failure, diagnosticFailure);
            }
        }
        if (failure == null && !claim.vanillaConsumerEntered) {
            try {
                failure = new IllegalStateException(
                        "guarded entity consumer returned without entering tickNonPassenger");
            } catch (Throwable diagnosticFailure) {
                failure = diagnosticFailure;
            }
        }
        return failure;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) return primary;
        if (primary == null) return secondary;
        if (primary != secondary) {
            try {
                if (primary.getSuppressed().length < 8) primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Diagnostics never replace the exact terminal primary.
            }
        }
        return primary;
    }

    /** Final obligation check after worker completion and published fallback replay. */
    synchronized Throwable verifyTerminal() {
        if (running != 0) {
            failLocked(new IllegalStateException(
                    "player phase terminal verification found " + running + " running claims"));
        }
        for (Claim<P, S> claim : claims.values()) {
            if (claim.state != State.DONE && claim.state != State.FAILED) {
                failLocked(new IllegalStateException(
                        "player tick obligation left non-terminal: " + claim.state));
                break;
            }
        }
        return firstFailure;
    }

    /**
     * Exceptional outer-world abort: no missing body is replayed after the original failure.
     * Pending obligations become terminal poison; RUNNING bodies remain owned by complete().
     */
    synchronized void abortUnstarted() {
        for (Claim<P, S> claim : claims.values()) {
            if (claim.state == State.RESERVED || claim.state == State.MAIN_FALLBACK) {
                claim.state = State.FAILED;
            }
        }
        notifyAll();
    }

    private void failLocked(Throwable failure) {
        if (firstFailure == null) firstFailure = failure;
    }

    private void rejectLocked(Claim<P, S> claim, String message) {
        IllegalStateException failure = new IllegalStateException(message);
        // Never rewrite RUNNING here: its real body still owns the sole decrement
        // in complete(), and changing the state would strand the barrier forever.
        if (claim != null && (claim.state == State.RESERVED
                || claim.state == State.MAIN_FALLBACK)) {
            claim.state = State.FAILED;
        }
        failLocked(failure);
    }
}
