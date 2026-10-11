package com.axalotl.fabrolia.common.parallelised.advancement;

import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Scope of one trigger call's evaluation phase: the matcher and {@code player:}
 * conditions of its listeners, which vanilla runs before the first award.
 * Nothing mutates a player between two listeners of that phase, so a player's
 * {@code saveWithoutId} tag is computed once per scope and reused; awards and
 * reward functions run outside the scope and therefore always see fresh NBT.
 *
 * <p>A fire-time scope runs on the player's owner (or an off-owner producer)
 * while other islands tick. Full player serialization walks the player's ender
 * pearls, which may live on any island or level. Inside a fire-time scope that
 * part is omitted: {@code NbtUtils.compareNbt} is a subset match, so a compare
 * whose expected tag has no top-level {@code ender_pearls} key cannot observe
 * it. A compare that does reference it raises {@link RouteSignal} before any
 * serialization and the evaluation returns {@link #ROUTE}; the caller routes the
 * call to the fenced Main lane, where a quiescent scope serializes vanilla's
 * complete tag.
 *
 * <p>The scope is one mutable per-thread frame, saved and restored by each
 * evaluation; the player-tag cache is allocated only by a scope that compares
 * player NBT.
 */
public final class AdvancementCacheContext {
    private static final int NONE = 0;
    private static final int FIRE_TIME = 1;
    private static final int QUIESCENT = 2;

    private static final String ENDER_PEARLS = "ender_pearls";

    /** Returned by {@link #evaluateAtFireTime} when the evaluation needs the fenced Main lane. */
    static final Object ROUTE = new Object();

    private static final class Frame {
        int mode;
        boolean routeRequested;
        IdentityHashMap<ServerPlayer, CompoundTag> cache;
    }

    private static final ThreadLocal<Frame> FRAME = ThreadLocal.withInitial(Frame::new);

    private AdvancementCacheContext() {
    }

    /** Non-capturing evaluation body; callers pass static method references. */
    @FunctionalInterface
    interface Evaluation<A, B, C> {
        Object evaluate(A a, B b, C c);
    }

    /**
     * Raised inside a fire-time scope by a compare that needs foreign state.
     * Preallocated, stackless and suppression-free: vanilla's try-with-resources
     * around entity serialization may call addSuppressed on it.
     */
    static final class RouteSignal extends RuntimeException {
        static final RouteSignal INSTANCE = new RouteSignal();

        private RouteSignal() {
            super("advancement evaluation needs the fenced Main lane", null, false, false);
        }
    }

    /**
     * Evaluates one trigger call's listeners at fire time.
     * @return the body's result, or {@link #ROUTE} when the evaluation needs foreign
     *         state and must be repeated under the fence
     */
    static <A, B, C> Object evaluateAtFireTime(Evaluation<A, B, C> body, A a, B b, C c) {
        Frame frame = FRAME.get();
        int mode = frame.mode;
        boolean routeRequested = frame.routeRequested;
        IdentityHashMap<ServerPlayer, CompoundTag> cache = frame.cache;
        frame.mode = FIRE_TIME;
        frame.routeRequested = false;
        frame.cache = null;
        try {
            Object result = body.evaluate(a, b, c);
            // A predicate implementation that swallowed the signal still cannot
            // turn its foreign compare into a fire-time result.
            return frame.routeRequested ? ROUTE : result;
        } catch (RouteSignal signal) {
            return ROUTE;
        } finally {
            frame.mode = mode;
            frame.routeRequested = routeRequested;
            frame.cache = cache;
        }
    }

    /**
     * Evaluates with vanilla's complete player serialization. Callers have just
     * established quiescent ownership ({@link AdvancementOwnership#quiescentContext}):
     * a held fence, no live island, or the V2 actor.
     */
    static <A, B, C> Object evaluateQuiescent(Evaluation<A, B, C> body, A a, B b, C c) {
        Frame frame = FRAME.get();
        int mode = frame.mode;
        boolean routeRequested = frame.routeRequested;
        IdentityHashMap<ServerPlayer, CompoundTag> cache = frame.cache;
        frame.mode = QUIESCENT;
        frame.routeRequested = false;
        frame.cache = null;
        try {
            return body.evaluate(a, b, c);
        } finally {
            frame.mode = mode;
            frame.routeRequested = routeRequested;
            frame.cache = cache;
        }
    }

    /**
     * Called by the NBT predicate wraps before {@code getEntityTagToCompare}.
     * Throws {@link RouteSignal} when a fire-time compare of a player's tag
     * references its ender pearls.
     */
    public static void checkFireTimeCompare(Entity entity, CompoundTag expected) {
        if (!(entity instanceof ServerPlayer)) return;
        Frame frame = FRAME.get();
        if (frame.mode == FIRE_TIME && expected.contains(ENDER_PEARLS)) {
            frame.routeRequested = true;
            throw RouteSignal.INSTANCE;
        }
    }

    /** True while a fire-time scope serializes a player: omit its ender pearls. */
    public static boolean excludesEnderPearls() {
        return FRAME.get().mode == FIRE_TIME;
    }

    public static CompoundTag getOrComputePlayerNbt(ServerPlayer player, Supplier<CompoundTag> vanillaFetch) {
        Frame frame = FRAME.get();
        if (frame.mode == NONE) {
            return null;
        }
        IdentityHashMap<ServerPlayer, CompoundTag> cache = frame.cache;
        if (cache == null) {
            cache = new IdentityHashMap<>(4);
            frame.cache = cache;
        }
        CompoundTag tag = cache.get(player);
        if (tag == null) {
            tag = vanillaFetch.get();
            cache.put(player, tag);
        }
        return tag;
    }
}
