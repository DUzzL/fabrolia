package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.config.FabroliaConfig;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * R4 phase 0 — the certification instrument (the WorldThreader idea adapted
 * to regions): catches BLOCK WRITES that land OUTSIDE the writing worker's
 * claimed region. The per-region collector unlock (dropping the global
 * neighbor-drain serialization) is only safe for tick classes this detector
 * proves region-local in practice; its live counters on real contraption
 * tests ARE the campaign's go/no-go data.
 *
 * <p>Enabled during live async execution or explicit debug observation.
 * Cross-domain writes log their origin and fail the generation at the write site.
 */
public final class RegionAccessDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-RegionAccess");

    private static final AtomicLong IN_REGION = new AtomicLong();
    private static final AtomicLong CROSS_REGION = new AtomicLong();
    /** Cross-region write while the TARGET region was concurrently claimed —
     *  the actually-dangerous case. With the color gate on this MUST be 0. */
    private static final AtomicLong CROSS_UNSAFE = new AtomicLong();
    private static final AtomicLong OFF_WORKER = new AtomicLong();
    /** A7 islands mode: writes that left the writer's TIMELINE — landed in a
     *  region whose domain differs from the execution context's. With async
     *  islands on, every such hit violates the separation invariant, whether
     *  caused by our topology/admission or an external writer. The origin is
     *  diagnostic only: either cause must stop the generation before this write. */
    private static final AtomicLong CROSS_DOMAIN = new AtomicLong();
    private static final AtomicLong LOGGED = new AtomicLong();
    private static volatile boolean FAIL_CLOSED_LATCHED;
    private static final int MAX_STACK_LOGS = 8;

    private RegionAccessDetector() {}

    public static void onBlockWrite(BlockPos pos) {
        // Production audit 2026-07-12: the detector is LIVE whenever async
        // islands run (fail-closed depends on it) — one static volatile read
        // per setBlock when everything is off.
        if (!FabroliaConfig.fabroliaDebugRegionAccess && !RegionScheduler.asyncLive()) {
            return;
        }
        Thread t = Thread.currentThread();
        if (!(t instanceof RegionWorkerThread w)) {
            OFF_WORKER.incrementAndGet();   // main / pool / IO — out of scope
            return;
        }
        Region r = w.currentRegion;
        if (r == null) {
            // Coordinator-context write (island task / BE phase / deferral
            // replay — currentDomain set, no claim): previously INVISIBLE to
            // the detector, which let a mod's cross-island write from an
            // island task pass silently. Classify by domain.
            EpochDomain mine = w.currentDomain;
            if (mine == null || mine.owner == null) {
                OFF_WORKER.incrementAndGet();
                return;
            }
            RegionizedServer rsd = RegionScheduler.forLevel(mine.owner.domainLevel());
            if (rsd == null) {
                OFF_WORKER.incrementAndGet();
                return;
            }
            long wk = rsd.regionizer().grid.keyFor(pos.getX() >> 4, pos.getZ() >> 4);
            Region target = rsd.regionizer().peekByGridKey(wk);
            EpochDomain theirs = target != null ? target.domain : null;
            if (theirs != null && theirs != mine) {
                recordCrossDomain(pos, w, null, target, mine, theirs);
            } else {
                IN_REGION.incrementAndGet();
            }
            return;
        }
        RegionizedServer rs = RegionScheduler.forLevel(r.level);
        if (rs == null) {
            OFF_WORKER.incrementAndGet();
            return;
        }
        long writeKey = rs.regionizer().grid.keyFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (writeKey == r.gridKey) {
            IN_REGION.incrementAndGet();
            return;
        }
        CROSS_REGION.incrementAndGet();
        Region target = rs.regionizer().peekByGridKey(writeKey);
        boolean unsafeCross = target != null && target.currentWorker() != null;
        if (unsafeCross) {
            CROSS_UNSAFE.incrementAndGet();
        }
        // A7 islands mode: timeline escape (async only). Writer context domain
        // vs target region domain — package-private reads, worker thread.
        if (rs.asyncActive && target != null) {
            EpochDomain mine = r.domain != null ? r.domain : w.currentDomain;
            EpochDomain theirs = target.domain;
            if (mine != null && theirs != null && mine != theirs) {
                recordCrossDomain(pos, w, r, target, mine, theirs);
            }
        }
        // Stack-log only the UNSAFE class: a cross write into an idle
        // neighbor is legal vanilla behavior under the color discipline.
        if (unsafeCross && LOGGED.get() < MAX_STACK_LOGS && LOGGED.incrementAndGet() <= MAX_STACK_LOGS) {
            LOGGER.warn("[region-access] UNSAFE cross-region write @ {} from region {} on {} (target running)",
                    pos.toShortString(), Long.toHexString(r.gridKey), t.getName(),
                    new Throwable("write origin"));
        }
    }

    /** Origin classes for a cross-domain write (2026-07-14: the old message
     *  blamed "a mod" unconditionally — the production trip was pure vanilla
     *  fluid spread routed by OUR topology; operators must not be told to
     *  remove mods for Fabrolia's own defects). */
    public enum CrossDomainOrigin {
        MOD_CROSS_DOMAIN_WRITE,
        TOPOLOGY_FRONTIER_VIOLATION,
        STALE_OWNERSHIP_CONTEXT,
        VANILLA_SCHEDULER_BUG,
        UNKNOWN
    }

    /** Classify by the actual write stack: any non-(vanilla|fabrolia|jdk)
     *  frame, or a foreign mixin handler grafted into vanilla (method names
     *  like {@code handler$abc000$MODID$name}), implicates that mod. A pure
     *  vanilla+fabrolia stack is OUR routing/topology defect: target in the
     *  global domain → frontier violation; executing island retired → stale
     *  context; island→island → scheduler bug. */
    static CrossDomainOrigin classify(StackTraceElement[] stack, EpochDomain mine,
                                      EpochDomain theirs, StringBuilder culprit) {
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            String m = f.getMethodName();
            int h = m.indexOf('$');
            if (h >= 0 && !m.startsWith("lambda$")) {
                // Mixin-grafted frame: handler$tag$modid$name. A Java lambda's
                // numeric suffix is not a mod ID, even for a dollar-named method.
                String[] parts = m.split("\\$");
                if (parts.length >= 3) {
                    String modid = parts[2];
                    if (!modid.isEmpty() && !"fabrolia".equals(modid)
                            && !"minecraft".equals(modid) && !modid.startsWith("mixin")) {
                        culprit.append("mixin '").append(modid).append("' at ")
                               .append(cls).append('.').append(m);
                        return CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE;
                    }
                }
            }
            if (cls.startsWith("net.minecraft") || cls.startsWith("com.axalotl.fabrolia")
                    || cls.startsWith("java.") || cls.startsWith("jdk.")
                    || cls.startsWith("it.unimi") || cls.startsWith("com.mojang")
                    || cls.startsWith("sun.")) {
                continue;
            }
            culprit.append(cls).append('.').append(m);
            return CrossDomainOrigin.MOD_CROSS_DOMAIN_WRITE;
        }
        if (theirs != null && theirs.owner == null) {
            return CrossDomainOrigin.TOPOLOGY_FRONTIER_VIOLATION;
        }
        if (mine != null && mine.owner != null && mine.owner.retired) {
            return CrossDomainOrigin.STALE_OWNERSHIP_CONTEXT;
        }
        if (mine != null && theirs != null) {
            return CrossDomainOrigin.VANILLA_SCHEDULER_BUG;
        }
        return CrossDomainOrigin.UNKNOWN;
    }

    /** Full-context cross-domain report. {@code claimed} null = coordinator/
     *  lane context (no region claim — the 2026-07-13 reroute bug's signature);
     *  the logged stack carries the mutation source (random tick vs neighbor
     *  update vs mod, and whether lithium selected the block). */
    private static void recordCrossDomain(BlockPos pos, RegionWorkerThread w,
                                          Region claimed, Region target,
                                          EpochDomain mine, EpochDomain theirs) {
        long n = CROSS_DOMAIN.incrementAndGet();
        Throwable origin = new Throwable("write origin");
        StringBuilder culprit = new StringBuilder();
        CrossDomainOrigin cls = classify(origin.getStackTrace(), mine, theirs, culprit);
        if (n <= MAX_STACK_LOGS) {
            String exec = claimed != null
                    ? "CLAIMED region " + Long.toHexString(claimed.gridKey)
                        + " island#" + (claimed.islandId)
                        + (mine.owner != null
                            ? " domain=island#" + mine.owner.id + "@e" + mine.epoch
                                + " L" + mine.owner.logicalTick
                            : " domain=global@e" + mine.epoch)
                    : "NO CLAIM (lane/coordinator)"
                        + (mine.owner != null
                            ? " domain=island#" + mine.owner.id + "@e" + mine.epoch
                                + " L" + mine.owner.logicalTick
                            : " domain=global@e" + mine.epoch);
            String tgt = target != null
                    ? "region " + Long.toHexString(target.gridKey)
                        + " island#" + target.islandId
                        + (theirs.owner != null
                            ? " domain=island#" + theirs.owner.id + "@e" + theirs.epoch
                                + " L" + theirs.owner.logicalTick
                                + " unitClaim=" + theirs.owner.unitClaim.get()
                            : " domain=global@e" + theirs.epoch)
                    : "region <unmapped>";
            LOGGER.error("[region-access] CROSS-DOMAIN write @ {} (chunk [{}, {}]) — target {} vs executing {} on {} "
                    + "(currentRegion={} currentDomain={}) — origin={}{} — the execution timeline does not own "
                    + "the target; report this full block",
                    pos.toShortString(), pos.getX() >> 4, pos.getZ() >> 4,
                    tgt, exec, w.getName(),
                    w.currentRegion == null ? "-" : Long.toHexString(w.currentRegion.gridKey),
                    w.currentDomain == null ? "-"
                            : (w.currentDomain.owner == null ? "global"
                                : "island#" + w.currentDomain.owner.id),
                    cls, culprit.length() == 0 ? "" : " culprit=" + culprit,
                    origin);
        }
        // This setBlock has not entered its native body. Its enclosing producer
        // may already have performed other effects, so neither a later replay
        // nor an async-to-sync retry can repair the ownership violation.
        FAIL_CLOSED_LATCHED = true;
        FabroliaConfig.asyncFailClosedTripped = true;
        String detail = switch (cls) {
            case MOD_CROSS_DOMAIN_WRITE -> "mod cross-domain writer " + culprit;
            case TOPOLOGY_FRONTIER_VIOLATION, STALE_OWNERSHIP_CONTEXT,
                    VANILLA_SCHEDULER_BUG -> "Fabrolia scheduler/topology defect " + cls;
            default -> "unclassified cross-domain writer";
        };
        IllegalStateException failure = new IllegalStateException(
                "cross-domain live write at " + pos.toShortString() + ": " + detail,
                origin);
        IslandCommands.publishAndPropagate(failure);
    }

    /** TEST-ONLY containment injection (/fabrolia regions debug tripfailclosed,
     *  perm 4): drives the exact fail-closed latch + marker + live-collapse
     *  path without a real violation, so containment liveness (rate ladders,
     *  fences, saves AFTER a trip) is rig-testable. Clearly logged as a drill. */
    public static void debugTrip() {
        LOGGER.error("[region-access] DEBUG fail-closed drill requested by operator — no real violation.");
        if (FabroliaConfig.asyncFailClosed && !FAIL_CLOSED_LATCHED) {
            FAIL_CLOSED_LATCHED = true;
            FabroliaConfig.asyncFailClosedTripped = true;
            LOGGER.error("[region-access] FAIL-CLOSED TRIPPED (DEBUG-DRILL): containment collapses async to "
                    + "SYNC at the next tick head for THIS session; a restart re-arms async "
                    + "(no state is persisted — 2026-07-18 policy).");
        }
    }

    public static String status() {
        return "regionAccess in=" + IN_REGION.get()
                + " cross=" + CROSS_REGION.get()
                + " crossUNSAFE=" + CROSS_UNSAFE.get()
                + " crossDOMAIN=" + CROSS_DOMAIN.get()
                + " offWorker=" + OFF_WORKER.get();
    }
}
