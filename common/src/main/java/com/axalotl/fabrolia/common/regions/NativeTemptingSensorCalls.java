package com.axalotl.fabrolia.common.regions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.sensing.TemptingSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

/** Keeps the native lazy stream inside its already-admitted query lifetime. */
public final class NativeTemptingSensorCalls {
    private static final ThreadLocal<Frame> FRAMES = ThreadLocal.withInitial(Frame::new);
    private static final ThreadLocal<Frame> ACTIVE = new ThreadLocal<>();

    private NativeTemptingSensorCalls() {}

    public static void tick(TemptingSensor sensor, ServerLevel level, PathfinderMob source,
                            Operation<Void> original) {
        Frame frame = FRAMES.get();
        while (frame.entered) {
            if (frame.next == null) frame.next = new Frame();
            frame = frame.next;
        }
        frame.previous = ACTIVE.get();
        frame.entered = true;
        ACTIVE.set(frame);
        try {
            NativeEntityDependencies.TemptingQuery query =
                    NativeEntityDependencies.beginTemptingSensor(sensor, level, source);
            if (query != null) {
                for (Frame outer = frame.previous; outer != null; outer = outer.previous) {
                    if (outer.query == query) {
                        // Do not close the outer invocation's declaration on rejection.
                        throw new IllegalStateException("reentrant native tempting-sensor declaration");
                    }
                }
            }
            frame.query = query;
            original.call(level, source);
        } finally {
            try {
                if (frame.query != null) frame.query.finish();
            } finally {
                ACTIVE.set(frame.previous);
                frame.previous = null;
                frame.query = null;
                frame.firstPredicate = null;
                frame.bound = false;
                frame.entered = false;
            }
        }
    }

    /** Receives the native copy after its native range write; never makes a copy. */
    public static TargetingConditions bind(TargetingConditions actualNativeCopy) {
        Frame frame = current();
        if (frame.bound || actualNativeCopy == null) {
            throw new IllegalStateException("invalid native tempting-sensor conditions binding");
        }
        if (frame.query != null) frame.query.bind(actualNativeCopy);
        frame.bound = true;
        return actualNativeCopy;
    }

    /** Replaces an argument, not a stream stage; all later native filters stay intact. */
    public static Predicate<? super ServerPlayer> firstPredicate(
            Predicate<? super ServerPlayer> original) {
        Frame frame = current();
        if (!frame.bound || frame.firstPredicate != null || original == null
                || frame.query != null && original != EntitySelector.NO_SPECTATORS) {
            throw new IllegalStateException("native tempting-sensor first predicate changed");
        }
        frame.firstPredicate = original;
        return frame;
    }

    private static Frame current() {
        Frame frame = ACTIVE.get();
        if (frame == null || !frame.entered) {
            throw new IllegalStateException("native tempting-sensor hook outside invocation");
        }
        return frame;
    }

    /** Allocated once per observed nesting depth and cleared after every invocation. */
    private static final class Frame implements Predicate<ServerPlayer> {
        private Frame next;
        private Frame previous;
        private NativeEntityDependencies.TemptingQuery query;
        private Predicate<? super ServerPlayer> firstPredicate;
        private boolean entered;
        private boolean bound;

        @Override
        public boolean test(ServerPlayer candidate) {
            if (!entered || ACTIVE.get() != this || !bound || firstPredicate == null) {
                throw new IllegalStateException("native tempting-sensor predicate outside invocation");
            }
            if (query != null) {
                // A true result includes real gameplay-observation authority;
                // only geometrically impossible supported candidates may be clipped.
                if (!query.permits(candidate)) return false;
            } else {
                // No declaration is no permission. Main or a genuinely owned
                // complete domain must authorize even the original first callback.
                NativePlayerObservations.requireGameplayObservation(candidate);
            }
            return firstPredicate.test(candidate);
        }
    }
}
