package com.axalotl.fabrolia.common.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ender-pearl chunk-loading forensics emitter (2026-08-18). Always on
 * ({@link FabroliaDebug#PEARL_TRACE}) but prod-safe by construction:
 *
 * <ul>
 *   <li><b>Anomaly-only.</b> Both events carry a live-pearl scan of the
 *       affected chunk: a pearl ticket expiring with NO live pearl inside is
 *       the normal case (pearl landed / left / owner logged out — the ticket
 *       simply burns down 40 ticks later) and stays silent. With thousands of
 *       pearls on a live server only genuine losses log.</li>
 *   <li><b>Located.</b> Every line carries the chunk and the pearl's uuid,
 *       block pos and owner uuid — the "where was the pearl" question is
 *       answered inline.</li>
 *   <li><b>Rate-limited.</b> Burst of {@value #BURST} lines (the interesting
 *       leading edge of a mass-loss event), then ≤{@value #PER_SECOND}/s; the
 *       first line after a throttled window reports the suppressed count.
 *       Shutdown unload cascades are additionally muted via
 *       {@code server.isRunning()}.</li>
 * </ul>
 */
public final class PearlTrace {

    private static final Logger LOG = LoggerFactory.getLogger("Fabrolia-PearlTicket");

    private static final int BURST = 40;
    private static final int PER_SECOND = 5;
    private static final int MAX_LISTED = 3;

    private static final java.util.concurrent.atomic.AtomicInteger EMITTED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_START =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger IN_WINDOW =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.LongAdder SUPPRESSED =
            new java.util.concurrent.atomic.LongAdder();

    private PearlTrace() {}

    private static boolean allow() {
        if (EMITTED.incrementAndGet() <= BURST) {
            return true;
        }
        long now = System.nanoTime();
        long start = WINDOW_START.get();
        if (now - start > 1_000_000_000L) {
            if (WINDOW_START.compareAndSet(start, now)) {
                IN_WINDOW.set(0);
            }
        }
        if (IN_WINDOW.incrementAndGet() <= PER_SECOND) {
            return true;
        }
        SUPPRESSED.increment();
        return false;
    }

    private static long takeSuppressed() {
        long n = SUPPRESSED.sum();
        if (n > 0) {
            SUPPRESSED.reset();
        }
        return n;
    }

    /** Live (loaded, not removed) pearl in the chunk's section storage —
     *  the liveness source for {@code PearlTicketHold}. Early-exit; never
     *  throws. Section storage (not the owner's pearl registry) is the
     *  authority here: the registry is blind exactly in the frozen scenarios
     *  this feeds (respawn does not copy the set, login places the ticket
     *  before the first tick registers, chunk-NBT orphans never registered),
     *  while an entity physically sitting in the chunk is precisely the one
     *  that resumes ticking — and renewing — when the chunk re-promotes.
     *  HIDDEN sections are deliberately included: that transition window is
     *  where C2ME knownUuids can reject a playerdata clone while public entity
     *  lookup and the previous accessible-only hold both fail to see the
     *  canonical pearl.  The removal check excludes completed unloads. */
    public static boolean hasLivePearlInChunk(ServerLevel level, long packedChunkPos) {
        var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                .fabrolia$entityManager();
        var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) em)
                .fabrolia$sectionStorage();
        for (var section : storage.getExistingSectionsInChunk(packedChunkPos).toList()) {
            var found = new boolean[1];
            section.getEntities().forEach(ea -> {
                if (!found[0] && ea instanceof ThrownEnderpearl pearl && !pearl.isRemoved()) {
                    found[0] = true;
                }
            });
            if (found[0]) {
                return true;
            }
        }
        // A clean weak section-list miss can still happen during a concurrent
        // section transfer. The successful-PESM-add index is maintained through
        // HIDDEN, so consult it before allowing a zero-age ticket to disappear.
        // A failed probe propagates unchanged; it is never converted into an
        // invented "pearl present" simulation result.
        return ((com.axalotl.fabrolia.common.parallelised.utils.LoadedPearlIndexAccess) em)
                .fabrolia$hasLoadedPearlInChunk(packedChunkPos);
    }

    /** Live pearls currently in the chunk's section storage — "" when none
     *  (the non-anomalous case; callers stay silent then). Never throws: a
     *  forensic read must not break the ticket/tracker seam it observes. */
    public static String livePearlsInChunk(ServerLevel level, long packedChunkPos) {
        try {
            var em = ((com.axalotl.fabrolia.common.mixin.world.ServerLevelEntityManagerAccessor) level)
                    .fabrolia$entityManager();
            var storage = ((com.axalotl.fabrolia.common.parallelised.utils.TickingChunksAccess) em)
                    .fabrolia$sectionStorage();
            StringBuilder sb = new StringBuilder();
            int[] found = {0};
            for (var section : storage.getExistingSectionsInChunk(packedChunkPos).toList()) {
                section.getEntities().forEach(ea -> {
                    if (!(ea instanceof ThrownEnderpearl pearl) || pearl.isRemoved()) {
                        return;
                    }
                    found[0]++;
                    if (found[0] > MAX_LISTED) {
                        return;
                    }
                    String owner;
                    try {
                        var o = pearl.getOwner();
                        owner = o != null ? String.valueOf(o.getUUID()) : "?";
                    } catch (Throwable t) {
                        owner = "?";
                    }
                    sb.append(found[0] == 1 ? " " : ", ")
                            .append(pearl.getUUID())
                            .append('@').append(pearl.blockPosition().toShortString())
                            .append("(owner ").append(owner).append(')');
                });
            }
            if (found[0] > MAX_LISTED) {
                sb.append(" (+").append(found[0] - MAX_LISTED).append(" more)");
            }
            return sb.toString();
        } catch (Throwable t) {
            return " <scan failed: " + t + ">";
        }
    }

    /** A burned-down ENDER_PEARL ticket is being held at zero because a live
     *  (frozen) pearl sits in its chunk — the self-heal engaged. Logged once
     *  per hold; this is THE prod signal that the margin race was caught. */
    public static void holdEngaged(ServerLevel level, long packedChunkPos, String probe) {
        if (!FabroliaDebug.PEARL_TRACE) {
            return;
        }
        long suppressed = takeSuppressed();
        if (allow()) {
            LOG.info("[pearl-ticket] holding ENDER_PEARL ticket at {} — live frozen pearl(s):{} | {}{}",
                    ChunkPos.unpack(packedChunkPos), livePearlsInChunk(level, packedChunkPos), probe,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }

    /** A previously held ticket renewed out of the hold (the pearl unfroze
     *  and its renewal reached the storage) — the self-heal completed. */
    public static void holdHealed(ServerLevel level, long packedChunkPos, int heldPasses) {
        if (!FabroliaDebug.PEARL_TRACE) {
            return;
        }
        long suppressed = takeSuppressed();
        if (allow()) {
            LOG.info("[pearl-ticket] hold released at {} after {} passes — pearl resumed renewing "
                            + "(self-heal OK); pearl(s):{}{}",
                    ChunkPos.unpack(packedChunkPos), heldPasses,
                    livePearlsInChunk(level, packedChunkPos),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }

    /** The hold's leak bound released a wedged pearl's ticket to vanilla
     *  expiry after {@code heldPasses} suppressed purge passes. */
    public static void holdReleased(ServerLevel level, long packedChunkPos, int heldPasses) {
        if (!FabroliaDebug.PEARL_TRACE) {
            return;
        }
        long suppressed = takeSuppressed();
        if (allow()) {
            LOG.info("[pearl-ticket] hold budget exhausted at {} after {} passes — releasing "
                            + "ticket to vanilla expiry; pearl(s):{}{}",
                    ChunkPos.unpack(packedChunkPos), heldPasses,
                    livePearlsInChunk(level, packedChunkPos),
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }

    /** ENDER_PEARL ticket burned its full timeout at this chunk. Logs only if
     *  a live pearl is stranded there — i.e. the renewal never reached the
     *  ticket storage. */
    public static void expired(ServerLevel level, long packedChunkPos) {
        String pearls = livePearlsInChunk(level, packedChunkPos);
        if (pearls.isEmpty()) {
            return;
        }
        long suppressed = takeSuppressed();
        if (allow()) {
            LOG.info("[pearl-ticket] ender_pearl ticket EXPIRED at {} with live pearl(s):{}{}",
                    ChunkPos.unpack(packedChunkPos), pearls,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }

    /** A chunk holding a live pearl left entity-ticking range — the pearl is
     *  now frozen (no ticks, no renewals). */
    public static void frozen(ServerLevel level, long packedChunkPos) {
        if (!level.getServer().isRunning()) {
            return; // shutdown unload cascade — every pearl "freezes", noise
        }
        String pearls = livePearlsInChunk(level, packedChunkPos);
        if (pearls.isEmpty()) {
            return;
        }
        long suppressed = takeSuppressed();
        if (allow()) {
            LOG.info("[pearl-ticket] chunk {} LEFT entity-ticking with live ender pearl(s):{}{}",
                    ChunkPos.unpack(packedChunkPos), pearls,
                    suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
        }
    }
}
