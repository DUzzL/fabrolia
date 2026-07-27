package com.axalotl.fabrolia.common.regions;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player simulation-ownership model — PHASE 0 (2026-07-20): observe, don't move.
 *
 * <p>Target architecture (a2 checkpoint, task #30): the player ENTITY simulates in
 * the ownership domain of the island containing it (movement, physics, collision,
 * environmental damage, local game events incl. sculk vibrations, local interactions),
 * while session/network/global processing stays main-owned (packets, login/disconnect,
 * commands, chat, global scoreboards, player list, respawn/dimension orchestration,
 * saves, chunk delivery). Handoffs use the {@link #epoch} token: any queued regional
 * task captures the epoch at creation and aborts if it no longer matches at execution,
 * so a stale task can never mutate a player whose ownership moved (border cross,
 * island merge/split, teleport, death, logout, shutdown).
 *
 * <p>THIS phase installs the owner record and the assertion surface only. The owner
 * of every player is MAIN today; assertions are REPORT-ONLY (rate-limited ERROR +
 * counters) and exist to produce the real-world census of foreign-thread player
 * mutations before any behavior moves — the two known offenders (pearl-island
 * registrations into {@code enderPearls}, pearl-landing teleports) were found by
 * crash forensics; this surfaces the remainder without waiting for crashes.
 */
public final class PlayerOwnership {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fabrolia-PlayerOwner");

    private PlayerOwnership() {}

    public enum Domain { MAIN, ISLAND }

    /** Per-player owner record. Phase 0: always MAIN, epoch bumps on lifecycle
     *  boundaries so the token plumbing is exercised before it matters. */
    public static final class Owner {
        public volatile Domain domain = Domain.MAIN;
        public volatile long islandId = -1;
        public final AtomicLong epoch = new AtomicLong();
    }

    private static final ConcurrentHashMap<java.util.UUID, Owner> OWNERS = new ConcurrentHashMap<>();
    public static final AtomicLong VIOLATIONS = new AtomicLong();
    private static final ConcurrentHashMap<String, Long> GATE = new ConcurrentHashMap<>();

    public static Owner owner(ServerPlayer p) {
        return OWNERS.computeIfAbsent(p.getUUID(), u -> {
            Owner o = new Owner();
            // Observed reality (phase-0 census finding, rig own1): carpet fake
            // players are plain entity-list members and ALREADY simulate on
            // island workers; real players tick on main via Connection.tick
            // (spark yLkvfg1seZ: 10.83% doTick under tickPlayer). Owner init
            // mirrors that split so the census reports true anomalies only.
            if (p.getClass().getName().contains("Fake")) {
                o.domain = Domain.ISLAND;
            }
            return o;
        });
    }

    /** Lifecycle boundary (login, respawn, dimension change, logout): invalidate
     *  every outstanding ownership token. */
    public static void bumpEpoch(ServerPlayer p) {
        owner(p).epoch.incrementAndGet();
    }

    public static void forget(ServerPlayer p) {
        OWNERS.remove(p.getUUID());
    }

    /**
     * Report-only assertion: the calling thread must be the player's simulation
     * owner (phase 0: the server thread). Violations are the migration work-list —
     * each names a mutation path that must be routed or re-owned before player
     * ticks move off main. Never throws, never alters behavior.
     */
    public static void assertSimulationOwner(ServerPlayer p, String what) {
        Owner o = owner(p);
        boolean ok = switch (o.domain) {
            case MAIN -> onMain();
            // Phase 0: island-owned (fake) players are also legitimately ticked
            // from main through the routed sync lanes and fence windows — only
            // the MAIN-owned-but-on-worker direction is a census hit today.
            case ISLAND -> true;
        };
        if (ok) return;
        VIOLATIONS.incrementAndGet();
        String key = what + "/" + Thread.currentThread().getName();
        long now = System.nanoTime();
        Long last = GATE.get(key);
        if (last == null || now - last > 60_000_000_000L) {
            GATE.put(key, now);
            LOGGER.error("[player-owner] '{}' on {} executed by thread '{}' but simulation owner is {}{} "
                    + "(epoch {}) — report-only census entry, no behavior changed (violations={})",
                    what, p.getName().getString(), Thread.currentThread().getName(),
                    o.domain, o.domain == Domain.ISLAND ? "#" + o.islandId : "",
                    o.epoch.get(), VIOLATIONS.get(),
                    new Exception("player-owner census stack"));
        }
    }

    private static boolean onMain() {
        net.minecraft.server.MinecraftServer s =
                com.axalotl.fabrolia.common.ParallelProcessor.getServer();
        return s == null || s.isSameThread();
    }

    public static String status() {
        return "playerOwner: tracked=" + OWNERS.size() + " violations=" + VIOLATIONS.get();
    }
}
