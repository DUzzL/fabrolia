package com.axalotl.fabrolia.common.regions;

import net.minecraft.core.SectionPos;

/**
 * Per-thread replay of the four values which decide whether {@code ChunkMap.move}
 * reaches its player-membership writer suffix.
 *
 * <p>The common same-section path deliberately runs without the global ChunkMap
 * monitor.  Its admission check and vanilla's later branch must nevertheless observe
 * one decision: a concurrent player-status or spectator-policy change may otherwise
 * turn an admitted reader into a writer after the check.  The mixin records the exact
 * admitted values here and substitutes them at vanilla's four branch reads.  Nested
 * move calls are represented by a small reusable stack; a transition call pushes a
 * live sentinel so it cannot inherit an outer fast-path snapshot.</p>
 */
public final class PlayerMoveReadSnapshot {

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private PlayerMoveReadSnapshot() {}

    public static void pushStable(SectionPos lastSection, SectionPos currentSection,
                                  boolean ignored, boolean skip) {
        STATE.get().push(lastSection, currentSection, ignored, skip, true);
    }

    public static void pushLive() {
        STATE.get().push(null, null, false, false, false);
    }

    public static void pop() {
        STATE.get().pop();
    }

    public static SectionPos lastSectionOr(SectionPos live) {
        State state = STATE.get();
        return state.stable() ? state.lastSection[state.depth - 1] : live;
    }

    public static SectionPos currentSectionOr(SectionPos live) {
        State state = STATE.get();
        return state.stable() ? state.currentSection[state.depth - 1] : live;
    }

    public static boolean ignoredOr(boolean live) {
        State state = STATE.get();
        return state.stable() ? state.ignored[state.depth - 1] : live;
    }

    public static boolean skipOr(boolean live) {
        State state = STATE.get();
        return state.stable() ? state.skip[state.depth - 1] : live;
    }

    private static final class State {
        private SectionPos[] lastSection = new SectionPos[2];
        private SectionPos[] currentSection = new SectionPos[2];
        private boolean[] ignored = new boolean[2];
        private boolean[] skip = new boolean[2];
        private boolean[] stable = new boolean[2];
        private int depth;

        private void push(SectionPos last, SectionPos current,
                          boolean ignoredValue, boolean skipValue,
                          boolean stableValue) {
            ensureCapacity(depth + 1);
            lastSection[depth] = last;
            currentSection[depth] = current;
            ignored[depth] = ignoredValue;
            skip[depth] = skipValue;
            stable[depth] = stableValue;
            depth++;
        }

        private void pop() {
            if (depth == 0) {
                throw new IllegalStateException("Player move snapshot stack underflow");
            }
            int index = --depth;
            lastSection[index] = null;
            currentSection[index] = null;
            stable[index] = false;
        }

        private boolean stable() {
            return depth != 0 && stable[depth - 1];
        }

        private void ensureCapacity(int required) {
            if (required <= stable.length) return;
            int capacity = stable.length << 1;
            while (capacity < required) capacity <<= 1;
            lastSection = java.util.Arrays.copyOf(lastSection, capacity);
            currentSection = java.util.Arrays.copyOf(currentSection, capacity);
            ignored = java.util.Arrays.copyOf(ignored, capacity);
            skip = java.util.Arrays.copyOf(skip, capacity);
            stable = java.util.Arrays.copyOf(stable, capacity);
        }
    }
}
