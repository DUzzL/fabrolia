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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 *       the shared IO worker — disclosed to the operator, with
 *       {@code repair-all cancel} available to abort.</li>
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
 *       Each batch waits inside its current Main invocation for the SAME
 *       all-islands quiesce fence the command fence uses. It never returns and
 *       replays a partially admitted batch on a later tick. World reads are as
 *       stable as the loaded pass's. Never offline NBT surgery.</li>
 * </ol>
 */
public final class TickRepairRun {

    private TickRepairRun() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-TickRepair");
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int CHUNKS_PER_BATCH = 4;

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
        try {
            Path reportDir = worldRoot.resolve("fabrolia").resolve("tick-repair");
            Path backupDir = reportDir.resolve("region-backup-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Set<Path> backedUp = new HashSet<>();
            ObjLongConsumer<ServerLevel> hook = mode.mutating
                    ? (lvl, key) -> backupRegionFile(worldRoot, backupDir, lvl, key, backedUp)
                    : null;

            TickRepair.Result r = TickRepair.runLoadedPass(levels, mode, reportDir, hook);
            if (mode.mutating && r.reportFile == null) {
                throw new IllegalStateException(
                        "mutating tick repair reached admission without its pre-mutation report");
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
                    + "can run long on a large world. Abort any time with "
                    + "'/fabrolia regions ticks repair-all cancel'.");
            starter.start(r.reportFile, backupDir, backedUp);
            release = false;   // scan thread / batcher now owns the flag
            return 1;
        } catch (Throwable failure) {
            // Brigadier/Minecraft task dispatch may log a command exception and
            // continue. Arm the shared physical-Main crash authority before the
            // exact entered-work failure escapes this command invocation.
            IslandCommands.publishAndPropagate(failure);
            throw new AssertionError("unreachable after fatal tick-repair command failure");
        } finally {
            if (release) {
                TickRepair.RUNNING.set(false);
            }
        }
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
        boolean handedOff = false;
        Throwable failure = null;
        try {
            handedOff = unloadedScanBodyEntered(server, src, scans, mode, reportFile,
                    worldRoot, backupDir, backedUp);
        } catch (Throwable primary) {
            failure = publishFatalPreserving(primary);
        } finally {
            if (!handedOff) {
                TickRepair.RUNNING.set(false);
            }
        }
        if (failure != null) {
            // This daemon is outside vanilla's physical-Main exception path.
            // Publish first so the next Main boundary crashes even if the
            // thread's uncaught-exception handler would otherwise only log it.
            IslandCommands.publishAndPropagate(failure);
            throw new AssertionError("unreachable after fatal unloaded tick scan");
        }
    }

    /** @return true only after the mutation batch was successfully enqueued. */
    private static boolean unloadedScanBodyEntered(MinecraftServer server, CommandSourceStack src,
                                                   List<LevelScan> scans, TickRepair.Mode mode,
                                                   Path reportFile, Path worldRoot, Path backupDir,
                                                   Set<Path> backedUp) throws IOException {
        if (reportFile == null) {
            throw new IllegalStateException("unloaded tick scan entered without its report file");
        }
        RegionScheduler.throwWorkerFailureIfAny();
        long chunksRead = 0, entries = 0, skippedEntries = 0, belowFull = 0;
        int mismatchEntries = 0;
        List<Candidate> candidates = new ArrayList<>();
        List<String> reportLines = new ArrayList<>();
        reportLines.add("== unloaded scan (read-only; saved data supports only the type-mismatch test) ==");
        boolean aborted = false;
        String abortReason = "";
        outer:
        for (LevelScan ls : scans) {
            RegionScheduler.throwWorkerFailureIfAny();
            String dim = TickRepair.dim(ls.level());
            BasicFileAttributes regionAttributes;
            try {
                regionAttributes = Files.readAttributes(
                        ls.regionDir(), BasicFileAttributes.class);
            } catch (NoSuchFileException absent) {
                reportLines.add(dim + ": no region directory at " + ls.regionDir()
                        + " — no saved chunks");
                continue;
            }
            if (!regionAttributes.isDirectory()) {
                throw new IOException("region path is not a directory: " + ls.regionDir());
            }
            List<Path> regionFiles;
            try (var stream = Files.list(ls.regionDir())) {
                regionFiles = stream
                        .filter(path -> REGION_FILE.matcher(path.getFileName().toString()).matches())
                        .sorted().toList();
            }
            for (Path regionFile : regionFiles) {
                RegionScheduler.throwWorkerFailureIfAny();
                if (server.isStopped() || TickRepair.CANCEL.get()) {
                    aborted = true;
                    abortReason = server.isStopped() ? "server stopping" : "cancelled by operator";
                    break outer;
                }
                Matcher matcher = REGION_FILE.matcher(regionFile.getFileName().toString());
                if (!matcher.matches()) {
                    throw new IllegalStateException(
                            "region-file snapshot admitted a non-region path: " + regionFile);
                }
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                for (int localX = 0; localX < 32; localX++) {
                    for (int localZ = 0; localZ < 32; localZ++) {
                        RegionScheduler.throwWorkerFailureIfAny();
                        int chunkX = (regionX << 5) + localX;
                        int chunkZ = (regionZ << 5) + localZ;
                        if (ls.loadedKeys().contains(ChunkPos.pack(chunkX, chunkZ))) {
                            continue;   // the loaded pass was the in-memory authority
                        }
                        // ChunkMap.read represents an absent saved chunk as
                        // Optional.empty. Any exceptional future is an actual IO
                        // or decode failure and remains fatal; it is not data
                        // absence and must not turn into a skipped candidate.
                        Optional<CompoundTag> tag = joinExact(
                                ls.chunkMap().read(new ChunkPos(chunkX, chunkZ)));
                        if (tag.isEmpty()) {
                            continue;
                        }
                        chunksRead++;
                        // TickRepairNbt deliberately accounts for undecodable
                        // individual entries in its result. A thrown classifier
                        // failure is unexpected and fail-stops the whole run.
                        TickRepairNbt.NbtChunkResult result =
                                TickRepairNbt.classifyChunkNbt(tag.get());
                        if (!result.fullStatus()) {
                            belowFull++;
                            continue;
                        }
                        entries += result.entries();
                        skippedEntries += result.skippedEntries();
                        if (!result.mismatches().isEmpty()) {
                            mismatchEntries += result.mismatches().size();
                            for (TickRepairNbt.NbtFinding finding : result.mismatches()) {
                                reportLines.add(finding.line(dim, chunkX, chunkZ));
                            }
                            for (String note : result.notes()) {
                                reportLines.add(dim + " chunk[" + chunkX + "," + chunkZ + "]: " + note);
                            }
                            candidates.add(new Candidate(ls.level(), chunkX, chunkZ));
                        }
                    }
                }
            }
        }
        String summary = "unloaded scan "
                + (aborted ? "ABORTED (" + abortReason + ", partial)" : "complete")
                + ": chunksRead=" + chunksRead + " entries=" + entries
                + " stale-type-mismatch=" + mismatchEntries
                + " in " + candidates.size() + " chunk(s), skipped-entries=" + skippedEntries
                + " (explicitly undecodable entries — never touched), below-full-chunks=" + belowFull
                + " (never touched — repairing would force generation)";
        reportLines.add(summary);
        TickRepair.appendLines(reportFile, reportLines);
        if (mode == TickRepair.Mode.DRY || candidates.isEmpty() || aborted) {
            reply(server, src, "tick repair-all: " + summary
                    + (mode == TickRepair.Mode.DRY && mismatchEntries > 0
                       ? " — run 'repair-all confirm' to repair (chunks loaded and ticking "
                         + "at that moment; still-unloaded/packed chunks are reported as "
                         + "DEFERRED and become repairable once naturally loaded and ticking)"
                       : "")
                    + " (report: " + reportFile + ")");
            return false;
        }

        // The report admission record must exist before mutation work can be
        // handed to Main. A failed write aborts exact and schedules nothing.
        TickRepair.appendLines(reportFile, List.of(
                "== applied to previously-unloaded chunks (appended per batch) =="));
        RegionScheduler.throwWorkerFailureIfAny();
        ArrayDeque<Candidate> queue = new ArrayDeque<>(candidates);
        TickRepair.Totals totals = new TickRepair.Totals();
        // Scheduling the first mutating batch is a successor publication.  Put
        // the actual enqueue inside the generation admission: a plain health
        // read followed by schedule() could publish this work after peer W.
        RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule(
                new TickTask(server.getTickCount(), () -> runBatch(
                        server, src, queue, totals, reportFile, worldRoot,
                        backupDir, backedUp, candidates.size()))));
        return true;
    }

    // ------------------------------------------------------------------
    // Mutation batcher (main thread, TickTask-chained, fenced per batch)
    // ------------------------------------------------------------------

    private static void runBatch(MinecraftServer server, CommandSourceStack src,
                                 ArrayDeque<Candidate> queue, TickRepair.Totals totals,
                                 Path reportFile, Path worldRoot, Path backupDir, Set<Path> backedUp,
                                 int total) {
        try {
            runBatchEntered(server, src, queue, totals, reportFile, worldRoot, backupDir,
                    backedUp, total);
        } catch (Throwable failure) {
            // This TickTask is allowed to perform only terminal bookkeeping
            // after failure. No report trailer, progress/success reply, or
            // successor batch can be published from this path.
            Throwable published = publishFatalPreserving(failure);
            TickRepair.RUNNING.set(false);
            IslandCommands.publishAndPropagate(published);
            throw new AssertionError("unreachable after fatal tick-repair batch");
        }
    }

    private static void runBatchEntered(MinecraftServer server, CommandSourceStack src,
                                        ArrayDeque<Candidate> queue, TickRepair.Totals totals,
                                        Path reportFile, Path worldRoot, Path backupDir,
                                        Set<Path> backedUp, int total) throws IOException {
        RegionScheduler.throwWorkerFailureIfAny();
        if (server.isStopped()) {
            TickRepair.RUNNING.set(false);
            return;
        }
        if (TickRepair.CANCEL.get()) {
            finishBatcherIfHealthy(server, src, totals, reportFile,
                    "CANCELLED by operator (" + (total - queue.size()) + "/"
                            + total + " flagged chunks processed)");
            return;
        }

        // A batch is one exact Main invocation. Contention waits here until
        // the current invocation owns the global fence; it is never accepted,
        // returned, and replayed by a later TickTask.
        boolean tookFence = false;
        Throwable failure = null;
        List<TickRepair.Finding> batchApplied = new ArrayList<>();
        List<String> batchNotes = new ArrayList<>();
        try {
            if (IslandCommands.requiresMainFence() && !IslandCommands.fenceWindowHeld()) {
                tookFence = IslandCommands.quiesceAllExcept(null);
            }
            RegionScheduler.throwWorkerFailureIfAny();
            int processed = 0;
            while (processed < CHUNKS_PER_BATCH && !queue.isEmpty()) {
                // Candidate detachment, classification, backup and mutation are
                // one admitted body.  W cannot win after a plain check but before
                // queue.remove(), nor can a detached candidate be silently skipped.
                boolean entered = RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                    Candidate candidate = queue.remove();
                    long chunkKey = ChunkPos.pack(candidate.cx(), candidate.cz());

                    // An actually absent live container is a deliberate data-state
                    // classification, not an exception fallback. It remains
                    // unloaded and is reported as such.
                    if (!chunkContainersLoaded(candidate.level(), chunkKey)) {
                        batchNotes.add(TickRepair.dim(candidate.level()) + " chunk["
                                + candidate.cx() + "," + candidate.cz()
                                + "] DEFERRED-UNLOADED: flagged by the NBT scan but not loaded — "
                                + "an unloaded chunk deserializes into the packed state whose entries "
                                + "cannot be mutated through any public container API; NOT loaded, NOT "
                                + "mutated; repairable by a future repair-all confirm once the chunk is "
                                + "naturally loaded and ticking");
                        totals.deferredUnloadedChunks++;
                        return true;
                    }

                    // Backup is a hard precondition. Its exact IO/flush failure
                    // aborts before applySingleChunk is entered.
                    backupRegionFile(worldRoot, backupDir, candidate.level(), chunkKey, backedUp);
                    TickRepair.applySingleChunk(
                            candidate.level(), chunkKey, TickRepair.Mode.CONFIRM,
                            totals, batchApplied, batchNotes);
                    return true;
                });
                if (!entered) break;
                processed++;
            }
        } catch (Throwable primary) {
            // Arm first-wins while every island is still fenced. Releasing the
            // claims before publication would let a successor race this exact
            // primary into the shared slot.
            failure = publishFatalPreserving(primary);
        }
        if (tookFence) {
            failure = IslandCommands.releaseAllExcept(failure);
        }
        if (failure != null) {
            TickRepairRun.<RuntimeException>rethrow(failure);
        }

        // Only a zero-failure batch may publish its applied records, progress,
        // successor, or terminal success summary.
        List<String> lines = new ArrayList<>();
        for (TickRepair.Finding finding : batchApplied) {
            lines.add(finding.line());
        }
        lines.addAll(batchNotes);
        if (!lines.isEmpty()) {
            // The durable applied-record append is its own success publication
            // after mandatory fence release. If W won first, no success record
            // is written; if the append wins entry, its exact IO failure becomes W.
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                try {
                    TickRepair.appendLines(reportFile, lines);
                } catch (IOException reportFailure) {
                    TickRepairRun.<RuntimeException>rethrow(reportFailure);
                }
            });
        }
        if (!queue.isEmpty()) {
            // The next batch is a successor, never a retry of an entered
            // candidate.  Its physical enqueue shares the W-vs-entry cut.
            RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule(
                    new TickTask(server.getTickCount(), () -> runBatch(
                            server, src, queue, totals, reportFile, worldRoot,
                            backupDir, backedUp, total))));
            return;
        }
        finishBatcherIfHealthy(server, src, totals, reportFile, "complete");
    }

    private static void finishBatcherIfHealthy(
            MinecraftServer server, CommandSourceStack src,
            TickRepair.Totals totals, Path reportFile, String status) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> {
            try {
                finishBatcher(server, src, totals, reportFile, status);
            } catch (IOException failureToFinish) {
                TickRepairRun.<RuntimeException>rethrow(failureToFinish);
            }
        });
    }

    private static void finishBatcher(MinecraftServer server, CommandSourceStack src,
                                      TickRepair.Totals totals, Path reportFile, String status)
            throws IOException {
        TickRepair.appendLines(reportFile,
                List.of("unloaded-repair " + status + ": " + totals.summary()));
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
                                 Set<Path> backedUp) {
        try {
            backupRegionFileEntered(worldRoot, backupDir, level, chunkKey, backedUp);
        } catch (IOException failure) {
            TickRepairRun.<RuntimeException>rethrow(failure);
        }
    }

    private static void backupRegionFileEntered(Path worldRoot, Path backupDir,
                                                ServerLevel level, long chunkKey,
                                                Set<Path> backedUp) throws IOException {
        Path regionDir = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
        Path source = regionDir.resolve("r." + (ChunkPos.getX(chunkKey) >> 5) + "."
                + (ChunkPos.getZ(chunkKey) >> 5) + ".mca");
        if (backedUp.contains(source)) {
            return;
        }
        // Flush BEFORE the copy so it cannot be torn by in-flight writes. An
        // exceptional storage future propagates its original cause exactly.
        joinExact(level.getChunkSource().chunkMap.synchronize(true));
        BasicFileAttributes sourceAttributes;
        try {
            sourceAttributes = Files.readAttributes(source, BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            return; // no external region data exists to back up
        }
        if (!sourceAttributes.isRegularFile()) {
            throw new IOException("region backup source is not a regular file: " + source);
        }
        Path destination = backupDir
                .resolve(TickRepair.dim(level).replace(':', '_').replace('/', '_'))
                .resolve(source.getFileName().toString());
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        if (!backedUp.add(source)) {
            throw new IllegalStateException("region backup ledger raced after copy: " + source);
        }
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    /** Thread-safe reply: hops to the server thread. */
    private static void reply(MinecraftServer server, CommandSourceStack src, String text) {
        RegionScheduler.runSuccessBodyIfHealthy(() -> server.schedule(
                new TickTask(server.getTickCount(), () -> runReply(src, text))));
    }

    private static void runReply(CommandSourceStack src, String text) {
        try {
            RegionScheduler.runSuccessBodyIfHealthy(() -> {
                src.sendSuccess(() -> Component.literal(text), false);
                LOGGER.info("[tick-repair] {}", text);
            });
        } catch (Throwable failure) {
            IslandCommands.publishAndPropagate(failure);
            throw new AssertionError("unreachable after fatal tick-repair feedback failure");
        }
    }

    /** CompletableFuture.join wraps an asynchronous primary; routing and
     *  storage gates propagate the exact underlying Throwable instead. */
    private static <T> T joinExact(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException wrapper) {
            Throwable primary = wrapper.getCause();
            if (primary == null) {
                throw wrapper;
            }
            TickRepairRun.<RuntimeException>rethrow(primary);
            throw new AssertionError("unreachable after exact future failure");
        }
    }

    private static Throwable publishFatalPreserving(Throwable primary) {
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
