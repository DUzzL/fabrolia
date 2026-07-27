package com.axalotl.fabrolia.common.parallelised.utils;

/**
 * Control-flow signal: the entity currently ticking has just handed a
 * cross-dimension transfer to main, so the REST OF ITS TICK MUST NOT RUN.
 *
 * <p>Why this exists. In vanilla, {@code handlePortal} performs the whole
 * teleport inline and calls {@code removeAfterChangingDimensions()} before it
 * returns (Entity.java:3088), so for the remainder of that tick the source
 * entity is {@code CHANGED_DIMENSION}-removed and every later
 * {@code isRemoved()} / {@code isAlive()} gate is inert. Fabrolia defers the
 * teleport, so the source entity stayed alive and un-removed and kept executing
 * statements vanilla makes dead:
 *
 * <ul>
 *   <li>{@code ThrowableProjectile.tick():61-62} — {@code super.tick()} reaches
 *       handlePortal, then {@code if (result != MISS && this.isAlive())
 *       hitTargetOrDeflectSelf(result)} fires IN THE SOURCE DIMENSION and
 *       discards the pearl. This is the operator's reproduction:
 *       {@code ABORTED (removed-while-queued) … ender_pearl … removal=DISCARDED}.</li>
 *   <li>{@code ItemEntity} — {@code mergeWithNeighbours()} / the 6000-tick
 *       despawn {@code discard()}.</li>
 *   <li>{@code LivingEntity.tick():2781} — a whole extra {@code aiStep()}, plus
 *       drowning / in-wall / world-border damage that can kill the entity.</li>
 *   <li>{@code AbstractMinecart.tick()} — {@code behavior.tick()},
 *       {@code updateFluidInteraction()}, lava damage.</li>
 *   <li>{@code ServerLevel.tickNonPassenger():821-833} — the passenger loop,
 *       which vanilla never reaches because {@code teleportCrossDimension}
 *       already ejected and teleported the riders.</li>
 * </ul>
 *
 * <p>Why not "remove the source on the worker instead". That is the shape the
 * brief prefers, and it is the right end state, but it cannot be done by simply
 * calling {@code setRemoved} at submit time: {@code Entity.teleport} is guarded
 * by {@code if (this.level() instanceof ServerLevel && !this.isRemoved())}
 * (Entity.java:3037) and returns {@code null} for a removed entity, so main
 * could no longer perform the transfer at all. Doing the source half eagerly
 * therefore requires reimplementing {@code teleportCrossDimension} — copy
 * creation, {@code restoreFrom}, passenger recursion, spectator following,
 * portal ticket, {@code postTeleportTransition} — which is a much larger
 * divergence surface than the defect it closes. Ending the tick achieves the
 * SAME observable invariant ("nothing further in this tick touches the source")
 * for every entity type at once, with no per-type mixin and no reimplementation.
 *
 * <p>This is thrown only from {@code EntityHandlePortalMixin} on the deferred
 * path and is swallowed by {@code ParallelProcessor.tickEntity}. It carries no
 * stack trace, no message and no suppression, so it costs nothing to throw.
 */
public final class PortalDeparture extends RuntimeException {

    public static final PortalDeparture INSTANCE = new PortalDeparture();

    private PortalDeparture() {
        super(null, null, /* enableSuppression */ false, /* writableStackTrace */ false);
    }

    @Override
    public String toString() {
        return "PortalDeparture (control flow: source entity handed off to main)";
    }
}
