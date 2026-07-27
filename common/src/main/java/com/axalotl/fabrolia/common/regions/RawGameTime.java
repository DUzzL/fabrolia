package com.axalotl.fabrolia.common.regions;

/** A8: duck interface on PrimaryLevelData — the REAL game-time counter
 *  regardless of execution-context redirection (clock-domain rebase math
 *  needs both clocks at once). All dimensions share the primary counter. */
public interface RawGameTime {
    long fabrolia$rawGameTime();
}
