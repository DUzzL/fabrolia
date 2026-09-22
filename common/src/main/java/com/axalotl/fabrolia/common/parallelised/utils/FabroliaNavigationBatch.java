package com.axalotl.fabrolia.common.parallelised.utils;

/** Optional level capability supplied by the Lithium navigation implementation. */
public interface FabroliaNavigationBatch {
    void fabrolia$beginNavigationBatch();

    /** Balances only an entered batch, including on failure; never recomputes paths. */
    void fabrolia$endNavigationBatch();

    /** Success-only tail: dispatches ready notifications through their navigation owners. */
    void fabrolia$drainNavigationBatch();
}
