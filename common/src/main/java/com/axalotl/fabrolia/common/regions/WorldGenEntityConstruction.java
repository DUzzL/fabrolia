package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.ParallelProcessor;
import com.axalotl.fabrolia.common.mixin.entity.EntityLevelCallbackAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Private entity construction during one synchronous vanilla generation invocation. */
public final class WorldGenEntityConstruction {
    private static final AtomicLong NEXT_SCOPE = new AtomicLong();
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    public interface Constructed {
        long fabrolia$constructionScope();
        void fabrolia$clearConstructionScope();
    }

    private WorldGenEntityConstruction() {}

    public static void enter(ServerLevel level) {
        Objects.requireNonNull(level, "generation level");
        State state = CURRENT.get();
        if (state == null) {
            state = new State();
            CURRENT.set(state);
        }
        if (state.depth == state.scopes.length) {
            int capacity = Math.multiplyExact(state.depth, 2);
            state.scopes = Arrays.copyOf(state.scopes, capacity);
            state.levels = Arrays.copyOf(state.levels, capacity);
        }
        long scope;
        long previous;
        do {
            previous = NEXT_SCOPE.get();
            if (previous == Long.MAX_VALUE) throw new IllegalStateException("generation scope ids exhausted");
            scope = previous + 1;
        } while (!NEXT_SCOPE.compareAndSet(previous, scope));
        state.scopes[state.depth] = scope;
        state.levels[state.depth++] = level;
    }

    public static void exit() {
        State state = CURRENT.get();
        if (state == null || state.depth == 0) throw new IllegalStateException("no generation construction scope");
        state.levels[--state.depth] = null;
        state.scopes[state.depth] = 0;
    }

    /** Constructor capture only: an entity is never re-stamped when a later scope opens. */
    public static long currentScope() {
        State state = CURRENT.get();
        return state == null || state.depth == 0 ? 0 : state.scopes[state.depth - 1];
    }

    /** Publication is irreversible, including after removal, cancellation or failed handback. */
    public static void revoke(Entity entity) {
        if (entity instanceof Constructed constructed) constructed.fabrolia$clearConstructionScope();
    }

    public static boolean owns(Entity... roots) {
        State state = CURRENT.get();
        if (state == null || state.depth == 0 || roots == null || roots.length == 0) return false;
        long scope = state.scopes[state.depth - 1];
        ServerLevel level = state.levels[state.depth - 1];
        // Reject all explicit participants before reading even the first ride link.
        for (Entity root : roots) if (!privateEntity(root, scope, level)) return false;
        try {
            for (Entity root : roots) {
                if (state.seen.containsKey(root)) continue;
                state.add(root);
                for (int index = state.cursor; index < state.size; index++) {
                    Entity entity = state.entities[index];
                    Entity vehicle = entity.getVehicle();
                    if (vehicle != null) {
                        if (!privateEntity(vehicle, scope, level)) return false;
                        if (!state.seen.containsKey(vehicle)) state.add(vehicle);
                    }
                    var passengers = entity.getPassengers();
                    for (int passengerIndex = 0; passengerIndex < passengers.size(); passengerIndex++) {
                        Entity passenger = passengers.get(passengerIndex);
                        if (!privateEntity(passenger, scope, level)) return false;
                        if (!state.seen.containsKey(passenger)) state.add(passenger);
                    }
                }
                state.cursor = state.size;
            }
            return true;
        } finally {
            // Reuse storage, but never retain entities/worlds after the synchronous operation.
            Arrays.fill(state.entities, 0, state.size, null);
            state.seen.clear();
            state.size = state.cursor = 0;
        }
    }

    private static boolean privateEntity(Entity entity, long scope, ServerLevel level) {
        if (entity == null || entity instanceof Player
                || !(entity instanceof Constructed constructed)
                || constructed.fabrolia$constructionScope() != scope
                || entity.level() != level || entity.isRemoved()
                || !(entity instanceof EntityLevelCallbackAccessor callback)
                || callback.fabrolia$getLevelCallback() != EntityInLevelCallback.NULL
                || !(entity instanceof RegionKeyed keyed) || keyed.fabrolia$getRegionKey() != RegionKeyed.NO_REGION
                || !(entity instanceof ParallelProcessor.TickGuard guard) || guard.fabrolia$isPortalPending()) return false;
        EntityTickLease lease = guard.fabrolia$tickLease();
        return lease != null && lease.get() == null && !lease.isArrivalPending()
                && NetherPortalPlayerAccess.requestForLifecycle(entity) == null;
    }

    private static final class State {
        long[] scopes = new long[4];
        ServerLevel[] levels = new ServerLevel[4];
        int depth;
        // Vanilla changes one side of a ride link first. Reuse a visited
        // worklist: classify every identity without validating transient topology.
        final IdentityHashMap<Entity, Boolean> seen = new IdentityHashMap<>();
        Entity[] entities = new Entity[8];
        int size;
        int cursor;

        void add(Entity entity) {
            if (size == entities.length) {
                int capacity = Math.multiplyExact(size, 2);
                entities = Arrays.copyOf(entities, capacity);
            }
            seen.put(entity, Boolean.TRUE);
            entities[size++] = entity;
        }
    }
}
