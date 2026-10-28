package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Writes a vanilla crash report when a Fabrolia generation selects its fatal winner
 * (AGENTS.md §9). The fail-stop itself is unchanged: the process keeps running and
 * the winner keeps surfacing at Main's poll points.
 *
 * <p>Callers are the exact first-set edges of a winner slot: every write of
 * {@code RegionWorkerPool.fatalFailure} (each guarded by "slot was null" under the
 * pool's lifecycle monitor, so it happens once per pool generation) and every
 * write of {@code RegionScheduler.orphanFatalFailure} (once per process; it is
 * never cleared). A merged orphan that is the retired pool's own winner is not
 * reported again.
 *
 * <p>This is diagnostic I/O only. {@link #winnerSelected} never throws and never
 * blocks: it starts one daemon writer thread and returns, so it cannot change which
 * Throwable wins, skip a fail-stop step, or wait on disk while a caller holds a
 * scheduler or pool monitor. The writer takes no Fabrolia lock. A failure to write
 * is logged at ERROR and swallowed; it is the only failure this class swallows.
 */
public final class FailStopCrashReports {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailStopCrashReports.class);
    private static final AtomicInteger WRITERS = new AtomicInteger();
    /**
     * {@code -Dfabrolia.failStopCrashReports=false} turns the file writer off. The
     * unit-test task sets it: many tests select winners on fixture servers whose
     * directory would otherwise collect reports.
     */
    static final String ENABLED_PROPERTY = "fabrolia.failStopCrashReports";
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));

    /** Test seam: receives each selected winner once. Must not block. */
    static volatile Consumer<Throwable> sink = FailStopCrashReports::startWriter;

    private FailStopCrashReports() {}

    /**
     * Report a winner which the caller has just installed into an empty fatal slot.
     * Never throws.
     */
    static void winnerSelected(Throwable winner) {
        if (winner == null) return;
        try {
            Consumer<Throwable> target = sink;
            if (target != null) target.accept(winner);
        } catch (Throwable ignored) {
            // Diagnostic only (for example an OutOfMemoryError while starting the
            // writer thread under an OOM winner): the selected winner and the
            // caller's remaining fail-stop steps take precedence.
        }
    }

    private static void startWriter(Throwable winner) {
        if (!ENABLED) return;
        Path serverDirectory = serverDirectoryOrNull();
        // No running server (unit tests, a failure before or after the server's
        // lifetime): there is no crash-reports directory to write into.
        if (serverDirectory == null) return;
        Thread writer = new Thread(() -> write(serverDirectory, winner),
                "Fabrolia-Crash-Report-" + WRITERS.incrementAndGet());
        writer.setDaemon(true);
        writer.start();
    }

    private static Path serverDirectoryOrNull() {
        MinecraftServer server = RegionScheduler.getServer();
        if (server == null) server = ParallelProcessor.getServer();
        return server == null ? null : server.getServerDirectory();
    }

    /** Vanilla's crash report location (see {@code MinecraftServer.runServer}). */
    static Path reportPath(Path serverDirectory, String formattedDateTime) {
        return serverDirectory.resolve("crash-reports")
                .resolve("crash-" + formattedDateTime + "-server.txt");
    }

    private static void write(Path serverDirectory, Throwable winner) {
        Path file = null;
        try {
            file = reportPath(serverDirectory, Util.getFilenameFormattedDateTime());
            // No MinecraftServer.fillSystemReport: it reads server state that only
            // physical Main may read, and this thread owns nothing.
            CrashReport report = CrashReport.forThrowable(winner, "Fabrolia fail-stop");
            if (report.saveToFile(file, ReportType.CRASH)) {
                LOGGER.error("Fabrolia fail-stop: crash report saved to {}",
                        file.toAbsolutePath());
            } else {
                LOGGER.error("Fabrolia fail-stop: could not save the crash report to {}",
                        file.toAbsolutePath());
            }
        } catch (Throwable writeFailure) {
            try {
                LOGGER.error("Fabrolia fail-stop: writing the crash report to {} failed",
                        file, writeFailure);
            } catch (Throwable ignored) {
                // Diagnostic I/O only; nothing depends on this thread.
            }
        }
    }
}
