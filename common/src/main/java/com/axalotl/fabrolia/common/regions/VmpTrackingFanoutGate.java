package com.axalotl.fabrolia.common.regions;

import java.util.concurrent.CompletableFuture;

public final class VmpTrackingFanoutGate {
    private volatile Throwable failure;

    public void runNext(Runnable body) {
        if (body == null) {
            throw new NullPointerException("body");
        }

        Throwable observed = this.failureOrNull();
        if (observed != null) {
            FailStops.rethrow(observed);
        }

        try {
            RegionScheduler.runSuccessBodyIfHealthy(body);
        } catch (Throwable fatal) {
            FailStops.rethrow(this.publishFailure(fatal));
        }
    }

    public Throwable failureOrNull() {
        return this.failure;
    }

    public synchronized void completeTerminal(CompletableFuture<Void> done) {
        if (this.failure == null) {
            try {
                RegionScheduler.commitSuccessIfHealthy(() -> {
                    if (!done.complete(null)) {
                        throw new IllegalStateException("VMP fan-out completed more than once");
                    }
                });
            } catch (Throwable fatal) {
                this.failure = RegionScheduler.publishFatalAndSelectWinner(fatal);
            }
        }

        if (this.failure != null) {
            done.completeExceptionally(this.failure);
        }
    }

    public synchronized Throwable publishFailure(Throwable candidate) {
        if (candidate == null) {
            candidate = new NullPointerException("VMP fan-out failure");
        }

        Throwable selected = this.failure;
        if (selected == null) {
            selected = candidate;
        } else if (selected != candidate) {
            mergeFailure(selected, candidate);
        }

        selected = RegionScheduler.publishFatalAndSelectWinner(selected);
        this.failure = selected;
        return selected;
    }

    private static void mergeFailure(Throwable primary, Throwable secondary) {
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // Suppression is diagnostic only; the primary stays the failure.
        }
    }
}
