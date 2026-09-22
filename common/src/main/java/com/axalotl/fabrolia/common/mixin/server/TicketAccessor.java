package com.axalotl.fabrolia.common.mixin.server;

import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to {@code Ticket.ticksLeft} for the pearl-ticket expiry trace
 *  ({@code TicketStorageMixin}): the moment a purge pass removes an
 *  {@code ENDER_PEARL} ticket is exactly {@code ticksLeft} reaching 0
 *  pre-decrement, and vanilla exposes no getter. */
@Mixin(Ticket.class)
public interface TicketAccessor {

    @Accessor("ticksLeft")
    long fabrolia$getTicksLeft();
}
