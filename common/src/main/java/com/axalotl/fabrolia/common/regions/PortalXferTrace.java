package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task #26 — ONE JOINED TRACE per cross-dimension transfer (operator spec,
 * 2026-07-23 portal-minecart incident): UUID + numeric id, source and
 * destination dimension, source island id/gen + logical epoch, destination
 * island id/gen + publish epoch, wall ticks for every phase transition
 * (submit → drain → source-removal → stage → publish → FIRST DESTINATION
 * TICK), portal entry/cooldown, position and velocity at submission, ticket
 * re-arms, duplicate submissions, aborts with reason (unload/save/stale), and
 * the publish cause (unit / global / flush / inline).
 *
 * <p>All entity types — minecarts, mobs, items, falling blocks — flow through
 * the same hooks. Default OFF ({@code -Dfabrolia.debug.portalTrace}); the
 * live-record dump ({@code regions debug xfer}) doubles as the stuck-transfer
 * detector: any record older than a few seconds IS a wedged transfer, with
 * its partial timeline attached.
 */
public final class PortalXferTrace {

    public static final boolean ON = Boolean.getBoolean("fabrolia.debug.portalTrace");
    private static final Logger LOG = LoggerFactory.getLogger("Fabrolia-XferTrace");

    private PortalXferTrace() {}

    public static final class Rec {
        public final UUID uuid;
        public final int entityId;
        public final String type;
        public final String srcDim;
        public String dstDim = "?";
        public long srcIsland = -1, srcGen = -1;
        public long srcEpoch = -1, srcDomain = -1;
        public long submitWall = -1, drainWall = -1, teleportWall = -1,
                stageWall = -1, publishWall = -1, publishL = -1,
                firstTickWall = -1, firstTickL = -1;
        public long dstIsland = -1, dstGen = -1;
        public long dstChunkKey = Long.MIN_VALUE;
        public ServerLevel dstLevel;
        public java.lang.ref.WeakReference<Entity> entityRef;
        public String submitPosVel = "?";
        public String arrivalPosVel = "?";
        public String entry = "?";
        public int cooldown;
        public int ticketRearms;
        public int dupSubmits;
        public String publishCause = "?";
        public String notes = "";
        public final long bornNanos = System.nanoTime();

        Rec(UUID uuid, int entityId, String type, String srcDim) {
            this.uuid = uuid;
            this.entityId = entityId;
            this.type = type;
            this.srcDim = srcDim;
        }

        String joined() {
            return "uuid=" + uuid + " id=" + entityId + " type=" + type
                    + " " + srcDim + "->" + dstDim
                    + " srcIsland#" + srcIsland + "/g" + srcGen
                    + " srcEpoch=" + srcEpoch + " srcDom=" + srcDomain
                    + " dstIsland#" + dstIsland + "/g" + dstGen
                    + " | wall: submit=" + submitWall + " drain=" + drainWall
                    + " teleport=" + teleportWall + " stage=" + stageWall
                    + " publish=" + publishWall + "(L=" + publishL + ",cause=" + publishCause + ")"
                    + " firstTick=" + firstTickWall + "(L=" + firstTickL + ")"
                    + " | entry=" + entry + " cooldown=" + cooldown
                    + " submitPosVel=" + submitPosVel
                    + " arrivalPosVel=" + arrivalPosVel
                    + " ticketRearms=" + ticketRearms + " dupSubmits=" + dupSubmits
                    + " ageMs=" + (System.nanoTime() - bornNanos) / 1_000_000
                    + (notes.isEmpty() ? "" : " notes:" + notes);
        }
    }

    private static final Map<UUID, Rec> LIVE = new ConcurrentHashMap<>();

    private static long wall(ServerLevel level) {
        return IslandClock.wallTime(level);
    }

    private static void islandOf(ServerLevel level, long chunkKey, java.util.function.LongBinaryOperator sink) {
        RegionizedServer rs = RegionScheduler.forLevel(level);
        IslandRegistry.PIsland p = rs == null ? null : rs.islandOwnerOfChunk(chunkKey);
        sink.applyAsLong(p == null ? -1 : p.id, p == null ? -1 : p.gen);
    }

    /** Worker-path submission (PTM deferred handoff). */
    public static void submit(Entity e, ServerLevel src, long srcEpoch, long srcDomain,
                              net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 vel,
                              net.minecraft.core.BlockPos entryPos, int cooldown) {
        if (!ON) return;
        Rec r = new Rec(e.getUUID(), e.getId(), e.getType().toString(),
                src.dimension().identifier().toString());
        r.srcEpoch = srcEpoch;
        r.srcDomain = srcDomain;
        r.submitWall = wall(src);
        r.submitPosVel = String.format("(%.2f,%.2f,%.2f)v(%.3f,%.3f,%.3f)",
                pos.x, pos.y, pos.z, vel.x, vel.y, vel.z);
        r.entry = entryPos.toShortString();
        r.cooldown = cooldown;
        islandOf(src, net.minecraft.world.level.ChunkPos.pack(entryPos),
                (id, gen) -> { r.srcIsland = id; r.srcGen = gen; return 0; });
        Rec prev = LIVE.put(e.getUUID(), r);
        if (prev != null) {
            r.dupSubmits = prev.dupSubmits + 1;
            r.notes += "[REPLACED-PRIOR-REC]";
            LOG.warn("[xfer] DUP-RECORD {} prior: {}", e.getUUID(), prev.joined());
        }
        LOG.info("[xfer] SUBMIT {}", r.joined());
    }

    /** Main-path (inline, vanilla-synchronous) submission. */
    public static void inlineSubmit(Entity e, ServerLevel src, net.minecraft.core.BlockPos entryPos, int cooldown) {
        if (!ON) return;
        Rec r = new Rec(e.getUUID(), e.getId(), e.getType().toString(),
                src.dimension().identifier().toString());
        r.submitWall = wall(src);
        r.drainWall = r.submitWall; // inline: no queue latency
        r.entry = entryPos == null ? "?" : entryPos.toShortString();
        r.cooldown = cooldown;
        r.submitPosVel = String.format("(%.2f,%.2f,%.2f)v(%.3f,%.3f,%.3f)",
                e.getX(), e.getY(), e.getZ(),
                e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z);
        r.notes += "[inline]";
        LIVE.put(e.getUUID(), r);
        LOG.info("[xfer] SUBMIT-INLINE {}", r.joined());
    }

    /** A second submission while one is queued (IN_FLIGHT add failed). */
    public static void dupSubmit(Entity e) {
        if (!ON) return;
        Rec r = LIVE.get(e.getUUID());
        if (r != null) {
            r.dupSubmits++;
        }
        LOG.warn("[xfer] DUP-SUBMIT uuid={} id={} (in-flight — ignored)", e.getUUID(), e.getId());
    }

    public static void drain(UUID uuid, ServerLevel src) {
        if (!ON) return;
        Rec r = LIVE.get(uuid);
        if (r != null) r.drainWall = wall(src);
    }

    public static void abort(Entity e, ServerLevel src, String reason) {
        if (!ON) return;
        Rec r = LIVE.remove(e.getUUID());
        String rem = e.getRemovalReason() == null ? "alive" : e.getRemovalReason().toString();
        LOG.warn("[xfer] ABORT reason={} removal={} level={} {}{}",
                reason, rem, e.level().dimension().identifier(),
                r == null ? ("uuid=" + e.getUUID() + " <no record>") : r.joined(),
                yChain(e.getUUID())); // also drains the Y-chain buffer (no leak)
    }

    /** task #26 Y-precision audit (operator spec 2026-07-24): full double-precision
     *  Y at each stage of the deferred handoff, so the rare 1/16-block (0.0625)
     *  truncation between the portal placement calculation and the first
     *  destination tick can be localized to the exact stage that drops it.
     *
     *  <p>TIMING-NEUTRAL (2026-07-24): the bug is a timing-dependent race, so
     *  per-stage {@code LOG.info} calls perturbed it out of existence. Instead we
     *  APPEND the stage/Y into a tiny per-UUID buffer — a cheap concurrent-map
     *  put, NO I/O on the hot path — and emit the whole chain once, in the CYCLE
     *  line (or the record dump), only when the transfer resolves. Same data,
     *  no per-crossing logging latency in the teleport pipeline. */
    private static final Map<UUID, String> Y_CHAIN = new ConcurrentHashMap<>();

    public static void noteY(java.util.UUID uuid, String stage, double y) {
        if (!ON) return;
        Y_CHAIN.merge(uuid, stage + "=" + Double.toString(y),
                (prev, add) -> prev.length() > 512 ? prev : prev + " " + add);
    }

    private static String yChain(java.util.UUID uuid) {
        String s = Y_CHAIN.remove(uuid);
        return s == null ? "" : " yChain[" + s + "]";
    }

    /** finishTeleport succeeded — the entity left the source level. */
    public static void teleported(Entity e, ServerLevel src, ServerLevel dst) {
        if (!ON) return;
        Rec r = LIVE.get(e.getUUID());
        if (r == null) return;
        r.teleportWall = wall(src);
        r.dstDim = dst.dimension().identifier().toString();
        LOG.info("[xfer] TELEPORT uuid={} {}->{} srcW={}", e.getUUID(), r.srcDim, r.dstDim, r.teleportWall);
    }

    /** Destination insertion STAGED (deferred to the dest owner's phase head). */
    public static void stage(Entity e, ServerLevel dst, long chunkKey) {
        if (!ON) return;
        Rec r = LIVE.get(e.getUUID());
        if (r == null) return;
        r.stageWall = wall(dst);
        r.dstDim = dst.dimension().identifier().toString();
        r.dstChunkKey = chunkKey;
        r.dstLevel = dst;
        r.entityRef = new java.lang.ref.WeakReference<>(e);
        islandOf(dst, chunkKey, (id, gen) -> { r.dstIsland = id; r.dstGen = gen; return 0; });
        LOG.info("[xfer] STAGE uuid={} dst={} dstIsland#{}/g{} chunk={},{}",
                e.getUUID(), r.dstDim, r.dstIsland, r.dstGen,
                net.minecraft.world.level.ChunkPos.getX(chunkKey),
                net.minecraft.world.level.ChunkPos.getZ(chunkKey));
    }

    /** The staged insertion op actually ran. */
    public static void publish(Entity e, ServerLevel dst, String cause) {
        if (!ON) return;
        Rec r = LIVE.get(e.getUUID());
        if (r == null) return;
        r.publishWall = wall(dst);
        r.publishL = IslandClock.contextTime();
        r.publishCause = cause;
        LOG.info("[xfer] PUBLISH uuid={} destW={} destL={} cause={} thread={}",
                e.getUUID(), r.publishWall, r.publishL, cause, Thread.currentThread().getName());
    }

    /** Post-insertion destination diagnostics (inert-arrival hunt): the region
     *  the entity landed in, its domain owner/state, and whether the entity
     *  actually entered the region's tick set. */
    public static void publishDetail(Entity e, ServerLevel dst, long chunkKey) {
        if (!ON) return;
        RegionizedServer rs = RegionScheduler.forLevel(dst);
        StringBuilder sb = new StringBuilder(128);
        if (rs == null) {
            sb.append("rs=null");
        } else {
            Region r = rs.regionFor(chunkKey);
            if (r == null) {
                sb.append("region=null");
            } else {
                sb.append("region=").append(Long.toHexString(r.gridKey))
                  .append(" state=").append(r.state())
                  .append(" domain=").append(r.domain == null ? "null"
                        : (r.domain.owner == null ? "global"
                        : ("island#" + r.domain.owner.id + "/g" + r.domain.owner.gen)))
                  .append(" inRegionSet=").append(r.entities.contains(e))
                  .append(" regionEntities=").append(r.entities.size())
                  .append(" entityRemoved=").append(e.isRemoved())
                  .append(" entityLevel=").append(e.level().dimension().identifier());
            }
        }
        sb.append(" gate:").append(gateOf(dst, chunkKey));
        Rec rec = LIVE.get(e.getUUID());
        if (rec != null) {
            rec.notes += "[dest:" + sb + "]";
        }
        LOG.info("[xfer] PUBLISH-DETAIL uuid={} {}", e.getUUID(), sb);
    }

    /** Live evaluation of the region drain's per-chunk ticking gate
     *  (entity-ticking range + completed ticking chunk) — the published-but-
     *  never-ticked discriminator. Worker-safe holder path (same as the
     *  drain's). */
    public static String gateOf(ServerLevel dst, long chunkKey) {
        try {
            var cs = dst.getChunkSource();
            var dm = cs.chunkMap.getDistanceManager();
            boolean range = dm.inEntityTickingRange(chunkKey);
            var holder = cs.getVisibleChunkIfPresent(chunkKey);
            boolean ticking = holder != null && holder.getTickingChunk() != null;
            return "range=" + range + ",holder=" + (holder != null) + ",tickingChunk=" + ticking;
        } catch (Throwable t) {
            return "eval-failed:" + t;
        }
    }

    /** Endpoint lease re-armed / portal ticket placed for this transfer's chunk.
     *  Attributed to live records whose staged destination chunk matches. */
    public static void ticket(ServerLevel level, long chunkKey, String why) {
        if (!ON) return;
        for (Rec r : LIVE.values()) {
            if (r.dstChunkKey == chunkKey) {
                r.ticketRearms++;
            }
        }
        LOG.info("[xfer] TICKET {} chunk={},{} why={}",
                level.dimension().identifier(),
                net.minecraft.world.level.ChunkPos.getX(chunkKey),
                net.minecraft.world.level.ChunkPos.getZ(chunkKey), why);
    }

    /** First destination tick — emit the joined line, close the record. */
    public static void firstTick(Entity e) {
        if (!ON) return;
        Rec r = LIVE.remove(e.getUUID());
        if (r == null) return;
        if (e.level() instanceof ServerLevel sl) {
            r.firstTickWall = wall(sl);
        }
        r.firstTickL = IslandClock.contextTime();
        // Arrival pose at the FIRST real destination tick — compare across
        // crossings: a trapping crossing's arrival lands off the rail / against a
        // wall while working crossings land on it (task #26 arrival-physics).
        net.minecraft.world.phys.Vec3 ap = e.position();
        net.minecraft.world.phys.Vec3 av = e.getDeltaMovement();
        r.arrivalPosVel = String.format("(%.2f,%.2f,%.2f)v(%.3f,%.3f,%.3f)",
                ap.x, ap.y, ap.z, av.x, av.y, av.z);
        noteY(e.getUUID(), "firstTick", ap.y);
        LOG.info("[xfer] CYCLE {}{}", r.joined(), yChain(e.getUUID()));
        // HANDOFF-PHASE INVARIANT (operator spec 2026-07-24): a published arrival
        // must become eligible exactly at its publication epoch boundary — its
        // first destination tick in the SAME epoch it published (delay 0). A
        // non-zero delay means it was published too late for this epoch's entity
        // snapshot and ticked in a LATER epoch — the "eligible midway / phase
        // drift" the operator flagged. Flag it loudly so the field run surfaces
        // exactly which crossings violate the invariant (arrival velocity then
        // resumes a tick or more later, off-phase from the source that launched
        // it). publishL/firstTickL are the island logical epochs; -1 = global/
        // main context (compared by wall instead).
        long pubL = r.publishL, ftL = r.firstTickL;
        boolean lViol = pubL >= 0 && ftL >= 0 && pubL != ftL;
        boolean wallViol = (pubL < 0 || ftL < 0) && r.publishWall >= 0
                && r.firstTickWall >= 0 && r.publishWall != r.firstTickWall;
        if (lViol || wallViol) {
            LOG.warn("[xfer] HANDOFF-PHASE-VIOLATION uuid={} publishL={} firstTickL={} (Δepoch={}) "
                    + "publishW={} firstTickW={} (Δwall={}) cause={} — arrival ticked OFF its publication "
                    + "epoch boundary",
                    e.getUUID(), pubL, ftL, (pubL >= 0 && ftL >= 0 ? ftL - pubL : "n/a"),
                    r.publishWall, r.firstTickWall, r.firstTickWall - r.publishWall, r.publishCause);
        }
    }

    /** Is a transfer record still open (submitted, not yet first-ticked) for
     *  this entity? Used by the trapped-in-portal probe to distinguish a
     *  stranded transfer from a completed-then-trapped cart. */
    public static boolean hasLiveRecord(UUID uuid) {
        return LIVE.containsKey(uuid);
    }

    /** Live (unfinished) transfers — the stuck detector for `regions debug xfer`. */
    public static String dump() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("portalTrace=").append(ON ? "on" : "OFF")
          .append(" live=").append(LIVE.size());
        for (Rec r : LIVE.values()) {
            sb.append("\n  IN-FLIGHT ").append(r.joined());
            if (r.dstLevel != null && r.dstChunkKey != Long.MIN_VALUE && r.publishWall >= 0) {
                // published-but-unticked: re-evaluate the ticking gate NOW —
                // the inert-arrival discriminator, live.
                sb.append("\n    gateNow: ").append(gateOf(r.dstLevel, r.dstChunkKey));
                Entity ent = r.entityRef == null ? null : r.entityRef.get();
                sb.append("\n    entNow: ");
                if (ent == null) {
                    sb.append("<gc'd>");
                } else {
                    boolean inLookup = false;
                    boolean inRegionNow = false;
                    String regionNow = "n/a";
                    try {
                        inLookup = ent.level() instanceof ServerLevel sl && sl.getEntity(ent.getUUID()) == ent;
                        RegionizedServer rs = ent.level() instanceof ServerLevel sl2
                                ? RegionScheduler.forLevel(sl2) : null;
                        if (rs != null) {
                            Region reg = rs.regionFor(ent.chunkPosition().pack());
                            inRegionNow = reg != null && reg.entities.contains(ent);
                            regionNow = reg == null ? "null" : (Long.toHexString(reg.gridKey)
                                    + "/" + reg.state()
                                    + "/" + (reg.domain == null ? "nullDomain"
                                    : (reg.domain.owner == null ? "global"
                                    : "island#" + reg.domain.owner.id + "g" + reg.domain.owner.gen)));
                        }
                    } catch (Throwable t) {
                        regionNow = "probe-failed:" + t;
                    }
                    sb.append("removed=").append(ent.getRemovalReason())
                      .append(" level=").append(ent.level().dimension().identifier())
                      .append(" pos=(").append(String.format("%.1f,%.1f,%.1f", ent.getX(), ent.getY(), ent.getZ()))
                      .append(") inLookup=").append(inLookup)
                      .append(" region=").append(regionNow)
                      .append(" inRegionSetNow=").append(inRegionNow)
                      .append(" regionKeyField=").append(Long.toHexString(((RegionKeyed) ent).fabrolia$getRegionKey()));
                }
                // Destination island DRIVE state — separates "island stopped
                // ticking" (L frozen, stale deadline) from "island runs,
                // region skipped" (L advancing).
                try {
                    RegionizedServer drs = RegionScheduler.forLevel(r.dstLevel);
                    IslandRegistry.PIsland owner = drs == null ? null
                            : drs.islandOwnerOfChunk(r.dstChunkKey);
                    if (owner == null) {
                        sb.append("\n    dstIslandNow: <none — cell unowned/global>");
                    } else {
                        sb.append("\n    dstIslandNow: island#").append(owner.id)
                          .append("/g").append(owner.gen)
                          .append(" retired=").append(owner.retired)
                          .append(" L=").append(owner.logicalTick)
                          .append(" wall=").append(IslandClock.wallTime(r.dstLevel))
                          .append(" nextDeadlineIn=").append(owner.nextDeadlineNanos == 0 ? "unscheduled"
                                : ((owner.nextDeadlineNanos - System.nanoTime()) / 1_000_000L) + "ms")
                          .append(" pendingArrivals=").append(owner.pendingArrivals.size())
                          .append(" claim=").append(owner.unitClaim.get());
                    }
                    Region reg2 = drs == null ? null : drs.regionFor(r.dstChunkKey);
                    if (reg2 != null) {
                        java.util.HashMap<String, Integer> hist = new java.util.HashMap<>();
                        for (Entity re : reg2.entities) {
                            if (re != null) {
                                hist.merge(re.getType().toString(), 1, Integer::sum);
                            }
                        }
                        sb.append("\n    dstRegionSet: ").append(hist);
                    }
                } catch (Throwable t) {
                    sb.append("\n    dstIslandNow: probe-failed:").append(t);
                }
            }
        }
        return sb.toString();
    }
}
