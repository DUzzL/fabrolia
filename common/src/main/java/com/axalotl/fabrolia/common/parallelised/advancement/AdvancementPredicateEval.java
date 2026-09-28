package com.axalotl.fabrolia.common.parallelised.advancement;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.regions.IslandCommands;
import com.axalotl.fabrolia.common.regions.RegionScheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/**
 * Ordered fan-out of deferred {@code player:} predicate evaluation over the tick pool
 * while physical Main holds the global fence.
 *
 * <p>Evaluation is read-only against quiescent state (every island unit has ended,
 * Main is parked in the fan-in), so the only per-thread hazard is state that a
 * predicate touches through its own player: {@code PlayerAdvancements.getOrStartProgress}
 * inserts into a plain map and the per-drain NBT cache is one serialization per player.
 * Every pending of one owner therefore evaluates inside ONE task, in index order; tasks
 * only ever share immutable pendings. Results are keyed by pending index, so the Main
 * award walk sees exactly the serial order regardless of which task produced a match.
 *
 * <p>Fail-stop: a task failure is published as the generation fatal, queued tasks are
 * cancelled, and Main rethrows the winner after every accepted task has terminated.
 */
final class AdvancementPredicateEval {

    private AdvancementPredicateEval() {}

    /** Below this many entries the fan-out overhead (pool wake, 20 µs park slices,
     *  chunk-executor pumps per slice) exceeds the serial walk. */
    static final int PARALLEL_MIN_ENTRIES = 128;

    interface Shape<P> {
        /** Partition key: all pendings of one owner evaluate in the same task, in order. */
        Object owner(P pending);

        int entries(P pending);
    }

    interface EntryPredicate<P> {
        boolean matches(P pending, int entry);
    }

    /**
     * @param fanOut    whether the caller holds the global fence (every island unit
     *                  ended, Main stays parked in the fan-in). Without it the tick
     *                  pool may be mid entity fan-out and the reads would race; the
     *                  walk then stays inline on the caller.
     * @param taskScope binds the per-task evaluation context (the drain-scoped player
     *                  NBT cache) around one task body; also used for the inline path.
     * @return {@code matched[i]} = ascending entry indices of {@code pendings[i]} that
     *         matched, or {@code null} when none did.
     */
    static <P> int[][] evaluate(P[] pendings, int n, Shape<P> shape,
                                EntryPredicate<P> predicate, boolean fanOut,
                                Consumer<Runnable> taskScope) {
        int[][] matched = new int[n][];
        if (n == 0) return matched;
        ForkJoinPool pool = fanOut && ParallelProcessor.executor instanceof ForkJoinPool candidate
                && !candidate.isShutdown() ? candidate : null;
        int[][] partition = pool == null ? null : partition(pendings, n, shape, pool.getParallelism());
        if (partition == null) {
            taskScope.accept(() -> evalRange(pendings, 0, n, shape, predicate, matched, null));
            return matched;
        }
        FailureGate gate = new FailureGate();
        EvalTask<P>[] tasks = newTaskArray(partition.length);
        for (int t = 0; t < partition.length; t++) {
            tasks[t] = new EvalTask<>(gate, pendings, partition[t], shape, predicate, matched, taskScope);
        }
        int submitted = 0;
        try {
            while (submitted < tasks.length) {
                EvalTask<P> task = tasks[submitted];
                submitted++;
                gate.runNext(() -> pool.execute(task));
            }
        } catch (Throwable submissionFailure) {
            gate.publishFailure(submissionFailure);
        }
        awaitFanIn(tasks, submitted, gate);
        AdvancementDiag.EVAL_FANOUTS.increment();
        AdvancementDiag.EVAL_TASKS.add(submitted);
        return matched;
    }

    /**
     * Groups pending indices by owner and packs the groups onto at most
     * {@code 2 × parallelism} tasks, heaviest group first onto the lightest task.
     * Index lists stay ascending because groups are built in index order.
     *
     * @return per-task ascending pending indices, or {@code null} when the batch is
     *         too small or the tick pool is unavailable and the walk must stay inline.
     */
    private static <P> int[][] partition(P[] pendings, int n, Shape<P> shape, int parallelism) {
        parallelism = Math.max(1, parallelism);
        IdentityHashMap<Object, Group> groups = new IdentityHashMap<>();
        ArrayList<Group> ordered = new ArrayList<>();
        int totalEntries = 0;
        for (int i = 0; i < n; i++) {
            P pending = pendings[i];
            int entries = shape.entries(pending);
            totalEntries += entries;
            Group group = groups.get(shape.owner(pending));
            if (group == null) {
                group = new Group();
                groups.put(shape.owner(pending), group);
                ordered.add(group);
            }
            group.add(i, entries);
        }
        if (ordered.size() < 2 || totalEntries < PARALLEL_MIN_ENTRIES) return null;
        int taskCount = Math.min(ordered.size(), parallelism << 1);
        ordered.sort((a, b) -> Integer.compare(b.weight, a.weight));
        Group[] bins = new Group[taskCount];
        for (int t = 0; t < taskCount; t++) bins[t] = new Group();
        for (Group group : ordered) {
            Group lightest = bins[0];
            for (int t = 1; t < taskCount; t++) {
                if (bins[t].weight < lightest.weight) lightest = bins[t];
            }
            lightest.absorb(group);
        }
        int[][] partition = new int[taskCount][];
        for (int t = 0; t < taskCount; t++) {
            int[] indices = Arrays.copyOf(bins[t].indices, bins[t].size);
            Arrays.sort(indices);
            partition[t] = indices;
        }
        return partition;
    }

    private static final class Group {
        int[] indices = new int[8];
        int size;
        int weight;

        void add(int index, int entries) {
            if (size == indices.length) indices = Arrays.copyOf(indices, size << 1);
            indices[size++] = index;
            weight += entries;
        }

        void absorb(Group other) {
            if (size + other.size > indices.length) {
                indices = Arrays.copyOf(indices, Math.max(size + other.size, size << 1));
            }
            System.arraycopy(other.indices, 0, indices, size, other.size);
            size += other.size;
            weight += other.weight;
        }
    }

    private static <P> void evalRange(P[] pendings, int from, int to, Shape<P> shape,
                                      EntryPredicate<P> predicate, int[][] matched,
                                      FailureGate gate) {
        for (int i = from; i < to; i++) {
            if (gate != null) gate.admitNext();
            matched[i] = evaluateOne(pendings[i], shape, predicate);
        }
    }

    private static <P> void evalIndices(P[] pendings, int[] indices, Shape<P> shape,
                                        EntryPredicate<P> predicate, int[][] matched,
                                        FailureGate gate) {
        for (int index : indices) {
            gate.admitNext();
            matched[index] = evaluateOne(pendings[index], shape, predicate);
        }
    }

    /** Inline evaluation of one pending on the caller; same result shape as the fan-out. */
    static <P> int[] evaluateOne(P pending, Shape<P> shape, EntryPredicate<P> predicate) {
        int entries = shape.entries(pending);
        int[] hits = null;
        int count = 0;
        for (int entry = 0; entry < entries; entry++) {
            if (!predicate.matches(pending, entry)) continue;
            if (hits == null) hits = new int[Math.min(entries, 4)];
            else if (count == hits.length) hits = Arrays.copyOf(hits, Math.min(entries, count << 1));
            hits[count++] = entry;
        }
        return hits == null || count == hits.length ? hits : Arrays.copyOf(hits, count);
    }

    /**
     * Main parks until every accepted task terminated. The park loop keeps servicing
     * the per-level chunk executors: an off-main block read that misses the cached
     * holder is bridged through {@code mainThreadProcessor}, which only Main pumps.
     * Nothing here may leave the loop while a task is still running: the caller's
     * scope releases the fence on return, and a surviving task would then read a
     * player its island has resumed ticking. A pump failure is published and waited out.
     */
    private static <P> void awaitFanIn(EvalTask<P>[] tasks, int count, FailureGate gate) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    RegionScheduler.throwWorkerFailureIfAny();
                } catch (Throwable fatal) {
                    gate.publishFailure(fatal);
                }
                if (gate.failureOrNull() != null) {
                    for (int i = 0; i < count; i++) tasks[i].cancelBeforeRun();
                }
                boolean complete = true;
                for (int i = 0; i < count; i++) {
                    if (!tasks[i].isDone()) {
                        complete = false;
                        break;
                    }
                }
                if (Thread.interrupted()) interrupted = true;
                if (complete) break;
                try {
                    IslandCommands.drainMainChunkServices();
                } catch (Throwable pumpFailure) {
                    gate.publishFailure(pumpFailure);
                }
                LockSupport.parkNanos("Fabrolia advancement-eval fan-in", 20_000L);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        for (int i = 0; i < count; i++) {
            try {
                tasks[i].join();
            } catch (Throwable encodedFailure) {
                if (gate.failureOrNull() == null) gate.publishFailure(encodedFailure);
            }
        }
        try {
            RegionScheduler.throwWorkerFailureIfAny();
        } catch (Throwable fatal) {
            gate.publishFailure(fatal);
        }
        Throwable failure = gate.failureOrNull();
        if (failure != null) AdvancementPredicateEval.<RuntimeException>rethrow(failure);
    }

    @SuppressWarnings("unchecked")
    private static <P> EvalTask<P>[] newTaskArray(int size) {
        return (EvalTask<P>[]) new EvalTask[size];
    }

    private static final class EvalTask<P> extends RecursiveAction {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;
        private static final int CANCELLED = 3;

        private final FailureGate gate;
        private final P[] pendings;
        private final int[] indices;
        private final Shape<P> shape;
        private final EntryPredicate<P> predicate;
        private final int[][] matched;
        private final Consumer<Runnable> taskScope;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private EvalTask(FailureGate gate, P[] pendings, int[] indices, Shape<P> shape,
                         EntryPredicate<P> predicate, int[][] matched,
                         Consumer<Runnable> taskScope) {
            this.gate = gate;
            this.pendings = pendings;
            this.indices = indices;
            this.shape = shape;
            this.predicate = predicate;
            this.matched = matched;
            this.taskScope = taskScope;
        }

        @Override
        protected void compute() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            Throwable failure = null;
            try {
                gate.admitNext();
                RegionScheduler.runSuccessBodyIfHealthy(() -> taskScope.accept(
                        () -> evalIndices(pendings, indices, shape, predicate, matched, gate)));
                gate.admitNext();
            } catch (Throwable thrown) {
                failure = gate.publishFailure(thrown);
            }
            state.set(TERMINAL);
            if (failure == null) {
                try {
                    RegionScheduler.commitSuccessIfHealthy(() -> {});
                } catch (Throwable terminalFailure) {
                    failure = gate.publishFailure(terminalFailure);
                }
            }
            if (failure != null) AdvancementPredicateEval.<RuntimeException>rethrow(failure);
        }

        void cancelBeforeRun() {
            if (state.compareAndSet(QUEUED, CANCELLED)) {
                Throwable failure = gate.failureOrNull();
                if (failure == null) {
                    complete(null);
                } else {
                    completeExceptionally(failure);
                }
            }
        }
    }

    /** First-wins failure slot; every publication also selects the generation W. */
    private static final class FailureGate {
        private Throwable failure;

        void runNext(Runnable body) {
            try {
                RegionScheduler.runSuccessBodyIfHealthy(() -> {
                    synchronized (this) {
                        if (failure != null) {
                            AdvancementPredicateEval.<RuntimeException>rethrow(failure);
                        }
                    }
                    body.run();
                });
            } catch (Throwable thrown) {
                AdvancementPredicateEval.<RuntimeException>rethrow(publishFailure(thrown));
            }
        }

        void admitNext() {
            Throwable observed = failureOrNull();
            if (observed != null) AdvancementPredicateEval.<RuntimeException>rethrow(observed);
            try {
                RegionScheduler.throwWorkerFailureIfAny();
            } catch (Throwable fatal) {
                AdvancementPredicateEval.<RuntimeException>rethrow(publishFailure(fatal));
            }
        }

        synchronized Throwable failureOrNull() {
            return failure;
        }

        synchronized Throwable publishFailure(Throwable candidate) {
            if (candidate == null) candidate = new NullPointerException("advancement eval failure");
            if (failure == null) {
                failure = candidate;
            } else if (failure != candidate) {
                try {
                    failure.addSuppressed(candidate);
                } catch (Throwable ignored) {
                    // Diagnostic suppression must never replace the exact primary.
                }
            }
            failure = RegionScheduler.publishFatalAndSelectWinner(failure);
            return failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
