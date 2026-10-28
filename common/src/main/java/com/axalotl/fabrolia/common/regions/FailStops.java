package com.axalotl.fabrolia.common.regions;

/**
 * Shared Throwable plumbing for the fail-stop paths (AGENTS.md §9).
 *
 * <p>These helpers never decide which failure wins a generation: that stays
 * {@link RegionScheduler#publishFatalAndSelectWinner}. They only keep a local
 * primary failure, attach cleanup failures to it as diagnostics, and rethrow a
 * selected Throwable unchanged.
 */
public final class FailStops {

    /** Upper bound of suppressed failures {@link #mergeFailureCapped} attaches. */
    private static final int SUPPRESSED_CAP = 8;

    private FailStops() {}

    /**
     * First non-null failure wins. A distinct later failure is attached to it as
     * suppressed. Suppression is diagnostic only: a failure while attaching (for
     * example an allocation failure under low memory) is ignored so it can never
     * replace the selected primary or skip the caller's remaining cleanup.
     */
    public static Throwable mergeFailure(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                primary.addSuppressed(secondary);
            } catch (Throwable ignored) {
                // Diagnostic only; the primary stays the selected failure.
            }
        }
        return primary;
    }

    /**
     * {@link #mergeFailure} that attaches nothing once the primary already carries
     * {@value #SUPPRESSED_CAP} suppressed failures, for loops whose cleanup may
     * fail once per element.
     */
    public static Throwable mergeFailureCapped(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (secondary != null && secondary != primary) {
            try {
                if (primary.getSuppressed().length < SUPPRESSED_CAP) {
                    primary.addSuppressed(secondary);
                }
            } catch (Throwable ignored) {
                // Diagnostic only; the primary stays the selected failure.
            }
        }
        return primary;
    }

    /** {@link #mergeFailure}, then publish the result and return the generation winner. */
    public static Throwable publishMergedFailure(Throwable primary, Throwable secondary) {
        return RegionScheduler.publishFatalAndSelectWinner(mergeFailure(primary, secondary));
    }

    /** Rethrow {@code failure} unchanged, checked or not, without wrapping it. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * {@link #rethrow} for expression positions: declared to return {@code R} so a
     * caller can write {@code return FailStops.<R, RuntimeException>rethrowValue(t)}.
     * It never returns.
     */
    @SuppressWarnings("unchecked")
    public static <R, T extends Throwable> R rethrowValue(Throwable failure) throws T {
        throw (T) failure;
    }
}
