package com.axalotl.fabrolia.common.regions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Main claim interest held on every island a {@code PlayerList.saveAll} pass
 * will claim, for the whole pass (F3).
 *
 * <p>Each player save claims its owner island through
 * {@link IslandCommands#mainClaimScoped}, which withdraws its own interest
 * right after the CAS. Without a standing interest a due unit of the same
 * island wins the claim between two of its players' saves, and the pass waits
 * the sum of the islands' units instead of the max. With it every island stops
 * at its next unit boundary in parallel (admission, chaining and the pacer
 * yield to {@link IslandRegistry.PIsland#mainInterest()}), and each later
 * claim of the island is an uncontended CAS. Main still holds at most one
 * claim at a time, as before; the vanilla loop and save order are untouched.
 * The same two-phase intent as {@code RegionizedServer.quiesceAllIslands}.
 */
public final class IslandSaveGroup {
    private final List<IslandRegistry.PIsland> islands;
    /** {@code islands[0, announced)} hold this group's interest. */
    private int announced;

    private IslandSaveGroup(List<IslandRegistry.PIsland> islands) {
        this.islands = islands;
    }

    /**
     * Announces interest once on every distinct island {@code ownerOf} names
     * for {@code keys} (null: the key's save takes no claim). Returns null when
     * there is none; otherwise the caller must {@link #close} the group.
     */
    public static <K> IslandSaveGroup open(Iterable<K> keys,
                                           Function<? super K, IslandRegistry.PIsland> ownerOf) {
        Set<IslandRegistry.PIsland> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IslandRegistry.PIsland> distinct = new ArrayList<>();
        for (K key : keys) {
            IslandRegistry.PIsland owner = ownerOf.apply(key);
            if (owner != null && owners.add(owner)) distinct.add(owner);
        }
        if (distinct.isEmpty()) return null;
        IslandSaveGroup group = new IslandSaveGroup(distinct);
        try {
            for (IslandRegistry.PIsland island : distinct) {
                island.announceClaimInterest();
                group.announced++;
            }
        } catch (Throwable thrown) {
            IslandCommands.publishAndPropagate(group.close(thrown));
        }
        return group;
    }

    /**
     * Withdraws every announced interest (a leaked one would stop its island
     * forever). Returns {@code primary} itself, or the published merged
     * failure when a withdrawal failed. Idempotent.
     */
    public Throwable close(Throwable primary) {
        Throwable failure = primary;
        while (announced > 0) {
            try {
                islands.get(--announced).withdrawClaimInterest();
            } catch (Throwable cleanup) {
                failure = merge(failure, cleanup);
            }
        }
        return failure;
    }

    private static Throwable merge(Throwable primary, Throwable cleanup) {
        if (primary == null) return RegionScheduler.publishFatalAndSelectWinner(cleanup);
        if (cleanup != primary) {
            try {
                primary.addSuppressed(cleanup);
            } catch (Throwable ignored) {
                // Cleanup diagnostics cannot replace the exact primary.
            }
        }
        return RegionScheduler.publishFatalAndSelectWinner(primary);
    }
}
