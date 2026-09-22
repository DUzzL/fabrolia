package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime and source pins for the pool-null -> live-pool success-gate transition. */
final class RegionSchedulerNoPoolAdmissionTest {

    private Field poolField;
    private Field transitionPoolField;
    private Field orphanFatalField;
    private Object lifecycleLock;
    private Object previousPool;
    private Object previousTransitionPool;
    private Object previousOrphanFatal;

    @BeforeEach
    void isolateSchedulerGate() throws Exception {
        poolField = RegionScheduler.class.getDeclaredField("pool");
        transitionPoolField = RegionScheduler.class.getDeclaredField("transitionPool");
        orphanFatalField = RegionScheduler.class.getDeclaredField("orphanFatalFailure");
        Field lifecycleField = RegionScheduler.class.getDeclaredField("LIFECYCLE_LOCK");
        poolField.setAccessible(true);
        transitionPoolField.setAccessible(true);
        orphanFatalField.setAccessible(true);
        lifecycleField.setAccessible(true);
        lifecycleLock = lifecycleField.get(null);
        previousPool = poolField.get(null);
        previousTransitionPool = transitionPoolField.get(null);
        previousOrphanFatal = orphanFatalField.get(null);
        poolField.set(null, null);
        transitionPoolField.set(null, null);
        orphanFatalField.set(null, null);
    }

    @AfterEach
    void restoreSchedulerGate() throws Exception {
        poolField.set(null, previousPool);
        transitionPoolField.set(null, previousTransitionPool);
        orphanFatalField.set(null, previousOrphanFatal);
    }

    @Test
    void runnableAdmittedWithoutPoolObservesPoolWinnerAtItsTerminalEdge() {
        RegionWorkerPool installed = new RegionWorkerPool(1);
        RuntimeException winner = new RuntimeException("installed-pool W");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    setPool(installed);
                    installed.publishFatalFailure(winner);
                }));

        assertSame(winner, thrown);
    }

    @Test
    void booleanAdmittedWithoutPoolObservesPoolWinnerAtItsTerminalEdge() {
        RegionWorkerPool installed = new RegionWorkerPool(1);
        RuntimeException winner = new RuntimeException("installed-pool boolean W");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RegionScheduler.callSuccessBooleanIfHealthy(() -> {
                    setTransitionPool(installed);
                    installed.publishFatalFailure(winner);
                    return true;
                }));

        assertSame(winner, thrown);
    }

    @Test
    void transitionPoolOwnsPhysicalFailureBeforeActivePublication() throws Exception {
        RegionWorkerPool transition = new RegionWorkerPool(1);
        RuntimeException winner = new RuntimeException("startup transition W");
        transitionPoolField.set(null, transition);

        assertSame(winner, RegionScheduler.publishFatalAndSelectWinner(winner));
        assertSame(winner, transition.fatalFailureOrNull());
        assertSame(null, orphanFatalField.get(null),
                "a visible transition gate must not fork W into the orphan slot");
    }

    @Test
    void commitSelectsPoolOnlyAfterAcquiringTheLifecycleCut() throws Exception {
        RegionWorkerPool installed = new RegionWorkerPool(1);
        RuntimeException winner = new RuntimeException("commit transition W");
        installed.publishFatalFailure(winner);
        AtomicBoolean committed = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread contender;
        synchronized (lifecycleLock) {
            contender = Thread.ofPlatform().name("no-pool-commit-transition").start(() -> {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> committed.set(true));
                } catch (Throwable failure) {
                    thrown.set(failure);
                }
            });
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (contender.getState() != Thread.State.BLOCKED
                    && contender.isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, contender.getState(),
                    "commit contender did not reach the held lifecycle cut");
            poolField.set(null, installed);
        }
        contender.join(2_000L);

        assertFalse(contender.isAlive());
        assertFalse(committed.get(), "commit crossed into the installed failed pool");
        assertSame(winner, thrown.get());
    }

    @Test
    void sourceKeepsAdmissionAndTerminalCutsOutsideTheFallibleBody() throws Exception {
        String source = source();
        assertTrue(source.contains("return pool != null ? pool : transitionPool"),
                "startup and published runtimes must share one selected failure gate");
        String admissionHelper = slice(source,
                "private static RegionWorkerPool beginSuccessBodyIfHealthy()",
                "/**\n     * Terminal edge for a directly admitted body");
        assertTrue(admissionHelper.contains("synchronized (LIFECYCLE_LOCK)"));
        assertTrue(admissionHelper.indexOf("RegionWorkerPool current = failureGateLocked()")
                        < admissionHelper.indexOf("Throwable failure = orphanFatalFailure"),
                "pool selection and orphan admission must share one lifecycle cut");
        assertTrue(admissionHelper.contains("lifecycleState == LifecycleState.STOPPING")
                        && admissionHelper.contains(
                                "lifecycleOwner == Thread.currentThread()")
                        && admissionHelper.contains("current.isTerminated()")
                        && admissionHelper.contains(
                                "current.commitSuccessIfHealthy(() -> {})"),
                "only the exact post-join lifecycle owner may enter a direct body, "
                        + "and its W-vs-entry cut must remain on the published pool");

        String commit = slice(source,
                "public static void commitSuccessIfHealthy(Runnable commit)",
                "/**\n     * Select the body gate");
        assertTrue(commit.indexOf("synchronized (LIFECYCLE_LOCK)")
                        < commit.indexOf("RegionWorkerPool p = failureGateLocked()"),
                "commit may not snapshot null before acquiring the lifecycle cut");

        String run = slice(source,
                "public static void runSuccessBodyIfHealthy(Runnable body)",
                "/** Allocation-free boolean form");
        int admission = run.indexOf("beginSuccessBodyIfHealthy()");
        int body = run.indexOf("body.run()", admission);
        int terminal = run.indexOf("finishNoPoolSuccessBody(failure)", body);
        assertTrue(admission >= 0 && body > admission && terminal > body);
        assertFalse(run.contains("orphanFatalFailure"),
                "volatile orphan reads cannot stand in for atomic entry/terminal cuts");

        String bool = slice(source,
                "public static boolean callSuccessBooleanIfHealthy(",
                "/**\n     * Publish a failure caught");
        int boolAdmission = bool.indexOf("beginSuccessBodyIfHealthy()");
        int boolBody = bool.indexOf("body.getAsBoolean()", boolAdmission);
        int boolTerminal = bool.indexOf("finishNoPoolSuccessBody(failure)", boolBody);
        assertTrue(boolAdmission >= 0 && boolBody > boolAdmission
                && boolTerminal > boolBody);

        String publication = slice(source,
                "public static void publishFatalFailure(Throwable failure)",
                "/** F2b: registration-time cell stamp");
        int publicationCut = publication.indexOf("synchronized (LIFECYCLE_LOCK)");
        int currentPool = publication.indexOf(
                "RegionWorkerPool p = failureGateLocked()", publicationCut);
        int orphan = publication.indexOf("orphanFatalFailure = failure", currentPool);
        assertTrue(publicationCut >= 0 && currentPool > publicationCut && orphan > currentPool,
                "pool/orphan W routing must be chosen inside the lifecycle cut");

        String coldLevel = slice(source,
                "public static RegionizedServer forLevel(ServerLevel level)",
                "static Collection<RegionizedServer> allLevels()");
        int coldLifecycle = coldLevel.indexOf("synchronized (LIFECYCLE_LOCK)");
        int owningPool = coldLevel.indexOf("RegionWorkerPool current = pool", coldLifecycle);
        int runningGate = coldLevel.indexOf(
                "lifecycleState != LifecycleState.RUNNING", owningPool);
        int constructionCut = coldLevel.indexOf(
                "current.runSuccessBodyIfHealthy", runningGate);
        int constructor = coldLevel.indexOf(
                "new RegionizedServer(level, currentGrid)", constructionCut);
        int constructionCatch = coldLevel.indexOf(
                "catch (Throwable constructionFailure)", constructor);
        int constructionWinner = coldLevel.indexOf(
                "current.publishAndSelectFatal(constructionFailure)", constructionCatch);
        int poolCut = coldLevel.indexOf("current.commitSuccessIfHealthy", constructionWinner);
        int mapPut = coldLevel.indexOf("PER_LEVEL.put(level, created)", poolCut);
        int failedCleanup = coldLevel.indexOf("PER_LEVEL.remove(level, created)", mapPut);
        assertTrue(coldLifecycle >= 0 && owningPool > coldLifecycle
                        && runningGate > owningPool && constructionCut > runningGate
                        && constructor > constructionCut
                        && constructionCatch > constructor
                        && constructionWinner > constructionCatch
                        && poolCut > constructionWinner && mapPut > poolCut
                        && failedCleanup > mapPut,
                "cold level preparation must be admitted by its owning generation, "
                        + "select constructor W, publish only at the success cut, and "
                        + "remove a failed exact-key put");
    }

    private void setPool(RegionWorkerPool installed) {
        try {
            poolField.set(null, installed);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private void setTransitionPool(RegionWorkerPool installed) {
        try {
            transitionPoolField.set(null, installed);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String source() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve(
                    "src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java");
            Path common = root.resolve(
                    "common/src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java");
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate RegionScheduler.java");
    }

    private static String slice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        if (start < 0 || end < 0) throw new AssertionError("cannot locate source slice");
        return source.substring(start, end);
    }
}
