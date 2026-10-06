package com.axalotl.fabrolia.common.utils;

/**
 * Compile-time switchboard for retained, bounded production diagnostics.
 * JVM system properties are deliberately not used: operator environments did
 * not propagate them reliably, while a constant is visible in the built jar.
 */
public final class FabroliaDebug {

    private FabroliaDebug() {}

    /** Ender-pearl chunk-loading forensics. Always ON: every emitter is
     *  anomaly-filtered (fires only when a LIVE pearl is affected) and
     *  rate-limited — see {@link PearlTrace}. */
    public static final boolean PEARL_TRACE = true;

}
