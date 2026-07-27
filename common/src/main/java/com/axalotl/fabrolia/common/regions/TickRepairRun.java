package com.axalotl.fabrolia.common.regions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ObjLongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestration for {@code /fabrolia regions ticks repair-all} — see
 * {@link TickRepair} for the classification predicate and safety contract.
 *
 * <p>Phases:
 * <ol>
 *   <li><b>Loaded pass</b> (synchronous, main): every loaded container in
 *       every dimension, classified — and in confirm mode repaired — under
 *       the lane locks. World reads are stable: every main-dispatched command
 *       already runs inside the all-islands quiesce fence
 *       (CommandsMixin → {@code IslandCommands.quiesceAllExcept}), so no
 *       island worker mutates block state during this pass.</li>
 *   <li><b>Unloaded scan</b> (background thread, read-only): every chunk in
 *       every region file, read through the live chunk-storage IO path
 *       ({@code ChunkMap.read}, which also sees not-yet-flushed pending
 *       writes). Only the type-mismatch test is decidable on saved data (the
 *       domain tag is not persisted and saved delays are relative — see
 *       {@link TickRepairNbt}). This competes with player chunk load/save on
 *       the shared IO worker — disclosed to the operator, with progress lines
 *       and {@code repair-all cancel} to abort.</li>
 *   <li><b>Mutation batcher</b> (confirm only, main, {@code TickTask}-chained,
 *       4 chunks per tick): flagged chunks that are LOADED (registered live
 *       containers — e.g. players moved in since the scan snapshot) are
 *       re-classified against live state, repaired in memory as per-chunk
 *       transactions, and marked dirty so vanilla serialization persists the
 *       result. Flagged chunks that are still unloaded are reported as
 *       DEFERRED and never loaded: an unloaded chunk deserializes into the
 *       PACKED state (relative {@code SavedTick} delays) that no public
 *       container API can mutate — see {@link TickRepair}'s audit15 notes —
 *       and the repair never forces an unpack; they become repairable by a
 *       future {@code repair-all confirm} once naturally loaded and ticking.
 *       Each batch re-takes the SAME all-islands quiesce fence the command
 *       fence uses (bounded attempt, defer-and-retry on contention — the
 *       save-all discipline), so batcher world reads are as stable as the
 *       loaded pass's. Never offline NBT surgery.</li>
 * </ol>
 */
public final class TickRepairRun {

    private TickRepairRun() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-TickRepair");
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int CHUNKS_PER_BATCH = 4;
    /** Bounded fence attempt per batch — same budget as the command fence. */
    private static final long FENCE_TRY_NANOS = 50_000_000L;

    // ------------------------------------------------------------------
    // Command entry points
    // ------------------------------------------------------------------

    /** Replies/failures sink — lets tests drive {@link #execute} without a
     *  CommandSourceStack. */
    interface Feedback {
        void reply(String text);

        void fail(String text);
    }

    /** Kicks off the background unloaded scan; separated so tests can verify
     *  the RUNNING lifecycle around a throwing/no-op starter. */
    interface ScanStarter {
        void start(Path reportFile, Path backupDir, Set<Path> backedUp);
    }

    public static int command(CommandSourceStack src, TickRepair.Mode mode) {
        MinecraftServer server = src.getServer();
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Feedback fb = new Feedback() {
            @Override public void reply(String text) {
                src.sendSuccess(() -> Component.literal(text), false);
                LOGGER.info("[tick-repair] {}", text);
            }

            @Override public void fail(String text) {
                src.sendFailure(Component.literal(text));
                LOGGER.warn("[tick-repair] {}", text);
            }
        };
        return execute(levels, worldRoot, mode, fb,
                (reportFile, backupDir, backedUp) -> startUnloadedScan(server, src, levels, mode,
                        reportFile, worldRoot, backupDir, backedUp));
    }

    /** Operator abort for a run in progress ({@code repair-all cancel}). */
    public static int cancel(CommandSourceStack src) {
        if (!TickRepair.RUNNING.get()) {
            src.sendFailure(Component.literal("no tick repair-all run is in progress"));
            return 0;
        }
        TickRepair.CANCEL.set(true);
        src.sendSuccess(() -> Component.literal(
                "tick repair-all: cancel requested — the scan/batcher stops at its next checkpoint "
                + "(already-applied repairs remain; the report file records them)"), false);
        return 1;
    }

    /**
     * Testable core. Holds {@link TickRepair#RUNNING} for the duration of the
     * loaded pass; keeps holding it (release deferred to the scan/batcher)
     * ONLY once {@code starter.start} has returned successfully — any throw
     * before that releases the flag (review finding #2).
     */
    static int execute(List<ServerLevel> levels, Path worldRoot, TickRepair.Mode mode,
                       Feedback fb, ScanStarter starter) {
        if (!TickRepair.RUNNING.compareAndSet(false, true)) {
            fb.fail("a tick repair-all run is already in progress — wait for its completion message "
                    + "or abort it with 'repair-all cancel'");
            return 0;
        }
        TickRepair.CANCEL.set(false);
        boolean release = true;
        // Held for the catch block (audit15): a failure message must state
        // whether any mutations were committed and identify them — never a
        // bare "command failed".
        TickRepair.Result[] resultSlot = new TickRepair.Result[1];
        try {
            Path reportDir = worldRoot.resolve("fabrolia").resolve("tick-repair");
            Path backupDir = reportDir.resolve("region-backup-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Set<Path> backedUp = new HashSet<>();
            List<String> backupNotes = new ArrayList<>();
            ObjLongConsumer<ServerLevel> hook = mode.mutating
                    ? (lvl, key) -> backupRegionFile(worldRoot, backupDir, lvl, key, backedUp, backupNotes)
                    : null;

            TickRepair.Result r = TickRepair.runLoadedPass(levels, mode, reportDir, hook);
            resultSlot[0] = r;
            r.notes.addAll(backupNotes);
            if (mode.mutating && r.reportFile == null) {
                fb.fail("ABORTED (nothing was changed): " + String.join("; ", r.notes));
                return 0;
            }
            String head = switch (mode) {
                case DRY -> "DRY RUN (nothing changed — append 'confirm' to apply)";
                case CONFIRM -> "REPAIR APPLIED (loaded chunks)";
                case CLEAR_DRY -> "CLEAR-AMBIGUOUS DRY RUN (nothing changed — append 'confirm' to apply)";
                case CLEAR_CONFIRM -> "CLEAR-AMBIGUOUS APPLIED";
            };
            fb.reply(TickRepair.SCOPE);
            fb.reply(TickRepair.SEMANTICS);
            fb.reply(head + " — loaded pass: " + r.totals.summary());
            if (r.reportFile != null) {
                fb.reply("report: " + r.reportFile);
            }
            if (mode.mutating && !backedUp.isEmpty()) {
                fb.reply("region-file backups (" + backedUp.size() + ", flushed before copy — consistent "
                        + "as of that flush; in-memory unsaved state not included; the report file is the "
                        + "authoritative change record): " + backupDir);
            }
            for (String n : r.notes) {
                fb.reply("note: " + n);
            }
            if (mode.clearAmbiguous) {
                fb.reply("clear-ambiguous operates on loaded chunks only: the on-disk format carries "
                        + "no domain tag, so nothing on disk can be AMBIGUOUS (saved delays are relative "
                        + "and re-anchor on load).");
                return 1;
            }
            // DRY + CONFIRM continue with the unloaded scan.
            fb.reply("unloaded-chunk scan starting in the background. It reads every region-file chunk "
                    + "through the live chunk IO worker — this COMPETES with player chunk load/save and "
                    + "can run long on a large world. Progress lines follow; abort any time with "
                    + "'/fabrolia regions ticks repair-all cancel'.");
            starter.start(r.reportFile, backupDir, backedUp);
            release = false;   // scan thread / batcher now owns the flag
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[tick-repair] command failed", t);
            fb.fail("tick repair failed: " + t + " — " + committedSummary(resultSlot[0]));
            return 0;
        } finally {
            if (release) {
                TickRepair.RUNNING.set(false);
            }
        }
    }

    /** Failure-path disclosure (audit15): state whether any mutations were
     *  committed before a failure and identify them (dimension + chunk
     *  coords), plus the report-file path — never a bare "command failed". */
    static String committedSummary(TickRepair.Result r) {
        if (r == null) {
            return "no mutations were committed (failed before the loaded pass produced a result)";
        }
        List<String> chunks = new ArrayList<>();
        for (TickRepair.Finding f : r.applied) {
            if (TickRepair.ACTION_DEFERRED.equals(f.action())
                    || TickRepair.ACTION_AMBIGUOUS.equals(f.action())
                    || f.reason().contains("NOT applied")) {
                continue;   // report-only records, not mutations
            }
            String key = f.dim() + " chunk[" + f.cx() + "," + f.cz() + "]";
            if (!chunks.contains(key)) {
                chunks.add(key);
            }
        }
        String report = r.reportFile != null
                ? "; full record: " + r.reportFile
                : "; NO report file was written";
        if (chunks.isEmpty()) {
            return "no mutations were committed" + report;
        }
        return "MUTATIONS WERE COMMITTED to " + chunks.size() + " chunk(s) before the failure: "
                + String.join(", ", chunks) + report;
    }

    // ------------------------------------------------------------------
    // Unloaded scan (background, read-only)
    // ------------------------------------------------------------------

    private record LevelScan(ServerLevel level, ChunkMap chunkMap, Path regionDir,
                             it.unimi.dsi.fastutil.longs.LongOpenHashSet loadedKeys) {}

    private record Candidate(ServerLevel level, int cx, int cz) {}

    private static void startUnloadedScan(MinecraftServer server, CommandSourceStack src,
                                          List<ServerLevel> levels, TickRepair.Mode mode,
                                          Path reportFile, Path worldRoot, Path backupDir,
                                          Set<Path> backedUp) {
        // Snapshots taken on main: chunkMap reference, region dir, and the set
        // of currently-loaded chunks (every FULL chunk registers containers).
        List<LevelScan> scans = new ArrayList<>();
        for (ServerLevel level : levels) {
            Path regionDir = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
            scans.add(new LevelScan(level, level.getChunkSource().chunkMap, regionDir, loadedChunkKeys(level)));
        }
        Thread t = new Thread(() -> unloadedScanBody(server, src, scans, mode, reportFile,
                worldRoot, backupDir, backedUp), "fabrolia-tick-repair-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Mutability probe for the batcher (audit15): live repairs go through the
     *  registered in-memory containers only, so a chunk without one in either
     *  lane is not repairable right now — and is never loaded just to find
     *  out it deserialized packed. */
    private static boolean chunkContainersLoaded(ServerLevel level, long chunkKey) {
        return laneHasContainer(level.getBlockTicks(), chunkKey)
                || laneHasContainer(level.getFluidTicks(), chunkKey);
    }

    private static boolean laneHasContainer(Object lane, long chunkKey) {
        if (!(lane instanceof LevelTicksIslandAccess<?> access)) {
            return false;
        }
        synchronized (access.fabrolia$tickLock()) {
            return access.fabrolia$allContainers().containsKey(chunkKey);
        }
    }

    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet loadedChunkKeys(ServerLevel level) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (level.getBlockTicks() instanceof LevelTicksIslandAccess<?> access) {
            synchronized (access.fabrolia$tickLock()) {
                keys.addAll(access.fabrolia$allContainers().keySet());
            }
        }
        return keys;
    }

    private static void unloadedScanBody(MinecraftServer server, CommandSourceStack src,
                                         List<LevelScan> scans, TickRepair.Mode mode,
                                         Path reportFile, Path worldRoot, Path backupDir,
                                         Set<Path> backedUp) {
        // RUNNING lifecycle (review finding #2): this thread owns the flag
        // until it either finishes/aborts (clear it) or hands off to the
        // batcher (which then owns it). try/finally makes the clear
        // crash-safe.
        boolean handedOff = false;
        try {
            long chunksRead = 0, entries = 0, skippedEntries = 0, belowFull = 0, unparseable = 0;
            int mismatchEntries = 0;
            List<Candidate> candidates = new ArrayList<>();
            List<String> reportLines = new ArrayList<>();
            reportLines.add("== unloaded scan (read-only; saved data supports only the type-mismatch test) ==");
            boolean aborted = false;
            String abortReason = "";
            try {
                outer:
                for (LevelScan ls : scans) {
                    String dim = TickRepair.dim(ls.level());
                    if (!Files.isDirectory(ls.regionDir())) {
                        reportLines.add(dim + ": no region directory at " + ls.regionDir() + " — skipped");
                        continue;
                    }
                    List<Path> regionFiles;
                    try (var s = Files.list(ls.regionDir())) {
                        regionFiles = s.filter(p -> REGION_FILE.matcher(p.getFileName().toString()).matches())
                                .sorted().toList();
                    }
                    int fileIdx = 0;
                    for (Path rf : regionFiles) {
                        if (server.isStopped() || TickRepair.CANCEL.get()) {
                            aborted = true;
                            abortReason = server.isStopped() ? "server stopping" : "cancelled by operator";
                            break outer;
                        }
                        Matcher m = REGION_FILE.matcher(rf.getFileName().toString());
                        if (!m.matches()) continue;
                        int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                        for (int lx = 0; lx < 32; lx++) {
                            for (int lz = 0; lz < 32; lz++) {
                                int cx = (rx << 5) + lx, cz = (rz << 5) + lz;
                                if (ls.loadedKeys().contains(ChunkPos.pack(cx, cz))) {
                                    continue;   // in-memory authority already handled by the loaded pass
                                }
                                Optional<CompoundTag> tag;
                                try {
                                    tag = ls.chunkMap().read(new ChunkPos(cx, cz)).join();
                                } catch (Throwable e) {
                                    unparseable++;
                                    continue;
                                }
                                if (tag.isEmpty()) {
                                    continue;
                                }
                                chunksRead++;
                                TickRepairNbt.NbtChunkResult res;
                                try {
                                    res = TickRepairNbt.classifyChunkNbt(tag.get());
                                } catch (Throwable e) {
                                    unparseable++;
                                    reportLines.add(dim + " chunk[" + cx + "," + cz + "]: NBT classification failed ("
                                            + e + ") — skipped");
                                    continue;
                                }
                                if (!res.fullStatus()) {
                                    belowFull++;
                                    continue;
                                }
                                entries += res.entries();
                                skippedEntries += res.skippedEntries();
                                if (!res.mismatches().isEmpty()) {
                                    mismatchEntries += res.mismatches().size();
                                    for (TickRepairNbt.NbtFinding f : res.mismatches()) {
                                        reportLines.add(f.line(dim, cx, cz));
                                    }
                                    for (String n : res.notes()) {
                                        reportLines.add(dim + " chunk[" + cx + "," + cz + "]: " + n);
                                    }
                                    candidates.add(new Candidate(ls.level(), cx, cz));
                                }
                            }
                        }
                        if (++fileIdx % 32 == 0) {
                            final String progress = "tick repair-all: " + dim + " scanned " + fileIdx + "/"
                                    + regionFiles.size() + " region files, " + candidates.size()
                                    + " flagged chunk(s) so far";
                            reply(server, src, progress);
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("[tick-repair] unloaded scan failed", e);
                reportLines.add("SCAN FAILED: " + e + " — results above this line are partial");
                aborted = true;
                abortReason = "scan error: " + e;
            }
            String summary = "unloaded scan " + (aborted ? "ABORTED (" + abortReason + ", partial)" : "complete")
                    + ": chunksRead=" + chunksRead + " entries=" + entries
                    + " stale-type-mismatch=" + mismatchEntries
                    + " in " + candidates.size() + " chunk(s), skipped-entries=" + skippedEntries
                    + " (undecodable — never touched), below-full-chunks=" + belowFull
                    + " (never touched — repairing would force generation), unreadable-chunks=" + unparseable;
            reportLines.add(summary);
            try {
                TickRepair.appendLines(reportFile, reportLines);
            } catch (IOException e) {
                LOGGER.warn("[tick-repair] could not append unloaded-scan lines to report", e);
            }
            if (mode == TickRepair.Mode.DRY || candidates.isEmpty() || aborted) {
                reply(server, src, "tick repair-all: " + summary
                        + (mode == TickRepair.Mode.DRY && mismatchEntries > 0
                           ? " — run 'repair-all confirm' to repair (chunks loaded and ticking "
                             + "at that moment; still-unloaded/packed chunks are reported as "
                             + "DEFERRED and become repairable once naturally loaded and ticking)"
                           : "")
                        + (reportFile != null ? " (report: " + reportFile + ")" : ""));
                return;
            }
            // CONFIRM: hand the flagged chunks to the main-thread batcher.
            reply(server, src, "tick repair-all: " + summary + " — processing flagged chunks "
                    + CHUNKS_PER_BATCH + " per tick under the all-islands quiesce fence. NOTE: a "
                    + "flagged chunk that is still unloaded (or loaded but not yet ticking) holds its "
                    + "entries in the packed relative-delay state that no public container API can "
                    + "mutate — those are reported as DEFERRED, never loaded/mutated by force, and "
                    + "become repairable by a future repair-all confirm once the chunk unpacks "
                    + "naturally at start-of-ticking; only chunks loaded and ticking right now are "
                    + "repaired in place");
            try {
                TickRepair.appendLines(reportFile, List.of(
                        "== applied to previously-unloaded chunks (appended per batch) =="));
            } catch (IOException e) {
                LOGGER.warn("[tick-repair] could not append batch header to report", e);
            }
            ArrayDeque<Candidate> queue = new ArrayDeque<>(candidates);
            TickRepair.Totals totals = new TickRepair.Totals();
            server.schedule(new TickTask(server.getTickCount(), () -> runBatch(server, src, queue, totals,
                    reportFile, worldRoot, backupDir, backedUp, candidates.size(), 0)));
            handedOff = true;
        } finally {
            if (!handedOff) {
                TickRepair.RUNNING.set(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Mutation batcher (main thread, TickTask-chained, fenced per batch)
    // ------------------------------------------------------------------

    private static void runBatch(MinecraftServer server, CommandSourceStack src,
                                 ArrayDeque<Candidate> queue, TickRepair.Totals totals,
                                 Path reportFile, Path worldRoot, Path backupDir, Set<Path> backedUp,
                                 int total, int fenceDeferrals) {
        try {
            if (server.isStopped()) {
                TickRepair.RUNNING.set(false);
                return;
            }
            if (TickRepair.CANCEL.get()) {
                finishBatcher(server, src, totals, reportFile, "CANCELLED by operator ("
                        + (total - queue.size()) + "/" + total + " flagged chunks processed)");
                return;
            }
            // WORLD-READ STABILITY (review finding #1a): batches run from the
            // server task queue, OUTSIDE the command fence — take the same
            // all-islands quiesce the command fence uses, bounded, with
            // defer-and-retry on contention (the save-all discipline). Never
            // mutate against live islands.
            boolean tookFence = false;
            if (RegionScheduler.anyAsyncActive() && !IslandCommands.fenceWindowHeld()) {
                tookFence = IslandCommands.quiesceAllExcept(null, FENCE_TRY_NANOS);
                if (!tookFence) {
                    int d = fenceDeferrals + 1;
                    if (d % 100 == 0) {
                        LOGGER.warn("[tick-repair] batcher still deferring behind busy islands ({} attempts) — "
                                + "retrying each tick, never unfenced", d);
                    }
                    server.schedule(new TickTask(server.getTickCount(), () -> runBatch(server, src, queue,
                            totals, reportFile, worldRoot, backupDir, backedUp, total, d)));
                    return;
                }
            }
            List<TickRepair.Finding> batchApplied = new ArrayList<>();
            List<String> batchNotes = new ArrayList<>();
            try {
                int n = 0;
                while (n < CHUNKS_PER_BATCH && !queue.isEmpty()) {
                    Candidate c = queue.poll();
                    n++;
                    long chunkKey = ChunkPos.pack(c.cx(), c.cz());
                    try {
                        // LIFECYCLE GATE (audit15): a chunk that is not loaded
                        // right now would come up PACKED (FULL but not
                        // ticking) — its entries sit as relative delays that
                        // no public container API can mutate, and forcing the
                        // load would only defer it after paying a synchronous
                        // chunk load (never force — or risk doubling — an
                        // unpack). Report it DEFERRED and move on; it becomes
                        // repairable by a future repair-all once it is
                        // naturally loaded and ticking. Only a chunk that got
                        // loaded since the scan snapshot (players moved in) is
                        // attempted — applySingleChunk still defers it if its
                        // containers are packed.
                        if (!chunkContainersLoaded(c.level(), chunkKey)) {
                            batchNotes.add(TickRepair.dim(c.level()) + " chunk[" + c.cx() + ","
                                    + c.cz() + "] DEFERRED-UNLOADED: flagged by the NBT scan but "
                                    + "not loaded — an unloaded chunk deserializes into the packed "
                                    + "state whose entries cannot be mutated through any public "
                                    + "container API; NOT loaded, NOT mutated; repairable by a "
                                    + "future repair-all confirm once the chunk is naturally "
                                    + "loaded and ticking");
                            totals.deferredUnloadedChunks++;
                            continue;
                        }
                        backupRegionFile(worldRoot, backupDir, c.level(), chunkKey, backedUp, batchNotes);
                        // Re-classified against LIVE state — the NBT scan was
                        // only a candidate filter. applySingleChunk is a
                        // per-chunk transaction: a throw rolls the chunk back
                        // (recorded in batchNotes) and the batcher continues.
                        TickRepair.applySingleChunk(c.level(), chunkKey, TickRepair.Mode.CONFIRM,
                                totals, batchApplied, batchNotes);
                    } catch (Throwable t) {
                        totals.skipped++;
                        batchNotes.add("failed to repair " + TickRepair.dim(c.level()) + " chunk["
                                + c.cx() + "," + c.cz() + "]: " + t + " — skipped (rolled back by "
                                + "the per-chunk transaction; earlier chunks' records stand)");
                        LOGGER.error("[tick-repair] failed to repair unloaded chunk {} [{},{}]",
                                TickRepair.dim(c.level()), c.cx(), c.cz(), t);
                    }
                }
            } finally {
                if (tookFence) {
                    IslandCommands.releaseAllExcept();
                }
            }
            // Crash-safe record (review finding #6a): append THIS batch's
            // mutations immediately, not only after the whole queue drains.
            List<String> lines = new ArrayList<>();
            for (TickRepair.Finding f : batchApplied) {
                lines.add(f.line());
            }
            lines.addAll(batchNotes);
            if (!lines.isEmpty()) {
                try {
                    TickRepair.appendLines(reportFile, lines);
                } catch (IOException e) {
                    LOGGER.warn("[tick-repair] could not append batch results to report", e);
                }
            }
            if (!queue.isEmpty()) {
                int done = total - queue.size();
                if (done % 64 < CHUNKS_PER_BATCH) {
                    reply(server, src, "tick repair-all: " + done + "/" + total + " flagged chunks processed");
                }
                server.schedule(new TickTask(server.getTickCount(), () -> runBatch(server, src, queue,
                        totals, reportFile, worldRoot, backupDir, backedUp, total, 0)));
                return;
            }
            finishBatcher(server, src, totals, reportFile, "complete");
        } catch (Throwable t) {
            // catch-all: the batcher must never leak RUNNING (finding #2)
            LOGGER.error("[tick-repair] batcher failed", t);
            try {
                TickRepair.appendLines(reportFile, List.of("BATCHER FAILED: " + t
                        + " — applied sections above this line are complete per batch"));
            } catch (IOException ignored) {
            }
            reply(server, src, "tick repair-all: batcher failed (" + t + ") — mutations committed "
                    + "before the failure ARE recorded per-batch in the report (each applied line "
                    + "names dimension + chunk): removals=" + totals.removedMismatch
                    + " rescheduled=" + totals.rescheduled + "; report: " + reportFile);
            TickRepair.RUNNING.set(false);
        }
    }

    private static void finishBatcher(MinecraftServer server, CommandSourceStack src,
                                      TickRepair.Totals totals, Path reportFile, String status) {
        try {
            TickRepair.appendLines(reportFile, List.of("unloaded-repair " + status + ": " + totals.summary()));
        } catch (IOException e) {
            LOGGER.warn("[tick-repair] could not append batcher summary to report", e);
        }
        reply(server, src, "tick repair-all " + status + ": unloaded-chunk repairs — "
                + "corruption repaired (type-mismatch removals)=" + totals.removedMismatch
                + " rescheduled=" + totals.rescheduled
                + " deferred-unloaded=" + totals.deferredUnloadedChunks
                + " chunk(s) (never loaded, never mutated)"
                + " deferred-packed=" + totals.deferredPackedContainers
                + " container(s) (loaded but still packed — never mutated)"
                + " — both repairable once naturally loaded and ticking, then re-run repair-all confirm"
                + " skipped=" + totals.skipped
                + (reportFile != null ? " (report: " + reportFile + ")" : ""));
        TickRepair.RUNNING.set(false);
    }

    // ------------------------------------------------------------------
    // Region-file backup (flushed-then-copied, pre-mutation)
    // ------------------------------------------------------------------

    /** Copy the region file covering {@code chunkKey} into the backup dir
     *  (once per file per run). To avoid a TORN copy racing the IO worker
     *  (review finding #6b), all pending chunk writes are flushed to disk
     *  ({@code synchronize(true)}) immediately before the copy; both the
     *  loaded-pass hook and the batcher run on main while it holds the tick,
     *  so no new writes are submitted between the flush and the copy — the
     *  copy is internally consistent as of that flush. In-memory unsaved
     *  chunk state is not included — the report file is the authoritative
     *  change record. */
    static void backupRegionFile(Path worldRoot, Path backupDir, ServerLevel level, long chunkKey,
                                 Set<Path> backedUp, List<String> notes) {
        try {
            Path regionDir = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
            Path src = regionDir.resolve("r." + (ChunkPos.getX(chunkKey) >> 5) + "."
                    + (ChunkPos.getZ(chunkKey) >> 5) + ".mca");
            if (backedUp.contains(src)) {
                return;
            }
            // flush BEFORE the copy so it cannot be torn by in-flight writes
            level.getChunkSource().chunkMap.synchronize(true).join();
            if (!Files.isRegularFile(src) || !backedUp.add(src)) {
                return;
            }
            Path dst = backupDir
                    .resolve(TickRepair.dim(level).replace(':', '_').replace('/', '_'))
                    .resolve(src.getFileName().toString());
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
        } catch (Throwable e) {
            notes.add("region backup failed for chunk [" + ChunkPos.getX(chunkKey) + ","
                    + ChunkPos.getZ(chunkKey) + "] (" + e + ") — repair proceeds; the report file "
                    + "lists every change");
        }
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    /** Thread-safe reply: hops to the server thread. */
    private static void reply(MinecraftServer server, CommandSourceStack src, String text) {
        server.schedule(new TickTask(server.getTickCount(), () -> {
            src.sendSuccess(() -> Component.literal(text), false);
            LOGGER.info("[tick-repair] {}", text);
        }));
    }
}
