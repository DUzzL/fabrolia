package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Minecraft-free model for A5's one-shot lifecycle-to-Main completion edge. */
final class MainHandoffStructuralEdgeModelTest {

    @FunctionalInterface
    private interface Scheduler {
        void publish(Runnable task);
    }

    private enum Execution { NEW, PUBLISHING, PUBLISHED, RUNNING, TERMINAL, CANCELLED }

    private static final class Continuation implements Runnable {
        Execution execution = Execution.NEW;
        int bodyCalls;
        int rollbackCalls;
        Throwable bodyFailure;
        Throwable cancellationFailure;

        synchronized void admitBatchExecution() {
            if (execution != Execution.NEW) throw new IllegalStateException("lost staged state");
            execution = Execution.PUBLISHED;
        }

        @Override
        public synchronized void run() {
            if (execution == Execution.CANCELLED) throwUnchecked(cancellationFailure);
            if (execution != Execution.PUBLISHED) {
                throw new IllegalStateException("duplicate continuation execution");
            }
            execution = Execution.RUNNING;
            bodyCalls++;
            if (bodyFailure != null) {
                rollbackCalls++;
                execution = Execution.TERMINAL;
                throwUnchecked(bodyFailure);
            }
            execution = Execution.TERMINAL;
        }

        synchronized void cancelAndRollback(Throwable primary) {
            if (execution == Execution.NEW || execution == Execution.PUBLISHING
                    || execution == Execution.PUBLISHED) {
                cancellationFailure = primary;
                execution = Execution.CANCELLED;
            } else if (execution != Execution.CANCELLED && execution != Execution.TERMINAL) {
                throw new IllegalStateException("cancellation crossed body");
            }
            rollbackCalls++;
        }
    }

    private static final class Token {
        Continuation completed;
    }

    private static final class LifecycleState {
        int depth;
        Throwable firstFailure;
        Continuation pending;

        LifecycleState(int depth, Continuation pending) {
            this.depth = depth;
            this.pending = pending;
        }

        Throwable retire(Token token, Throwable primary) {
            if (depth <= 0) throw new IllegalStateException("depth underflow");
            if (primary != null && firstFailure == null) firstFailure = primary;
            depth--;
            if (depth != 0) return primary;

            Continuation closer = pending;
            pending = null;
            Throwable failure = firstFailure == null ? primary : firstFailure;
            firstFailure = null;
            if (closer == null) return failure;
            if (failure != null) {
                closer.cancelAndRollback(failure);
                return failure;
            }
            token.completed = closer;
            return null;
        }
    }

    private static final class Batch implements Runnable {
        final List<Token> tokens;
        Execution execution = Execution.NEW;
        int scheduleCalls;
        Throwable cancellationFailure;

        Batch(List<Token> tokens) {
            this.tokens = tokens;
        }

        synchronized void publishOnce(Scheduler scheduler) {
            if (execution != Execution.NEW) throw new IllegalStateException("duplicate publish");
            execution = Execution.PUBLISHING;
            scheduleCalls++;
            try {
                scheduler.publish(this);
            } catch (RuntimeException | Error failure) {
                if (execution == Execution.PUBLISHING) {
                    cancellationFailure = failure;
                    execution = Execution.CANCELLED;
                }
                throw failure;
            }
            if (execution == Execution.PUBLISHING) execution = Execution.PUBLISHED;
            else if (execution != Execution.TERMINAL) {
                throw new IllegalStateException("lost batch publication state");
            }
        }

        @Override
        public synchronized void run() {
            Throwable failure = null;
            if (execution == Execution.CANCELLED) {
                failure = cancellationFailure;
            } else if (execution != Execution.PUBLISHING
                    && execution != Execution.PUBLISHED) {
                failure = new IllegalStateException("duplicate batch execution");
            } else {
                execution = Execution.RUNNING;
            }
            try {
                if (failure == null) {
                    for (Token token : tokens) {
                        Continuation closer = token.completed;
                        if (closer == null) continue;
                        closer.admitBatchExecution();
                        token.completed = null;
                        closer.run();
                    }
                }
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
            } finally {
                failure = cancelAll(failure);
                execution = Execution.TERMINAL;
            }
            if (failure != null) throwUnchecked(failure);
        }

        synchronized Throwable cancelAll(Throwable primary) {
            if (execution == Execution.NEW || execution == Execution.PUBLISHING
                    || execution == Execution.PUBLISHED) {
                cancellationFailure = primary;
                execution = Execution.CANCELLED;
            }
            for (int index = tokens.size() - 1; index >= 0; index--) {
                Continuation closer = tokens.get(index).completed;
                tokens.get(index).completed = null;
                if (closer != null) closer.cancelAndRollback(primary);
            }
            return primary;
        }
    }

    private static Throwable publishScope(
            List<Token> tokens, Throwable primary, Scheduler scheduler,
            List<Batch> created) {
        if (primary != null) {
            return new Batch(tokens).cancelAll(primary);
        }
        if (tokens.stream().noneMatch(token -> token.completed != null)) return null;
        Batch batch = new Batch(tokens);
        created.add(batch);
        try {
            batch.publishOnce(scheduler);
            return null;
        } catch (RuntimeException | Error failure) {
            return batch.cancelAll(failure);
        }
    }

    @Test
    void allTokensRetireBeforeOneScopeBatchPublishes() {
        Continuation first = new Continuation();
        Continuation second = new Continuation();
        Token firstToken = new Token();
        Token secondToken = new Token();
        LifecycleState firstState = new LifecycleState(1, first);
        LifecycleState secondState = new LifecycleState(1, second);
        List<Runnable> main = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();

        assertNull(firstState.retire(firstToken, null));
        assertEquals(0, main.size(), "one retired token cannot publish early");
        assertNull(secondState.retire(secondToken, null));
        assertEquals(0, main.size(), "retirement only stages exact closers");
        assertNull(publishScope(List.of(firstToken, secondToken), null,
                main::add, batches));
        assertEquals(1, batches.getFirst().scheduleCalls);
        assertEquals(1, main.size(), "one scope publishes one current-tick batch");

        main.getFirst().run();
        assertEquals(1, first.bodyCalls);
        assertEquals(1, second.bodyCalls);
        assertThrows(IllegalStateException.class, main.getFirst()::run,
                "a duplicate batch invocation is fail-stop, never a replay");
    }

    @Test
    void laterScopeFailureCancelsAlreadyStagedCloserWithoutPublishing() {
        Continuation closer = new Continuation();
        Token staged = new Token();
        LifecycleState state = new LifecycleState(1, closer);
        RuntimeException primary = new RuntimeException("exact vanilla body failure");
        List<Runnable> main = new ArrayList<>();

        assertNull(state.retire(staged, null));
        assertSame(primary, publishScope(List.of(staged), primary, main::add,
                new ArrayList<>()));
        assertEquals(0, main.size());
        assertEquals(1, closer.rollbackCalls);
        assertEquals(Execution.CANCELLED, closer.execution);
    }

    @Test
    void enqueueThenThrowCancelsQueuedBatchAndCarriesExactFailure() {
        Continuation closer = new Continuation();
        Token token = new Token();
        token.completed = closer;
        RuntimeException primary = new RuntimeException("exact schedule failure");
        List<Runnable> accepted = new ArrayList<>();

        Throwable result = publishScope(List.of(token), null, task -> {
            accepted.add(task);
            throw primary;
        }, new ArrayList<>());

        assertSame(primary, result);
        assertEquals(1, closer.rollbackCalls);
        RuntimeException replayed = assertThrows(
                RuntimeException.class, accepted.getFirst()::run);
        assertSame(primary, replayed,
                "a queued cancelled batch must rethrow the exact publication failure");
        assertEquals(0, closer.bodyCalls,
                "an enqueue-then-throw batch may not enter a closer after rollback");
    }

    @Test
    void firstCloserFailurePreventsEverySuccessorBody() {
        RuntimeException primary = new RuntimeException("exact closer failure");
        Continuation failed = new Continuation();
        failed.bodyFailure = primary;
        Continuation successor = new Continuation();
        Token failedToken = new Token();
        failedToken.completed = failed;
        Token successorToken = new Token();
        successorToken.completed = successor;
        List<Runnable> main = new ArrayList<>();

        assertNull(publishScope(List.of(failedToken, successorToken), null,
                main::add, new ArrayList<>()));
        RuntimeException thrown = assertThrows(
                RuntimeException.class, main.getFirst()::run);

        assertSame(primary, thrown);
        assertEquals(1, failed.bodyCalls);
        assertEquals(0, successor.bodyCalls,
                "no successor tick/packet closer may enter after the winner");
        assertEquals(1, successor.rollbackCalls);
        assertEquals(Execution.CANCELLED, successor.execution);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
