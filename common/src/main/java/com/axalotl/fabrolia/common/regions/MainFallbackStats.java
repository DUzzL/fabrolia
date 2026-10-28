package com.axalotl.fabrolia.common.regions;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Observability only: why A5 island players leave their island for physical Main,
 * and which operations take the all-island Main fence. Every counter sits on a path
 * that is already a slow fallback (a Main handoff, a Main entity ordinal or a global
 * fence); the ordinary island path never touches this class. Counting never changes
 * a decision.
 *
 * <p>Read with {@code /fabrolia regions debug fallbacks [reset]}.
 */
public final class MainFallbackStats {

    /** Island-to-Main session handoffs, keyed by the cause that forced them. */
    public static final CauseCounter HANDOFFS = new CauseCounter(128);
    /** Player entity ordinals that took the Main fallback instead of an island turn. */
    public static final CauseCounter FALLBACKS = new CauseCounter(64);
    /** All-island Main fences taken for one routed or physical-Main mutation. */
    public static final CauseCounter FENCES = new CauseCounter(64);

    private MainFallbackStats() {}

    /** A packet routed to Main because its class is on the Main lane. */
    static void mainLanePacket(Packet<?> packet) {
        HANDOFFS.increment(new PacketCause("packet", packetKey(packet)));
    }

    /** A packet whose decoded block-ack sequence is invalid (always Main). */
    static void invalidAckPacket(Packet<?> packet) {
        HANDOFFS.increment(new PacketCause("invalid ack", packetKey(packet)));
    }

    /** An island-safe packet that turned out to need a Main interaction (PvP, rod reel). */
    static void interactionPacket(Packet<?> packet) {
        HANDOFFS.increment(new PacketCause("interaction", packetKey(packet)));
    }

    /**
     * Key a packet without building a string: its class, or for a custom payload the
     * payload type id (the channel). Clients choose payload ids freely, which is why
     * {@link CauseCounter} caps the number of distinct keys.
     */
    static Object packetKey(Packet<?> packet) {
        if (packet == null) return "null packet";
        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            try {
                return new PayloadKey(custom.payload().type().id());
            } catch (RuntimeException malformed) {
                // A payload without a type is still a custom payload packet.
                return ServerboundCustomPayloadPacket.class;
            }
        }
        return packet.getClass();
    }

    /** Human-readable name of a key, built only when reporting. */
    public static String describe(Object key) {
        if (key instanceof PacketCause cause) {
            return cause.kind() + " " + describe(cause.packet());
        }
        if (key instanceof PayloadKey payload) {
            return "ServerboundCustomPayloadPacket[" + payload.id() + "]";
        }
        if (key instanceof Class<?> type) return type.getSimpleName();
        return String.valueOf(key);
    }

    public static void resetAll() {
        HANDOFFS.reset();
        FALLBACKS.reset();
        FENCES.reset();
    }

    record PacketCause(String kind, Object packet) {}

    record PayloadKey(Object id) {}

    /** One reported row. */
    public record Entry(String cause, long count) {}

    /**
     * Concurrent per-cause counter. One map lookup plus one {@link LongAdder}
     * increment per event. The number of distinct keys is capped: once full, new
     * keys count under {@link #OTHER}, so client-chosen keys cannot grow the map.
     */
    public static final class CauseCounter {
        public static final String OTHER = "other";

        private final ConcurrentHashMap<Object, LongAdder> counts = new ConcurrentHashMap<>();
        private final int maxKeys;

        public CauseCounter(int maxKeys) {
            if (maxKeys < 1) throw new IllegalArgumentException("maxKeys");
            this.maxKeys = maxKeys;
        }

        public void increment(Object cause) {
            Object key = cause == null ? "unknown" : cause;
            LongAdder adder = counts.get(key);
            if (adder == null) {
                // Soft cap: a racing insert may exceed it by the number of racers.
                if (counts.size() >= maxKeys) key = OTHER;
                adder = counts.computeIfAbsent(key, ignored -> new LongAdder());
            }
            adder.increment();
        }

        public long count(Object cause) {
            LongAdder adder = counts.get(cause);
            return adder == null ? 0L : adder.sum();
        }

        public long total() {
            long total = 0L;
            for (LongAdder adder : counts.values()) total += adder.sum();
            return total;
        }

        public int distinctKeys() {
            return counts.size();
        }

        /** The {@code limit} largest counts, descending; ties by name. */
        public List<Entry> top(int limit, Function<Object, String> names) {
            List<Entry> all = new ArrayList<>(counts.size());
            for (Map.Entry<Object, LongAdder> e : counts.entrySet()) {
                long n = e.getValue().sum();
                if (n > 0L) all.add(new Entry(names.apply(e.getKey()), n));
            }
            all.sort(Comparator.comparingLong(Entry::count).reversed()
                    .thenComparing(Entry::cause));
            return all.size() <= limit ? all : List.copyOf(all.subList(0, Math.max(0, limit)));
        }

        public void reset() {
            counts.clear();
        }
    }
}
