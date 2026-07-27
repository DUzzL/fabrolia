package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gravity-block cross-dimension TRANSFER TRANSACTIONS (2026-07-15 rework of
 * the 2026-07-14 counter shim, per production feedback: the old inFlightIds
 * set retained every committed identity below its 4096 prune threshold —
 * committed is TERMINAL and must leave active storage immediately — and
 * sourceDupeTicks had no hook at all).
 *
 * <p>The exactly-once unit is the COMPLETE vanilla dupe activation: one
 * destination commit AND one source-side continuation outcome (landed
 * placement, item drop, or the vanilla-legal none when the original was
 * mid-air). A transaction leaves ACTIVE the moment its source-side outcome
 * resolves; a bounded completed-history rejects genuine scheduler replays
 * without ever blocking a later legitimate activation (fresh entities have
 * fresh UUIDs; the guard keys the entity lifecycle, not the source position).
 */
public final class SandDuperDiag {

    public enum TxState {
        TRANSFER_REQUESTED,
        DESTINATION_COMMITTED,
        SOURCE_CONTINUATION_PENDING,
        COMPLETE_PLACED,      // source-side landed: block re-placed (the dupe)
        COMPLETE_DROPPED,     // source-side landed on non-placeable: item drop
        COMPLETE_NO_CONTINUATION, // original was mid-air: vanilla places nothing
        ABORTED
    }

    public static final class Tx {
        public final long id = TX_IDS.incrementAndGet();
        public final UUID entity;
        public final long srcPos;
        public final long startNanos = System.nanoTime();
        public volatile TxState state = TxState.TRANSFER_REQUESTED;

        Tx(UUID entity, long srcPos) {
            this.entity = entity;
            this.srcPos = srcPos;
        }
    }

    private static final AtomicLong TX_IDS = new AtomicLong();
    /** ACTIVE transactions only — incomplete transfers. Committed/aborted
     *  entries retire immediately to the bounded history below. */
    private static final ConcurrentHashMap<UUID, Tx> ACTIVE = new ConcurrentHashMap<>();
    /** Bounded completed history (duplicate-replay rejection only). */
    private static final ArrayDeque<UUID> HISTORY = new ArrayDeque<>();
    private static final ConcurrentHashMap<UUID, Long> HISTORY_SET = new ConcurrentHashMap<>();
    private static final int HISTORY_CAP = 1024;

    // counters (per the required semantics)
    public static final AtomicLong FALL_CREATED = new AtomicLong();
    public static final AtomicLong TRANSFER_REQUESTS = new AtomicLong();
    public static final AtomicLong DESTINATION_COMMITS = new AtomicLong();
    public static final AtomicLong TRANSFER_NULL = new AtomicLong();
    public static final AtomicLong SRC_CONT_PENDING = new AtomicLong();
    public static final AtomicLong SRC_CONT_PLACED = new AtomicLong();
    public static final AtomicLong SRC_CONT_DROPPED = new AtomicLong();
    public static final AtomicLong SRC_CONT_NONE = new AtomicLong();
    public static final AtomicLong SRC_CONT_LOST = new AtomicLong();
    public static final AtomicLong DUPLICATE_REJECTED = new AtomicLong();
    public static final AtomicLong COMPLETED = new AtomicLong();
    public static final AtomicLong ABORTED = new AtomicLong();
    public static final AtomicLong STUCK = new AtomicLong();
    /** Deferred source-side landing writes verified after replay; a failed
     *  replay conserves matter by popping the block item (vanilla fallback). */
    public static final AtomicLong SOURCE_DEFER_VERIFIED = new AtomicLong();
    public static final AtomicLong SOURCE_DEFER_CORRECTED = new AtomicLong();

    // ------------------------------------------------------------------
    // WATCHED PERMANENT SOURCE POSITIONS (2026-07-15 production A/B: async
    // OFF correct, async ON loses a permanent source block in ~5 cycles).
    // Every write to a watched position is recorded with thread/lane, island
    // domain + logical tick, and an activation generation (bumped when the
    // position's block is removed to spawn a falling entity). The FIRST
    // transition to a non-expected stable state freezes the ring (no
    // overwrite of first-divergence evidence) and emits ONE ERROR.
    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // RUNTIME FEATURE KILL-SWITCHES (2026-07-15 regression bisect): the
    // production machine was stable BEFORE the duper-investigation changes
    // and dies in ~5 cycles after — each subsystem can be disabled live so
    // one short production run per flag isolates the regressing change.
    // `sandduper feature <name> <on|off>`, `sandduper feature list`.
    // ------------------------------------------------------------------
    public static volatile boolean FEAT_TRANSFER_GUARD = true;   // teleport exactly-once guard + tx tracking
    public static volatile boolean FEAT_XDIM_STAGING = true;     // CrossDimArrivals cohort staging (off = old inline vanilla)
    public static volatile boolean FEAT_DEFER_VERIFY = true;     // deferred-landing verification/correction
    public static volatile boolean FEAT_HANDLING_TICK = true;    // isHandlingTick domain redirect
    public static volatile boolean FEAT_PLACEMENT_WRAP = true;   // landing-wrap counters (off = pure original.call)
    /** OFF = island-owned block events run in MAIN's vanilla slot instead of
     *  island units (pre-increment-3 behavior) — THE missing bisect switch:
     *  worker-side events are the only subsystem that reads piston state off
     *  main (isHandlingTick family). Costs island-cadence event parity while
     *  off; diagnostic only. */
    public static volatile boolean FEAT_ISLAND_EVENTS = true;

    public static String feature(String name, String val) {
        boolean on = "on".equalsIgnoreCase(val);
        switch (name.toLowerCase()) {
            case "guard" -> FEAT_TRANSFER_GUARD = on;
            case "staging" -> FEAT_XDIM_STAGING = on;
            case "verify" -> FEAT_DEFER_VERIFY = on;
            case "handlingtick" -> FEAT_HANDLING_TICK = on;
            case "placementwrap" -> FEAT_PLACEMENT_WRAP = on;
            case "islandevents" -> FEAT_ISLAND_EVENTS = on;
            case "allduperfixes" -> {
                FEAT_TRANSFER_GUARD = on;
                FEAT_XDIM_STAGING = on;
                FEAT_DEFER_VERIFY = on;
                FEAT_PLACEMENT_WRAP = on;
            }
            default -> {
                return "unknown feature '" + name + "' — " + featureList();
            }
        }
        return "feature " + name + " -> " + (on ? "ON" : "OFF") + " | " + featureList();
    }

    public static String featureList() {
        return "features: guard=" + FEAT_TRANSFER_GUARD
                + " staging=" + FEAT_XDIM_STAGING
                + " verify=" + FEAT_DEFER_VERIFY
                + " handlingtick=" + FEAT_HANDLING_TICK
                + " placementwrap=" + FEAT_PLACEMENT_WRAP
                + " islandevents=" + FEAT_ISLAND_EVENTS + " (OFF = events on main, pre-increment-3)"
                + " | NOTE handlingtick is a FIX toggle (OFF = restore the BROKEN pre-03:36 coin flip)"
                + " (allduperfixes sets guard+staging+verify+placementwrap)";
    }

    public static volatile boolean watching;
    private static final ConcurrentHashMap<Long, WatchedPos> WATCHED = new ConcurrentHashMap<>();
    public static volatile boolean frozen;

    public enum WatchKind { SOURCE, DYNAMIC, DISCOVER }

    public static final class WatchedPos {
        final long pos;
        volatile WatchKind kind;
        volatile long activationGen;
        volatile String expected = "minecraft:sand";
        volatile long lastEventNanos;
        /** discovery/lifecycle classification evidence */
        volatile boolean sawExpected;
        volatile boolean sawPiston;
        volatile boolean sawFall;
        volatile boolean mismatchWarned;
        final ArrayDeque<String> ring = new ArrayDeque<>();

        WatchedPos(long pos, WatchKind kind) {
            this.pos = pos;
            this.kind = kind;
        }

        synchronized void event(String e) {
            lastEventNanos = System.nanoTime();
            if (frozen) return;
            ring.addLast(e);
            while (ring.size() > 96) ring.pollFirst();
        }
    }

    /** Legacy cuboid registration — now DISCOVER kind (event recording +
     *  lifecycle classification, loss-eligible only after a REAL activation
     *  is observed at a position: sand seen + FALL_ACTIVATED). The first
     *  production capture registered a piston-sweep cell as "permanent
     *  sand" and false-froze on the post-shutdown air phase — a cuboid
     *  cannot be assumed to be all permanent sources. */
    public static String watch(int x1, int y1, int z1, int x2, int y2, int z2) {
        return register(x1, y1, z1, x2, y2, z2, WatchKind.DISCOVER);
    }

    public static String watchSource(int x, int y, int z) {
        return register(x, y, z, x, y, z, WatchKind.SOURCE);
    }

    public static String watchDynamic(int x1, int y1, int z1, int x2, int y2, int z2) {
        return register(x1, y1, z1, x2, y2, z2, WatchKind.DYNAMIC);
    }

    private static String register(int x1, int y1, int z1, int x2, int y2, int z2, WatchKind kind) {
        int n = 0;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    if (WATCHED.size() >= 256) return "watch limit (256) reached; " + n + " added";
                    WATCHED.putIfAbsent(BlockPos.asLong(x, y, z),
                            new WatchedPos(BlockPos.asLong(x, y, z), kind));
                    n++;
                }
        watching = !WATCHED.isEmpty();
        frozen = false;
        return "watching " + WATCHED.size() + " positions (" + n + " added as " + kind + "); "
                + "loss-eligible = SOURCE, or DISCOVER after a real sand+fall lifecycle is observed; "
                + "verify with 'sandduper discoverreport' before starting the machine";
    }

    /** Discovery classification for operator confirmation. */
    public static String discoverReport() {
        StringBuilder sb = new StringBuilder(256);
        int src = 0, piston = 0, other = 0;
        for (WatchedPos w : WATCHED.values()) {
            BlockPos p = BlockPos.of(w.pos);
            String cls;
            if (w.sawExpected && w.sawFall) { cls = "SOURCE-CANDIDATE"; src++; }
            else if (w.sawPiston) { cls = "PISTON-PATH"; piston++; }
            else if (w.sawExpected) { cls = "SAND-SEEN(no fall yet)"; other++; }
            else if (w.lastEventNanos != 0) { cls = "ACTIVE-OTHER"; other++; }
            else { cls = "SILENT"; other++; }
            if (sb.length() < 1800) {
                sb.append("\n [").append(p.getX()).append(',').append(p.getY()).append(',')
                  .append(p.getZ()).append("] ").append(cls)
                  .append(" gen=").append(w.activationGen);
            }
        }
        return "discovery: sourceCandidates=" + src + " pistonPath=" + piston + " other=" + other + sb;
    }

    /** Block-event execution at a watched position (piston timing trace). */
    public static void onWatchedBlockEvent(long posLong, String desc) {
        WatchedPos w = WATCHED.get(posLong);
        if (w != null) {
            w.event("gen" + w.activationGen + " BLOCK_EVENT " + desc
                    + " t=" + System.nanoTime() / 1_000_000 + "ms");
        }
    }

    public static String watchClear() {
        WATCHED.clear();
        watching = false;
        frozen = false;
        return "watch cleared";
    }

    /** HOT-PATH HOOK (gated by {@link #watching}): every block write to a
     *  watched position, from LevelMixin's setBlock probe. */
    public static void onWatchedWrite(long posLong, String newState, boolean deferredPath) {
        WatchedPos w = WATCHED.get(posLong);
        if (w == null) return;
        String lane;
        Thread t = Thread.currentThread();
        String domain = "-";
        long lTick = -1;
        if (t instanceof RegionWorkerThread rwt) {
            lane = "worker:" + t.getName();
            var d = rwt.currentRegion != null ? rwt.currentRegion.domain : rwt.currentDomain;
            if (d != null && d.owner != null) {
                domain = "island#" + d.owner.id + "/g" + d.owner.gen;
                lTick = d.owner.logicalTick;
            } else if (d != null) {
                domain = "global";
            }
        } else {
            lane = "main";
        }
        if (newState.equals(w.expected)) {
            w.sawExpected = true;
        } else if (newState.contains("piston")) {
            w.sawPiston = true;
        }
        w.event("gen" + w.activationGen + " write=" + newState + (deferredPath ? " [DEFERRED]" : "")
                + " lane=" + lane + " dom=" + domain + " L=" + lTick
                + " t=" + System.nanoTime() / 1_000_000 + "ms");
        lineageWrite(posLong, newState);
    }

    /** Fall-entity creation from a watched position = activation start. */
    public static void onWatchedFall(long posLong) {
        WatchedPos w = WATCHED.get(posLong);
        if (w == null) return;
        w.activationGen++;
        w.sawFall = true;
        w.event("gen" + w.activationGen + " FALL_ACTIVATED (source removed for falling entity)");
    }

    /** Main, ~1/s: loss = the position is AIR and the machine has gone
     *  SILENT there — no write/fall event for ≥10s despite events having
     *  flowed before. Event-staleness is machine-cadence-proof: a healthy
     *  duper's duty cycle may be air-dominant (the first detector design
     *  false-froze on exactly that), but a healthy machine WRITES the
     *  position constantly; only a lost source is air AND eventless. */
    public static void watchTick(net.minecraft.server.MinecraftServer server) {
        if (!watching || frozen) return;
        long now = System.nanoTime();
        // machine-alive witness: the newest event across the whole watch set —
        // a manual shutdown silences EVERYTHING and must never classify as a
        // loss (the first production capture froze on exactly that).
        long newest = 0;
        for (WatchedPos w : WATCHED.values()) {
            newest = Math.max(newest, w.lastEventNanos);
        }
        boolean machineAlive = newest != 0 && now - newest < 5_000_000_000L;
        for (WatchedPos w : WATCHED.values()) {
            if (w.lastEventNanos == 0) continue; // never active here
            boolean lifecycleProven = w.sawExpected && w.sawFall && w.activationGen > 0;
            if (w.kind == WatchKind.DYNAMIC) continue; // legal sweep positions
            if (w.kind == WatchKind.DISCOVER && !lifecycleProven) {
                continue; // classification only — never loss-eligible
            }
            if (w.kind == WatchKind.SOURCE && !lifecycleProven && !w.mismatchWarned
                    && now - w.lastEventNanos > 10_000_000_000L) {
                w.mismatchWarned = true;
                org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").warn(
                        "[sandduper-watch] WATCH_CONFIGURATION_MISMATCH: {} was registered as a "
                        + "permanent source but no sand state or falling activation was ever observed "
                        + "there (gen=0) — likely a piston-movement position; reclassified DYNAMIC. "
                        + "Use 'sandduper discoverreport' to find the real source positions.",
                        BlockPos.of(w.pos).toShortString());
                w.kind = WatchKind.DYNAMIC;
                continue;
            }
            if (!lifecycleProven || !machineAlive) {
                continue;
            }
            BlockPos pos = BlockPos.of(w.pos);
            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                if (!lvl.isLoaded(pos)) continue;
                var st = lvl.getBlockState(pos);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                if ("minecraft:air".equals(id) && now - w.lastEventNanos > 10_000_000_000L) {
                    // REAL loss: this position ran genuine activations (sand +
                    // FALL), the machine is STILL RUNNING elsewhere, yet this
                    // position has been dead air for 10s.
                    frozen = true;
                    StringBuilder sb = new StringBuilder(512);
                    synchronized (w) {
                        for (String e : w.ring) sb.append("\n  ").append(e);
                    }
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                            "[sandduper-watch] PERMANENT SOURCE POSITION LOST: {} expected={} gen={} "
                            + "(air + position silent {}s while the machine still runs) — ring FROZEN "
                            + "(first-divergence evidence):{}",
                            pos.toShortString(), w.expected, w.activationGen,
                            (now - w.lastEventNanos) / 1_000_000_000L, sb);
                    return;
                }
                break;
            }
        }
    }

    public static String watchStatus() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("watch: positions=").append(WATCHED.size())
          .append(" frozen=").append(frozen);
        long maxGen = 0;
        for (WatchedPos w : WATCHED.values()) maxGen = Math.max(maxGen, w.activationGen);
        sb.append(" maxActivationGen=").append(maxGen);
        return sb.toString();
    }

    public static String watchDump(int x, int y, int z) {
        WatchedPos w = WATCHED.get(BlockPos.asLong(x, y, z));
        if (w == null) return "not watched: [" + x + "," + y + "," + z + "]";
        StringBuilder sb = new StringBuilder("watch [" + x + "," + y + "," + z + "] gen=" + w.activationGen
                + " frozen=" + frozen + ":");
        synchronized (w) {
            for (String e : w.ring) sb.append("\n ").append(e);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 12-MEMBER LIFECYCLE TRACER (2026-07-15): every falling block spawned
    // inside the trace area is a MEMBER of an ACTIVATION (spawns within
    // 100ms group). Every tick, transfer, continuation and terminal state
    // is recorded per member — the End copy PRESERVES the source UUID, so
    // one identity map follows both halves of the dupe. Unbounded until
    // terminal (cap 512 events/member as a belt); last 3 activations kept
    // in full; freeze at the first member whose matter vanishes without a
    // terminal record while the machine is still alive.
    // ------------------------------------------------------------------
    public static volatile boolean traceActive;
    private static volatile int tMinX, tMinY, tMinZ, tMaxX, tMaxY, tMaxZ;
    private static volatile boolean traceAreaSet;
    private static final AtomicLong ACTIVATION_IDS = new AtomicLong();
    private static volatile Activation currentActivation;
    private static final ArrayDeque<Activation> RECENT = new ArrayDeque<>();
    private static final ConcurrentHashMap<UUID, Member> MEMBER_BY_UUID = new ConcurrentHashMap<>();

    public static final class Member {
        final long activationId;
        final int memberId;
        final long srcPos;
        final UUID uuid;
        volatile Lineage lineage; // deterministic provenance: set at consume
        volatile String stage = "SPAWNED";
        volatile long lastSeenNanos = System.nanoTime();
        final ArrayList<String> log = new ArrayList<>(64);

        Member(long a, int m, long srcPos, UUID uuid) {
            this.activationId = a;
            this.memberId = m;
            this.srcPos = srcPos;
            this.uuid = uuid;
        }

        synchronized void rec(String e) {
            lastSeenNanos = System.nanoTime();
            if (log.size() < 512) {
                log.add(e);
            }
        }
    }

    public static final class Activation {
        final long id = ACTIVATION_IDS.incrementAndGet();
        final long startNanos = System.nanoTime();
        final ArrayList<Member> members = new ArrayList<>(12);
    }

    public static String traceArea(int x1, int y1, int z1, int x2, int y2, int z2) {
        tMinX = Math.min(x1, x2); tMaxX = Math.max(x1, x2);
        tMinY = Math.min(y1, y2); tMaxY = Math.max(y1, y2);
        tMinZ = Math.min(z1, z2); tMaxZ = Math.max(z1, z2);
        traceAreaSet = true;
        // auto-arm block-write recording over the same volume (DYNAMIC —
        // no coordinate guessing, no false loss classification)
        watchDynamic(x1, y1, z1, x2, y2, z2);
        return "trace area set [" + tMinX + "," + tMinY + "," + tMinZ + "]..[" + tMaxX + "," + tMaxY + "," + tMaxZ
                + "] + block-write watch armed; run 'sandduper trace start'";
    }

    public static boolean inTraceArea(BlockPos p) {
        return traceAreaSet && p.getX() >= tMinX && p.getX() <= tMaxX
                && p.getY() >= tMinY && p.getY() <= tMaxY
                && p.getZ() >= tMinZ && p.getZ() <= tMaxZ;
    }

    public static String traceStart() {
        if (!traceAreaSet) return "set 'sandduper tracearea' first";
        traceActive = true;
        frozen = false;
        synchronized (RECENT) { RECENT.clear(); }
        MEMBER_BY_UUID.clear();
        currentActivation = null;
        synchronized (SandDuperDiag.class) {
            LINEAGES.clear();
            LINEAGE_BY_POS.clear();
        }
        return "member trace ACTIVE — every falling block spawned in the area is tracked "
                + "through fall/ticks/transfer/continuation to a terminal state; freezes at first divergence";
    }

    public static String traceStop() {
        traceActive = false;
        return "member trace stopped (data retained; 'sandduper trace dump')";
    }

    /** fall() inside the area: assign activation/member identity. */
    public static void traceFall(UUID uuid, BlockPos pos) {
        if (!traceActive || frozen) return;
        Activation a = currentActivation;
        long now = System.nanoTime();
        if (a == null || now - a.startNanos > 100_000_000L) {
            a = new Activation();
            currentActivation = a;
            synchronized (RECENT) {
                RECENT.addLast(a);
                while (RECENT.size() > 4) {
                    for (Member m : RECENT.peekFirst().members) MEMBER_BY_UUID.remove(m.uuid, m);
                    RECENT.pollFirst();
                }
            }
        }
        Member m;
        synchronized (a.members) {
            m = new Member(a.id, a.members.size(), pos.asLong(), uuid);
            a.members.add(m);
        }
        MEMBER_BY_UUID.put(uuid, m);
        m.rec("a" + m.activationId + "/m" + m.memberId + " FALL @" + pos.toShortString()
                + " " + ctx());
        lineageConsume(pos.asLong(), m);
    }

    /** every FallingBlockEntity tick for a tracked member (both halves). */
    public static void traceTick(UUID uuid, net.minecraft.world.entity.Entity e) {
        if (!traceActive || frozen) return;
        Member m = MEMBER_BY_UUID.get(uuid);
        if (m == null) return;
        m.rec(String.format(java.util.Locale.ROOT,
                "TICK dim=%s x=%.3f y=%.3f z=%.3f vel=%.3f ground=%s removed=%s %s",
                e.level().dimension().identifier().getPath(), e.getX(), e.getY(), e.getZ(),
                e.getDeltaMovement().y, e.onGround(), e.isRemoved(), ctx()));
        if (e.isRemoved() && !m.stage.startsWith("TERMINAL")) {
            // natural end of either half (End copy landed/dropped, original
            // discarded post-teleport): terminal, not vanished matter.
            m.stage = "TERMINAL REMOVED(" + e.level().dimension().identifier().getPath() + ")";
            m.rec(m.stage + " " + ctx());
        }
    }

    public static void traceEvent(UUID uuid, String event) {
        if (!traceActive) return;
        Member m = MEMBER_BY_UUID.get(uuid);
        if (m != null) {
            m.rec(event + " " + ctx());
            if (event.startsWith("TERMINAL")) {
                m.stage = event;
            }
        }
    }

    private static String ctx() {
        Thread t = Thread.currentThread();
        if (t instanceof RegionWorkerThread w) {
            var d = w.currentRegion != null ? w.currentRegion.domain : w.currentDomain;
            if (d != null && d.owner != null) {
                return "[worker " + d.owner.id + "/g" + d.owner.gen + " L" + d.owner.logicalTick + "]";
            }
            return "[worker global]";
        }
        return "[main]";
    }

    /** ~1/s: a live member whose entity went silent without a terminal
     *  record while the machine still runs = matter vanished → freeze. */
    public static void traceLivenessTick() {
        if (!traceActive || frozen) return;
        long now = System.nanoTime();
        long newest = 0;
        for (Member m : MEMBER_BY_UUID.values()) newest = Math.max(newest, m.lastSeenNanos);
        boolean alive = newest != 0 && now - newest < 5_000_000_000L;
        lineageWatch(now, alive);
        if (!alive) return;
        for (Member m : MEMBER_BY_UUID.values()) {
            if (!m.stage.startsWith("TERMINAL") && now - m.lastSeenNanos > 8_000_000_000L) {
                frozen = true;
                org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                        "[sandduper-trace] MEMBER MATTER VANISHED: a{}/m{} src={} stage={} — no event for {}s "
                        + "while the machine runs. Trace FROZEN; run 'sandduper trace dump'.",
                        m.activationId, m.memberId, BlockPos.of(m.srcPos).toShortString(), m.stage,
                        (now - m.lastSeenNanos) / 1_000_000_000L);
                return;
            }
        }
    }

    public static String traceStatus() {
        Activation a = currentActivation;
        StringBuilder sb = new StringBuilder(192);
        sb.append("trace: active=").append(traceActive).append(" frozen=").append(frozen)
          .append(" activation=").append(a == null ? "-" : a.id)
          .append(" liveMembers=").append(MEMBER_BY_UUID.size());
        if (a != null) {
            synchronized (a.members) {
                sb.append(" currentMembers=").append(a.members.size());
                for (Member m : a.members) {
                    sb.append("\n  m").append(m.memberId).append(' ').append(m.stage)
                      .append(" events=").append(m.log.size());
                }
            }
        }
        return sb.toString();
    }

    public static String traceDump() {
        StringBuilder sb = new StringBuilder(4096);
        synchronized (RECENT) {
            for (Activation a : RECENT) {
                sb.append("\n== activation ").append(a.id).append(" members=").append(a.members.size());
                synchronized (a.members) {
                    for (Member m : a.members) {
                        sb.append("\n -- m").append(m.memberId).append(" [").append(m.stage).append(']');
                        synchronized (m) {
                            for (String e : m.log) sb.append("\n    ").append(e);
                        }
                    }
                }
            }
        }
        String out = sb.length() == 0 ? "no trace data" : sb.toString();
        // full dump also to the log for capture
        org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").info("[sandduper-trace dump]{}", out);
        return out.length() > 3000 ? out.substring(0, 3000) + "\n... (full dump written to server log)" : out;
    }

    // ------------------------------------------------------------------
    // SOURCE LINEAGES (2026-07-16): SOURCE_PLACED is NOT terminal — the
    // permanent sand is reused across cycles, so activation N's placed
    // block must be followed through piston transit into activation N+1's
    // fall. Each machine slot is one continuous lineage; a lineage whose
    // matter has no owner (block / moving-piston transit / next member)
    // for >1s while the machine runs = the physical loss → immediate
    // freeze with the full lineage history and the inter-activation
    // window (the area's block-write rings hold every write).
    // ------------------------------------------------------------------
    public static final class Lineage {
        final int slotId;
        volatile long currentPos;
        volatile String state;           // SOURCE_PLACED_PENDING / SOURCE_STABLE /
                                          // SOURCE_MOVED_BY_PISTON(in-transit) /
                                          // SOURCE_CONSUMED_BY_NEXT / SOURCE_LOST
        volatile long lastActivation;
        volatile long lastOwnedNanos = System.nanoTime();
        final ArrayList<String> log = new ArrayList<>(64);

        Lineage(int slotId, long pos) {
            this.slotId = slotId;
            this.currentPos = pos;
        }

        synchronized void rec(String e) {
            if (log.size() < 512) log.add(e);
        }
    }

    private static final ConcurrentHashMap<Long, Lineage> LINEAGE_BY_POS = new ConcurrentHashMap<>();
    private static final ArrayList<Lineage> LINEAGES = new ArrayList<>(12);

    private static Lineage lineageAt(long pos) {
        return LINEAGE_BY_POS.get(pos);
    }

    private static synchronized Lineage newLineage(long pos, String seed) {
        Lineage l = new Lineage(LINEAGES.size(), pos);
        LINEAGES.add(l);
        LINEAGE_BY_POS.put(pos, l);
        l.rec("slot" + l.slotId + " SEEDED " + seed + " @" + BlockPos.of(pos).toShortString());
        return l;
    }

    /** Source continuation placed its block: the PLACING MEMBER's lineage is
     *  the deterministic owner (entity provenance) — never create a second
     *  lineage for a slot the matter already owns (the 24-lineage bug: the
     *  placement cell differed from the consumed cell after a piston lift,
     *  and position-keyed binding forked every slot). */
    public static void lineagePlaced(UUID placer, long pos) {
        if (!traceActive) return;
        Member m = placer == null ? null : MEMBER_BY_UUID.get(placer);
        Lineage l = m != null ? m.lineage : null;
        if (l == null) {
            l = lineageAt(pos);
        }
        if (l == null) {
            if (!LINEAGES.isEmpty()) {
                frozen = true;
                org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                        "[sandduper-lineage] TRACE_IDENTITY_AMBIGUOUS: SOURCE_PLACED @{} with no owning "
                        + "member lineage ({} lineages exist) — FROZEN",
                        BlockPos.of(pos).toShortString(), LINEAGES.size());
                return;
            }
            l = newLineage(pos, "by SOURCE_PLACED");
        }
        synchronized (SandDuperDiag.class) {
            LINEAGE_BY_POS.remove(l.currentPos, l);
            l.currentPos = pos;
            LINEAGE_BY_POS.put(pos, l);
        }
        l.state = "SOURCE_PLACED_PENDING_VERIFICATION";
        l.lastOwnedNanos = System.nanoTime();
        l.rec("a" + l.lastActivation + " SOURCE_PLACED @" + BlockPos.of(pos).toShortString() + " " + ctx());
    }

    /** Every trace-area block write feeds the lineage machine (called from
     *  onWatchedWrite AFTER ring recording). */
    public static void lineageWrite(long pos, String newState) {
        if (!traceActive || frozen) return;
        Lineage l = lineageAt(pos);
        if (l != null) {
            if (newState.contains("air") || newState.contains("moving_piston") || newState.contains("piston")) {
                // matter departed this cell: piston transit (or consumption —
                // traceFall/consume below re-owns it). The 1s transit grace
                // covers 2-3gt piston movement many times over.
                if (l.state.startsWith("SOURCE_PLACED") || "SOURCE_STABLE".equals(l.state)) {
                    l.state = "SOURCE_MOVED_BY_PISTON(in-transit)";
                    l.rec("gen a" + l.lastActivation + " DEPARTED cell -> " + newState + " " + ctx());
                }
            } else if (newState.equals("minecraft:sand")) {
                l.state = "SOURCE_STABLE";
                l.lastOwnedNanos = System.nanoTime();
                l.rec("a" + l.lastActivation + " re-owned as block @" + BlockPos.of(pos).toShortString() + " " + ctx());
            }
            return;
        }
        // sand arriving at an untracked cell: piston move completing —
        // attach to the nearest in-transit lineage (<=2 blocks manhattan)
        if (newState.equals("minecraft:sand")) {
            BlockPos p = BlockPos.of(pos);
            Lineage best = null;
            int bestD = 3;
            synchronized (SandDuperDiag.class) {
                for (Lineage cand : LINEAGES) {
                    if (!cand.state.startsWith("SOURCE_MOVED")) continue;
                    BlockPos cp = BlockPos.of(cand.currentPos);
                    int d = Math.abs(cp.getX() - p.getX()) + Math.abs(cp.getY() - p.getY()) + Math.abs(cp.getZ() - p.getZ());
                    if (d < bestD) { bestD = d; best = cand; }
                }
                if (best != null) {
                    LINEAGE_BY_POS.remove(best.currentPos, best);
                    best.currentPos = pos;
                    LINEAGE_BY_POS.put(pos, best);
                    best.state = "SOURCE_STABLE";
                    best.lastOwnedNanos = System.nanoTime();
                    best.rec("a" + best.lastActivation + " ARRIVED (piston move) @" + p.toShortString() + " " + ctx());
                }
            }
        }
    }

    /** A fall consumed a block: link the lineage into the new activation. */
    public static void lineageConsume(long pos, Member m) {
        Lineage l = lineageAt(pos);
        if (l == null) {
            // no lineage at this cell: only legal during discovery (first
            // cycles). Afterwards a fall from an untracked cell means the
            // provenance chain broke — that is itself the divergence.
            if (m.activationId > 3 && !LINEAGES.isEmpty()) {
                frozen = true;
                org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                        "[sandduper-lineage] TRACE_IDENTITY_AMBIGUOUS: fall a{}/m{} @{} has no known "
                        + "predecessor lineage ({} lineages exist) — provenance broke; FROZEN",
                        m.activationId, m.memberId, BlockPos.of(pos).toShortString(), LINEAGES.size());
                return;
            }
            l = newLineage(pos, "by first FALL a" + m.activationId + "/m" + m.memberId);
        }
        if ("SOURCE_CONSUMED_BY_NEXT".equals(l.state) && l.lastActivation == m.activationId) {
            frozen = true;
            org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                    "[sandduper-lineage] DOUBLE CONSUME: slot{} consumed twice in activation {} — FROZEN",
                    l.slotId, m.activationId);
        }
        long prevActivation = l.lastActivation;
        l.state = "SOURCE_CONSUMED_BY_NEXT";
        l.lastActivation = m.activationId;
        l.lastOwnedNanos = System.nanoTime();
        m.lineage = l; // deterministic provenance: the entity IS this slot's matter now
        l.rec("HANDOFF a" + prevActivation + " -> a" + m.activationId + "/m" + m.memberId + " FALL " + ctx());
        m.rec("LINEAGE slot" + l.slotId + " handed off (a" + prevActivation + " -> a" + m.activationId + ")");
    }

    /** Immediate conservation watchdog (called every second + cheap). A
     *  lineage unowned >1s while the machine runs = the physical loss. */
    private static void lineageWatch(long now, boolean machineAlive) {
        if (!machineAlive) return;
        synchronized (SandDuperDiag.class) {
            for (Lineage l : LINEAGES) {
                if (l.state.startsWith("SOURCE_MOVED") && now - l.lastOwnedNanos > 1_000_000_000L) {
                    l.state = "SOURCE_LOST";
                    frozen = true;
                    StringBuilder sb = new StringBuilder(512);
                    synchronized (l) {
                        for (String e : l.log) sb.append("\n  ").append(e);
                    }
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                            "[sandduper-lineage] SOURCE LINEAGE LOST: slot{} last@{} lastActivation={} — no owner "
                            + "(block/moving-piston/next-member) for 1s while machine runs. FROZEN. Lineage:{}\n"
                            + "Inter-activation window = block-write rings (sandduper watchdump on the positions above).",
                            l.slotId, BlockPos.of(l.currentPos).toShortString(), l.lastActivation, sb);
                    return;
                }
            }
        }
    }

    public static String lineageTable() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("lineages: ").append(LINEAGES.size());
        synchronized (SandDuperDiag.class) {
            for (Lineage l : LINEAGES) {
                sb.append("\n slot").append(l.slotId)
                  .append(" @").append(BlockPos.of(l.currentPos).toShortString())
                  .append(' ').append(l.state)
                  .append(" lastAct=").append(l.lastActivation)
                  .append(" events=").append(l.log.size());
            }
        }
        return sb.toString();
    }

    public static String lineageDump(int slot) {
        synchronized (SandDuperDiag.class) {
            for (Lineage l : LINEAGES) {
                if (l.slotId == slot) {
                    StringBuilder sb = new StringBuilder("slot" + slot + " [" + l.state + "]:");
                    synchronized (l) {
                        for (String e : l.log) sb.append("\n ").append(e);
                    }
                    org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").info("[sandduper-lineage dump]{}", sb);
                    return sb.length() > 3000 ? sb.substring(0, 3000) + "\n... (full in server log)" : sb.toString();
                }
            }
        }
        return "no such slot";
    }

    /** trace: armed source positions (block pos packed) → ring of events. */
    private static final ConcurrentHashMap<Long, ArrayDeque<String>> TRACES = new ConcurrentHashMap<>();

    private SandDuperDiag() {}

    /** teleport() HEAD for a cross-dimension move. @return false = this exact
     *  entity lifecycle already ran its transfer (scheduler replay) — no-op. */
    public static boolean begin(UUID uuid, BlockPos src) {
        TRANSFER_REQUESTS.incrementAndGet();
        if (HISTORY_SET.containsKey(uuid) || ACTIVE.containsKey(uuid)) {
            DUPLICATE_REJECTED.incrementAndGet();
            org.slf4j.LoggerFactory.getLogger("Fabrolia-SandDuper").error(
                    "[sandduper] DUPLICATE cross-dimension transfer rejected for {} @ {} — replay path, report",
                    uuid, src.toShortString());
            return false;
        }
        Tx tx = new Tx(uuid, src.asLong());
        ACTIVE.put(uuid, tx);
        trace(tx, "TRANSFER_REQUESTED @" + src.toShortString());
        return true;
    }

    /** teleport() RETURN: copy created (or vanilla returned null). The flag
     *  tells whether vanilla armed the source-side duplicate continuation. */
    public static void committed(UUID uuid, boolean copyCreated, boolean forceTickArmed) {
        Tx tx = ACTIVE.get(uuid);
        if (tx == null) return;
        if (!copyCreated) {
            TRANSFER_NULL.incrementAndGet();
            retire(tx, TxState.ABORTED);
            return;
        }
        DESTINATION_COMMITS.incrementAndGet();
        if (forceTickArmed) {
            SRC_CONT_PENDING.incrementAndGet();
            tx.state = TxState.SOURCE_CONTINUATION_PENDING;
            trace(tx, "DESTINATION_COMMITTED + continuation pending");
            // resolution arrives from the same entity tick, same thread,
            // within this tick: sourceOutcome() below.
        } else {
            trace(tx, "DESTINATION_COMMITTED (no continuation armed)");
            retire(tx, TxState.COMPLETE_NO_CONTINUATION);
        }
    }

    /** Source-side continuation outcome (same tick as the commit). */
    public static void sourceOutcome(UUID uuid, TxState outcome) {
        Tx tx = ACTIVE.get(uuid);
        if (tx == null) return;
        switch (outcome) {
            case COMPLETE_PLACED -> SRC_CONT_PLACED.incrementAndGet();
            case COMPLETE_DROPPED -> SRC_CONT_DROPPED.incrementAndGet();
            case COMPLETE_NO_CONTINUATION -> SRC_CONT_NONE.incrementAndGet();
            default -> { }
        }
        trace(tx, "SOURCE " + outcome);
        retire(tx, outcome);
    }

    /** End of the original's post-teleport tick: whatever is still pending
     *  resolves as vanilla's no-placement outcome (mid-air original). */
    public static void tickEnded(UUID uuid) {
        tickEnded(uuid, null, null);
    }

    /** @param groundedCell non-null = the continuation ran GROUNDED and still
     *  produced nothing: vanilla's landing branch early-outed (moving_piston
     *  wait-gate / occupied cell / unsurvivable) — but a teleported original
     *  is a one-shot, so the "wait" silently destroys the block. Production
     *  2026-07-16: the one-tick-early cycle put the sweeper's moving_piston
     *  in the landing cell at continuation time — 12/12 lost, no items. */
    public static void tickEnded(UUID uuid, String groundedCell, String cellPos) {
        Tx tx = ACTIVE.get(uuid);
        if (tx != null && tx.state == TxState.SOURCE_CONTINUATION_PENDING) {
            if (groundedCell != null) {
                SRC_CONT_LOST.incrementAndGet();
                trace(tx, "SOURCE CONTINUATION_LOST grounded one-shot blocked by cell="
                        + groundedCell + " @" + cellPos);
                if (traceActive) {
                    traceEvent(uuid, "TERMINAL CONTINUATION_LOST cell=" + groundedCell
                            + " @" + cellPos + " (grounded one-shot: landing branch early-out)");
                }
                retire(tx, TxState.COMPLETE_NO_CONTINUATION);
                return;
            }
            SRC_CONT_NONE.incrementAndGet();
            trace(tx, "SOURCE continuation ended without placement (mid-air, vanilla-legal)");
            retire(tx, TxState.COMPLETE_NO_CONTINUATION);
        }
    }

    private static void retire(Tx tx, TxState terminal) {
        tx.state = terminal;
        ACTIVE.remove(tx.entity);
        if (terminal == TxState.ABORTED) {
            ABORTED.incrementAndGet();
        } else {
            COMPLETED.incrementAndGet();
        }
        synchronized (HISTORY) {
            HISTORY.addLast(tx.entity);
            HISTORY_SET.put(tx.entity, System.nanoTime());
            while (HISTORY.size() > HISTORY_CAP) {
                HISTORY_SET.remove(HISTORY.pollFirst());
            }
        }
    }

    private static void trace(Tx tx, String event) {
        ArrayDeque<String> ring = TRACES.get(tx.srcPos);
        if (ring == null) return;
        synchronized (ring) {
            ring.addLast("tx#" + tx.id + " " + tx.entity.toString().substring(0, 8) + " " + event);
            while (ring.size() > 64) ring.pollFirst();
        }
    }

    public static String armTrace(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        TRACES.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (TRACES.size() > 16) {
            TRACES.keySet().iterator().remove();
        }
        return "sandduper trace armed for [" + x + "," + y + "," + z + "]";
    }

    public static String readTrace(int x, int y, int z) {
        ArrayDeque<String> ring = TRACES.get(BlockPos.asLong(x, y, z));
        if (ring == null) return "no trace armed at [" + x + "," + y + "," + z + "] — arm first";
        StringBuilder sb = new StringBuilder("trace [" + x + "," + y + "," + z + "]:");
        synchronized (ring) {
            for (String s : ring) sb.append("\n ").append(s);
        }
        return sb.toString();
    }

    public static String status() {
        long now = System.nanoTime();
        long oldest = 0;
        for (Tx tx : ACTIVE.values()) {
            oldest = Math.max(oldest, now - tx.startNanos);
            if (now - tx.startNanos > 60_000_000_000L) {
                STUCK.incrementAndGet(); // an active transfer older than any
                                          // valid duration is an invariant fail
            }
        }
        return "sandduper: fallCreated=" + FALL_CREATED.get()
                + " transferRequests=" + TRANSFER_REQUESTS.get()
                + " destinationCommits=" + DESTINATION_COMMITS.get()
                + " transferNull=" + TRANSFER_NULL.get()
                + " srcContinuations{pending=" + SRC_CONT_PENDING.get()
                + " placed=" + SRC_CONT_PLACED.get()
                + " dropped=" + SRC_CONT_DROPPED.get()
                + " none=" + SRC_CONT_NONE.get()
                + " LOST=" + SRC_CONT_LOST.get() + "}"
                + " duplicateRejected=" + DUPLICATE_REJECTED.get()
                + " activeTransactions=" + ACTIVE.size()
                + " oldestActiveMs=" + oldest / 1_000_000
                + " completed=" + COMPLETED.get()
                + " aborted=" + ABORTED.get()
                + " stuck=" + STUCK.get()
                + " srcDeferVerified=" + SOURCE_DEFER_VERIFIED.get()
                + " srcDeferCorrected=" + SOURCE_DEFER_CORRECTED.get()
                + " | " + CrossDimArrivals.status();
    }
}
