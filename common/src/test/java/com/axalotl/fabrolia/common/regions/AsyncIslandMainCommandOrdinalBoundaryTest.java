package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MC-free regression model for the real-player join/entity-ordinal/command edge.
 *
 * <p>An accepted entity ordinal deliberately remains the listener FIFO head while
 * its physical-Main body is in flight.  A command's vanilla global task may run in
 * that interval, but it must park its exact packet-bound baton before entering the
 * command fence.  Entity completion publishes (but never runs inline) the first
 * deferred command in FIFO order.</p>
 */
final class AsyncIslandMainCommandOrdinalBoundaryTest {


    @Test
    void realJoinEntityOrdinalAndSlashCommandDoNotReactivateRetainedEntityHead() {
        Model model = new Model();
        model.acceptPacket("P0-player-loaded");
        model.acceptEntityOrdinal("E1");

        model.drainListenerMainUntilBlocked();
        assertEquals(List.of("P0-player-loaded@M"), model.commits);
        assertEquals(List.of("E1"), model.fifoIds(),
                "the active entity ordinal stays the authoritative FIFO head");
        assertEquals("E1", model.activeEntity.id);
        assertEquals(1, model.entityBodyPublications);

        CommandBaton command = model.acceptCommand("C1-/fabrolia regions players");
        model.runExactCommandTask(command);

        assertEquals(CommandPhase.DEFERRED, command.phase);
        assertEquals(0, model.commandFenceEntries,
                "a task blocked by retained E1 must defer before opening the fence");
        assertEquals(List.of("E1", "C1-/fabrolia regions players"), model.fifoIds());
        assertSame(model.activeEntity, model.fifo.peekFirst(),
                "the command task may neither remove nor reactivate active E1");
        assertEquals(1, model.entityBodyPublications,
                "the retained head has exactly one physical body publication");

        model.completeEntityBody();
        assertEquals(List.of("P0-player-loaded@M", "E1@M"), model.commits,
                "entity completion must not run the deferred command inline");
        assertEquals(CommandPhase.QUEUED, command.phase);
        assertEquals(2, command.publications,
                "the original TickTask and the one completion rearm are exact");
        assertEquals(0, model.commandFenceEntries);

        model.runExactCommandTask(command);
        assertEquals(CommandPhase.COMPLETE, command.phase);
        assertEquals(List.of("P0-player-loaded@M", "E1@M",
                "C1-/fabrolia regions players@M"), model.commits,
                "P0,E1,C1 must commit exactly once in listener FIFO order");
        assertEquals(1, model.commandFenceEntries);
        assertEquals(1, command.bodyEntries);
        assertTrue(model.fifo.isEmpty());
        assertFalse(model.listenerChainScheduled);
    }

    @Test
    void entityAndPriorCommandCompletionRearmOnlyFirstDeferredFifoCommand() {
        Model model = new Model();
        model.acceptEntityOrdinal("E1");
        model.drainListenerMainUntilBlocked();
        CommandBaton first = model.acceptCommand("C1");
        CommandBaton second = model.acceptCommand("C2");

        // C1's original vanilla slot is blocked by retained E1.
        model.runExactCommandTask(first);
        assertEquals(CommandPhase.DEFERRED, first.phase);
        assertEquals(CommandPhase.NEW, second.phase);
        assertEquals(0, model.commandFenceEntries);

        model.completeEntityBody();
        assertEquals(CommandPhase.QUEUED, first.phase,
                "E1 completion must rearm the first deferred FIFO command");
        assertEquals(CommandPhase.NEW, second.phase);
        assertEquals(List.of("E1@M"), model.commits,
                "publication of C1 is not permission to execute it inline");

        // C2's original vanilla slot can now run before the rearmed C1 slot. The
        // exact C1 marker, not task arrival order, remains C2's admission blocker.
        model.runExactCommandTask(second);
        assertEquals(CommandPhase.DEFERRED, second.phase);
        assertEquals(List.of("C1", "C2"), model.fifoIds());
        assertEquals(0, model.commandFenceEntries);

        model.runExactCommandTask(first);
        assertEquals(CommandPhase.COMPLETE, first.phase);
        assertEquals(CommandPhase.QUEUED, second.phase,
                "C1 completion transfers the exact baton to the next deferred command");
        assertEquals(List.of("E1@M", "C1@M"), model.commits);

        model.runExactCommandTask(second);
        assertEquals(CommandPhase.COMPLETE, second.phase);
        assertEquals(List.of("E1@M", "C1@M", "C2@M"), model.commits);
        assertEquals(2, model.commandFenceEntries);
        assertEquals(1, first.bodyEntries);
        assertEquals(1, second.bodyEntries);
        assertTrue(model.fifo.isEmpty());
        assertFalse(model.listenerChainScheduled);
    }

    @Test
    void terminalReceiptFifoPreventsNewerOriginalTaskOvertakingDeferredCommand() {
        Model model = new Model();
        model.acceptEntityOrdinal("E1");
        model.drainListenerMainUntilBlocked();
        CommandBaton first = model.acceptCommand("C1");
        CommandBaton second = model.acceptCommand("C2");

        model.runExactCommandTask(first);
        assertEquals(CommandPhase.DEFERRED, first.phase);
        assertEquals(CommandPhase.NEW, second.phase);

        model.terminalizeListener();
        assertEquals(List.of("C1", "C2"), model.terminalCommandIds(),
                "disconnect must retain exact command identities in listener FIFO order");
        assertEquals(CommandPhase.QUEUED, first.phase);
        assertEquals(CommandPhase.NEW, second.phase);

        // C2's original vanilla task was already published and may occupy Main
        // before C1's replacement slot. The terminal receipt FIFO still owns order.
        model.runExactCommandTask(second);
        assertEquals(CommandPhase.DEFERRED, second.phase);
        assertEquals(List.of("C1", "C2"), model.terminalCommandIds());
        assertTrue(model.commits.isEmpty());
        assertEquals(0, model.commandFenceEntries);

        model.runExactCommandTask(first);
        assertEquals(CommandPhase.COMPLETE, first.phase);
        assertEquals(CommandPhase.QUEUED, second.phase);
        assertEquals(List.of("C1@M"), model.commits);
        assertEquals(List.of("C2"), model.terminalCommandIds());

        model.runExactCommandTask(second);
        assertEquals(CommandPhase.COMPLETE, second.phase);
        assertEquals(List.of("C1@M", "C2@M"), model.commits,
                "terminal C1,C2 bodies must enter exactly once in retained order");
        assertEquals(1, first.bodyEntries);
        assertEquals(1, second.bodyEntries);
        assertEquals(2, model.commandFenceEntries);
        assertTrue(model.terminalCommands.isEmpty());
        assertFalse(model.listenerChainScheduled);
    }


    private enum Kind { PACKET, ENTITY, COMMAND }

    /** The production packet-bound baton has these exact legal task phases. */
    private enum CommandPhase { NEW, RUNNING, DEFERRED, QUEUED, COMPLETE }

    private static final class Entry {
        final String id;
        final Kind kind;
        final CommandBaton command;

        private Entry(String id, Kind kind, CommandBaton command) {
            this.id = id;
            this.kind = kind;
            this.command = command;
        }

        static Entry packet(String id) {
            return new Entry(id, Kind.PACKET, null);
        }

        static Entry entity(String id) {
            return new Entry(id, Kind.ENTITY, null);
        }

        static Entry command(CommandBaton baton) {
            return new Entry(baton.id, Kind.COMMAND, baton);
        }
    }

    private static final class CommandBaton {
        final String id;
        CommandPhase phase = CommandPhase.NEW;
        int publications = 1;
        int bodyEntries;

        private CommandBaton(String id) {
            this.id = id;
        }

        void beginTask() {
            if (phase != CommandPhase.NEW && phase != CommandPhase.QUEUED) {
                throw new IllegalStateException("command task entered from " + phase);
            }
            phase = CommandPhase.RUNNING;
        }

        void defer() {
            if (phase != CommandPhase.RUNNING) {
                throw new IllegalStateException("command defer from " + phase);
            }
            phase = CommandPhase.DEFERRED;
        }

        void rearm() {
            if (phase != CommandPhase.DEFERRED) {
                throw new IllegalStateException("command rearm from " + phase);
            }
            phase = CommandPhase.QUEUED;
            publications++;
        }

        void complete() {
            if (phase != CommandPhase.RUNNING) {
                throw new IllegalStateException("command completion from " + phase);
            }
            phase = CommandPhase.COMPLETE;
        }
    }

    private static final class Model {
        final ArrayDeque<Entry> fifo = new ArrayDeque<>();
        final ArrayDeque<CommandBaton> terminalCommands = new ArrayDeque<>();
        final ArrayList<String> commits = new ArrayList<>();
        Entry activeEntity;
        boolean listenerChainScheduled;
        boolean terminal;
        int entityBodyPublications;
        int commandFenceEntries;

        void acceptPacket(String id) {
            fifo.addLast(Entry.packet(id));
            listenerChainScheduled = true;
        }

        void acceptEntityOrdinal(String id) {
            fifo.addLast(Entry.entity(id));
            listenerChainScheduled = true;
        }

        CommandBaton acceptCommand(String id) {
            CommandBaton baton = new CommandBaton(id);
            fifo.addLast(Entry.command(baton));
            listenerChainScheduled = true;
            return baton;
        }

        void drainListenerMainUntilBlocked() {
            while (listenerChainScheduled) {
                Entry head = fifo.peekFirst();
                if (head == null) {
                    listenerChainScheduled = false;
                    return;
                }
                if (head.kind == Kind.COMMAND) {
                    // Only this marker's exact tagged vanilla task may consume it.
                    return;
                }
                if (head.kind == Kind.ENTITY) {
                    if (activeEntity == null) {
                        activeEntity = head;
                        entityBodyPublications++;
                    } else if (activeEntity != head) {
                        throw new IllegalStateException(
                                "another entity ordinal owns the listener chain");
                    }
                    // Retain the active ordinal until the physical body completes.
                    return;
                }
                fifo.removeFirst();
                commits.add(head.id + "@M");
            }
        }

        void runExactCommandTask(CommandBaton baton) {
            baton.beginTask();
            if (terminal) {
                runTerminalExactCommandTask(baton);
                return;
            }
            while (true) {
                Entry head = fifo.peekFirst();
                if (head == null) {
                    throw new IllegalStateException("exact command marker disappeared");
                }
                if (head.kind == Kind.PACKET) {
                    fifo.removeFirst();
                    commits.add(head.id + "@M");
                    continue;
                }
                if (head.kind == Kind.ENTITY) {
                    if (activeEntity == null) {
                        activeEntity = head;
                        entityBodyPublications++;
                    } else if (activeEntity != head) {
                        throw new IllegalStateException(
                                "command observed a non-head active entity ordinal");
                    }
                    baton.defer();
                    return;
                }
                if (head.command != baton) {
                    // A prior exact command owns the same FIFO boundary. Its packet
                    // completion is the sole edge allowed to rearm this task.
                    baton.defer();
                    return;
                }

                fifo.removeFirst();
                commandFenceEntries++;
                baton.bodyEntries++;
                commits.add(head.id + "@M");
                baton.complete();
                scheduleFirstDeferredCommand();
                if (fifo.isEmpty()) listenerChainScheduled = false;
                return;
            }
        }

        void completeEntityBody() {
            if (activeEntity == null || fifo.peekFirst() != activeEntity) {
                throw new IllegalStateException("entity completion lost retained head");
            }
            Entry completed = fifo.removeFirst();
            commits.add(completed.id + "@M");
            activeEntity = null;

            // The ordinary drain observes an exact command head and leaves it for
            // its tagged task. The completion edge then publishes only that task.
            drainListenerMainUntilBlocked();
            scheduleFirstDeferredCommand();
            if (fifo.isEmpty()) listenerChainScheduled = false;
        }

        void terminalizeListener() {
            if (terminal || !terminalCommands.isEmpty()) {
                throw new IllegalStateException("terminal command FIFO reused");
            }
            for (Entry entry : fifo) {
                if (entry.kind == Kind.COMMAND) {
                    terminalCommands.addLast(entry.command);
                }
            }
            fifo.clear();
            activeEntity = null;
            terminal = true;
            listenerChainScheduled = !terminalCommands.isEmpty();
            scheduleFirstDeferredCommand();
        }

        void runTerminalExactCommandTask(CommandBaton baton) {
            int matches = 0;
            for (CommandBaton retained : terminalCommands) {
                if (retained == baton) matches++;
            }
            if (matches != 1) {
                throw new IllegalStateException(
                        "terminal command lost its exact order receipt");
            }
            if (terminalCommands.peekFirst() != baton) {
                baton.defer();
                return;
            }
            if (terminalCommands.removeFirst() != baton) {
                throw new IllegalStateException("terminal command FIFO changed");
            }
            commandFenceEntries++;
            baton.bodyEntries++;
            commits.add(baton.id + "@M");
            baton.complete();
            scheduleFirstDeferredCommand();
            if (terminalCommands.isEmpty()) listenerChainScheduled = false;
        }

        void scheduleFirstDeferredCommand() {
            if (terminal) {
                CommandBaton first = terminalCommands.peekFirst();
                if (first != null && first.phase == CommandPhase.DEFERRED) {
                    first.rearm();
                }
                return;
            }
            for (Entry entry : fifo) {
                if (entry.kind != Kind.COMMAND) continue;
                if (entry.command.phase == CommandPhase.DEFERRED) {
                    entry.command.rearm();
                    return;
                }
                // A live earlier command is the admission token for every successor.
                if (entry.command.phase != CommandPhase.COMPLETE) return;
            }
        }

        List<String> fifoIds() {
            return fifo.stream().map(entry -> entry.id).toList();
        }

        List<String> terminalCommandIds() {
            return terminalCommands.stream().map(command -> command.id).toList();
        }
    }

}
