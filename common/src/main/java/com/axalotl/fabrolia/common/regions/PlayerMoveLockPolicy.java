package com.axalotl.fabrolia.common.regions;

/** Pure admission rule for ChunkMap's player-position transaction. */
public final class PlayerMoveLockPolicy {

    private PlayerMoveLockPolicy() {}

    /**
     * The vanilla move body mutates player/ticket membership only when the section or
     * ignored state changes. Ordinary same-section heartbeats may retain the component-
     * local tracking work without entering ChunkMap's global monitor.
     */
    public static boolean requiresGlobalTransaction(
            long previousSection, long currentSection,
            boolean previouslyIgnored, boolean shouldIgnore) {
        return previousSection != currentSection || previouslyIgnored != shouldIgnore;
    }
}
