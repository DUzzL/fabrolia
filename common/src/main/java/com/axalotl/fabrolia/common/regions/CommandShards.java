package com.axalotl.fabrolia.common.regions;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * A8 Phase 2 — invocation SHARDS (docs/ASYNC-ISLANDS.md §12).
 *
 * <p>A routed command whose effect decomposes into independent per-target
 * applications (the adapter registry below) is executed as ONE logical
 * invocation carried by N ephemeral shards — one per affected ownership
 * domain — instead of one fenced global dispatch:
 *
 * <ol>
 *   <li>the SOURCE island's coordinator resolves the selector once, in its
 *       own command-source context (position/rotation-correct for @p-style
 *       sorting), against the live ownership index;</li>
 *   <li>targets are partitioned by owner island: the source's own targets
 *       apply INLINE (we are already on the owner thread), each foreign
 *       island gets one shard on its shard lane, global-lane targets ride
 *       the fenced routed batch;</li>
 *   <li>each shard applies on ITS island's thread — at that island's
 *       cadence, mutating only state that island owns. Ownership is
 *       re-validated per target at apply time; a target whose owner changed
 *       (merge/split/teleport between resolution and application) is
 *       REDIRECTED to its current owner (bounded, then falls back to the
 *       fenced path) — never applied twice, never dropped;</li>
 *   <li>the LAST committing shard aggregates the success count once and
 *       completes the invocation future; the source's chain resumes with
 *       the exact vanilla-shaped result. The island count never multiplies
 *       the result.</li>
 * </ol>
 *
 * <p>Exactly-once: a target appears in exactly one shard; shard bodies are
 * consumed from their queues exactly once (queues are claim-protected by the
 * owner's unit or serviced by its PARKED coordinator — never both, see
 * {@link #serviceShards}); redirected targets move, they are not copied.
 * Merge/split safety: rebuild's quiesce window re-partitions every pending
 * shard by CURRENT ownership (same invocation identity, committed shards
 * unaffected). A parked source waiting on its shards keeps servicing ITS
 * OWN island's incoming shards, so two islands sharding at each other
 * complete within a park-poll latency instead of deadlocking.
 */
public final class CommandShards {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-A8");

    private CommandShards() {}

    // ---- diagnostics ----
    public static final LongAdder SHARDED_INVOCATIONS = new LongAdder();
    public static final LongAdder SHARDS_COMMITTED = new LongAdder();
    public static final LongAdder TARGETS_APPLIED = new LongAdder();
    public static final LongAdder TARGETS_REDIRECTED = new LongAdder();
    public static final LongAdder REDIRECT_FALLBACKS = new LongAdder();
    /** Exactly-once proof: apply attempts on an already-committed shard. */
    public static final LongAdder DUPLICATE_SHARD_ATTEMPTS = new LongAdder();

    public static String stats() {
        return "shards inv=" + SHARDED_INVOCATIONS.sum()
                + " committed=" + SHARDS_COMMITTED.sum()
                + " applied=" + TARGETS_APPLIED.sum()
                + " redirected=" + TARGETS_REDIRECTED.sum()
                + " fallbacks=" + REDIRECT_FALLBACKS.sum()
                + " dupShard=" + DUPLICATE_SHARD_ATTEMPTS.sum();
    }

    // ------------------------------------------------------------------
    // Adapter registry — conservative grammars only; anything else fences.
    // ------------------------------------------------------------------

    /** Owner-thread application of one target. @return success (counted). */
    interface TargetApply {
        boolean apply(Entity target);
    }

    record Prepared(String selector, TargetApply apply) {}

    /** Parse {@code cmd}; null = not decomposable (Phase-1 fence path). */
    static Prepared prepare(String cmd, ServerLevel level) {
        String t = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        String[] tok = t.trim().split("\\s+");
        try {
            if (tok.length == 4 && "tag".equals(tok[0]) && tok[1].startsWith("@")
                    && ("add".equals(tok[2]) || "remove".equals(tok[2]))) {
                String name = tok[3];
                boolean add = "add".equals(tok[2]);
                return new Prepared(tok[1], e ->
                        add ? e.addTag(name) : e.removeTag(name));
            }
            if (tok.length >= 4 && tok.length <= 7 && "effect".equals(tok[0])
                    && "give".equals(tok[1]) && tok[2].startsWith("@")) {
                Identifier effId = Identifier.tryParse(tok[3]);
                if (effId == null) return null;
                var reg = level.registryAccess().lookupOrThrow(Registries.MOB_EFFECT);
                Holder<MobEffect> eff = reg.get(effId).orElse(null);
                if (eff == null) return null;
                boolean infinite = tok.length > 4 && "infinite".equals(tok[4]);
                int seconds = tok.length > 4 && !infinite ? Integer.parseInt(tok[4]) : -1;
                int amp = tok.length > 5 ? Integer.parseInt(tok[5]) : 0;
                boolean hide = tok.length > 6 && Boolean.parseBoolean(tok[6]);
                // Vanilla EffectCommands: default duration = instantaneous ? 1 : 600.
                int duration = infinite ? MobEffectInstance.INFINITE_DURATION
                        : seconds >= 0 ? seconds * 20
                        : eff.value().isInstantaneous() ? 1 : 600;
                return new Prepared(tok[2], e -> e instanceof LivingEntity le
                        && le.addEffect(new MobEffectInstance(eff, duration, amp, false, !hide)));
            }
        } catch (RuntimeException e) {
            return null; // any parse doubt → fenced path
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The sharded execution
    // ------------------------------------------------------------------

    /** One shard: targets for one owner, applied on that owner's thread. */
    static final class Shard {
        final ShardedInvocation inv;
        final List<Entity> targets;
        final int redirectDepth;
        volatile byte state; // 0 pending, 1 committed
        Shard(ShardedInvocation inv, List<Entity> targets, int redirectDepth) {
            this.inv = inv;
            this.targets = targets;
            this.redirectDepth = redirectDepth;
        }
    }

    static final class ShardedInvocation {
        final IslandCommands.Invocation ledger;
        final TargetApply apply;
        final AtomicInteger pendingShards = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final CompletableFuture<Boolean> fut = new CompletableFuture<>();
        ShardedInvocation(IslandCommands.Invocation ledger, TargetApply apply) {
            this.ledger = ledger;
            this.apply = apply;
        }
        void shardDone() {
            if (pendingShards.decrementAndGet() == 0) {
                ledger.result = successes.get();
                ledger.state = 2;
                SHARDS_COMMITTED.increment();
                fut.complete(successes.get() > 0);
            } else {
                SHARDS_COMMITTED.increment();
            }
        }
    }

    /**
     * Attempt sharded execution from an ISLAND coordinator context.
     * @return null when the command is not decomposable here (caller uses the
     *         fenced routed path); otherwise the invocation future — the
     *         caller parks on it exactly like a routed command (the waiting
     *         flag makes the source fence-safe), servicing its OWN island's
     *         incoming shards while it waits.
     */
    static CompletableFuture<Boolean> tryExecuteSharded(String command, ServerLevel level,
                                                        RegionizedServer rs, EpochDomain d,
                                                        CommandSourceStack source,
                                                        IslandCommands.Invocation ledger) {
        IslandRegistry.PIsland self = d.owner;
        if (self == null) return null;
        Prepared p = prepare(command, level);
        if (p == null) return null;
        List<? extends Entity> targets;
        try {
            EntitySelector sel = new EntitySelectorParser(new StringReader(p.selector()), true).parse();
            targets = sel.findEntities(source);
        } catch (Exception e) {
            return null; // selector parse/resolve failure → fenced path (vanilla error semantics)
        }
        SHARDED_INVOCATIONS.increment();
        ShardedInvocation inv = new ShardedInvocation(ledger, p.apply());
        // Partition by CURRENT owner. Self-targets apply inline (we own them,
        // right now, on this thread).
        java.util.HashMap<IslandRegistry.PIsland, ArrayList<Entity>> byOwner = new java.util.HashMap<>();
        ArrayList<Entity> globalLane = null;
        ArrayList<Entity> selfLane = null;
        for (Entity e : targets) {
            IslandRegistry.PIsland owner = ownerOf(rs, e);
            if (owner == self) {
                if (selfLane == null) selfLane = new ArrayList<>();
                selfLane.add(e);
            } else if (owner == null) {
                if (globalLane == null) globalLane = new ArrayList<>();
                globalLane.add(e);
            } else {
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(e);
            }
        }
        int shardCount = (selfLane != null ? 1 : 0) + (globalLane != null ? 1 : 0) + byOwner.size();
        if (shardCount == 0) {
            ledger.result = 0;
            ledger.state = 2;
            inv.fut.complete(false); // vanilla: selector matched nothing → failure result
            return inv.fut;
        }
        inv.pendingShards.set(shardCount);
        if (selfLane != null) {
            applyShard(rs, self, new Shard(inv, selfLane, 0));
        }
        for (var en : byOwner.entrySet()) {
            enqueue(en.getKey(), new Shard(inv, en.getValue(), 0));
        }
        if (globalLane != null) {
            ArrayList<Entity> g = globalLane;
            Shard shard = new Shard(inv, g, 0);
            IslandCommands.enqueueFencedBody(() -> {
                applyShard(rs, null, shard);
                return true;
            });
        }
        return inv.fut;
    }

    static void enqueue(IslandRegistry.PIsland owner, Shard s) {
        synchronized (owner.pendingCommandShards) {
            owner.pendingCommandShards.add(s);
        }
        RegionScheduler.wakeWorkers(1);
    }

    /**
     * Drain and apply every shard queued for {@code island}. Called from the
     * island's coordinator only — at unit start AND from the routed/shard
     * wait loops (a PARKED source keeps servicing its own island so mutual
     * sharding cannot deadlock). Single-threaded per island by construction.
     */
    static void serviceShards(RegionizedServer rs, IslandRegistry.PIsland island) {
        while (true) {
            Shard s;
            synchronized (island.pendingCommandShards) {
                // FIFO: deterministic cross-invocation order per island.
                s = island.pendingCommandShards.isEmpty() ? null
                        : (Shard) island.pendingCommandShards.remove(0);
            }
            if (s == null) break;
            applyShard(rs, island, s);
        }
    }

    /** CADENCE-BOUND island tasks (tick functions): run ONLY at unit start —
     *  never from opportunistic/waiter servicing, or a tick function would
     *  run at dispatch (server) cadence instead of the island's (the M==gt
     *  regression in the 2026-07-12 stress rerun). Coordinator only. */
    static void serviceIslandTasks(IslandRegistry.PIsland island) {
        while (true) {
            Runnable task;
            synchronized (island.pendingIslandTasks) {
                task = island.pendingIslandTasks.isEmpty() ? null
                        : island.pendingIslandTasks.remove(0);
            }
            if (task == null) break;
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("[a8] island task failed", t);
            }
        }
    }

    /** A8 Phase 3: dispatch work to an island's lane (tick-tag local
     *  functions). Serviced at unit start / park loops / main-held quiesce. */
    public static void enqueueIslandTask(IslandRegistry.PIsland owner, Object coalesceKey,
                                         Runnable task) {
        synchronized (owner.pendingIslandTasks) {
            if (coalesceKey != null && !owner.pendingIslandTaskKeys.add(coalesceKey)) {
                return; // one pending instance per key: missed-cycle semantics
            }
            owner.pendingIslandTasks.add(coalesceKey == null ? task : () -> {
                synchronized (owner.pendingIslandTasks) {
                    owner.pendingIslandTaskKeys.remove(coalesceKey);
                }
                task.run();
            });
        }
        RegionScheduler.wakeWorkers(1);
    }

    /** Apply one shard on the current thread (owner island's coordinator, or
     *  main under the fence for the global lane). Ownership re-validated per
     *  target; movers are redirected (bounded), never duplicated. */
    private static void applyShard(RegionizedServer rs, IslandRegistry.PIsland expectedOwner, Shard s) {
        if (s.state != 0) {
            DUPLICATE_SHARD_ATTEMPTS.increment();
            return;
        }
        s.state = 1;
        for (int i = 0, n = s.targets.size(); i < n; i++) {
            Entity e = s.targets.get(i);
            if (e.isRemoved()) continue;
            IslandRegistry.PIsland owner = ownerOf(rs, e);
            if (owner != expectedOwner) {
                // Moved since resolution (merge/split/teleport): redirect to
                // its CURRENT owner — commit-time generation validation.
                TARGETS_REDIRECTED.increment();
                if (s.redirectDepth >= 2 || owner == null) {
                    REDIRECT_FALLBACKS.increment();
                    Entity mover = e;
                    ShardedInvocation inv = s.inv;
                    inv.pendingShards.incrementAndGet();
                    IslandCommands.enqueueFencedBody(() -> {
                        Shard fb = new Shard(inv, java.util.List.of(mover), 99);
                        applyShardTargetsOnly(inv, fb);
                        return true;
                    });
                } else {
                    ShardedInvocation inv = s.inv;
                    inv.pendingShards.incrementAndGet();
                    enqueue(owner, new Shard(inv, java.util.List.of(e), s.redirectDepth + 1));
                }
                continue;
            }
            if (s.inv.apply.apply(e)) {
                s.inv.successes.incrementAndGet();
                TARGETS_APPLIED.increment();
            }
        }
        s.inv.shardDone();
    }

    /** Fallback application without ownership check (runs under the all-island
     *  fence — everything is quiescent, any owner is safe). */
    private static void applyShardTargetsOnly(ShardedInvocation inv, Shard s) {
        for (int i = 0, n = s.targets.size(); i < n; i++) {
            Entity e = s.targets.get(i);
            if (!e.isRemoved() && inv.apply.apply(e)) {
                inv.successes.incrementAndGet();
                TARGETS_APPLIED.increment();
            }
        }
        inv.shardDone();
    }

    static IslandRegistry.PIsland ownerOf(RegionizedServer rs, Entity e) {
        ThreadedRegionizer reg = rs.regionizer();
        Region r = reg.peekByGridKey(reg.grid.keyFor(e.chunkPosition().pack()));
        EpochDomain d = r != null ? r.domain : null;
        return d != null ? d.owner : null;
    }

    /** Rebuild-time unstash (all units quiesced): re-partition every pending
     *  shard by CURRENT ownership, preserving invocation identity. */
    static void repartitionAt(RegionizedServer rs, IslandRegistry.PIsland island,
                              List<Object> drained) {
        for (Object o : drained) {
            Shard s = (Shard) o;
            if (s.state != 0) continue;
            // Consume this shard; its targets re-shard to current owners.
            s.state = 1;
            java.util.HashMap<IslandRegistry.PIsland, ArrayList<Entity>> byOwner = new java.util.HashMap<>();
            ArrayList<Entity> globalLane = null;
            for (Entity e : s.targets) {
                if (e.isRemoved()) continue;
                IslandRegistry.PIsland owner = ownerOf(rs, e);
                if (owner == null) {
                    if (globalLane == null) globalLane = new ArrayList<>();
                    globalLane.add(e);
                } else {
                    byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(e);
                }
            }
            int add = byOwner.size() + (globalLane != null ? 1 : 0);
            s.inv.pendingShards.addAndGet(add);
            for (var en : byOwner.entrySet()) {
                enqueue(en.getKey(), new Shard(s.inv, en.getValue(), s.redirectDepth));
            }
            if (globalLane != null) {
                ArrayList<Entity> g = globalLane;
                ShardedInvocation inv = s.inv;
                IslandCommands.enqueueFencedBody(() -> {
                    applyShardTargetsOnly(inv, new Shard(inv, g, 99));
                    return true;
                });
            }
            s.inv.shardDone(); // retire the consumed original
        }
    }
}
