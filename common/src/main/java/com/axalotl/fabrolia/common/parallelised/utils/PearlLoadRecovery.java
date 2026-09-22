package com.axalotl.fabrolia.common.parallelised.utils;

import com.axalotl.fabrolia.common.mixin.entity.ProjectileOwnerAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repairs the two halves of Minecraft 26.2's player-pearl load collision.
 *
 * <p>{@code ThrownEnderpearl.readAdditionalSaveData} resolves its owner and
 * registers the newly constructed object <em>before</em>
 * {@code ServerLevel.addWithUUID} decides whether the UUID is already in the
 * entity manager.  A rejected object therefore remains in the owner's set and
 * can be written back to playerdata on the next save.  This is the vanilla
 * dual-persistence/load-collision behaviour tracked as MC-306936: the paired
 * UUID/failed-spawn warnings and repeated UUIDs in playerdata are not, by
 * themselves, evidence of a Fabrolia pearl loss.
 *
 * <p>The normal path preflights a visible canonical entity before constructing
 * a duplicate.  A processor wrapper around this one playerdata load call is the
 * mandatory late-race belt: when {@code addWithUUID} still rejects a freshly
 * deserialized candidate, detach that candidate by identity, adopt the existing
 * live pearl when visible, or place one temporary recovery ticket at the saved
 * position when the collision is with a HIDDEN
 * entity.  The latter is important under C2ME: {@code knownUuids} rejects a
 * hidden entity's clone, while the public UUID lookup cannot see the
 * hidden canonical object.  Re-loading its saved chunk lets the real pearl tick,
 * re-register and resume its own ticket renewal; the temporary ticket expires
 * normally if the collision was unrelated/corrupt.  This mitigation prevents
 * rejected-candidate registry feedback, but intentionally does not claim to fix
 * MC-306936's separate stale-resurrection case when no live canonical exists.
 */
public final class PearlLoadRecovery {

    private static final Logger LOG = LoggerFactory.getLogger("Fabrolia-PearlLoad");
    private static final int BURST = 32;
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong EVENTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LAST_LOG_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.LongAdder SUPPRESSED =
            new java.util.concurrent.atomic.LongAdder();

    private PearlLoadRecovery() {}

    /**
     * Called by the playerdata HEAD preflight when the saved UUID already
     * resolves to a world entity.  The caller cancels construction either way:
     * a wrong-type/owner UUID collision could not pass {@code addWithUUID}
     * either, and constructing it would only contaminate the owner registry.
     */
    public static void preexisting(ServerPlayer expectedOwner, Entity existing) {
        if (adopt(expectedOwner, existing, "preflight")) {
            return;
        }
        emit("preflight-collision", expectedOwner,
                "uuid=" + existing.getUUID() + " existing=" + existing.getType()
                        + " existingRemoved=" + existing.isRemoved());
    }

    /**
     * Other levels have a technically separate UUID index, so only suppress a
     * stale saved-level record when the visible object is provably the same
     * owner's live pearl migrated through a portal.  Wrong-type/wrong-owner
     * collisions in another level are ignored by the caller.
     */
    public static boolean migratedPreexisting(ServerPlayer expectedOwner, Entity existing) {
        return adopt(expectedOwner, existing, "migrated-preflight");
    }

    /**
     * Public Level UUID lookup excludes HIDDEN sections.  Fall back to the
     * successful-PESM-add index so playerdata restore can find the canonical
     * pearl during precisely that C2ME transition window.  Non-pearl visible
     * collisions are retained for the same-level caller's vanilla-equivalent
     * rejection policy.
     */
    public static Entity loadedEntity(ServerLevel level, java.util.UUID uuid) {
        Entity visible = level.getEntity(uuid);
        if (visible != null && !(visible instanceof ThrownEnderpearl)) {
            return visible;
        }
        Object manager =
                ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                        .fabrolia$entityManager();
        ThrownEnderpearl indexed =
                ((LoadedPearlIndexAccess) manager).fabrolia$loadedPearl(uuid);
        // A visible pearl is still validated through the resident-section
        // index: C2ME recovery can leave stale byId entries after dropping a
        // section without the normal callbacks.
        return indexed;
    }

    /**
     * Late-race cleanup after a failed {@code ServerLevel.addWithUUID}.
     * Returns without touching non-pearl entities or candidates without a live
     * server-player owner.
     */
    public static void rejected(ServerLevel attemptedLevel, Entity rejected) {
        if (!(rejected instanceof ThrownEnderpearl candidate)) {
            return;
        }
        java.util.UUID ownerUuid = ownerUuid(candidate);
        if (ownerUuid == null) {
            return;
        }
        ServerPlayer owner = attemptedLevel.getServer().getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            return;
        }

        // A processor is allowed to reject an entity it already removed. Do
        // not reinterpret that as a duplicate collision. Still mirror the
        // exact vanilla owner-registry removal rule so a non-UWP candidate
        // which registered during NBT load cannot survive as a ghost.
        if (candidate.isRemoved()) {
            if (PearlRegistration.noLongerBelongsToOwner(candidate.getRemovalReason())) {
                owner.deregisterEnderPearl(candidate);
            }
            emit("rejected-removed-candidate", owner,
                    "uuid=" + candidate.getUUID() + " reason=" + candidate.getRemovalReason());
            return;
        }

        // readAdditionalSaveData registered this exact object before the add.
        // ConcurrentIdentitySet removes by stable object identity and preserves
        // a different source/destination/canonical lifecycle object.
        owner.deregisterEnderPearl(candidate);

        Entity existing = loadedEntity(attemptedLevel, candidate.getUUID());
        if (existing != null && existing != candidate && adopt(owner, existing, "late-race")) {
            return;
        }

        // C2ME/PESM can retain the UUID in a HIDDEN section which the public
        // entity lookup does not expose.  The candidate carries the last saved
        // position of that UUID; a bounded vanilla ENDER_PEARL ticket is the
        // only non-destructive way to make the canonical entity visible again.
        // Never register or add the rejected candidate.
        ServerPlayer.placeEnderPearlTicket(attemptedLevel, candidate.chunkPosition());
        emit("hidden-recovery-ticket", owner,
                "uuid=" + candidate.getUUID() + " chunk=" + candidate.chunkPosition()
                        + " dim=" + attemptedLevel.dimension().identifier()
                        + " visibleCollision=" + (existing == null ? "none" : existing.getType()));
    }

    /** Warn when an old/repeated session tried to overwrite current playerdata. */
    public static void stalePlayerSave(ServerPlayer stale, ServerPlayer current) {
        emit("stale-session-save-blocked", stale,
                "current=" + (current == null ? "none" : current.getName().getString())
                        + " staleRemoval=" + stale.getRemovalReason());
    }

    /** Warn when an obsolete ServerPlayer was prevented from UWP-removing the
     * replacement session's still-live canonical pearls. */
    public static void stalePlayerPearlRemoval(
            ServerPlayer stale, ServerPlayer current, int handedOff) {
        emit("stale-session-pearl-unload-blocked", stale,
                "current=" + (current == null ? "none" : current.getName().getString())
                        + " handedOff=" + handedOff
                        + " staleRemoval=" + stale.getRemovalReason());
    }

    /** Warn when a repeated/concurrent PlayerList.remove was contained. */
    public static void duplicatePlayerRemoval(ServerPlayer stale, ServerPlayer current) {
        emit("duplicate-player-remove-blocked", stale,
                "current=" + (current == null ? "none" : current.getName().getString())
                        + " staleRemoval=" + stale.getRemovalReason());
    }

    private static boolean adopt(ServerPlayer expectedOwner, Entity existing, String stage) {
        if (!(existing instanceof ThrownEnderpearl canonical) || canonical.isRemoved()) {
            return false;
        }
        java.util.UUID canonicalOwner = ownerUuid(canonical);
        if (!expectedOwner.getUUID().equals(canonicalOwner)) {
            return false;
        }
        if (!(canonical.level() instanceof ServerLevel canonicalLevel)) {
            return false;
        }

        expectedOwner.registerEnderPearl(canonical);
        ServerPlayer.placeEnderPearlTicket(canonicalLevel, canonical.chunkPosition());
        emit("canonical-reused", expectedOwner,
                "stage=" + stage + " uuid=" + canonical.getUUID()
                        + " chunk=" + canonical.chunkPosition()
                        + " dim=" + canonicalLevel.dimension().identifier());
        return true;
    }

    /** Owner UUID remains available even when player and projectile are in
     * different dimensions and Projectile.getOwner therefore resolves null. */
    private static java.util.UUID ownerUuid(ThrownEnderpearl pearl) {
        EntityReference<Entity> reference =
                ((ProjectileOwnerAccessor) (Object) pearl).fabrolia$getOwnerReference();
        return reference == null ? null : reference.getUUID();
    }

    private static void emit(String kind, ServerPlayer owner, String detail) {
        long event = EVENTS.incrementAndGet();
        long now = System.nanoTime();
        long last = LAST_LOG_NANOS.get();
        boolean allowed = event <= BURST
                || (now - last >= LOG_INTERVAL_NANOS
                    && LAST_LOG_NANOS.compareAndSet(last, now));
        if (!allowed) {
            SUPPRESSED.increment();
            return;
        }
        long suppressed = SUPPRESSED.sumThenReset();
        LOG.warn("[pearl-load] {} owner={}({}) {} events={} suppressed={}",
                kind, owner.getName().getString(), owner.getUUID(), detail, event, suppressed);
    }
}
