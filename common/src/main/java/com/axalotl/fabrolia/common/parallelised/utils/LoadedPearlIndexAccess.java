package com.axalotl.fabrolia.common.parallelised.utils;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

import java.util.UUID;

/**
 * Hidden-inclusive pearl UUID index owned by one PersistentEntitySectionManager.
 * Membership begins only after addEntity succeeds and ends by exact identity in
 * its callback, so a rejected playerdata clone can never enter the index.
 */
public interface LoadedPearlIndexAccess {

    ThrownEnderpearl fabrolia$loadedPearl(UUID uuid);

    /** Visit every identity still proven resident in this manager, including
     * HIDDEN sections. Used under the all-level logout fence to recover the
     * published-before-first-tick owner-registration gap. */
    void fabrolia$forEachResidentPearl(
            java.util.function.Consumer<ThrownEnderpearl> action);

    /** Hidden-inclusive conservative fallback for the bounded zero-age ticket
     * guard. An indexed nonremoved pearl counts even while its callback is
     * moving; stale positives are capped by the hold budget. */
    boolean fabrolia$hasLoadedPearlInChunk(long packedChunkPos);

    void fabrolia$indexLoadedPearl(ThrownEnderpearl pearl);

    void fabrolia$unindexLoadedPearl(ThrownEnderpearl pearl);
}
