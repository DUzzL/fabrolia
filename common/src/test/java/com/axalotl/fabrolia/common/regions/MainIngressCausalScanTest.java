package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The causal-cutoff selection of the Main ingress has constant-time paths for "no routed
 * entry" and "the only routed entry is the newest envelope". They must pick exactly what the
 * full FIFO walk picks, for every queue shape.
 */
class MainIngressCausalScanTest {

    @Test
    void theConstantTimePathsSelectWhatTheFullWalkSelects() throws Exception {
        Random random = new Random(20261118L);
        for (int round = 0; round < 4000; round++) {
            int size = random.nextInt(9);
            boolean[] routed = new boolean[size];
            int routedCount = 0;
            for (int i = 0; i < size; i++) {
                // Bias towards the shapes of interest: few routed entries, often at the tail.
                routed[i] = random.nextInt(4) == 0 || (i == size - 1 && random.nextBoolean());
                if (routed[i]) routedCount++;
            }
            long cutoff = random.nextInt(4) == 0 ? 0L : 1 + random.nextInt(8);

            AsyncIslandPlayerTicks.PlayerState production = state(routed);
            AsyncIslandPlayerTicks.PlayerState reference = state(routed);
            setCutoff(production, cutoff);
            long referenceCutoff = cutoff;

            // Drain both queues completely, comparing every decision.
            for (int step = 0; step <= size; step++) {
                synchronized (production) {
                    boolean has = (boolean) HAS_CANDIDATE.invoke(null, production);
                    Decision expectedHas = referenceHas(reference, referenceCutoff);
                    referenceCutoff = expectedHas.cutoff;
                    assertEquals(expectedHas.found, has, "hasCausalRoutedCandidate, round " + round);
                    assertEquals(referenceCutoff, cutoff(production), "cutoff after has, round " + round);

                    Object peeked = PEEK.invoke(null, production);
                    assertEquals(position(reference, referencePeek(reference, referenceCutoff)),
                            position(production, peeked), "peek, round " + round);

                    Object polled = POLL.invoke(null, production);
                    Decision expectedPoll = referencePoll(reference, referenceCutoff);
                    referenceCutoff = expectedPoll.cutoff;
                    assertEquals(expectedPoll.sequenceOrOrdinal, describe(polled), "poll, round " + round);
                    assertEquals(referenceCutoff, cutoff(production), "cutoff after poll, round " + round);
                    assertEquals(reference.mainIngress.size(), production.mainIngress.size());
                    assertEquals(reference.routedIngressCount, production.routedIngressCount);
                }
            }
            assertTrue(production.mainIngress.isEmpty());
            assertTrue(routedCount >= 0);
        }
    }

    @Test
    void aLongOrdinaryBacklogInFrontOfTheOnlyRoutedEntryIsNotWalked() throws Exception {
        boolean[] routed = new boolean[50_001];
        routed[50_000] = true;
        AsyncIslandPlayerTicks.PlayerState state = state(routed);
        Object tail = state.mainIngress.peekLast();
        setCutoff(state, 50_001L); // the tail envelope carries sequence 50 001
        synchronized (state) {
            assertTrue((boolean) HAS_CANDIDATE.invoke(null, state));
            assertSame(tail, PEEK.invoke(null, state));
            assertSame(tail, POLL.invoke(null, state), "the handler's own continuation runs before the backlog");
            assertEquals(0, state.routedIngressCount);
            assertEquals(50_000, state.mainIngress.size());
        }
    }

    // ---- reference: the full FIFO walk as it was before the constant-time paths ----

    private record Decision(boolean found, long cutoff, String sequenceOrOrdinal) { }

    private static Decision referenceHas(AsyncIslandPlayerTicks.PlayerState state, long cutoff) {
        if (cutoff == 0L) return new Decision(false, 0L, null);
        for (AsyncIslandPlayerTicks.InboundPacket candidate : state.mainIngress) {
            if (candidate.isRoutedMutation() && candidate.routedSequence() <= cutoff) {
                return new Decision(true, cutoff, null);
            }
        }
        return new Decision(false, 0L, null);
    }

    private static Object referencePeek(AsyncIslandPlayerTicks.PlayerState state, long cutoff) {
        if (cutoff != 0L) {
            for (AsyncIslandPlayerTicks.InboundPacket candidate : state.mainIngress) {
                if (candidate.isRoutedMutation() && candidate.routedSequence() <= cutoff) return candidate;
            }
        }
        return state.mainIngress.peekFirst();
    }

    private static Decision referencePoll(AsyncIslandPlayerTicks.PlayerState state, long cutoff) {
        if (cutoff != 0L) {
            Iterator<AsyncIslandPlayerTicks.InboundPacket> iterator = state.mainIngress.iterator();
            while (iterator.hasNext()) {
                AsyncIslandPlayerTicks.InboundPacket candidate = iterator.next();
                if (candidate.isRoutedMutation() && candidate.routedSequence() <= cutoff) {
                    iterator.remove();
                    state.routedIngressCount--;
                    return new Decision(true, cutoff, describe(candidate));
                }
            }
            cutoff = 0L;
        }
        AsyncIslandPlayerTicks.InboundPacket head = state.mainIngress.pollFirst();
        if (head != null && head.isRoutedMutation()) state.routedIngressCount--;
        return new Decision(head != null, cutoff, describe(head));
    }

    // ---- fixture ----

    private static final Method HAS_CANDIDATE = method("hasCausalRoutedCandidateLocked");
    private static final Method PEEK = method("peekMainIngressLocked");
    private static final Method POLL = method("pollMainIngressLocked");
    private static final PacketListener LISTENER = allocate(net.minecraft.server.network.ServerGamePacketListenerImpl.class);

    private static Method method(String name) {
        try {
            Method method = AsyncIslandPlayerTicks.class.getDeclaredMethod(name, AsyncIslandPlayerTicks.PlayerState.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A queue whose i-th envelope is a routed mutation with sequence i + 1 where {@code routed[i]}. */
    private static AsyncIslandPlayerTicks.PlayerState state(boolean[] routed) throws Exception {
        AsyncIslandPlayerTicks.PlayerState state = new AsyncIslandPlayerTicks.PlayerState();
        Class<?> routedClass = Class.forName(AsyncIslandPlayerTicks.class.getName() + "$RoutedMutationPacket");
        Constructor<?> routedCtor = routedClass.getDeclaredConstructors()[0];
        routedCtor.setAccessible(true);
        List<AsyncIslandPlayerTicks.InboundPacket> entries = new ArrayList<>();
        for (int i = 0; i < routed.length; i++) {
            if (routed[i]) {
                Packet<?> packet = (Packet<?>) routedCtor.newInstance(state, null, "test",
                        (AsyncIslandPlayerTicks.RoutedPlayerMutation) (runtime, region, island) -> { });
                entries.add(new AsyncIslandPlayerTicks.InboundPacket(packet, LISTENER, i + 1L));
                state.routedIngressCount++;
            } else {
                Packet<?> ordinary = allocate(net.minecraft.network.protocol.game.ServerboundEditBookPacket.class);
                entries.add(new AsyncIslandPlayerTicks.InboundPacket(ordinary, LISTENER));
            }
        }
        state.mainIngress.addAll(entries);
        return state;
    }

    private static String describe(Object inbound) {
        if (inbound == null) return "none";
        AsyncIslandPlayerTicks.InboundPacket packet = (AsyncIslandPlayerTicks.InboundPacket) inbound;
        return packet.isRoutedMutation() ? "routed#" + packet.routedSequence() : "ordinary";
    }

    /** Index of an envelope in its queue: both queues were built in the same order. */
    private static int position(AsyncIslandPlayerTicks.PlayerState state, Object inbound) {
        int index = 0;
        for (AsyncIslandPlayerTicks.InboundPacket candidate : new ArrayDeque<>(state.mainIngress)) {
            if (candidate == inbound) return index;
            index++;
        }
        return -1;
    }

    private static void setCutoff(AsyncIslandPlayerTicks.PlayerState state, long cutoff) {
        state.causalRoutedCutoff = cutoff;
    }

    private static long cutoff(AsyncIslandPlayerTicks.PlayerState state) {
        return state.causalRoutedCutoff;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (T) ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
