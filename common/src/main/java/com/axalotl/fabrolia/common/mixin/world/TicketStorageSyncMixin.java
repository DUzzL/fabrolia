package com.axalotl.fabrolia.common.mixin.world;

import com.axalotl.fabrolia.common.regions.TicketLockBind;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Close the REMAINING ticket-storage race (2026-08-18, ender-pearl incident
 * continued). {@code WorkerTicketRouteMixin} routed worker-thread pearl/portal
 * ticket ADDS to main, and {@code ChunkMapMixin#fabrolia$syncPlayerMove}
 * serialises worker player membership transitions on the ChunkMap monitor — but main's
 * own ticket work runs lock-free: {@code purgeStaleTickets} (fastIterator over
 * {@code tickets} every tick), the deferred pearl renewal drained from the
 * main-safe queue, forceload, view-distance changes. A worker's
 * {@code DistanceManager.addPlayer/removePlayer → TicketStorage.addTicket/
 * removeTicket} (plain {@code Long2ObjectOpenHashMap<List<Ticket>>} +
 * {@code ObjectArrayList} grow) racing any of those is the same hazard class
 * the pearl route fixed — a renewal silently lost under a rehash. Window
 * frequency scales with moving-player and worker ticket activity, which is why the
 * stand looked healthy and production did not.
 *
 * <p><b>Fix.</b> EVERY public entry point that touches the ticket maps takes
 * the owning ChunkMap's monitor (bound at {@code ServerChunkCache} init via
 * {@link TicketLockBind}). Worker move-writes already hold it, so the whole
 * ticket system — storage maps, per-chunk ticket lists, {@code Ticket.ticksLeft},
 * and the tracker-queue feeds inside the change listeners — is again mutated
 * under one global lock, exactly vanilla's single-thread invariant. Reentrancy
 * covers the internal self-calls ({@code updateChunkForced} → {@code addTicket},
 * {@code purgeStaleTickets} → {@code removeTicketIf}, consolidation reads under
 * {@code runDistanceManagerUpdates}). No new lock order: every path that holds
 * both ChunkMap and ticket state acquires ChunkMap first; purge/consolidation
 * under the monitor are bounded and never park, matching the existing
 * {@code fabrolia$serializeDistanceUpdatesWithPlayerMove} argument.
 *
 * <p>Unconditional (not gated on regions): an uncontended monitor on rare
 * ticket ops is noise, and worker pearl/portal and compatibility writers remain
 * possible independently of player-tick dispatch.
 */
@Mixin(TicketStorage.class)
public abstract class TicketStorageSyncMixin implements TicketLockBind {

    @Unique
    private volatile Object fabrolia$ticketLock;

    @Override
    public void fabrolia$setTicketLock(Object lock) {
        this.fabrolia$ticketLock = lock;
    }

    @WrapMethod(method = "addTicketWithRadius")
    private void fabrolia$syncAddRadius(TicketType type, ChunkPos pos, int radius,
                                        Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(type, pos, radius);
            return;
        }
        synchronized (lock) {
            original.call(type, pos, radius);
        }
    }

    @WrapMethod(method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V")
    private void fabrolia$syncAdd(Ticket ticket, ChunkPos pos, Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(ticket, pos);
            return;
        }
        synchronized (lock) {
            original.call(ticket, pos);
        }
    }

    @WrapMethod(method = "addTicket(JLnet/minecraft/server/level/Ticket;)Z")
    private boolean fabrolia$syncAddKeyed(long key, Ticket ticket, Operation<Boolean> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(key, ticket);
        }
        synchronized (lock) {
            return original.call(key, ticket);
        }
    }

    @WrapMethod(method = "removeTicketWithRadius")
    private void fabrolia$syncRemoveRadius(TicketType type, ChunkPos pos, int radius,
                                           Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(type, pos, radius);
            return;
        }
        synchronized (lock) {
            original.call(type, pos, radius);
        }
    }

    @WrapMethod(method = "removeTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V")
    private void fabrolia$syncRemove(Ticket ticket, ChunkPos pos, Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(ticket, pos);
            return;
        }
        synchronized (lock) {
            original.call(ticket, pos);
        }
    }

    @WrapMethod(method = "removeTicket(JLnet/minecraft/server/level/Ticket;)Z")
    private boolean fabrolia$syncRemoveKeyed(long key, Ticket ticket, Operation<Boolean> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(key, ticket);
        }
        synchronized (lock) {
            return original.call(key, ticket);
        }
    }

    @WrapMethod(method = "removeTicketIf")
    private void fabrolia$syncRemoveIf(TicketStorage.TicketPredicate predicate,
                                       @Nullable Long2ObjectOpenHashMap<List<Ticket>> removed,
                                       Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(predicate, removed);
            return;
        }
        synchronized (lock) {
            original.call(predicate, removed);
        }
    }

    @WrapMethod(method = "purgeStaleTickets")
    private void fabrolia$syncPurge(ChunkMap chunkMap, Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(chunkMap);
            return;
        }
        synchronized (lock) {
            original.call(chunkMap);
        }
    }

    @WrapMethod(method = "replaceTicketLevelOfType")
    private void fabrolia$syncReplaceLevel(int newLevel, TicketType type, Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call(newLevel, type);
            return;
        }
        synchronized (lock) {
            original.call(newLevel, type);
        }
    }

    @WrapMethod(method = "updateChunkForced")
    private boolean fabrolia$syncUpdateForced(ChunkPos pos, boolean forced,
                                              Operation<Boolean> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(pos, forced);
        }
        synchronized (lock) {
            return original.call(pos, forced);
        }
    }

    @WrapMethod(method = "activateAllDeactivatedTickets")
    private void fabrolia$syncActivateAll(Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call();
            return;
        }
        synchronized (lock) {
            original.call();
        }
    }

    @WrapMethod(method = "deactivateTicketsOnClosing")
    private void fabrolia$syncDeactivate(Operation<Void> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            original.call();
            return;
        }
        synchronized (lock) {
            original.call();
        }
    }

    @WrapMethod(method = "getTickets")
    private List<Ticket> fabrolia$syncGetTickets(long key, Operation<List<Ticket>> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(key);
        }
        synchronized (lock) {
            return original.call(key);
        }
    }

    @WrapMethod(method = "getTicketLevelAt(JZ)I")
    private int fabrolia$syncGetLevelAt(long key, boolean simulation, Operation<Integer> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(key, simulation);
        }
        synchronized (lock) {
            return original.call(key, simulation);
        }
    }

    @WrapMethod(method = "getTicketDebugString")
    private String fabrolia$syncDebugString(long key, boolean simulation,
                                            Operation<String> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call(key, simulation);
        }
        synchronized (lock) {
            return original.call(key, simulation);
        }
    }

    @WrapMethod(method = "hasTickets")
    private boolean fabrolia$syncHasTickets(Operation<Boolean> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call();
        }
        synchronized (lock) {
            return original.call();
        }
    }

    @WrapMethod(method = "shouldKeepDimensionActive")
    private boolean fabrolia$syncKeepActive(Operation<Boolean> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call();
        }
        synchronized (lock) {
            return original.call();
        }
    }

    @WrapMethod(method = "getForceLoadedChunks")
    private LongSet fabrolia$syncForcedChunks(Operation<LongSet> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call();
        }
        synchronized (lock) {
            return original.call();
        }
    }

    /** Autosave path: {@code packTickets} iterates both maps on main while worker
     *  moves keep writing — same race, rarer window. */
    @WrapMethod(method = "packTickets")
    private List<com.mojang.datafixers.util.Pair<ChunkPos, Ticket>> fabrolia$syncPack(
            Operation<List<com.mojang.datafixers.util.Pair<ChunkPos, Ticket>>> original) {
        Object lock = this.fabrolia$ticketLock;
        if (lock == null) {
            return original.call();
        }
        synchronized (lock) {
            return original.call();
        }
    }
}
