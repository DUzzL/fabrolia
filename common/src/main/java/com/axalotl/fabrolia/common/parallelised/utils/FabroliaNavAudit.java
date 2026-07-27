package com.axalotl.fabrolia.common.parallelised.utils;

/** Duck on {@code ServerLevel} (lithium-compat path): navigation-listener leak
 *  audit + sweep (task #24 watchdog wedges). Absent when lithium is not
 *  installed — callers instanceof-check. */
public interface FabroliaNavAudit {
    String fabrolia$navStats();
    void fabrolia$sweepNavLeaks();
}
