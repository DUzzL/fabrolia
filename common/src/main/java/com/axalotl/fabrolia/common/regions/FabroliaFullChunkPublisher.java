package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.Packet;

/** Physical-Main publication surface for one immutable full-chunk batch. */
public interface FabroliaFullChunkPublisher {

    /**
     * Offers exactly one receipt-gated task to the ordered connection event
     * loop. The publication owns Start, every immutable full snapshot, and
     * Finished; it admits none of those bodies until this method has returned
     * successfully and the caller has committed its receipt.
     */
    void fabrolia$enqueueFullChunkBatch(FullChunkBatchPublication publication);

    /** The only capability a committed publication receives on the EventLoop. */
    @FunctionalInterface
    interface PacketSender {
        void send(Packet<?> packet);
    }

    /**
     * Receipt and terminal callback implemented by the delivery transaction.
     * Implementations must run this exact object once; it decides whether the
     * queued body was committed or cancelled after an offer-then-throw.
     */
    @FunctionalInterface
    interface FullChunkBatchPublication {
        void runPublished(PacketSender sender);
    }

    @FunctionalInterface
    interface TaskOffer {
        void offer(Runnable task);
    }

    /**
     * Small receipt gate for other lifecycle-ordered transport tasks such as
     * Forget. A concurrently scheduled task can wait only for the queue offer
     * call itself; Main never waits for the EventLoop. If the offer throws
     * after accepting the Runnable, the accepted Runnable observes CANCELLED,
     * rethrows the globally selected winner, and cannot enter its send body.
     */
    final class PublicationReceipt {
        private static final int NEW = 0;
        private static final int OFFERING = 1;
        private static final int COMMITTED = 2;
        private static final int CANCELLED = 3;
        private static final int RUNNING = 4;
        private static final int TERMINAL = 5;

        private int state = NEW;
        private Throwable cancellation;
        private Thread publisherThread;
        private boolean inlineAttempt;

        public void publish(TaskOffer offer, Runnable body) {
            Throwable failure = null;
            synchronized (this) {
                if (state != NEW) {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            new IllegalStateException(
                                    "transport receipt was published more than once"));
                    state = CANCELLED;
                    cancellation = failure;
                } else {
                    publisherThread = Thread.currentThread();
                    state = OFFERING;
                    try {
                        RegionScheduler.throwWorkerFailureIfAny();
                        offer.offer(() -> runPublished(body));
                        RegionScheduler.commitSuccessIfHealthy(() -> {
                            if (state != OFFERING) {
                                throw new IllegalStateException(
                                        "transport receipt changed during publication: "
                                                + state);
                            }
                            state = COMMITTED;
                        });
                    } catch (Throwable offerFailure) {
                        failure = RegionScheduler.publishFatalAndSelectWinner(offerFailure);
                        state = CANCELLED;
                        cancellation = failure;
                    }
                }
            }
            if (failure != null) {
                PublicationReceipt.<RuntimeException>rethrow(failure);
            }
            if (inlineAttempt) runPublished(body);
        }

        private void runPublished(Runnable body) {
            Throwable failure = null;
            synchronized (this) {
                if (state == OFFERING && Thread.currentThread() == publisherThread) {
                    // A defensive direct executor called the offered task before
                    // offer() returned. Defer the unique body until COMMITTED and
                    // until both the receipt and scheduler locks have been released.
                    inlineAttempt = true;
                    return;
                }
                if (state == COMMITTED) {
                    state = RUNNING;
                } else if (state == CANCELLED) {
                    failure = cancellation;
                    state = TERMINAL;
                } else {
                    failure = RegionScheduler.publishFatalAndSelectWinner(
                            new IllegalStateException(
                                    "transport task entered before its publication receipt: "
                                            + state));
                    state = TERMINAL;
                }
            }
            if (failure != null) {
                PublicationReceipt.<RuntimeException>rethrow(failure);
            }
            try {
                RegionScheduler.runSuccessBodyIfHealthy(body);
            } catch (Throwable bodyFailure) {
                failure = RegionScheduler.publishFatalAndSelectWinner(bodyFailure);
            }
            synchronized (this) {
                state = TERMINAL;
            }
            if (failure != null) {
                PublicationReceipt.<RuntimeException>rethrow(failure);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void rethrow(Throwable failure) throws T {
            throw (T) failure;
        }
    }
}
