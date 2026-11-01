package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to {@code Ticket.ticksLeft} for {@code TicketStorageMixin}: the
 *  pearl-ticket expiry trace (the moment a purge pass removes an
 *  {@code ENDER_PEARL} ticket is exactly {@code ticksLeft} reaching 0
 *  pre-decrement; vanilla exposes no getter) and island-tick expiry. */
@Mixin(Ticket.class)
public interface TicketAccessor {

    @Accessor("ticksLeft")
    long fabrolia$getTicksLeft();

    /** Island-tick expiry ({@code TicketStorageMixin}): a purge pass that saw
     *  several island ticks takes the extra decrements here. */
    @Accessor("ticksLeft")
    void fabrolia$setTicksLeft(long ticksLeft);
}
