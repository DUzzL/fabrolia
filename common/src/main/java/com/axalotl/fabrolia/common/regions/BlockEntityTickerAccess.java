package com.axalotl.fabrolia.common.regions;

/** Level-local exclusion for authoritative ticker registration and index maintenance.
 * Acquire after any graph/region ownership locks; never acquire those locks while
 * holding this monitor, and never hold it while executing ticker bodies. */
public interface BlockEntityTickerAccess {
    Object fabrolia$blockEntityTickerLock();
}
