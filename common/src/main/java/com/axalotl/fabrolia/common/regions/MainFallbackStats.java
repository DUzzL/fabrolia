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
 * which operations take the all-island Main fence, and how long Main waits for
 * island units. Every counter sits on a path that is already a slow fallback (a Main
 * handoff, a Main entity ordinal, a global fence, a contended Main claim or a
 * blocking island-bucket drain); the ordinary island path and an uncontended claim
 * never touch this class. Counting never changes a decision.
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

    /** Every all-island fence that had to take claims ({@code IslandCommands.quiesceAllExcept}):
     *  acquisition wait and hold time, keyed by caller. */
    public static final WaitLedger FENCE_WAITS = new WaitLedger(64);
    /** Contended single-island Main claims ({@code IslandCommands.mainClaim}), keyed by caller. */
    public static final WaitLedger CLAIM_WAITS = new WaitLedger(64);
    /** Blocking island-bucket drains ({@code RegionScheduler.IslandBuckets.waitForRemaining}):
     *  the claim waits of one drain, without the bucket bodies, keyed by caller. */
    public static final WaitLedger BUCKET_WAITS = new WaitLedger(32);
    /** Standing service requests (MAIN_SYNC, MAIN_TRACKING): time from the request
     *  until Main served the island, keyed by lane. The island stops at its next
     *  unit boundary for this time instead of Main waiting for its unit. */
    public static final WaitLedger SERVICE_STOPS = new WaitLedger(8);

    /** Suffix of a fence whose spin budget expired before it held every claim. */
    public static final String BUDGET_EXPIRED = " [budget expired]";

    /** Caller frames named in a wait key, innermost first. */
    static final int KEY_DEPTH = 3;
    /** Upper bound on frames inspected while building one wait key. */
    static final int KEY_WALK_LIMIT = 32;

    private static final StackWalker WALKER = StackWalker.getInstance();

    private static volatile long windowStartNanos = System.nanoTime();

    private MainFallbackStats() {}

    /**
     * Caller of the current wait, read from the stack: the first {@link #KEY_DEPTH}
     * frames outside the wait machinery. Called only on a slow path that already
     * waited, never on an uncontended claim.
     */
    static String currentCallerKey() {
        return WALKER.walk(frames -> callerKey(frames.limit(KEY_WALK_LIMIT)
                .map(f -> new FrameName(f.getClassName(), f.getMethodName()))));
    }

    /** {@link #currentCallerKey} over given frames, innermost first. */
    static String callerKey(java.util.stream.Stream<FrameName> frames) {
        StringBuilder key = new StringBuilder();
        String last = null;
        int named = 0;
        for (java.util.Iterator<FrameName> it = frames.iterator(); it.hasNext() && named < KEY_DEPTH; ) {
            FrameName frame = it.next();
            if (skipped(frame.className(), frame.methodName())) continue;
            String name = simpleClassName(frame.className()) + "." + methodName(frame.methodName());
            if (name.equals(last)) continue;
            if (named > 0) key.append(" < ");
            key.append(name);
            last = name;
            named++;
        }
        return named == 0 ? "unknown" : key.toString();
    }

    private static boolean skipped(String className, String method) {
        if (className.startsWith("java.") || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.llamalad7.mixinextras.")
                || className.startsWith("org.spongepowered.")) {
            return true;
        }
        String regions = "com.axalotl.fabrolia.common.regions.";
        if (!className.startsWith(regions)) return false;
        String local = className.substring(regions.length());
        if (local.equals("MainFallbackStats") || local.startsWith("MainFallbackStats$")
                || local.equals("FailStops")
                || local.equals("RegionScheduler$IslandBuckets")
                || local.startsWith("RegionScheduler$IslandBuckets$")) {
            return true;
        }
        if (local.equals("IslandCommands")) {
            return switch (method) {
                case "mainClaim", "mainClaimScoped", "tryMainClaim", "quiesceAllExcept" -> true;
                default -> false;
            };
        }
        if (local.equals("RegionScheduler")) {
            return switch (method) {
                case "runSuccessBodyIfHealthy", "callSuccessBooleanIfHealthy",
                     "commitSuccessIfHealthy" -> true;
                default -> false;
            };
        }
        return false;
    }

    /** Simple binary name; a mixin-merged class's {@code $<32 hex>} suffix is dropped. */
    static String simpleClassName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        int cut = name.lastIndexOf('$');
        if (cut >= 0 && name.length() - cut - 1 == 32
                && name.substring(cut + 1).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            name = name.substring(0, cut);
        }
        return name;
    }

    /** {@code lambda$foo$3} is {@code foo}; a mixin handler is its {@code fabrolia$} name. */
    static String methodName(String method) {
        String name = method;
        if (name.startsWith("lambda$")) {
            name = name.substring("lambda$".length());
            int end = name.length();
            while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
            if (end > 0 && end < name.length() && name.charAt(end - 1) == '$') {
                name = name.substring(0, end - 1);
            }
        }
        int handler = name.lastIndexOf("fabrolia$");
        return handler > 0 ? name.substring(handler) : name;
    }

    /** Real time covered by the counters: since start or the last reset. */
    public static long windowNanos() {
        return System.nanoTime() - windowStartNanos;
    }

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
        FENCE_WAITS.reset();
        CLAIM_WAITS.reset();
        BUCKET_WAITS.reset();
        SERVICE_STOPS.reset();
        windowStartNanos = System.nanoTime();
    }

    record PacketCause(String kind, Object packet) {}

    record PayloadKey(Object id) {}

    record FrameName(String className, String methodName) {}

    /** One reported row. */
    public record Entry(String cause, long count) {}

    /** One reported wait row; {@code holdNanos} is only filled for fences. */
    public record WaitEntry(String caller, long events, long waitNanos, long maxWaitNanos,
                            long holdNanos) {}

    /**
     * Time Main spent inside one kind of wait, per caller. One map lookup plus a few
     * adders per event, distinct callers capped like {@link CauseCounter}.
     */
    public static final class WaitLedger {
        private final ConcurrentHashMap<String, WaitStat> stats = new ConcurrentHashMap<>();
        private final int maxKeys;

        public WaitLedger(int maxKeys) {
            if (maxKeys < 1) throw new IllegalArgumentException("maxKeys");
            this.maxKeys = maxKeys;
        }

        /** One wait of the current caller. */
        public String record(long waitNanos) {
            String caller = currentCallerKey();
            record(caller, waitNanos);
            return caller;
        }

        public void record(String caller, long waitNanos) {
            WaitStat stat = stat(caller);
            stat.events.increment();
            stat.waitNanos.add(Math.max(0L, waitNanos));
            stat.maxWaitNanos.accumulate(waitNanos);
        }

        /** Hold time of an event recorded earlier under {@code caller}. */
        public void recordHold(String caller, long holdNanos) {
            stat(caller).holdNanos.add(Math.max(0L, holdNanos));
        }

        private WaitStat stat(String caller) {
            String key = caller == null ? "unknown" : caller;
            WaitStat stat = stats.get(key);
            if (stat == null) {
                // Soft cap: a racing insert may exceed it by the number of racers.
                if (stats.size() >= maxKeys) key = CauseCounter.OTHER;
                stat = stats.computeIfAbsent(key, ignored -> new WaitStat());
            }
            return stat;
        }

        public long events() {
            long total = 0L;
            for (WaitStat stat : stats.values()) total += stat.events.sum();
            return total;
        }

        public long waitNanos() {
            long total = 0L;
            for (WaitStat stat : stats.values()) total += stat.waitNanos.sum();
            return total;
        }

        public long holdNanos() {
            long total = 0L;
            for (WaitStat stat : stats.values()) total += stat.holdNanos.sum();
            return total;
        }

        public int distinctKeys() {
            return stats.size();
        }

        /** The {@code limit} largest wait totals, descending; ties by caller. */
        public List<WaitEntry> top(int limit) {
            List<WaitEntry> all = new ArrayList<>(stats.size());
            for (Map.Entry<String, WaitStat> e : stats.entrySet()) {
                WaitStat s = e.getValue();
                long events = s.events.sum();
                if (events > 0L) {
                    all.add(new WaitEntry(e.getKey(), events, s.waitNanos.sum(),
                            s.maxWaitNanos.get(), s.holdNanos.sum()));
                }
            }
            all.sort(Comparator.comparingLong(WaitEntry::waitNanos).reversed()
                    .thenComparing(WaitEntry::caller));
            return all.size() <= limit ? all : List.copyOf(all.subList(0, Math.max(0, limit)));
        }

        public void reset() {
            stats.clear();
        }
    }

    private static final class WaitStat {
        final LongAdder events = new LongAdder();
        final LongAdder waitNanos = new LongAdder();
        final java.util.concurrent.atomic.LongAccumulator maxWaitNanos =
                new java.util.concurrent.atomic.LongAccumulator(Math::max, 0L);
        final LongAdder holdNanos = new LongAdder();
    }

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
